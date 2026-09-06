package gg.vape.module.combat.blockhit;

import gg.vape.combat.AttackPacketTimingTracker;
import gg.vape.config.ClientSettings;
import gg.vape.event.EventHandler;
import gg.vape.event.impl.EventLivingUpdate;
import gg.vape.event.impl.EventMouseButton;
import gg.vape.event.impl.EventPreTick;
import gg.vape.event.impl.EventWorldChange;
import gg.vape.module.Mod;
import gg.vape.module.combat.BlockHit;
import gg.vape.value.BooleanValue;
import gg.vape.value.NumberValue;
import gg.vape.value.Value;
import gg.vape.wrapper.impl.EntityLivingBase;
import gg.vape.wrapper.impl.EntityPlayerSP;
import gg.vape.wrapper.impl.KeyBinding;
import gg.vape.wrapper.impl.Minecraft;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.util.Locale;

/**
 * SMART5/R10 defensive BlockHit timing.
 *
 * Design goals:
 * - normal use-item KeyBinding only; no packet buffering/cancellation;
 * - one geometry-based first-hit attempt per genuine range encounter;
 * - after damage, use the local damageability clock instead of range pulsing;
 * - release promptly and minimize movement lost to unnecessary blocking.
 */
public class SmartBlockHitModeV4 extends BlockHitMode {
    private static final long TICK_MILLIS = 50L;
    private static final long RECENT_DAMAGE_WINDOW_MS = 1000L;
    private static final long TARGET_LOSS_GRACE_MS = 350L;
    private static final long RANGE_EXIT_CONFIRM_MS = 100L;
    private static final long LOCAL_DAMAGE_FALLBACK_MS = 500L;
    private static final long HURT_CLOCK_ESTABLISH_GRACE_MS = 100L;
    private static final long HEALTH_SIGNAL_MERGE_AFTER_BOUNDARY_MS = 300L;
    private static final long EPOCH_COMPLETE_GRACE_MS = 150L;
    private static final double APPROACH_EPSILON = 0.015D;
    private static final double RANGE_HYSTERESIS = 0.25D;
    private static final long MAX_PING_COMPENSATION_MS = 100L;
    private static final long MIN_POST_BOUNDARY_COVERAGE_MS = 50L;
    private static final float HEALTH_EPSILON = 0.001F;

    private static volatile Field timeUntilRegenField;
    private static volatile Class<?> timeUntilRegenOwner;
    private static volatile boolean timeUntilRegenResolveAttempted;
    private static volatile String timeUntilRegenFieldName = "unresolved";

    private final NumberValue targetAngle;
    private final NumberValue targetSearchDistance;
    private final NumberValue blockRange;
    private final NumberValue maximumHurtTime;
    private final BooleanValue includePing;
    private final NumberValue maximumHoldDuration;
    private final BooleanValue blockFirstHit;
    private final BooleanValue requireApproaching;

    private State state = State.IDLE;
    private boolean blocking;
    private String activeBlockReason = "";
    private long blockStartedAt;

    private int currentTargetId = Integer.MIN_VALUE;
    private long targetLostAt;
    private double previousTargetDistance = Double.NaN;
    private int approachStreak;
    private boolean firstHitSpent;
    private long outsideBlockRangeSince;

    private boolean damageCycle;
    private boolean hurtWindowSpent;
    private long recentDamageAt;
    private int regenPeak; // diagnostic only in R10; never a scheduler/trigger
    private long damageEpochId;
    private long damageEpochStartedAt;
    private boolean epochHurtClockLocked;
    private boolean epochFallbackClockLocked;
    private boolean epochHealthConfirmed;
    private long predictedBoundaryAt;
    private long activeBoundaryAt;
    private long desiredBlockEndAt;
    private int lastRegen = -1;
    private int lastHurtTime = -1;
    private float lastHealth = Float.NaN;
    private long eventDamageHintAt;
    private String lastClockLog = "";
    private boolean regenFieldLogged;

    private enum State {
        IDLE,
        FIRST_HIT_ARMED,
        POST_HIT,
        WAITING_VULNERABLE,
        BLOCKING
    }

