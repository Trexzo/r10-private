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
 * SMART4/R9 defensive BlockHit timing.
 *
 * Design goals:
 * - normal use-item KeyBinding only; no packet buffering/cancellation;
 * - one geometry-based first-hit attempt per genuine range encounter;
 * - after damage, use the local damageability clock instead of range pulsing;
 * - release promptly and minimize movement lost to unnecessary blocking.
 */
public class SmartBlockHitModeV3 extends BlockHitMode {
    private static final long TICK_MILLIS = 50L;
    private static final long RECENT_DAMAGE_WINDOW_MS = 1000L;
    private static final long TARGET_LOSS_GRACE_MS = 350L;
    private static final long RANGE_EXIT_CONFIRM_MS = 100L;
    private static final long LOCAL_DAMAGE_FALLBACK_MS = 500L;
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
    private int regenPeak;
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

    public SmartBlockHitModeV3(Mod parent, String name) {
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
        appendLog("SMART5-R10 tombstone active; legacy V3 handler disabled");
        resetAll("r10-v3-tombstone-enable");
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
     * EventLivingUpdate is retained only as a hint. R9 does not treat it alone as
     * proof of damage because the 1.21.11 runtime trace showed it can be displaced
     * from the actual local hurt countdown.
     */
    @EventHandler
    public void onDamaged(EventLivingUpdate event) {
        // R10 tombstone: legacy V3 damage handler intentionally disabled.
    }

    @EventHandler
    public void onMouseButton(EventMouseButton event) {
        // R10 tombstone: legacy V3 mouse handler intentionally disabled.
    }

    @EventHandler
    public void onTick(EventPreTick event) {
        // R10 tombstone: release any legacy key state and do nothing else.
        setBlocking(false);
        this.blockStartedAt = 0L;
        this.activeBlockReason = "";
    }

    private void observeDamage(String source, EntityPlayerSP player, long now,
                               float health, int hurtTime, int regen) {
        // Multiple local signals may rise on adjacent ticks for the same hit.
        if (this.damageCycle && this.recentDamageAt > 0L && now - this.recentDamageAt < 150L) {
            if (regen > this.regenPeak) this.regenPeak = regen;
            return;
        }
        setBlocking(false);
        this.blockStartedAt = 0L;
        this.activeBlockReason = "";
        this.damageCycle = true;
        this.hurtWindowSpent = false;
        this.recentDamageAt = now;
        this.regenPeak = Math.max(0, regen);
        this.firstHitSpent = true;
        long hintAge = this.eventDamageHintAt > 0L ? Math.max(0L, now - this.eventDamageHintAt) : -1L;
        appendLog("damageObserved source=" + source
                + " health=" + fmtHealth(health) + " previousHealth=" + fmtHealth(this.lastHealth)
                + " hurtTime=" + hurtTime + " timeUntilRegen=" + regen
                + " regenField=" + timeUntilRegenFieldName
                + " eventHintAgeMs=" + hintAge + " targetId=" + this.currentTargetId);
        transition(State.POST_HIT, "damage", null, Double.NaN, false);
    }

    private void expireDamageCycleIfNeeded(long now, int hurtTime, int regen) {
        if (!this.damageCycle) return;
        boolean clocksFinished = regen <= 0 && hurtTime <= 0;
        if (clocksFinished && now - this.recentDamageAt >= LOCAL_DAMAGE_FALLBACK_MS) {
            appendLog("damageCycleComplete hurtTime=" + hurtTime + " timeUntilRegen=" + regen
                    + " firstHitSpent=" + this.firstHitSpent);
            this.damageCycle = false;
            this.hurtWindowSpent = false;
            this.recentDamageAt = 0L;
            this.regenPeak = 0;
            this.lastClockLog = "";
            transition(this.currentTargetId == Integer.MIN_VALUE ? State.IDLE : State.FIRST_HIT_ARMED,
                    "damage-cycle-complete", null, Double.NaN, false);
        } else if (now - this.recentDamageAt > RECENT_DAMAGE_WINDOW_MS) {
            appendLog("damageCycleTimeout hurtTime=" + hurtTime + " timeUntilRegen=" + regen);
            this.damageCycle = false;
            this.hurtWindowSpent = false;
            this.recentDamageAt = 0L;
            this.regenPeak = 0;
            this.lastClockLog = "";
        }
    }

    private Clock getDamageClock(long now, int hurtTime, int regen) {
        if (regen > 0) {
            if (regen > this.regenPeak) this.regenPeak = regen;
            int threshold = this.regenPeak > 10 ? 10 : 0;
            int ticks = Math.max(0, regen - threshold);
            return new Clock("timeUntilRegen", regen, this.regenPeak, threshold, ticks * TICK_MILLIS);
        }
        if (hurtTime > 0) {
            return new Clock("hurtTime", hurtTime, 10, 0, Math.max(0, hurtTime) * TICK_MILLIS);
        }
        long remaining = Math.max(0L, LOCAL_DAMAGE_FALLBACK_MS - Math.max(0L, now - this.recentDamageAt));
        return new Clock("local500ms", (int)((remaining + TICK_MILLIS - 1L) / TICK_MILLIS), 10, 0, remaining);
    }

    private void logClockIfChanged(Clock clock, EntityLivingBase target, double distance) {
        String sig = clock.name + ':' + clock.raw + ':' + clock.threshold;
        if (sig.equals(this.lastClockLog)) return;
        this.lastClockLog = sig;
        // Keep diagnostics useful without logging every render frame. PreTick is 20 Hz;
        // log the countdown edge values and all short final-window values.
        if (clock.raw > 4 && (clock.raw & 1) != 0) return;
        appendLog("damageClock clock=" + clock.name + " raw=" + clock.raw
                + " peak=" + clock.peak + " threshold=" + clock.threshold
                + " msUntilDamageable=" + clock.msUntilDamageable
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
                               double distance, boolean approaching, long now) {
        setBlocking(true);
        this.blockStartedAt = now;
        this.activeBlockReason = reason;
        transition(State.BLOCKING, reason, target, distance, approaching);
    }

    private void finishBlocking(String reason, EntityLivingBase target, double distance,
                                boolean approaching, long now, int hurtTime, int regen) {
        long age = this.blockStartedAt > 0L ? now - this.blockStartedAt : 0L;
        String completedReason = this.activeBlockReason;
        setBlocking(false);
        this.blockStartedAt = 0L;
        this.activeBlockReason = "";
        transition(this.damageCycle ? State.WAITING_VULNERABLE : State.FIRST_HIT_ARMED,
                reason, target, distance, approaching);
        appendLog("blockEnd reason=" + reason + " blockType=" + completedReason
                + " blockAgeMs=" + age + " hurtTime=" + hurtTime + " timeUntilRegen=" + regen
                + " firstHitSpent=" + this.firstHitSpent + " hurtWindowSpent=" + this.hurtWindowSpent);
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
        synchronized (SmartBlockHitModeV3.class) {
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

        Clock(String name, int raw, int peak, int threshold, long msUntilDamageable) {
            this.name = name;
            this.raw = raw;
            this.peak = peak;
            this.threshold = threshold;
            this.msUntilDamageable = msUntilDamageable;
        }
    }
}
