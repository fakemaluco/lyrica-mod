package dev.fandroid.client.music;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

final class PlaybackProgressTransitionTest {
    @Test
    void movesFromThePreviousTrackPositionToTheNewOne() {
        AtomicLong nanos = new AtomicLong();
        PlaybackProgressTransition transition = new PlaybackProgressTransition(nanos::get);

        assertThat(transition.update("one", 8_000, 10_000, true)).isEqualTo(.8);
        assertThat(transition.update("two", 500, 10_000, true)).isEqualTo(.8);

        nanos.addAndGet(PlaybackProgressTransition.DURATION_NANOS / 2);
        assertThat(transition.update("two", 850, 10_000, true)).isBetween(.43, .45);
        nanos.addAndGet(PlaybackProgressTransition.DURATION_NANOS / 2);
        assertThat(transition.update("two", 1_200, 10_000, true)).isEqualTo(.12);
    }

    @Test
    void explicitSeekAndDisabledAnimationsSnapImmediately() {
        AtomicLong nanos = new AtomicLong();
        PlaybackProgressTransition transition = new PlaybackProgressTransition(nanos::get);
        transition.update("one", 9_000, 10_000, true);
        transition.update("two", 0, 10_000, true);

        transition.snap("two", 6_000, 10_000);
        assertThat(transition.update("two", 6_000, 10_000, true)).isEqualTo(.6);
        assertThat(transition.update("three", 2_000, 10_000, false)).isEqualTo(.2);
    }
}
