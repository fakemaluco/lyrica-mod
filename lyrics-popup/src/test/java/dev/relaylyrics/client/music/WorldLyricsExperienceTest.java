package dev.relaylyrics.client.music;

import dev.relaylyrics.protocol.lyrics.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class WorldLyricsExperienceTest {
    @Test void indexedTimelineMatchesReferenceAcrossDuetsLongOverlapsAndSeeks() {
        List<LyricVocalGroup> groups = new ArrayList<>();
        Random random = new Random(59);
        for (int i = 0; i < 900; i++) {
            long start = i * 240L, end = start + 800 + random.nextInt(4500);
            LyricLine lead = line(start, end, "voice " + i);
            groups.add(new LyricVocalGroup(start, end, lead,
                    i % 3 == 0 ? List.of(line(start + 160, end, "backing")) : List.of(),
                    i % 2 == 0 ? LyricAlignment.NORMAL : LyricAlignment.OPPOSITE));
        }
        var doc = new LyricsDocument(groups, LyricsMetadata.unknown());
        var timeline = new WorldLyricsExperience.Timeline(doc);
        for (int i = 0; i < 1200; i++) {
            long now = random.nextInt(225000);
            assertThat(timeline.visible(now)).as("seek %s", now).isEqualTo(WorldLyricsPlan.visibleLines(doc, now));
        }
    }
    @Test void pausedAndReducedMotionNeverDriftOrPulse() {
        for (var style : WorldLyricsExperience.Style.values()) {
            var pose = WorldLyricsExperience.word(0, 4000, 2000, style, true, 1);
            assertThat(WorldLyricsExperience.word(0, 4000, 2000, style, true, 1)).isEqualTo(pose);
            assertThat(WorldLyricsExperience.word(0, 4000, 2000, style, false, 1))
                    .isEqualTo(new WorldLyricsExperience.Pose(0, 1, 0, 0));
        }
        assertThat(WorldLyricsExperience.entrance(1000, 900, false)).isZero();
    }
    @Test void glowAndMotionDoNotSpillIntoFutureOrFinishedWords() {
        for (long now : new long[]{-1, 4000, 5000})
            assertThat(WorldLyricsExperience.word(0, 4000, now, WorldLyricsExperience.Style.SPICY, true, 1))
                    .isEqualTo(new WorldLyricsExperience.Pose(0, 1, 0, 0));
        for (int t = 0; t < 4000; t += 7) {
            var pose = WorldLyricsExperience.word(0, 4000, t, WorldLyricsExperience.Style.SPICY, true, 1.5);
            assertThat(pose.scale()).isBetween(1.0, 1.12);
            assertThat(pose.lift()).isBetween(0.0, .10);
            assertThat(pose.glow()).isBetween(0.0, 1.0);
        }
    }
    @Test void geometryBudgetCannotGrowWithTheNumberOfSingers() {
        var budget = new WorldLyricsExperience.Budget(WorldLyricsDirector.Quality.MEDIUM, false);
        int accepted = 0;
        for (int i = 0; i < 1000; i++) if (budget.mesh(1024)) accepted++;
        assertThat(accepted).isEqualTo(32);
        assertThat(budget.remainingFaces()).isZero();
        assertThat(budget.halo()).isFalse();
        assertThat(new WorldLyricsExperience.Budget(WorldLyricsDirector.Quality.HIGH, true).mesh(1)).isTrue();
        assertThat(new WorldLyricsExperience.Budget(WorldLyricsDirector.Quality.LOW, false).halo()).isFalse();
    }
    @Test void untimedDocumentsStayInTheReadablePopup() {
        var metadata = new LyricsMetadata(LyricsSource.UNKNOWN, LyricsSyncMode.STATIC, "", "", "", "");
        var line = line(0, Long.MAX_VALUE, "plain");
        var doc = new LyricsDocument(List.of(new LyricVocalGroup(0, Long.MAX_VALUE, line, List.of(), LyricAlignment.NORMAL)), metadata);
        assertThat(new WorldLyricsExperience.Timeline(doc).visible(12000)).isEmpty();
    }
    private static LyricLine line(long start, long end, String text) {
        return new LyricLine(start, end, text, List.of(), "v1", false, null, null);
    }
}