    public SmartBlockHitModeV4(Mod parent, String name) {
        super(parent, name);
        this.targetAngle = NumberValue.create(
                this, "Target angle", "#", "°", 30.0D, 90.0D, 180.0D, 5.0D,
                "Maximum angle used to find a relevant combat target");
        this.targetSearchDistance = NumberValue.create(
                this, "Target search distance", "#.#", "", 3.0D, 5.0D, 6.0D, 0.1D,
                "How far away a player can be before Smart stops considering them");
        this.blockRange = NumberValue.create(
                this, "Block range", "#.#", "", 2.5D, 3.35D, 5.0D, 0.05D,
                "Actual threat range where Smart is allowed to raise the sword");
        this.maximumHurtTime = NumberValue.create(
                this, "Maximum hurt time", "#", "ms", 0.0D, 150.0D, 500.0D, 10.0D,
                "How early before the next damageable boundary Smart may start blocking");
        this.includePing = BooleanValue.create(
                this, "Include ping", true,
                "Adds a bounded observed combat feedback delay to the early block window");
        this.maximumHoldDuration = NumberValue.create(
                this, "Maximum hold duration", "#", "ms", 50.0D, 125.0D, 500.0D, 5.0D,
                "Hard upper bound for a Smart-generated block");
        this.blockFirstHit = BooleanValue.create(
                this, "Block first hit", true,
                "Allow one geometry-based block attempt when entering a new close-range exchange");
        this.requireApproaching = BooleanValue.create(
                this, "Require approaching", true,
                "Require stable closing distance before the first-hit block attempt");

        this.addValue(new Value[]{
                this.targetAngle,
                this.targetSearchDistance,
                this.blockRange,
                this.maximumHurtTime,
                this.includePing,
                this.maximumHoldDuration,
                this.blockFirstHit,
                this.requireApproaching
        });
    }

    @Override
    public boolean shouldBlock() {
        return false;
    }

    @Override
    public boolean isBlocking() {
        return this.blocking;
    }

    @Override
    public void onEnable() {
        resetAll("enable");
        sampleBaseline();
        appendLog("SMART5-R10 enabled model=damage-epochs+locked-hurt-clock maxHold="
                + Math.round(this.maximumHoldDuration.getValue().doubleValue())
                + "ms postBoundaryCoverage=" + MIN_POST_BOUNDARY_COVERAGE_MS
                + "ms preTickGuard=" + TICK_MILLIS
                + "ms requireApproaching=" + this.requireApproaching.getEffectiveValue().booleanValue());
    }

    @Override
    public void onDisable() {
        resetAll("disable");
    }

    @EventHandler
    public void onWorldChange(EventWorldChange event) {
        resetAll("world-change");
        sampleBaseline();
    }

    /**
     * EventLivingUpdate is retained only as a hint. R10 does not treat it alone as
     * proof of damage because the 1.21.11 runtime trace showed it can be displaced
     * from the actual local hurt countdown.
     */
    @EventHandler
    public void onDamaged(EventLivingUpdate event) {
        try {
            EntityPlayerSP player = Minecraft.thePlayer();
            if (player == null || player.isNull() || event.getEntity() == null || event.getEntity().isNull()) return;
            if (!event.getEntity().getObject().equals(player.getObject())) return;
            this.eventDamageHintAt = System.currentTimeMillis();
        } catch (Throwable ignored) {
        }
    }

    @EventHandler
    public void onMouseButton(EventMouseButton event) {
        try {
            if (!event.getButtonState() || !parent().isHoldingSword()) return;
            int buttonBinding = -100 + event.getButton();
            if (buttonBinding != useKey().getKeyCode()) return;
            if (!parent().requiresMouseDown()) return;
            if (!parent().ignoreManualBlock.getEffectiveValue().booleanValue()) return;
            if (ClientSettings.isAttackButtonDown()) event.setCancelled(true);
        } catch (Throwable ignored) {
        }
    }

