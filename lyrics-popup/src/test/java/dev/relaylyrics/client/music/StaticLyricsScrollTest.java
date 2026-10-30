package dev.relaylyrics.client.music;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StaticLyricsScrollTest {
    @Test
    void untimedLyricsKeepTheReadersPositionAfterTheTimedReadingTimeout() {
        var scroll = new ManualLyricsScroll();
        long input = 1_000_000_000L;
        scroll.nudge(0, 100, 0, 400, input);
        assertThat(scroll.target(0, 0, 400, input + 60_000_000_000L, false)).isEqualTo(100);
        scroll.nudge(100, 50, 0, 400, input + 61_000_000_000L);
        assertThat(scroll.target(0, 0, 400, input + 120_000_000_000L, false)).isEqualTo(150);
        scroll.reset();
        assertThat(scroll.target(0, 0, 400, input + 121_000_000_000L, false)).isZero();
    }

    @Test
    void timedLyricsStillReturnToTheSungLineAfterTheReadingTimeout() {
        var scroll = new ManualLyricsScroll();
        long input = 1_000_000_000L;
        scroll.nudge(0, 100, 0, 400, input);
        assertThat(scroll.target(20, 0, 400, input + ManualLyricsScroll.HOLD_NANOS, true)).isEqualTo(20);
    }
}
