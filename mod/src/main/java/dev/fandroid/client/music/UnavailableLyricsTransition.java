package dev.fandroid.client.music;

import java.util.Objects;
import java.util.function.LongSupplier;

/** Wall-clock presentation of a terminal lyric lookup result, independent of playback position. */
public final class UnavailableLyricsTransition {
    public static final long NOTICE_MILLIS = 10_000;
    public static final long FADE_MILLIS = 650;
    private final LongSupplier nanoTime;
    private String track;
    private boolean unavailable;
    private long unavailableNanos;

    public UnavailableLyricsTransition() { this(System::nanoTime); }

    public UnavailableLyricsTransition(LongSupplier nanoTime) {
        this.nanoTime = Objects.requireNonNull(nanoTime);
    }

    public synchronized void track(String key) {
        if (!Objects.equals(track, key)) {
            track = key;
            reset();
        }
    }

    /** Loading, retrying and successfully loaded lyrics restore the full lyric surface. */
    public synchronized void reset() { unavailable = false; }

    /** Repeated lookup/status updates for the same result must not postpone the fade. */
    public synchronized void unavailable() {
        if (unavailable) return;
        unavailable = true;
        unavailableNanos = nanoTime.getAsLong();
    }

    public synchronized State sample(boolean animated) {
        if (!unavailable) return new State(1);
        double elapsedMillis = Math.max(0, (nanoTime.getAsLong() - unavailableNanos) / 1_000_000.0);
        if (elapsedMillis <= NOTICE_MILLIS) return new State(1);
        if (!animated) return new State(0);
        double fade = smooth((elapsedMillis - NOTICE_MILLIS) / FADE_MILLIS);
        return new State(1 - fade);
    }

    private static double smooth(double value) {
        double t = Math.max(0, Math.min(1, value));
        return t * t * (3 - 2 * t);
    }

    /** Notice opacity only: lookup status never changes the popup's configured bounds. */
    public record State(double messageOpacity) { }
}