    @EventHandler
    public void onTick(EventPreTick event) {
        final long now = System.currentTimeMillis();
        EntityPlayerSP player = event.getThePlayer();
        if (player == null || player.isNull()) {
            resetContext("invalid-player");
            return;
        }

        int hurtTime = safeHurtTime(player);
        int regen = readTimeUntilRegen(player);
        float health = safeHealth(player);
        logRegenFieldOnce();

        boolean baselineReady = this.lastHurtTime >= 0 || this.lastRegen >= 0 || !Float.isNaN(this.lastHealth);
        boolean healthDrop = baselineReady && !Float.isNaN(health) && !Float.isNaN(this.lastHealth)
                && health < this.lastHealth - HEALTH_EPSILON;
        boolean sharpHurtRise = baselineReady && hurtTime >= 8
                && (this.lastHurtTime <= 1 || hurtTime >= this.lastHurtTime + 3);
        boolean regenRise = baselineReady && regen > 0
                && (this.lastRegen <= 0 || regen > this.lastRegen + 1);

        // R10 damage epochs: a sharp hurtTime reset is authoritative. A later health
        // drop inside the same hurt cycle is confirmation, not a second epoch.
        if (sharpHurtRise) {
            startDamageEpoch("hurtTime-rise", now, health, hurtTime, regen);
        }
        if (healthDrop) {
            observeHealthDrop(now, health, hurtTime, regen);
        }
        if (regenRise) {
            appendLog("regenDiagnosticRise health=" + fmtHealth(health)
                    + " previousHealth=" + fmtHealth(this.lastHealth)
                    + " hurtTime=" + hurtTime + " timeUntilRegen=" + regen
                    + " epoch=" + this.damageEpochId);
        }

        this.lastHealth = health;
        this.lastHurtTime = hurtTime;
        this.lastRegen = regen;
        if (this.damageCycle && regen > this.regenPeak) this.regenPeak = regen;

        if (!parent().isHoldingSword()) {
            setBlocking(false);
            clearActiveTargetTracking(now, "invalid-player-or-item");
            return;
        }
        if (parent().requiresMouseDown() && !ClientSettings.isUseItemButtonDown()) {
            setBlocking(false);
            clearActiveTargetTracking(now, "input-released");
            return;
        }

        EntityLivingBase target = parent().findTarget(
                this.targetAngle.getValue().doubleValue(),
                this.targetSearchDistance.getValue().doubleValue());

        if (target == null || target.isNull()) {
            setBlocking(false);
            this.blockStartedAt = 0L;
            this.activeBlockReason = "";
            this.activeBoundaryAt = 0L;
            this.desiredBlockEndAt = 0L;
            this.previousTargetDistance = Double.NaN;
            this.approachStreak = 0;
            this.outsideBlockRangeSince = 0L;
            if (this.currentTargetId != Integer.MIN_VALUE && this.targetLostAt == 0L) this.targetLostAt = now;
            if (this.targetLostAt > 0L && now - this.targetLostAt > TARGET_LOSS_GRACE_MS) {
                this.currentTargetId = Integer.MIN_VALUE;
                this.firstHitSpent = false;
                this.targetLostAt = 0L;
            }
            transition(this.damageCycle ? State.WAITING_VULNERABLE : State.IDLE,
                    "no-target", null, Double.NaN, false);
            expireDamageCycleIfNeeded(now, hurtTime, regen);
            return;
        }

        int targetId = target.S();
        if (this.currentTargetId != targetId) {
            int old = this.currentTargetId;
            setBlocking(false);
            this.currentTargetId = targetId;
            this.targetLostAt = 0L;
            this.previousTargetDistance = Double.NaN;
            this.approachStreak = 0;
            this.firstHitSpent = this.damageCycle;
            this.outsideBlockRangeSince = 0L;
            this.blockStartedAt = 0L;
            this.activeBlockReason = "";
            this.activeBoundaryAt = 0L;
            this.desiredBlockEndAt = 0L;
            appendLog("targetStateReset oldTargetId=" + old + " newTarget=" + safeTargetName(target) + "#" + targetId
                    + " damageCycle=" + this.damageCycle + " firstHitSpent=" + this.firstHitSpent);
            transition(this.damageCycle ? State.WAITING_VULNERABLE : State.FIRST_HIT_ARMED,
                    "target-change", target, Double.NaN, false);
        } else {
            this.targetLostAt = 0L;
        }

        double distance = player.getDistanceToEntity(target);
        boolean approaching = updateApproach(distance);
        double allowedRange = this.blockRange.getValue().doubleValue();

        if (distance > allowedRange + RANGE_HYSTERESIS) {
            if (this.outsideBlockRangeSince == 0L) this.outsideBlockRangeSince = now;
            if (this.firstHitSpent && now - this.outsideBlockRangeSince >= RANGE_EXIT_CONFIRM_MS) {
                this.firstHitSpent = false;
                appendLog("firstHitRearmed reason=range-exit target=" + safeTargetName(target) + "#" + targetId
                        + " distance=" + fmt(distance));
            }
        } else {
            this.outsideBlockRangeSince = 0L;
        }

        if (this.blocking) {
            if (distance > allowedRange + RANGE_HYSTERESIS) {
                finishBlocking("target-out-of-block-range", target, distance, approaching, now, hurtTime, regen);
                this.previousTargetDistance = distance;
                return;
            }
            long age = now - this.blockStartedAt;
            long maxHold = Math.round(this.maximumHoldDuration.getValue().doubleValue());
            long preTickSafeHold = Math.max(1L, maxHold - TICK_MILLIS);

            // Boundary is the normal hurt-window exit. Maximum Hold remains a failsafe.
            // The one-tick early guard compensates for the next PreTick arriving up to
            // roughly 50 ms later, keeping the observed wall-clock hold near/below the
            // configured hard ceiling without moving input from a background thread.
            if ("hurt-window".equals(this.activeBlockReason)
                    && this.desiredBlockEndAt > 0L
                    && now >= this.desiredBlockEndAt) {
                finishBlocking("boundary", target, distance, approaching, now, hurtTime, regen);
                this.previousTargetDistance = distance;
                return;
            }
            if (age >= preTickSafeHold) {
                finishBlocking("max-hold-failsafe", target, distance, approaching, now, hurtTime, regen);
                this.previousTargetDistance = distance;
                return;
            }
            this.previousTargetDistance = distance;
            return;
        }

        if (this.damageCycle) {
            if (this.state == State.POST_HIT) {
                transition(State.WAITING_VULNERABLE, "damage-epoch", target, distance, approaching);
            }
            Clock clock = getDamageClock(now, hurtTime, regen);
            logClockIfChanged(clock, target, distance);

            long maxHold = Math.round(this.maximumHoldDuration.getValue().doubleValue());
            long configuredEarlyLead = Math.round(this.maximumHurtTime.getValue().doubleValue());
            long earlyLead = Math.min(configuredEarlyLead,
                    Math.max(0L, maxHold - MIN_POST_BOUNDARY_COVERAGE_MS));
            long pingCompensation = getPingCompensationMillis();
            long compensatedBoundaryAt = clock.predictedBoundaryAt > 0L
                    ? Math.max(this.damageEpochStartedAt, clock.predictedBoundaryAt - pingCompensation) : 0L;
            long compensatedMsUntilDamageable = compensatedBoundaryAt > 0L
                    ? Math.max(0L, compensatedBoundaryAt - now) : Long.MAX_VALUE;

            if (!this.hurtWindowSpent
                    && clock.schedulable
                    && distance <= allowedRange
                    && compensatedMsUntilDamageable <= earlyLead) {
                this.hurtWindowSpent = true;
                long desiredEnd = compensatedBoundaryAt + MIN_POST_BOUNDARY_COVERAGE_MS;
                startBlocking("hurt-window", target, distance, approaching, now, compensatedBoundaryAt, desiredEnd);
                appendLog("hurtWindowStart epoch=" + this.damageEpochId + " clock=" + clock.name
                        + " raw=" + clock.raw + " msUntilDamageable=" + clock.msUntilDamageable
                        + " compensatedMsUntilDamageable=" + compensatedMsUntilDamageable
                        + " pingCompensation=" + pingCompensation + "ms earlyLead=" + earlyLead + "ms"
                        + " predictedBoundaryAt=" + compensatedBoundaryAt
                        + " desiredBlockEndAt=" + desiredEnd + " maxHold=" + maxHold + "ms");
            } else {
                transition(State.WAITING_VULNERABLE, "hurt-window-wait", target, distance, approaching);
            }

            expireDamageCycleIfNeeded(now, hurtTime, regen);
            this.previousTargetDistance = distance;
            return;
        }

        transition(State.FIRST_HIT_ARMED, "first-hit-context", target, distance, approaching);
        if (!this.firstHitSpent
                && this.blockFirstHit.getEffectiveValue().booleanValue()
                && distance <= allowedRange
                && passesApproachGate()) {
            this.firstHitSpent = true;
            startBlocking("first-hit-range", target, distance, approaching, now, 0L, 0L);
            appendLog("firstHitSpent target=" + safeTargetName(target) + "#" + targetId
                    + " distance=" + fmt(distance) + " approachStreak=" + this.approachStreak);
        }

        this.previousTargetDistance = distance;
    }

