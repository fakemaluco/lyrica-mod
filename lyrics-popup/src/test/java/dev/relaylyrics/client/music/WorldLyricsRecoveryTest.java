package dev.relaylyrics.client.music;

import dev.relaylyrics.protocol.lyrics.LyricLine;
import dev.relaylyrics.protocol.lyrics.LyricAlignment;
import dev.relaylyrics.protocol.lyrics.TimedWord;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.stream.IntStream;
import static dev.relaylyrics.client.music.WorldLyricsPresentation.*;
import static org.assertj.core.api.Assertions.*;

class WorldLyricsRecoveryTest {
    @Test void sideSlotsArePreferredWithCentralProbesStillAvailableInNarrowSpaces() {
        for (int side : List.of(-1, 1)) {
            var probes = WorldLyricsRecovery.readingProbes(8, 3, .8, side);
            var first = probes.getFirst();
            assertThat(first.distance()).isEqualTo(8);
            assertThat(first.side() * side - first.width() / 2).isGreaterThan(.36);
            assertThat(probes).anySatisfy(probe -> {
                assertThat(probe.side()).isZero();
                assertThat(probe.distance()).isLessThan(3);
            });
        }
    }

    private static final long MS = 1_000_000L;

    @Test void skySearchStartsAtTheSelectedMaximumAndKeepsItsLaneWhenItComesCloser() {
        var range = new WorldLyricsSpace.DistanceRange(8, 32);
        var left = WorldLyricsRecovery.skyProbes(range, 4, 1, -1);
        var right = WorldLyricsRecovery.skyProbes(range, 4, 1, 1);
        var finale = WorldLyricsRecovery.skyProbes(range, 4, 1, 0);
        assertThat(left.getFirst().distance()).isEqualTo(32);
        assertThat(left.getLast().distance()).isEqualTo(8);
        assertThat(left).allMatch(probe -> probe.side() < 0 && range.clamp(probe.distance()) == probe.distance());
        assertThat(right).allMatch(probe -> probe.side() > 0);
        assertThat(finale).allMatch(probe -> probe.side() == 0);
        assertThat(finale.getFirst().up()).isPositive();
        assertThat(WorldLyricsRecovery.skyProbes(new WorldLyricsSpace.DistanceRange(12, 12), 4, 1, -1))
                .hasSize(3).allMatch(probe -> probe.distance() == 12);
    }

    @Test void missingBirthRecoversImmediatelyAndBacksOffAfterFailedSearch() {
        var monitor = new WorldLyricsRecovery.Monitor();
        assertThat(monitor.evaluate(0, false, false)).isTrue();
        monitor.attempted(0);
        assertThat(monitor.evaluate(599 * MS, false, false)).isFalse();
        assertThat(monitor.evaluate(600 * MS, false, false)).isTrue();
    }

    @Test void aNewMissingWordIsDebouncedAsOnePhraseTransaction() {
        var monitor = new WorldLyricsRecovery.Monitor();
        assertThat(monitor.evaluate(0, true, true)).isFalse();
        assertThat(monitor.evaluate(100 * MS, false, true)).isFalse();
        assertThat(monitor.evaluate(299 * MS, false, true)).isFalse();
        assertThat(monitor.evaluate(300 * MS, false, true)).isTrue();
    }

    @Test void briefObstructionDoesNotMoveOtherwiseStableLyrics() {
        var monitor = new WorldLyricsRecovery.Monitor();
        monitor.evaluate(0, true, true);
        assertThat(monitor.evaluate(10 * MS, false, false)).isFalse();
        assertThat(monitor.evaluate(150 * MS, true, true)).isFalse();
        assertThat(monitor.evaluate(151 * MS, false, false)).isFalse();
        assertThat(monitor.evaluate(350 * MS, false, false)).isFalse();
        assertThat(monitor.evaluate(351 * MS, false, false)).isTrue();
    }

