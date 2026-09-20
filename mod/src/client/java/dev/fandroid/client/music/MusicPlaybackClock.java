package dev.fandroid.client.music;

import dev.fandroid.protocol.music.PlaybackSnapshot;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Smooth monotonic playback clock that gently absorbs normal snapshot jitter. */
public final class MusicPlaybackClock {
    private static final double CORRECTION_SECONDS = .24;
    private static final long SNAP_THRESHOLD_MILLIS = 750;
    private static final long JITTER_TOLERANCE_MILLIS = 18;

    private final LongSupplier nanoTime;
    private final Supplier<Instant> wallTime;
    private long lastNanos;
    private double positionMillis;
    private double pendingCorrection;
    private long durationMillis;
    private boolean playing;
    private String trackKey = "";
    private boolean initialized;

    public MusicPlaybackClock() {
        this(System::nanoTime, Instant::now);
    }

    MusicPlaybackClock(LongSupplier nanoTime) {
        this(nanoTime, Instant::now);
    }

    MusicPlaybackClock(LongSupplier nanoTime, Supplier<Instant> wallTime) {
        this.nanoTime = nanoTime;
        this.wallTime = wallTime;
    }

    public synchronized void update(PlaybackSnapshot snapshot) {
        long now = nanoTime.getAsLong();
        advance(now);
        String nextKey = key(snapshot);
        double observed = observedPosition(snapshot);
        boolean discontinuity = !initialized || !Objects.equals(trackKey, nextKey)
                || Math.abs(observed - positionMillis) >= SNAP_THRESHOLD_MILLIS;
        boolean playbackStateChanged = initialized && playing != snapshot.playing();
        durationMillis = Math.max(0, snapshot.durationMillis());
        playing = snapshot.playing();
        trackKey = nextKey;
        if (discontinuity) {
            positionMillis = observed;
            pendingCorrection = 0;
        } else if (playbackStateChanged) {
            positionMillis = observed;
            pendingCorrection = 0;
        } else {
            double error = observed - positionMillis;
            pendingCorrection = Math.abs(error) <= JITTER_TOLERANCE_MILLIS ? 0 : error;
        }
        positionMillis = clamp(positionMillis);
        lastNanos = now;
        initialized = true;
    }

    public synchronized long positionMillis() {
        advance(nanoTime.getAsLong());
        return Math.round(clamp(positionMillis));
    }

    public synchronized void seekTo(long positionMillis) {
        this.positionMillis = clamp(positionMillis);
        pendingCorrection = 0;
        lastNanos = nanoTime.getAsLong();
        initialized = true;
    }

    private void advance(long now) {
        if (!initialized) {
            lastNanos = now;
            return;
        }
        double delta = Math.clamp((now - lastNanos) / 1_000_000_000.0, 0, 2.0);
        lastNanos = now;
        if (playing) positionMillis += delta * 1_000;
        if (Math.abs(pendingCorrection) > .05) {
            double amount = 1 - Math.exp(-delta / CORRECTION_SECONDS);
            double correction = pendingCorrection * amount;
            positionMillis += correction;
            pendingCorrection -= correction;
        }
        positionMillis = clamp(positionMillis);
    }

    private double clamp(double value) {
        return Math.clamp(value, 0, durationMillis <= 0 ? Math.max(0, value) : durationMillis);
    }

    private double observedPosition(PlaybackSnapshot snapshot) {
        long value = Math.max(0, snapshot.positionMillis());
        Instant observed = snapshot.observedAt();
        if (snapshot.playing() && observed != null) {
            value += Math.clamp(Duration.between(observed, wallTime.get()).toMillis(), 0, 2_000);
        }
        return snapshot.durationMillis() <= 0 ? value : Math.min(value, snapshot.durationMillis());
    }

    private static String key(PlaybackSnapshot snapshot) {
        if (snapshot.spotifyUri() != null && !snapshot.spotifyUri().isBlank()) return snapshot.spotifyUri();
        return String.valueOf(snapshot.title()) + '\n' + snapshot.artist() + '\n' + snapshot.album();
    }
}