    private void startDamageEpoch(String source, long now, float health, int hurtTime, int regen) {
        if (this.blocking) {
            releaseForDamage(now, hurtTime, regen);
        } else {
            setBlocking(false);
            this.blockStartedAt = 0L;
            this.activeBlockReason = "";
            this.activeBoundaryAt = 0L;
            this.desiredBlockEndAt = 0L;
        }
        this.damageCycle = true;
        this.hurtWindowSpent = false;
        this.recentDamageAt = now;
        this.damageEpochStartedAt = now;
        this.damageEpochId++;
        this.epochHurtClockLocked = false;
        this.epochFallbackClockLocked = false;
        this.epochHealthConfirmed = "health-drop".equals(source);
        this.predictedBoundaryAt = 0L;
        this.regenPeak = Math.max(0, regen);
        this.firstHitSpent = true;
        this.lastClockLog = "";
        long hintAge = this.eventDamageHintAt > 0L ? Math.max(0L, now - this.eventDamageHintAt) : -1L;
        appendLog("damageEpochStart id=" + this.damageEpochId + " source=" + source
                + " health=" + fmtHealth(health) + " previousHealth=" + fmtHealth(this.lastHealth)
                + " hurtTime=" + hurtTime + " timeUntilRegen=" + regen
                + " regenField=" + timeUntilRegenFieldName
                + " eventHintAgeMs=" + hintAge + " targetId=" + this.currentTargetId);
        if (hurtTime > 0) lockHurtClock(now, hurtTime);
        transition(State.POST_HIT, "damage-epoch", null, Double.NaN, false);
    }

