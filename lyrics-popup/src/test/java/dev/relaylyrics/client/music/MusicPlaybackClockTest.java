package dev.relaylyrics.client.music;

import dev.relaylyrics.protocol.music.MusicCapabilities;
import dev.relaylyrics.protocol.music.PlaybackSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class MusicPlaybackClockTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void replacesRepeatedCorrectionInsteadOfAccumulatingLatency() {
        AtomicLong nanos = new AtomicLong();
        MusicPlaybackClock clock = new MusicPlaybackClock(nanos::get, () -> NOW);
        clock.update(snapshot(1_000, true));
        nanos.addAndGet(250_000_000);
        clock.update(snapshot(1_150, true));
        clock.update(snapshot(1_150, true));
        nanos.addAndGet(250_000_000);

        assertThat(clock.positionMillis()).isBetween(1_420L, 1_510L);
    }

    @Test
    void snapsWhenPlaybackStateChanges() {
        AtomicLong nanos = new AtomicLong();
        MusicPlaybackClock clock = new MusicPlaybackClock(nanos::get, () -> NOW);
        clock.update(snapshot(3_000, true));
        nanos.addAndGet(200_000_000);
        clock.update(snapshot(3_050, false));
        assertThat(clock.positionMillis()).isEqualTo(3_050);
    }

    @Test
    void repeatedWindowsPositionDoesNotFreezeLyrics() {
        AtomicLong nanos = new AtomicLong();
        MusicPlaybackClock clock = new MusicPlaybackClock(nanos::get, () -> NOW);
        for (int index = 0; index < 8; index++) {
            clock.update(snapshot(1_000, true));
            nanos.addAndGet(500_000_000);
        }
        assertThat(clock.positionMillis()).isBetween(4_950L, 5_050L);
        clock.update(snapshot(2_000, false));
        assertThat(clock.positionMillis()).isEqualTo(2_000);
    }

    @Test
    void renderStallsDoNotDiscardElapsedMusicTime() {
        AtomicLong nanos = new AtomicLong();
        MusicPlaybackClock clock = new MusicPlaybackClock(nanos::get, () -> NOW);
        clock.update(snapshot(1_000, true));
        nanos.addAndGet(8_000_000_000L);
        assertThat(clock.positionMillis()).isEqualTo(9_000);
        clock.update(snapshot(9_000, false));
        nanos.addAndGet(8_000_000_000L);
        assertThat(clock.positionMillis()).isEqualTo(9_000);
    }

    @Test
    void repeatedReportsCannotCancelACorrectionForLyricsThatAreAhead() {
        AtomicLong nanos = new AtomicLong();
        MusicPlaybackClock clock = new MusicPlaybackClock(nanos::get, () -> NOW);
        clock.update(snapshot(1_000, true));
        nanos.addAndGet(500_000_000);
        clock.update(snapshot(1_100, true));
        for (int index = 0; index < 20; index++) {
            nanos.addAndGet(50_000_000);
            clock.update(snapshot(1_100, true));
        }
        assertThat(clock.positionMillis()).isBetween(2_100L, 2_115L);
    }

    @Test
    void pausingOnARepeatedRawPositionCannotPreserveAnAdvancedLyricClock() {
        AtomicLong nanos = new AtomicLong();
        MusicPlaybackClock clock = new MusicPlaybackClock(nanos::get, () -> NOW);
        clock.update(snapshot(1_000, true));
        nanos.addAndGet(600_000_000);
        clock.update(snapshot(1_000, false));
        nanos.addAndGet(1_000_000_000);
        assertThat(clock.positionMillis()).isEqualTo(1_000);
    }

    private static PlaybackSnapshot snapshot(long position, boolean playing) {
        return new PlaybackSnapshot("Track", "Artist", "Album", null,
                "spotify:track:1234567890123456789012", null, position, 20_000,
                playing, .5, false, "off", false, 0, 0, 0, .5,
                new double[0], NOW,
                new MusicCapabilities(true, true, true, true, true, true, true, true, false));
    }
}
