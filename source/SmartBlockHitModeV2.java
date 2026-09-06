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
import java.util.Locale;

/**
 * Experimental no-buffer AutoBlock mode.
 *
 * Smart deliberately does not hook packet send/receive and does not use Lag's
 * queue/PacketDispatchGuard path. It drives the same normal use-item KeyBinding
 * used by Predict/Manual.
 */
public class SmartBlockHitModeV2 extends BlockHitMode {
    private static final int DAMAGEABLE_HURT_RESISTANT_TIME = 10;
    private static final long TICK_MILLIS = 50L;
    private static final long RECENT_DAMAGE_WINDOW_MS = 1200L;
    private static final long REARM_AFTER_TIMEOUT_MS = 200L;
    private static final double APPROACH_EPSILON = 0.015D;
    private static final double POINT_BLANK_ALLOWANCE = 0.18D;
    private static final long MAX_PING_COMPENSATION_MS = 150L;

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
    private boolean damageObserved;
    private volatile boolean damageReleasePending;
    private long recentDamageAt;
    private long blockStartedAt;
    private long rearmNotBefore;
    private int currentTargetId = Integer.MIN_VALUE;
    private double previousTargetDistance = Double.NaN;
    private String blockReason = "";

    private enum State {
        IDLE,
        FIRST_HIT_ARMED,
        WAITING_VULNERABLE,
        BLOCKING,
        POST_HIT
    }

