package dev.fandroid.client.music;

import java.util.Objects;
import java.util.function.LongSupplier;

/** Keeps the visual timeline continuous while the underlying track changes instantly. */
public final class PlaybackProgressTransition {
    static final long DURATION_NANOS = 700_000_000L;

    private final LongSupplier nanoTime;
    private String trackKey = "";
    private double displayed;
    private double from;
    private double target;
    private long startedNanos;
    private boolean initialized;
    private boolean transitioning;

    public PlaybackProgressTransition() {
        this(System::nanoTime);
    }

    PlaybackProgressTransition(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
    }

    public synchronized double update(String nextTrackKey, long positionMillis,
                                      long durationMillis, boolean animated) {
        long now = nanoTime.getAsLong();
        String key = nextTrackKey == null ? "" : nextTrackKey;
        double next = fraction(positionMillis, durationMillis);
        if (!initialized) {
            set(key, next, now);
            return displayed;
        }
        if (!animated) {
            set(key, next, now);
            return displayed;
        }
        if (!Objects.equals(trackKey, key)) {
            sample(now);
            trackKey = key;
            from = displayed;
            target = next;
            startedNanos = now;
            transitioning = Math.abs(target - from) > .0001;
            return displayed;
        }
        if (!transitioning) {
            displayed = next;
            target = next;
            return displayed;
        }
        // The new track keeps advancing while the visual transition runs.
        target = next;
        sample(now);
        return displayed;
    }

    public synchronized void snap(String nextTrackKey, long positionMillis, long durationMillis) {
        set(nextTrackKey == null ? "" : nextTrackKey,
                fraction(positionMillis, durationMillis), nanoTime.getAsLong());
    }

    private void sample(long now) {
        if (!transitioning) return;
        double linear = Math.clamp((now - startedNanos) / (double) DURATION_NANOS, 0, 1);
        displayed = from + (target - from) * easeInOutQuint(linear);
        if (linear >= 1) {
            displayed = target;
            transitioning = false;
        }
    }

    private void set(String key, double value, long now) {
        trackKey = key;
        displayed = value;
        from = value;
        target = value;
        startedNanos = now;
        initialized = true;
        transitioning = false;
    }

    private static double fraction(long positionMillis, long durationMillis) {
        if (durationMillis <= 0) return 0;
        return Math.clamp(positionMillis / (double) durationMillis, 0, 1);
    }

    private static double easeInOutQuint(double value) {
        double clamped = Math.clamp(value, 0, 1);
        return clamped < .5
                ? 16 * Math.pow(clamped, 5)
                : 1 - Math.pow(-2 * clamped + 2, 5) / 2;
    }
}
