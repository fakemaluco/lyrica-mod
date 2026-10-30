package dev.fandroid.client.music;

/** Holds a manually selected lyric camera position before returning to synchronized playback. */
public final class ManualLyricsScroll {
    public static final long HOLD_NANOS = 3_000_000_000L;
    public static final long SCROLL_ACTIVITY_NANOS = 220_000_000L;

    private boolean active;
    private double target;
    private long lastInputNanos;

    public void nudge(double renderedPosition, double amount, double minimum, double maximum, long nowNanos) {
        double low = Math.min(minimum, maximum);
        double high = Math.max(minimum, maximum);
        double origin = active ? target : renderedPosition;
        target = Math.clamp(origin + amount, low, high);
        lastInputNanos = nowNanos;
        active = true;
    }

    public double target(double synchronizedPosition, double minimum, double maximum, long nowNanos) {
        return target(synchronizedPosition, minimum, maximum, nowNanos, true);
    }

    /** Untimed lyrics have no active line to return to, so the reader keeps their position. */
    public double target(double synchronizedPosition, double minimum, double maximum,
                         long nowNanos, boolean timed) {
        if (timed && active && nowNanos - lastInputNanos >= HOLD_NANOS) active = false;
        if (!active) return synchronizedPosition;
        target = Math.clamp(target, Math.min(minimum, maximum), Math.max(minimum, maximum));
        return target;
    }

    public boolean active(long nowNanos) {
        return active && nowNanos - lastInputNanos < HOLD_NANOS;
    }

    /** True only while wheel events are actively moving the lyric camera. */
    public boolean scrolling(long nowNanos) {
        return active && nowNanos - lastInputNanos < SCROLL_ACTIVITY_NANOS;
    }

    public void reset() {
        active = false;
        target = 0;
        lastInputNanos = 0;
    }
}
