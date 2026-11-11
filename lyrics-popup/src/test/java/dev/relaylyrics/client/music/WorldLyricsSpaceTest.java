package dev.relaylyrics.client.music;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class WorldLyricsSpaceTest {
    @Test void movementAndCompactRecoveryRespectThePlacementRange() {
        var range = WorldLyricsSpace.placementRange(8, 25, 96);
        assertThat(WorldLyricsSpace.readingDistance(0, 2, 20, false, range)).isEqualTo(8);
        assertThat(WorldLyricsSpace.readingDistance(40, 2, 20, true, range)).isEqualTo(25);
        assertThat(WorldLyricsSpace.placementRange(40, 60, 28)).isEqualTo(new WorldLyricsSpace.DistanceRange(28, 28));
        assertThat(WorldLyricsRecovery.compactProbes(25, 4, 1.2, range.minimum())).isNotEmpty()
                .allSatisfy(probe -> assertThat(probe.distance()).isBetween(8.0, 25.0));
    }
    @Test void elytraReadingRunwayGrowsWithSpeedAndPhraseLengthWithinVisibleChunks() {
        double shortLine=WorldLyricsSpace.readingDistance(20,2,20,true,256);
        double longLine=WorldLyricsSpace.readingDistance(20,2,130,true,256);
        assertThat(shortLine).isGreaterThan(WorldLyricsSpace.readingDistance(5,2,20,true,256));
        assertThat(longLine).isGreaterThan(shortLine+50);
        assertThat(shortLine).isGreaterThan(WorldLyricsSpace.readingDistance(20,2,20,false,256));
        assertThat(WorldLyricsSpace.readingDistance(160,12,400,true,96)).isEqualTo(96);
    }
    @Test void completePhraseBoundsStayOutsideTheCrosshairCorridor() {
        double gap=30*Math.tan(Math.toRadians(12));
        for (int side : new int[]{-1,1}) {
            var around=WorldLyricsSpace.offset(WorldLyricsSpace.Position.AROUND,true,false,30,12,5,side);
            assertThat(Math.abs(around.side())-6).isGreaterThan(gap);
            var sky=WorldLyricsSpace.offset(WorldLyricsSpace.Position.SKY,true,false,30,12,5,side);
            assertThat(sky.up()-2.5).isGreaterThan(gap);
        }
        assertThat(WorldLyricsSpace.offset(WorldLyricsSpace.Position.ADAPTIVE,false,false,30,12,5,1))
                .isEqualTo(WorldLyricsSpace.offset(WorldLyricsSpace.Position.AROUND,false,false,30,12,5,1));
    }
    @Test void allStylesHaveRealDepthIncludingTheLegacyMinimalStyle() {
        for (var style : WorldLyricsExperience.Style.values())
            assertThat(WorldLyricsExperience.composition(style,false,WorldLyricsDirector.Quality.MEDIUM,.1).depth()).isGreaterThan(0);
    }
    @Test void elytraTurnsRetargetAheadWithoutRestartingOnEverySmallMovement() {
        assertThat(WorldLyricsSpace.courseChanged(true,.2,1.5,3)).isTrue();
        assertThat(WorldLyricsSpace.courseChanged(true,.5,2,3)).isFalse();
        assertThat(WorldLyricsSpace.courseChanged(true,.9,2,3)).isFalse();
        assertThat(WorldLyricsSpace.courseChanged(true,.5,.1,3)).isFalse();
        assertThat(WorldLyricsSpace.courseChanged(true,.5,1,.2)).isFalse();
        assertThat(WorldLyricsSpace.courseChanged(false,.5,1,3)).isFalse();
    }

    @Test void aTurnMustPersistAndStationaryOrSlowFlightNeverRetargets() {
        var state = new WorldLyricsSpace.RelocationState();
        assertThat(state.evaluate(1500, 150, 180, 6, 30, 4, 1.5, true, false))
                .isEqualTo(WorldLyricsSpace.Relocation.KEEP);
        // A brief bank ends before the hold time and clears the pending turn.
        assertThat(state.evaluate(1800, 150, 180, 27, 30, 4, 1.8, true, false))
                .isEqualTo(WorldLyricsSpace.Relocation.KEEP);
        assertThat(state.evaluate(1900, 150, 180, 6, 30, 4, 1.9, true, false))
                .isEqualTo(WorldLyricsSpace.Relocation.KEEP);
        assertThat(state.evaluate(2349, 150, 180, 6, 30, 4, 2.349, true, false))
                .isEqualTo(WorldLyricsSpace.Relocation.KEEP);
        assertThat(state.evaluate(2350, 150, 180, 6, 30, 4, 2.35, true, false))
                .isEqualTo(WorldLyricsSpace.Relocation.TURN);
        for (double speed : new double[]{0, .1, 3.9}) {
            assertThat(state.evaluate(3000, 150, 180, 0, speed, 4, 3, true, false))
                    .isEqualTo(WorldLyricsSpace.Relocation.KEEP);
            assertThat(state.evaluate(4000, 150, 180, 0, speed, 4, 4, true, false))
                    .isEqualTo(WorldLyricsSpace.Relocation.KEEP);
        }
    }

    @Test void movesFinishBeforeAnotherAttemptAndFailedTerrainChecksBackOff() {
        var state = new WorldLyricsSpace.RelocationState();
        assertThat(state.evaluate(2000, 30, 180, 30, 30, 4, 2, true, true))
                .isEqualTo(WorldLyricsSpace.Relocation.KEEP);
        assertThat(state.evaluate(2100, 30, 180, 30, 30, 4, 2.1, true, false))
                .isEqualTo(WorldLyricsSpace.Relocation.ADVANCE);
        state.attempted(2100);
        for (int now = 2100; now < 3300; now += 16)
            assertThat(state.evaluate(now, 30, 180, 30, 30, 4, 2, true, false))
                    .isEqualTo(WorldLyricsSpace.Relocation.KEEP);
        assertThat(state.evaluate(3300, 30, 180, 30, 30, 4, 3.3, true, false))
                .isEqualTo(WorldLyricsSpace.Relocation.ADVANCE);
    }

    @Test void pageChangesWithRunwayKeepTheAnchorAndAPauseCannotCompleteATurn() {
        var state = new WorldLyricsSpace.RelocationState();
        assertThat(state.evaluate(2000, 150, 180, 30, 30, 2, 2, true, false))
                .isEqualTo(WorldLyricsSpace.Relocation.KEEP);
        assertThat(state.evaluate(2001, 150, 180, 30, 30, 5, 2.001, true, false))
                .isEqualTo(WorldLyricsSpace.Relocation.KEEP);
        for (int i = 0; i < 144; i++)
            assertThat(state.evaluate(2500, 150, 180, -20, 30, 5, 2.5, true, false))
                    .isEqualTo(WorldLyricsSpace.Relocation.KEEP);
        assertThat(state.evaluate(2950, 150, 180, -20, 30, 5, 2.95, true, false))
                .isEqualTo(WorldLyricsSpace.Relocation.TURN);
        assertThat(state.evaluate(3000, 10, 180, 30, 30, .7, 3, true, false))
                .isEqualTo(WorldLyricsSpace.Relocation.KEEP);
    }

    @Test void seekingBackClearsTheRetryAndPendingTurn() {
        var state = new WorldLyricsSpace.RelocationState();
        state.attempted(5000);
        state.evaluate(5000, 30, 180, 30, 30, 4, 2, true, false);
        assertThat(state.evaluate(2000, 30, 180, 30, 30, 4, 2, true, false))
                .isEqualTo(WorldLyricsSpace.Relocation.ADVANCE);
    }

    @Test void successfulMovesUseTheNewRunwayInsteadOfWaitingOutATerrainFailure() {
        var state = new WorldLyricsSpace.RelocationState();
        state.attempted(2000);
        state.placed();
        assertThat(state.evaluate(2500, 30, 96, 160, 160, 4, .5, true, true))
                .isEqualTo(WorldLyricsSpace.Relocation.KEEP);
        assertThat(state.evaluate(2500, 30, 96, 160, 160, 4, .5, true, false))
                .isEqualTo(WorldLyricsSpace.Relocation.ADVANCE);
    }
}
