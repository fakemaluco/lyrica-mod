package dev.relaylyrics.client.music;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class WorldLyricsSpaceTest {
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
        assertThat(WorldLyricsSpace.courseChanged(true,.5,.8,3)).isTrue();
        assertThat(WorldLyricsSpace.courseChanged(true,.9,2,3)).isFalse();
        assertThat(WorldLyricsSpace.courseChanged(true,.5,.1,3)).isFalse();
        assertThat(WorldLyricsSpace.courseChanged(true,.5,1,.2)).isFalse();
        assertThat(WorldLyricsSpace.courseChanged(false,.5,1,3)).isFalse();
    }
}
