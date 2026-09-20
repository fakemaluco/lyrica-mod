package dev.fandroid.client.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LyricsOverlayInterludeTest {
    @Test
    void interludeDoesNotReserveSpaceOutsideTheSilentInterval() {
        assertThat(LyricsOverlay.interludeLayoutVisibility(0, 3_000)).isZero();
        assertThat(LyricsOverlay.interludeLayoutVisibility(3_000, 3_000)).isZero();
        assertThat(LyricsOverlay.interludeLayoutVisibility(500, 1_499)).isZero();
    }

    @Test
    void interludeOpensThenClosesSmoothly() {
        long gap = 3_000;
        double early = LyricsOverlay.interludeLayoutVisibility(80, gap);
        double entered = LyricsOverlay.interludeLayoutVisibility(320, gap);
        double middle = LyricsOverlay.interludeLayoutVisibility(1_500, gap);
        double leaving = LyricsOverlay.interludeLayoutVisibility(2_920, gap);

        assertThat(early).isBetween(0.0, 1.0);
        assertThat(entered).isEqualTo(1);
        assertThat(middle).isEqualTo(1);
        assertThat(leaving).isBetween(0.0, 1.0);
        assertThat(early).isLessThan(entered);
        assertThat(leaving).isLessThan(middle);
    }
}
