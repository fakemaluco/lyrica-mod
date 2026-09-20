package dev.fandroid.client.music;

import dev.fandroid.protocol.music.MusicCapabilities;
import dev.fandroid.protocol.music.PlaybackSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class MusicPlaybackClockTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void extrapolatesAndCorrectsSmallJitterWithoutJumping() {
        AtomicLong nanos = new AtomicLong();
        MusicPlaybackClock clock = new MusicPlaybackClock(nanos::get, () -> NOW);
        clock.update(snapshot("track", 1_000, true));
        nanos.addAndGet(500_000_000);
        assertThat(clock.positionMillis()).isBetween(1_499L, 1_501L);
        clock.update(snapshot("track", 1_350, true));
        long immediately = clock.positionMillis();
        assertThat(immediately).isBetween(1_499L, 1_501L);
        nanos.addAndGet(700_000_000);
        assertThat(clock.positionMillis()).isGreaterThan(immediately);
    }

    @Test
    void snapsOnTrackChangeAndExplicitSeek() {
        AtomicLong nanos = new AtomicLong();
        MusicPlaybackClock clock = new MusicPlaybackClock(nanos::get, () -> NOW);
        clock.update(snapshot("one", 9_000, false));
        clock.update(snapshot("two", 500, false));
        assertThat(clock.positionMillis()).isEqualTo(500);
        clock.seekTo(7_500);
        assertThat(clock.positionMillis()).isEqualTo(7_500);
    }

    private static PlaybackSnapshot snapshot(String uri, long position, boolean playing) {
        return new PlaybackSnapshot("Track", "Artist", "Album", null, "spotify:track:" + uri,
                null, position, 20_000, playing, .5, false, "off", false, 0.0, 0, 0, 0.5,
                new double[0], NOW,
                new MusicCapabilities(true, true, true, true, true, true, true, true, false));
    }
}
