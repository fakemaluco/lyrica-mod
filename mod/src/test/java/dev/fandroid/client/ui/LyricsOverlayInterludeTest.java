package dev.fandroid.client.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LyricsOverlayInterludeTest {
    @Test
    void interludeDoesNotReserveSpaceOutsideTheSilentInterval() {
        assertThat(LyricsOverlay.interludeLayoutVisibility(0, 3_000)).isZero();
        assertThat(LyricsOverlay.interludeLayoutVisibility(3_000, 3_000)).isZero();
        assertThat(LyricsOverlay.interludeLayoutVisibility(500, 2_999)).isZero();
    }

    @Test
    void interludeOpensThenClosesSmoothly() {
        long gap = 3_000;
        double early = LyricsOverlay.interludeLayoutVisibility(70, gap);
        double entered = LyricsOverlay.interludeLayoutVisibility(140, gap);
        double middle = LyricsOverlay.interludeLayoutVisibility(1_500, gap);
        double leaving = LyricsOverlay.interludeLayoutVisibility(2_700, gap);
        double hidden = LyricsOverlay.interludeLayoutVisibility(2_900, gap);

        assertThat(early).isBetween(0.0, 1.0);
        assertThat(entered).isEqualTo(1);
        assertThat(middle).isEqualTo(1);
        assertThat(leaving).isBetween(0.0, 1.0);
        assertThat(hidden).isZero();
        assertThat(early).isLessThan(entered);
        assertThat(leaving).isLessThan(middle);
    }
}