    private void observeHealthDrop(long now, float health, int hurtTime, int regen) {
        if (!this.damageCycle) {
            startDamageEpoch("health-drop", now, health, hurtTime, regen);
            return;
        }

        boolean merge;
        if (this.epochHurtClockLocked) {
            long mergeUntil = this.predictedBoundaryAt > 0L
                    ? this.predictedBoundaryAt + HEALTH_SIGNAL_MERGE_AFTER_BOUNDARY_MS
                    : this.damageEpochStartedAt + LOCAL_DAMAGE_FALLBACK_MS;
            merge = now <= mergeUntil;
        } else if (!this.epochFallbackClockLocked) {
            merge = now - this.damageEpochStartedAt <= LOCAL_DAMAGE_FALLBACK_MS;
        } else {
            merge = now <= this.predictedBoundaryAt;
        }

        if (!merge) {
            startDamageEpoch("health-drop", now, health, hurtTime, regen);
            return;
        }

        this.epochHealthConfirmed = true;
        if (!this.epochHurtClockLocked && !this.epochFallbackClockLocked && hurtTime > 0) {
            lockHurtClock(now, hurtTime);
        }
        appendLog("damageSignalMerged id=" + this.damageEpochId + " source=health-drop"
                + " health=" + fmtHealth(health) + " previousHealth=" + fmtHealth(this.lastHealth)
                + " hurtTime=" + hurtTime + " timeUntilRegen=" + regen);
    }

    private void lockHurtClock(long now, int hurtTime) {
        if (this.epochHurtClockLocked || this.epochFallbackClockLocked || hurtTime <= 0) return;
        this.epochHurtClockLocked = true;
        this.predictedBoundaryAt = now + Math.max(0, hurtTime) * TICK_MILLIS;
        appendLog("damageEpochClock id=" + this.damageEpochId + " source=hurtTime"
                + " raw=" + hurtTime + " predictedBoundaryAt=" + this.predictedBoundaryAt);
    }

    private void lockFallbackClock(long now) {
        if (this.epochHurtClockLocked || this.epochFallbackClockLocked) return;
        this.epochFallbackClockLocked = true;
        this.predictedBoundaryAt = this.damageEpochStartedAt + LOCAL_DAMAGE_FALLBACK_MS;
        appendLog("damageEpochFallback id=" + this.damageEpochId + " source=local500ms"
                + " predictedBoundaryAt=" + this.predictedBoundaryAt
                + " establishedAfterMs=" + Math.max(0L, now - this.damageEpochStartedAt));
    }

    private void releaseForDamage(long now, int hurtTime, int regen) {
        long age = this.blockStartedAt > 0L ? now - this.blockStartedAt : 0L;
        String completedReason = this.activeBlockReason;
        setBlocking(false);
        this.blockStartedAt = 0L;
        this.activeBlockReason = "";
        this.activeBoundaryAt = 0L;
        this.desiredBlockEndAt = 0L;
        appendLog("blockEnd reason=damage blockType=" + completedReason
                + " blockAgeMs=" + age + " hurtTime=" + hurtTime + " timeUntilRegen=" + regen);
    }

    private void expireDamageCycleIfNeeded(long now, int hurtTime, int regen) {
        if (!this.damageCycle) return;
        long completeAfter = this.predictedBoundaryAt > 0L
                ? this.predictedBoundaryAt + EPOCH_COMPLETE_GRACE_MS
                : this.damageEpochStartedAt + RECENT_DAMAGE_WINDOW_MS;
        boolean clockFinished = (this.epochHurtClockLocked || this.epochFallbackClockLocked)
                && now >= completeAfter;
        if (clockFinished && !this.blocking) {
            appendLog("damageEpochComplete id=" + this.damageEpochId
                    + " clock=" + (this.epochHurtClockLocked ? "hurtTime" : "local500ms")
                    + " hurtTime=" + hurtTime + " timeUntilRegen=" + regen
                    + " healthConfirmed=" + this.epochHealthConfirmed
                    + " firstHitSpent=" + this.firstHitSpent);
            clearDamageEpoch();
            transition(this.currentTargetId == Integer.MIN_VALUE ? State.IDLE : State.FIRST_HIT_ARMED,
                    "damage-epoch-complete", null, Double.NaN, false);
        } else if (now - this.damageEpochStartedAt > RECENT_DAMAGE_WINDOW_MS + EPOCH_COMPLETE_GRACE_MS) {
            appendLog("damageEpochTimeout id=" + this.damageEpochId
                    + " hurtTime=" + hurtTime + " timeUntilRegen=" + regen);
            clearDamageEpoch();
        }
    }

