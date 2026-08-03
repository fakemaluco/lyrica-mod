package dev.fandroid.client.music;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ManualLyricsScrollTest {
    @Test
    void manualTargetIsHeldForThreeSecondsThenReturnsToSync() {
        ManualLyricsScroll scroll = new ManualLyricsScroll();
        long started = 1_000_000_000L;
        scroll.nudge(120, 60, 20, 400, started);

        assertThat(scroll.target(250, 20, 400, started + ManualLyricsScroll.HOLD_NANOS - 1))
                .isEqualTo(180);
        assertThat(scroll.target(250, 20, 400, started + ManualLyricsScroll.HOLD_NANOS))
                .isEqualTo(250);
    }

    @Test
    void everyNudgeRestartsTheHoldAndTargetsAreClamped() {
        ManualLyricsScroll scroll = new ManualLyricsScroll();
        scroll.nudge(100, -500, 20, 400, 1_000);
        assertThat(scroll.target(200, 20, 400, 2_000)).isEqualTo(20);

        scroll.nudge(999, 600, 20, 400, 2_000_000_000L);
        assertThat(scroll.target(200, 20, 400, 4_999_999_999L)).isEqualTo(400);
        assertThat(scroll.active(4_999_999_999L)).isTrue();
    }

    @Test
    void scrollingStateEndsBeforeTheThreeSecondSyncHold() {
        ManualLyricsScroll scroll = new ManualLyricsScroll();
        long started = 1_000_000_000L;
        scroll.nudge(120, 60, 20, 400, started);

        assertThat(scroll.scrolling(started + ManualLyricsScroll.SCROLL_ACTIVITY_NANOS - 1)).isTrue();
        assertThat(scroll.scrolling(started + ManualLyricsScroll.SCROLL_ACTIVITY_NANOS)).isFalse();
        assertThat(scroll.active(started + ManualLyricsScroll.SCROLL_ACTIVITY_NANOS)).isTrue();
    }
}
