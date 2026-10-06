package dev.relaylyrics.client.music;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorldLyricsPlacementTest {
    private static WorldLyricsPlacement.Candidate ground(double distance, double relief) {
        return new WorldLyricsPlacement.Candidate(WorldLyricsPlacement.Surface.GROUND,
                distance, 14, 22, 1, relief, true, true, true, true);
    }

    @Test void rejectsUnsupportedWetOccludedAndSteppedSurfaces() {
        assertThat(WorldLyricsPlacement.score(ground(14, 0))).isFinite();
        assertThat(WorldLyricsPlacement.score(ground(14, .3))).isEqualTo(Double.NEGATIVE_INFINITY);
        assertThat(WorldLyricsPlacement.score(new WorldLyricsPlacement.Candidate(
                WorldLyricsPlacement.Surface.GROUND, 14, 14, 22, 1, 0,
                false, true, true, true))).isEqualTo(Double.NEGATIVE_INFINITY);
        assertThat(WorldLyricsPlacement.score(new WorldLyricsPlacement.Candidate(
                WorldLyricsPlacement.Surface.GROUND, 14, 14, 22, 1, 0,
                true, false, true, true))).isEqualTo(Double.NEGATIVE_INFINITY);
        assertThat(WorldLyricsPlacement.score(new WorldLyricsPlacement.Candidate(
                WorldLyricsPlacement.Surface.GROUND, 14, 14, 22, 1, 0,
                true, true, false, true))).isEqualTo(Double.NEGATIVE_INFINITY);
        assertThat(WorldLyricsPlacement.score(new WorldLyricsPlacement.Candidate(
                WorldLyricsPlacement.Surface.GROUND, 14, 14, 22, 1, 0,
                true, true, true, false))).isEqualTo(Double.NEGATIVE_INFINITY);
        assertThat(WorldLyricsPlacement.score(ground(14, 0))).isGreaterThan(WorldLyricsPlacement.score(ground(5.5, 0)));
    }

    @Test void pageTransitionsUseTimedBoundaryAndCanBeRevisitedAfterSeeking() {
        var pages = List.of(new WorldLyricsLayout.Page(List.of(), 0, 0, 1000),
                new WorldLyricsLayout.Page(List.of(), 0, 1000, 2000));
        assertThat(WorldLyricsPlacement.pageIndex(pages, 919)).isZero();
        assertThat(WorldLyricsPlacement.pageIndex(pages, 920)).isEqualTo(1);
        assertThat(WorldLyricsPlacement.pageIndex(pages, 1500)).isEqualTo(1);
        assertThat(WorldLyricsPlacement.pageIndex(pages, 500)).isZero();
    }
}