    private void clearDamageEpoch() {
        this.damageCycle = false;
        this.hurtWindowSpent = false;
        this.recentDamageAt = 0L;
        this.damageEpochStartedAt = 0L;
        this.epochHurtClockLocked = false;
        this.epochFallbackClockLocked = false;
        this.epochHealthConfirmed = false;
        this.predictedBoundaryAt = 0L;
        this.regenPeak = 0;
        this.lastClockLog = "";
    }

    private Clock getDamageClock(long now, int hurtTime, int regen) {
        if (this.epochHurtClockLocked) {
            if (hurtTime > 0) {
                // Keep the source locked to hurtTime while refreshing the boundary from
                // the real descending samples. Never fall over to local500ms at zero.
                this.predictedBoundaryAt = now + Math.max(0, hurtTime) * TICK_MILLIS;
            }
            long remaining = this.predictedBoundaryAt > 0L
                    ? Math.max(0L, this.predictedBoundaryAt - now) : 0L;
            return new Clock("hurtTime", Math.max(0, hurtTime), 10, 0, remaining, this.predictedBoundaryAt, true);
        }

        if (!this.epochFallbackClockLocked && hurtTime > 0) {
            lockHurtClock(now, hurtTime);
            return getDamageClock(now, hurtTime, regen);
        }

        if (!this.epochFallbackClockLocked
                && now - this.damageEpochStartedAt < HURT_CLOCK_ESTABLISH_GRACE_MS) {
            long provisionalBoundary = this.damageEpochStartedAt + LOCAL_DAMAGE_FALLBACK_MS;
            long remaining = Math.max(0L, provisionalBoundary - now);
            return new Clock("await-hurtTime", hurtTime, 10, 0, remaining, provisionalBoundary, false);
        }

        if (!this.epochFallbackClockLocked) lockFallbackClock(now);
        long remaining = Math.max(0L, this.predictedBoundaryAt - now);
        int raw = (int)((remaining + TICK_MILLIS - 1L) / TICK_MILLIS);
        return new Clock("local500ms", raw, 10, 0, remaining, this.predictedBoundaryAt, true);
    }

    private void logClockIfChanged(Clock clock, EntityLivingBase target, double distance) {
        String sig = clock.name + ':' + clock.raw + ':' + clock.predictedBoundaryAt;
        if (sig.equals(this.lastClockLog)) return;
        this.lastClockLog = sig;
        if ("await-hurtTime".equals(clock.name)) return;
        if (clock.raw > 4 && clock.raw != 10 && (clock.raw & 1) != 0) return;
        appendLog("damageClock epoch=" + this.damageEpochId + " clock=" + clock.name + " raw=" + clock.raw
                + " msUntilDamageable=" + clock.msUntilDamageable
                + " predictedBoundaryAt=" + clock.predictedBoundaryAt
                + " target=" + (target == null || target.isNull() ? "none" : safeTargetName(target) + "#" + target.S())
                + " distance=" + (Double.isNaN(distance) ? "nan" : fmt(distance)));
    }

    private boolean updateApproach(double distance) {
        boolean approaching = !Double.isNaN(this.previousTargetDistance)
                && distance < this.previousTargetDistance - APPROACH_EPSILON;
        if (approaching) {
            if (this.approachStreak < 8) this.approachStreak++;
        } else if (!Double.isNaN(this.previousTargetDistance)
                && distance > this.previousTargetDistance + APPROACH_EPSILON) {
            this.approachStreak = 0;
        }
        return approaching;
    }

    private boolean passesApproachGate() {
        if (!this.requireApproaching.getEffectiveValue().booleanValue()) return true;
        return this.approachStreak >= 2;
    }

    private long getPingCompensationMillis() {
        if (!this.includePing.getEffectiveValue().booleanValue()) return 0L;
        long observed = AttackPacketTimingTracker.INSTANCE.getAverageHitDelay();
        if (observed <= 0L) return 0L;
        return Math.min(MAX_PING_COMPENSATION_MS, observed);
    }

    private void startBlocking(String reason, EntityLivingBase target,
                               double distance, boolean approaching, long now,
                               long boundaryAt, long desiredEndAt) {
        setBlocking(true);
        this.blockStartedAt = now;
        this.activeBlockReason = reason;
        this.activeBoundaryAt = boundaryAt;
        this.desiredBlockEndAt = desiredEndAt;
        transition(State.BLOCKING, reason, target, distance, approaching);
    }