    @Test void sustainedOutOfViewUsesWallTimeEvenWhenSongIsPaused() {
        var monitor = new WorldLyricsRecovery.Monitor();
        monitor.evaluate(0, true, true);
        assertThat(monitor.evaluate(100 * MS, true, false)).isFalse();
        assertThat(monitor.evaluate(449 * MS, true, false)).isFalse();
        assertThat(monitor.evaluate(450 * MS, true, false)).isTrue();
        monitor.attempted(450 * MS);
        assertThat(monitor.evaluate(500 * MS, true, true)).isFalse();
    }

    @Test void successfulReframeDoesNotAllowImmediateRepeatedMoves() {
        var monitor = new WorldLyricsRecovery.Monitor();
        monitor.evaluate(0, false, false); monitor.attempted(0);
        assertThat(monitor.evaluate(100 * MS, true, true)).isFalse();
        assertThat(monitor.evaluate(200 * MS, false, false)).isFalse();
        assertThat(monitor.evaluate(500 * MS, false, false)).isFalse();
        assertThat(monitor.evaluate(600 * MS, false, false)).isTrue();
    }

    @Test void readableWorldAnchorsRemainFixedAndResetClearsDebounce() {
        var monitor = new WorldLyricsRecovery.Monitor();
        for (int second = 0; second < 20; second++)
            assertThat(monitor.evaluate(second * 1_000 * MS, true, true)).isFalse();
        monitor.evaluate(20_000 * MS, false, false); monitor.attempted(20_000 * MS);
        monitor.reset();
        assertThat(monitor.evaluate(20_010 * MS, false, false)).isTrue();
    }

    @Test void clockReinitializationCannotLeaveAStaleCooldown() {
        var monitor = new WorldLyricsRecovery.Monitor();
        monitor.evaluate(1_000 * MS, false, false); monitor.attempted(1_000 * MS);
        assertThat(monitor.evaluate(10 * MS, false, false)).isTrue();
    }

    @Test void forestSearchReachesNearClearanceAndScalesTheWholeRectangle() {
        var probes = WorldLyricsRecovery.compactProbes(8, 4, 1);
        assertThat(probes.stream().map(WorldLyricsRecovery.Probe::distance).distinct())
                .containsExactly(2.0, 1.25, .8, .5, .3, .18);
        assertThat(probes.stream().filter(probe -> probe.distance() >= .8)).allSatisfy(probe -> {
            assertThat(probe.width() / probe.distance()).isCloseTo(.5, within(1e-9));
            assertThat(probe.height() / probe.distance()).isCloseTo(.125, within(1e-9));
        });
        assertThat(probes.getLast().width()).isLessThan(.5);
        assertThat(probes.getFirst().up()).isNegative();
    }

    @Test void searchEnvelopesAreFiniteBoundedAndDoNotCoverTheCrosshair() {
        for (var probe : WorldLyricsRecovery.compactProbes(8, 80, 40)) {
            assertThat(probe.width()).isLessThanOrEqualTo(probe.distance() * .9 + 1e-9);
            assertThat(probe.height()).isLessThanOrEqualTo(probe.distance() * .38 + 1e-9);
            assertThat(Math.abs(probe.side()) >= probe.width() / 2
                    || Math.abs(probe.up()) >= probe.height() / 2).isTrue();
            if (probe.distance() < .8) {
                assertThat(probe.side()).isZero();
                assertThat(probe.height()).isLessThanOrEqualTo(probe.distance() * .34 + 1e-9);
            }
        }
        assertThat(WorldLyricsRecovery.compactProbes(Double.NaN, Double.POSITIVE_INFINITY, -10))
                .allSatisfy(probe -> assertThat(Double.isFinite(probe.width() + probe.height() + probe.distance())).isTrue());
    }

