package dev.fandroid.client.music;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LyricsViewportTest {
    @Test
    void findsCurrentGroupWithoutScanningTheDocument() {
        long[] starts = {1_000, 2_000, 3_500, 9_000};

        assertThat(LyricsViewport.activeIndex(starts, 0)).isZero();
        assertThat(LyricsViewport.activeIndex(starts, 3_499)).isEqualTo(1);
        assertThat(LyricsViewport.activeIndex(starts, 3_500)).isEqualTo(2);
        assertThat(LyricsViewport.activeIndex(starts, 99_000)).isEqualTo(3);
        assertThat(LyricsViewport.activeIndex(new long[0], 1_000)).isEqualTo(-1);
    }

    @Test
    void mountsOnlyTheVisibleWindowWithBoundedOverscan() {
        double[] starts = {0, 40, 80, 120, 160, 200};
        double[] ends = {30, 70, 110, 150, 190, 230};

        LyricsViewport.Range range = LyricsViewport.visibleRange(starts, ends, 85, 165, 1);

        assertThat(range.firstInclusive()).isEqualTo(1);
        assertThat(range.lastExclusive()).isEqualTo(6);
        assertThat(range.size()).isEqualTo(5);
    }

    @Test
    void returnsAnEmptyRangeForAnEmptyDocument() {
        assertThat(LyricsViewport.visibleRange(new double[0], new double[0], 0, 100, 2))
                .isEqualTo(LyricsViewport.Range.EMPTY);
    }

    @Test
    void keepsTheMountedWindowBoundedForVeryLargeDocuments() {
        int count = 10_000;
        double[] starts = new double[count];
        double[] ends = new double[count];
        for (int index = 0; index < count; index++) {
            starts[index] = index * 48.0;
            ends[index] = starts[index] + 36;
        }

        LyricsViewport.Range range = LyricsViewport.visibleRange(
                starts, ends, starts[5_000] - 100, starts[5_000] + 220, 1);

        assertThat(range.size()).isLessThanOrEqualTo(10);
        assertThat(range.firstInclusive()).isBetween(4_996, 5_000);
        assertThat(range.lastExclusive()).isBetween(5_004, 5_008);
    }

    @Test
    void anticipatesSlowLinesWithoutDraggingFastPassagesTooEarly() {
        double slow = LyricsViewport.predictiveCenter(100, 200, 2_750, 1_000, 2_700, 3_000);
        double fast = LyricsViewport.predictiveCenter(100, 200, 1_700, 1_000, 1_750, 1_800);

        assertThat(slow).isBetween(118.0, 172.0);
        assertThat(fast).isBetween(100.0, 111.0);
        assertThat(LyricsViewport.predictiveCenter(100, 200, 3_000, 1_000, 2_700, 3_000))
                .isEqualTo(200);
    }
}