    private void finishBlocking(String reason, EntityLivingBase target, double distance,
                                boolean approaching, long now, int hurtTime, int regen) {
        long age = this.blockStartedAt > 0L ? now - this.blockStartedAt : 0L;
        String completedReason = this.activeBlockReason;
        long boundaryAt = this.activeBoundaryAt;
        long desiredEnd = this.desiredBlockEndAt;
        long boundaryError = boundaryAt > 0L ? now - boundaryAt : Long.MIN_VALUE;
        setBlocking(false);
        this.blockStartedAt = 0L;
        this.activeBlockReason = "";
        this.activeBoundaryAt = 0L;
        this.desiredBlockEndAt = 0L;
        transition(this.damageCycle ? State.WAITING_VULNERABLE : State.FIRST_HIT_ARMED,
                reason, target, distance, approaching);
        appendLog("blockEnd reason=" + reason + " blockType=" + completedReason
                + " blockAgeMs=" + age + " hurtTime=" + hurtTime + " timeUntilRegen=" + regen
                + " firstHitSpent=" + this.firstHitSpent + " hurtWindowSpent=" + this.hurtWindowSpent
                + (boundaryAt > 0L ? " boundaryAt=" + boundaryAt + " desiredBlockEndAt=" + desiredEnd
                    + " boundaryErrorMs=" + boundaryError : ""));
        if ("hurt-window".equals(completedReason)) {
            appendLog("hurtWindowEnd epoch=" + this.damageEpochId + " reason=" + reason
                    + " actualHoldMs=" + age
                    + (boundaryAt > 0L ? " boundaryErrorMs=" + boundaryError : ""));
        }
    }

    private void setBlocking(boolean value) {
        if (this.blocking == value) return;
        this.blocking = value;
        if (value) {
            useKey().setPressed(true);
            return;
        }
        boolean preserveManual = false;
        try {
            preserveManual = !parent().ignoreManualBlock.getEffectiveValue().booleanValue()
                    && ClientSettings.isUseItemButtonDown();
        } catch (Throwable ignored) {
        }
        useKey().setPressed(preserveManual);
    }

    private KeyBinding useKey() {
        return Minecraft.gameSettings().b$src$Lgg_vape_wrapper_impl_KeyBinding_$1yi3362();
    }

    private void clearActiveTargetTracking(long now, String reason) {
        this.previousTargetDistance = Double.NaN;
        this.approachStreak = 0;
        this.outsideBlockRangeSince = 0L;
        this.blockStartedAt = 0L;
        this.activeBlockReason = "";
        this.activeBoundaryAt = 0L;
        this.desiredBlockEndAt = 0L;
        if (this.currentTargetId != Integer.MIN_VALUE && this.targetLostAt == 0L) this.targetLostAt = now;
        transition(this.damageCycle ? State.WAITING_VULNERABLE : State.IDLE,
                reason, null, Double.NaN, false);
    }

    private void resetContext(String reason) {
        setBlocking(false);
        this.state = State.IDLE;
        this.blockStartedAt = 0L;
        this.activeBlockReason = "";
        this.currentTargetId = Integer.MIN_VALUE;
        this.targetLostAt = 0L;
        this.previousTargetDistance = Double.NaN;
        this.approachStreak = 0;
        this.firstHitSpent = false;
        this.outsideBlockRangeSince = 0L;
        this.damageCycle = false;
        this.hurtWindowSpent = false;
        this.recentDamageAt = 0L;
        this.regenPeak = 0;
        this.damageEpochStartedAt = 0L;
        this.epochHurtClockLocked = false;
        this.epochFallbackClockLocked = false;
        this.epochHealthConfirmed = false;
        this.predictedBoundaryAt = 0L;
        this.activeBoundaryAt = 0L;
        this.desiredBlockEndAt = 0L;
        this.eventDamageHintAt = 0L;
        this.lastClockLog = "";
        appendLog("reset reason=" + reason);
    }

    private void resetAll(String reason) {
        resetContext(reason);
        this.lastRegen = -1;
        this.lastHurtTime = -1;
        this.lastHealth = Float.NaN;
    }

    private void sampleBaseline() {
        try {
            EntityPlayerSP p = Minecraft.thePlayer();
            if (p == null || p.isNull()) return;
            this.lastHealth = safeHealth(p);
            this.lastHurtTime = safeHurtTime(p);
            this.lastRegen = readTimeUntilRegen(p);
            logRegenFieldOnce();
        } catch (Throwable ignored) {
        }
    }

    private void transition(State next, String reason, EntityLivingBase target,
                            double distance, boolean approaching) {
        if (this.state == next) return;
        State previous = this.state;
        this.state = next;
        StringBuilder line = new StringBuilder();
        line.append(previous.name()).append(" -> ").append(next.name());
        line.append(" reason=").append(reason);
        if (target != null && target.isNotNull()) {
            line.append(" target=").append(safeTargetName(target)).append('#').append(target.S());
        }
        if (!Double.isNaN(distance)) {
            line.append(" distance=").append(fmt(distance)).append(" approaching=").append(approaching);
        }
        appendLog(line.toString());
    }