    @Test void compactReconstructionKeepsEarlierWordsWithoutTheFourSpatialBlockLimit() {
        var cue = cue(Composition.FALLING_WORDS, 10);
        var tokens = WorldLyricsPlan.tokens(cue.source().line(), false);
        var window = WorldLyricsRecovery.compactWindow(cue, tokens, 4_600);
        assertThat(window.firstToken()).isZero();
        assertThat(window.lastStartedToken()).isEqualTo(9);
        assertThat(sample(cue, tokens, window, 0, 4_600, false).alpha()).isPositive();
        assertThat(sample(cue, tokens, WorldLyricsPresentation.window(cue, tokens, 4_600), 0, 4_600, false).alpha()).isZero();
        assertThat(cue.source().line().words()).hasSize(10);
    }

    @Test void compactFallingWordsStillDoNotAnticipateFutureWords() {
        var cue = cue(Composition.FALLING_WORDS, 10);
        var tokens = WorldLyricsPlan.tokens(cue.source().line(), false);
        var window = WorldLyricsRecovery.compactWindow(cue, tokens, 750);
        assertThat(window.lastStartedToken()).isEqualTo(1);
        assertThat(sample(cue, tokens, window, 2, 750, false).alpha()).isZero();
    }

    @Test void compactProgressiveReconstructionRetainsChunkRevealTimes() {
        var original = cue(Composition.PROGRESSIVE, 6);
        var cue = new Cue(original.id(), original.source(), Composition.PROGRESSIVE, Region.RIGHT,
                Entrance.FADE, false, 1_500, List.of(), List.of(3, 6));
        var tokens = WorldLyricsPlan.tokens(cue.source().line(), false);
        var window = WorldLyricsRecovery.compactWindow(cue, tokens, 750);
        assertThat(window.lastStartedToken()).isEqualTo(2);
        assertThat(sample(cue, tokens, window, 2, 750, false).alpha()).isPositive();
        assertThat(sample(cue, tokens, window, 3, 750, false).alpha()).isZero();
    }

    @Test void compactWholePhraseRespectsFortyEightWordsAndKeepsTheSingingWord() {
        var cue = cue(Composition.COEXIST, 100);
        var tokens = WorldLyricsPlan.tokens(cue.source().line(), false);
        var atStart = WorldLyricsRecovery.compactWindow(cue, tokens, 100);
        assertThat(atStart.firstToken()).isZero();
        assertThat(atStart.lastStartedToken()).isEqualTo(47);
        var later = WorldLyricsRecovery.compactWindow(cue, tokens, 30_100);
        assertThat(later.firstToken()).isEqualTo(13);
        assertThat(later.lastStartedToken()).isEqualTo(60);
        assertThat(sample(cue, tokens, later, 60, 30_100, false).alpha()).isPositive();
    }

    @Test void compactWindowNeverCutsTheStartOfATimedSyllableWord() {
        var line = new LyricLine(0, 1_500, "hello world", List.of(new TimedWord(0, 500, "hel"),
                new TimedWord(500, 1_000, "lo", true), new TimedWord(1_000, 1_500, " world")), "v1", false, null, null);
        var cue = demonstration(new WorldLyricsPlan.WorldLine(line, LyricAlignment.NORMAL, false), Composition.FALLING_WORDS);
        var tokens = WorldLyricsPlan.tokens(line, false);
        var window = WorldLyricsRecovery.compactWindow(cue, tokens, 750);
        assertThat(window.firstToken()).isZero();
        assertThat(window.lastStartedToken()).isEqualTo(1);
        assertThat(tokens).extracting(WorldLyricsPlan.Token::startMillis).containsExactly(0L, 500L, 1_000L);
    }

    private static Cue cue(Composition composition, int count) {
        var words = IntStream.range(0, count).mapToObj(index -> new TimedWord(index * 500L,
                (index + 1) * 500L, (index == 0 ? "" : " ") + "word" + index)).toList();
        String text = words.stream().map(TimedWord::text).collect(java.util.stream.Collectors.joining());
        var line = new LyricLine(0, count * 500L, text, words, "v1", false, null, null);
        return demonstration(new WorldLyricsPlan.WorldLine(line, LyricAlignment.NORMAL, false), composition);
    }
}
