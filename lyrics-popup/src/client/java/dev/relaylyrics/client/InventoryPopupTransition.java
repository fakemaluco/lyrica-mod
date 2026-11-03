package dev.relaylyrics.client;

import dev.relaylyrics.client.music.SpringValue;

/** Time-based visibility used when the HUD popup crosses an inventory boundary. */
final class InventoryPopupTransition {
    static final long HIDE_DURATION_NANOS = 1_000_000_000L;
    static final long SHOW_DURATION_NANOS = 1_000_000_000L;

    private final SpringValue gentleSpring;
    private long sampledNanos;
    InventoryPopupTransition() { this(false); }
    InventoryPopupTransition(boolean gentle) { gentleSpring=gentle?new SpringValue(1,4.5,.84):null; }

    private double value = 1;
    private double from = 1;
    private double target = 1;
    private long startedNanos;

    void setVisible(boolean visible, long nowNanos, boolean animated) {
        double requested = visible ? 1 : 0;
        double current = value(nowNanos, animated);
        if(gentleSpring!=null) {
            target=requested; gentleSpring.setGoal(requested); sampledNanos=nowNanos;
            if(!animated) gentleSpring.snap(requested);
            value=animated?current:requested;
            return;
        }
        if (!animated) {
            value = requested;
            from = requested;
            target = requested;
            startedNanos = nowNanos;
            return;
        }
        if (target == requested) return;
        value = current;
        from = current;
        target = requested;
        startedNanos = nowNanos;
    }

    double value(long nowNanos, boolean animated) {
        if(gentleSpring!=null) {
            double delta=Math.max(0,(nowNanos-sampledNanos)/1_000_000_000.0); sampledNanos=nowNanos;
            if(animated) gentleSpring.step(delta*.85); else gentleSpring.snap(target);
            value=Math.clamp(gentleSpring.position(),0,1);
            if(Math.abs(value-target)<.001) { value=target; gentleSpring.snap(target); }
            return value;
        }
        if (!animated) {
            value = target;
            return value;
        }
        if (value == target) return value;
        long duration = target < from ? HIDE_DURATION_NANOS : SHOW_DURATION_NANOS;
        double linear = Math.clamp((nowNanos - startedNanos) / (double) duration, 0, 1);
        double eased = easeInOutBack(linear);
        value = from + (target - from) * eased;
        if (linear >= 1) value = target;
        return value;
    }

    boolean isFullyVisible() {
        return target == 1 && value == 1;
    }

    private static double easeInOutBack(double value) {
        double clamped = Math.clamp(value, 0, 1);
        double overshoot = 1.70158;
        double boosted = overshoot * 1.525;
        if (clamped < .5) {
            double doubled = 2 * clamped;
            return doubled * doubled * ((boosted + 1) * doubled - boosted) / 2;
        }
        double shifted = 2 * clamped - 2;
        return (shifted * shifted * ((boosted + 1) * shifted + boosted) + 2) / 2;
    }
}