    public SmartBlockHitModeV2(Mod parent, String name) {
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
                "Milliseconds before the player becomes damageable again to start blocking");
        this.includePing = BooleanValue.create(
                this, "Include ping", true,
                "Adds the smoothed combat feedback delay to the early block window");
        this.maximumHoldDuration = NumberValue.create(
                this, "Maximum hold duration", "#", "ms", 50.0D, 125.0D, 500.0D, 5.0D,
                "Hard upper bound for a Smart-generated block");
        this.blockFirstHit = BooleanValue.create(
                this, "Block first hit", true,
                "Use range and approach geometry before the first incoming hit");
        this.requireApproaching = BooleanValue.create(
                this, "Require approaching", true,
                "Reject first-hit blocks while distance is increasing; point-blank stationary fights are still allowed");

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
        // Like Predict, Smart owns its KeyBinding state directly.
        return false;
    }

    @Override
    public boolean isBlocking() {
        return this.blocking;
    }

    @Override
    public void onEnable() {
        appendLog("SMART4-R9 tombstone active; legacy V2 handler disabled");
        resetAll("r9-tombstone-enable");
    }

    @Override
    public void onDisable() {
        resetAll("disable");
    }

    @EventHandler
    public void onWorldChange(EventWorldChange event) {
        resetAll("world-change");
    }

    @EventHandler
    public void onDamaged(EventLivingUpdate event) {
        // R9 tombstone: legacy V2 damage handler intentionally disabled.
    }

    @EventHandler
    public void onMouseButton(EventMouseButton event) {
        // R9 tombstone: legacy V2 mouse handler intentionally disabled.
    }

    @EventHandler
    public void onTick(EventPreTick event) {
        // R9 tombstone: release any legacy key state and do nothing else.
        setBlocking(false);
        this.blockStartedAt = 0L;
    }

    private boolean isRecentDamage(long now) {
        return this.damageObserved
                && this.recentDamageAt > 0L
                && now - this.recentDamageAt <= RECENT_DAMAGE_WINDOW_MS;
    }

    private boolean isApproaching(double currentDistance) {
        return !Double.isNaN(this.previousTargetDistance)
                && currentDistance < this.previousTargetDistance - APPROACH_EPSILON;
    }

    private boolean passesApproachGate(double distance, boolean approaching) {
        if (!this.requireApproaching.getEffectiveValue().booleanValue()) {
            return true;
        }
        return approaching;
    }

    private long getPingCompensationMillis() {
        if (!this.includePing.getEffectiveValue().booleanValue()) {
            return 0L;
        }
        long observed = AttackPacketTimingTracker.INSTANCE.getAverageHitDelay();
        if (observed <= 0L) {
            return 0L;
        }
        return Math.min(MAX_PING_COMPENSATION_MS, observed);
    }

    private long getEffectiveEarlyWindowMillis() {
        long base = Math.round(this.maximumHurtTime.getValue().doubleValue());
        return base + getPingCompensationMillis();
    }

    private void startBlocking(String reason, EntityLivingBase target,
                               double distance, boolean approaching, long now) {
        setBlocking(true);
        this.blockStartedAt = now;
        this.blockReason = reason;
        transition(State.BLOCKING, reason, target, distance, approaching, now);
    }

    private void setBlocking(boolean value) {
        if (this.blocking == value) {
            return;
        }
        this.blocking = value;
        if (value) {
            useKey().setPressed(true);
            return;
        }

        // If manual block is allowed and the user is physically holding use,
        // restore their input instead of fighting it.
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

    private void resetContext(String reason) {
        setBlocking(false);
        this.currentTargetId = Integer.MIN_VALUE;
        this.previousTargetDistance = Double.NaN;
        this.blockStartedAt = 0L;
        this.rearmNotBefore = 0L;
        this.damageObserved = false;
        this.damageReleasePending = false;
        this.recentDamageAt = 0L;
        this.blockReason = "";
        transition(State.IDLE, reason, null, Double.NaN, false, System.currentTimeMillis());
    }

    private void resetAll(String reason) {
        resetContext(reason);
    }

    private BlockHit parent() {
        return (BlockHit)getParent();
    }

    private void transition(State next, String reason, EntityLivingBase target,
                            double distance, boolean approaching, long now) {
        if (this.state == next) {
            return;
        }
        State previous = this.state;
        this.state = next;
        StringBuilder line = new StringBuilder();
        line.append(previous.name()).append(" -> ").append(next.name());
        line.append(" reason=").append(reason);
        if (target != null && target.isNotNull()) {
            line.append(" target=").append(safeTargetName(target));
            line.append("#").append(target.S());
        }
        if (!Double.isNaN(distance)) {
            line.append(" distance=").append(String.format(Locale.ROOT, "%.2f", distance));
            line.append(" approaching=").append(approaching);
        }
        appendLog(line.toString());
    }

    private void logDetail(String reason, EntityLivingBase target, double distance,
                           boolean approaching, int hurtResistantTime,
                           int ticksUntilDamageable, long earlyWindow, long blockAge) {
        StringBuilder line = new StringBuilder();
        line.append("state=").append(this.state.name());
        line.append(" reason=").append(reason);
        if (target != null && target.isNotNull()) {
            line.append(" target=").append(safeTargetName(target)).append("#").append(target.S());
        }
        line.append(" distance=").append(String.format(Locale.ROOT, "%.2f", distance));
        line.append(" approaching=").append(approaching);
        line.append(" legacyHurtResistantTime=").append(hurtResistantTime);
        try {
            EntityPlayerSP p = Minecraft.thePlayer();
            line.append(" hurtTime=").append(p == null || p.isNull() ? -1 : p.c$src$I$15a9iwo());
        } catch (Throwable ignored) { line.append(" hurtTime=-1"); }
        line.append(" ticksUntilDamageable=").append(ticksUntilDamageable);
        line.append(" effectiveEarlyWindow=").append(earlyWindow).append("ms");
        line.append(" pingCompensation=").append(getPingCompensationMillis()).append("ms");
        line.append(" blockAgeMs=").append(blockAge);
        appendLog(line.toString());
    }

    private static String safeTargetName(EntityLivingBase target) {
        try {
            String name = target.getName();
            return name == null ? "?" : name.replace('\n', '_').replace('\r', '_');
        } catch (Throwable ignored) {
            return "?";
        }
    }

    private static void appendLog(String message) {
        PrintWriter out = null;
        try {
            String base = System.getenv("LOCALAPPDATA");
            File dir = base == null || base.isEmpty()
                    ? new File(System.getProperty("user.home"), "VapeTrainer")
                    : new File(base, "VapeTrainer");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            File log = new File(dir, "smart-blockhit.log");
            out = new PrintWriter(new FileWriter(log, true));
            out.println("[SmartBlockHit] " + System.currentTimeMillis() + " " + message);
        } catch (Throwable ignored) {
            System.out.println("[SmartBlockHit] " + message);
        } finally {
            if (out != null) {
                out.close();
            }
        }
    }
}
