package dev.relaylyrics.client.music;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorldLyricsEntranceTest {
    @Test void disablingGlobalAnimationsLeavesEveryPresentationStatic() {
        for (var entrance : WorldLyricsDirector.Entrance.values()) {
            assertThat(WorldLyricsMotion.page(entrance, 1000, 3000, 500, 4, 2, 1, false))
                    .isEqualTo(WorldLyricsMotion.Pose.STATIC);
            assertThat(WorldLyricsMotion.page(entrance, 1000, 3000, 3300, 4, 2, 1, false))
                    .isEqualTo(WorldLyricsMotion.Pose.STATIC);
        }
    }

    @Test void landingBeginsAboveTheAnchorAndExitWaitsForTheSungPage() {
        var entering = WorldLyricsMotion.page(WorldLyricsDirector.Entrance.LAND,
                1000, 3000, 700, 4, 1, 1, true);
        var singing = WorldLyricsMotion.page(WorldLyricsDirector.Entrance.LAND,
                1000, 3000, 2000, 4, 1, 1, true);
        var exiting = WorldLyricsMotion.page(WorldLyricsDirector.Entrance.LAND,
                1000, 3000, 3250, 4, 1, 1, true);
        assertThat(entering.vertical()).isPositive();
        assertThat(singing.vertical()).isZero();
        assertThat(exiting.zoom()).isLessThan(singing.zoom());
    }
}