    private static int safeHurtTime(EntityPlayerSP p) {
        try { return p.c$src$I$15a9iwo(); } catch (Throwable ignored) { return -1; }
    }

    private static float safeHealth(EntityPlayerSP p) {
        try { return p.w$src$F$15l9epb(); } catch (Throwable ignored) { return Float.NaN; }
    }

    private int readTimeUntilRegen(EntityPlayerSP p) {
        try {
            Object raw = p.getObject();
            if (raw == null) return -1;
            Field f = resolveTimeUntilRegenField(raw.getClass());
            if (f == null) return -1;
            return f.getInt(raw);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static Field resolveTimeUntilRegenField(Class<?> runtimeClass) {
        Field cached = timeUntilRegenField;
        if (cached != null && timeUntilRegenOwner != null && timeUntilRegenOwner.isAssignableFrom(runtimeClass)) return cached;
        synchronized (SmartBlockHitModeV4.class) {
            cached = timeUntilRegenField;
            if (cached != null && timeUntilRegenOwner != null && timeUntilRegenOwner.isAssignableFrom(runtimeClass)) return cached;
            String[] names = new String[]{"timeUntilRegen", "field_6008", "ax"};
            for (String name : names) {
                Class<?> c = runtimeClass;
                while (c != null) {
                    // In the 1.21.11 official namespace the field is the very short
                    // name `ax`. Restrict that fallback to the known Entity owner so
                    // an unrelated subclass field with the same obfuscated name cannot
                    // be mistaken for the invulnerability timer.
                    if ("ax".equals(name) && !isEntityClockOwner(c)) {
                        c = c.getSuperclass();
                        continue;
                    }
                    try {
                        Field f = c.getDeclaredField(name);
                        if (f.getType() == Integer.TYPE) {
                            f.setAccessible(true);
                            timeUntilRegenField = f;
                            timeUntilRegenOwner = c;
                            timeUntilRegenFieldName = c.getName() + "." + name;
                            timeUntilRegenResolveAttempted = true;
                            return f;
                        }
                    } catch (Throwable ignored) {
                    }
                    c = c.getSuperclass();
                }
            }
            timeUntilRegenResolveAttempted = true;
            timeUntilRegenFieldName = "unavailable";
            return null;
        }
    }


    private static boolean isEntityClockOwner(Class<?> c) {
        String n = c.getName();
        return "cgk".equals(n)
                || "net.minecraft.class_1297".equals(n)
                || "net.minecraft.entity.Entity".equals(n)
                || "net.minecraft.world.entity.Entity".equals(n)
                || "net.minecraft.src.C_507_".equals(n);
    }

    private void logRegenFieldOnce() {
        if (this.regenFieldLogged || !timeUntilRegenResolveAttempted) return;
        this.regenFieldLogged = true;
        appendLog("damageClockField timeUntilRegen=" + timeUntilRegenFieldName);
    }

    private BlockHit parent() {
        return (BlockHit)getParent();
    }

    private static String safeTargetName(EntityLivingBase target) {
        try {
            String name = target.getName();
            return name == null ? "?" : name.replace('\n', '_').replace('\r', '_');
        } catch (Throwable ignored) {
            return "?";
        }
    }

    private static String fmt(double d) {
        return String.format(Locale.ROOT, "%.2f", d);
    }

    private static String fmtHealth(float f) {
        if (Float.isNaN(f)) return "nan";
        return String.format(Locale.ROOT, "%.2f", f);
    }

    private static void appendLog(String message) {
        PrintWriter out = null;
        try {
            String base = System.getenv("LOCALAPPDATA");
            File dir = base == null || base.isEmpty()
                    ? new File(System.getProperty("user.home"), "VapeTrainer")
                    : new File(base, "VapeTrainer");
            if (!dir.exists()) dir.mkdirs();
            File log = new File(dir, "smart-blockhit.log");
            out = new PrintWriter(new FileWriter(log, true));
            out.println("[SmartBlockHit] " + System.currentTimeMillis() + " " + message);
        } catch (Throwable ignored) {
            System.out.println("[SmartBlockHit] " + message);
        } finally {
            if (out != null) out.close();
        }
    }

    private static final class Clock {
        final String name;
        final int raw;
        final int peak;
        final int threshold;
        final long msUntilDamageable;
        final long predictedBoundaryAt;
        final boolean schedulable;

        Clock(String name, int raw, int peak, int threshold, long msUntilDamageable,
              long predictedBoundaryAt, boolean schedulable) {
            this.name = name;
            this.raw = raw;
            this.peak = peak;
            this.threshold = threshold;
            this.msUntilDamageable = msUntilDamageable;
            this.predictedBoundaryAt = predictedBoundaryAt;
            this.schedulable = schedulable;
        }
    }
}
