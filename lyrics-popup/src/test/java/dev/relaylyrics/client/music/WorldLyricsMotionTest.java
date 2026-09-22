package dev.relaylyrics.client.music;

import dev.relaylyrics.protocol.lyrics.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class WorldLyricsMotionTest {
    @Test void travelSpeedKeepsFullVelocityInsteadOfSaturatingAtRunningSpeed() {
        var state = new WorldLyricsPlan.SceneState(); Object world = new Object(), doc = new Object();
        state.update(world, doc, "track", 0, true, 0, false, true, 2, false);
        assertThat(state.blocksPerSecond()).isEqualTo(40);
        assertThat(state.flying()).isTrue();
        assertThat(state.reaction(false)).isEqualTo(WorldLyricsPlan.PlayerReaction.NONE);
    }
    private static LyricLine line(long start, long end, String text, List<TimedWord> words) {
        return new LyricLine(start, end, text, words, "v1", false, null, null);
    }
    private static WorldLyricsPlan.Motion motion(LyricLine line, long now, double fraction, boolean wall) {
        return WorldLyricsPlan.motion(line, WorldLyricsPlan.tokens(line, false).getFirst(), now, fraction,
                true, true, 1, 6, WorldLyricsPlan.PlayerReaction.NONE, wall);
    }

    @Test void shortWordsMoveTogetherAndHeldWordsHaveAVisibleWave() {
        var shortLine = line(1000, 1800, "hello", List.of(new TimedWord(1000, 1800, "hello")));
        assertThat(motion(shortLine, 1500, 0, false)).isEqualTo(motion(shortLine, 1500, 1, false));
        assertThat(motion(shortLine, 1500, 0, false).y()).isPositive();
        var held = line(1000, 4000, "hello", List.of(new TimedWord(1000, 4000, "hello")));
        assertThat(motion(held, 2500, 0, false).y()).isNotEqualTo(motion(held, 2500, 1, false).y());
    }

    @Test void syllableMotionUsesOnlyItsOwnTiming() {
        var line = line(1000, 3000, "hello", List.of(new TimedWord(1000, 1500, "hel"),
                new TimedWord(1500, 3000, "lo", true)));
        var tokens = WorldLyricsPlan.tokens(line, false);
        assertThat(tokens).hasSize(2);
        assertThat(tokens.get(0).emphasis(1300)).isPositive();
        assertThat(tokens.get(1).emphasis(1300)).isZero();
        assertThat(tokens.get(1).startsWord()).isFalse();
    }

    @Test void combinedMotionAlwaysStaysWithinTheBudgetAndOutsideWalls() {
        var line = line(1000, 4000, "held", List.of(new TimedWord(1000, 4000, "held")));
        var token = WorldLyricsPlan.tokens(line, false).getFirst();
        for (long now = 820; now < 4450; now += 13) {
            for (double distance : new double[]{.5, 2, 6}) {
                var value = WorldLyricsPlan.motion(line, token, now, .7, true, true, 1.4, distance,
                        new WorldLyricsPlan.PlayerReaction(1, 1, 1, 1), true);
                assertThat(Math.sqrt(value.x()*value.x() + value.y()*value.y() + value.depth()*value.depth()))
                        .isLessThanOrEqualTo(.25000001);
                assertThat(value.scale()).isBetween(1.0, 1.1);
                assertThat(value.tilt()).isBetween(-5.0, 5.0);
                assertThat(value.depth()).isGreaterThanOrEqualTo(0);
            }
        }
    }

    @Test void disabledAnimationsOverrideEveryReaction() {
        var line = line(0, 3000, "held", List.of(new TimedWord(0, 3000, "held")));
        assertThat(WorldLyricsPlan.motion(line, WorldLyricsPlan.tokens(line, false).getFirst(), 1500, .5,
                true, false, 1.4, 1, new WorldLyricsPlan.PlayerReaction(1, 1, 1, 1), false))
                .isEqualTo(WorldLyricsPlan.Motion.STILL);
    }

    @Test void untimedLinesStayWholeAndDoNotInventWordTimestamps() {
        var line = line(1000, 4000, "a whole phrase", List.of());
        assertThat(WorldLyricsPlan.hasWordTiming(line)).isFalse();
        assertThat(WorldLyricsPlan.tokens(line, false)).hasSize(1);
        assertThat(WorldLyricsPlan.tokens(line, false).getFirst().text()).isEqualTo("a whole phrase");
        assertThat(WorldLyricsPlan.hasWordTiming(line(0, 1000, "a phrase",
                List.of(new TimedWord(0, 1000, "a phrase"))))).isFalse();
    }

    @Test void romanizationKeepsSyllableTimesAndRtlFillMovesFromTheRight() {
        var line = new LyricLine(1000, 3000, "hello", List.of(
                new TimedWord(1000, 1600, "hel", false, "he"),
                new TimedWord(1600, 3000, "lo", true, "llo")), "v1", false, null, null);
        var tokens = WorldLyricsPlan.tokens(line, true);
        assertThat(tokens).extracting(WorldLyricsPlan.Token::startMillis).containsExactly(1000L, 1600L);
        assertThat(tokens).extracting(WorldLyricsPlan.Token::text).containsExactly("he", "llo");
        assertThat(LyricsAnimation.fillInterval(100, .25, true)).isEqualTo(new LyricsAnimation.FillInterval(75, 100));
        assertThat(LyricsAnimation.isRightToLeft("\u0645\u0631\u062d\u0628\u0627")).isTrue();
    }

    @Test void unmatchedRomanizationFallsBackToOnePhrase() {
        var line = new LyricLine(1000, 4000, "one two", List.of(
                new TimedWord(1000, 1700, "one"), new TimedWord(1700, 4000, "two")),
                "v1", false, null, "a b c");
        assertThat(WorldLyricsPlan.hasWordTiming(line, true)).isFalse();
        assertThat(WorldLyricsPlan.tokens(line, true)).hasSize(1);
        assertThat(WorldLyricsPlan.tokens(line, true).getFirst().text()).isEqualTo("a b c");
    }

    @Test void activeLeadsSurviveAnOverflowOfBackingVocals() {
        var lead = line(1000, 4000, "lead", List.of());
        var second = line(1200, 4000, "duet", List.of());
        var doc = new LyricsDocument(List.of(
                new LyricVocalGroup(1000, 4000, lead, List.of(line(1300, 4000, "b1", List.of()),
                        line(1400, 4000, "b2", List.of()), line(1500, 4000, "b3", List.of())), LyricAlignment.NORMAL),
                new LyricVocalGroup(1200, 4000, second, List.of(), LyricAlignment.OPPOSITE)), LyricsMetadata.unknown());
        var visible = WorldLyricsPlan.visibleLines(doc, 2000);
        assertThat(visible).hasSize(3);
        assertThat(visible.stream().filter(value -> !value.backing()).map(value -> value.line().text()))
                .containsExactly("lead", "duet");
    }

    @Test void interludesCoverRealGapsButNeverOverlapAnActiveVoiceOrContinueAfterTheSong() {
        var doc = new LyricsDocument(List.of(line(1000, 2000, "a", List.of()), line(6000, 7000, "b", List.of())));
        assertThat(WorldLyricsPlan.interlude(doc, 1500)).isNull();
        var gap = WorldLyricsPlan.interlude(doc, 4000);
        assertThat(gap).isEqualTo(new WorldLyricsPlan.Interlude(2000, 6000));
        assertThat(gap.visibility(4000)).isEqualTo(1);
        assertThat(gap.visibility(5999)).isZero();
        assertThat(WorldLyricsPlan.interlude(doc, 7500)).isNull();
    }

    @Test void sceneResetsOnlyOnIdentityChangesOrSeeks() {
        var state = new WorldLyricsPlan.SceneState(); Object world = new Object(), doc = new Object();
        assertThat(state.update(world, doc, "a", 1000, true, 0, true, false, 0, true)).isTrue();
        assertThat(state.update(world, doc, "a", 1100, true, 100_000_000, true, false, 0, true)).isFalse();
        assertThat(state.update(world, doc, "a", 1100, false, 200_000_000, true, false, 0, true)).isFalse();
        assertThat(state.update(world, doc, "a", 1100, true, 2_000_000_000L, true, false, 0, true)).isFalse();
        assertThat(state.update(world, doc, "a", 8000, true, 2_100_000_000L, true, false, 0, true)).isTrue();
        assertThat(state.update(world, doc, "a", 1000, true, 2_200_000_000L, true, false, 0, true)).isTrue();
        assertThat(state.update(world, doc, "b", 1100, true, 2_300_000_000L, true, false, 0, true)).isTrue();
        assertThat(state.update(new Object(), doc, "b", 1200, true, 2_400_000_000L, true, false, 0, true)).isTrue();
        assertThat(state.update(world, new Object(), "b", 1200, true, 2_500_000_000L, true, false, 0, true)).isTrue();
    }

    @Test void jumpIsAnEdgeEventAndPauseFreezesItsDecay() {
        var state = new WorldLyricsPlan.SceneState(); Object world = new Object(), doc = new Object();
        state.update(world, doc, "a", 0, true, 0, true, false, 0, true);
        state.update(world, doc, "a", 100, true, 100_000_000, false, false, 0, true);
        assertThat(state.reaction(true).impulse()).isEqualTo(1);
        state.update(world, doc, "a", 200, true, 200_000_000, false, false, 0, true);
        assertThat(state.reaction(true).impulse()).isLessThan(.5);
        double impulse = state.reaction(true).impulse();
        state.update(world, doc, "a", 200, false, 300_000_000, false, false, 0, true);
        state.update(world, doc, "a", 200, false, 900_000_000, false, false, 0, true);
        assertThat(state.reaction(true).impulse()).isEqualTo(impulse);
        state.update(world, doc, "a", 210, true, 910_000_000, true, false, 0, true);
        assertThat(state.reaction(true).impulse()).isEqualTo(-.8);
        state.update(world, doc, "a", 220, true, 920_000_000, true, false, 0, false);
        assertThat(state.reaction(false)).isEqualTo(WorldLyricsPlan.PlayerReaction.NONE);
        state.update(world, doc, "a", 230, true, 930_000_000, true, false, 0, true);
        assertThat(state.reaction(true).impulse()).isZero();
    }

    @Test void flightAndRunningAreSmoothAndIndependentOfFrameRate() {
        var slow = simulate(30); var fast = simulate(144);
        assertThat(slow.flight()).isCloseTo(fast.flight(), within(.00001));
        assertThat(slow.speed()).isCloseTo(fast.speed(), within(.00001));
        assertThat(slow.flight()).isBetween(.9, 1.0);
    }
    private WorldLyricsPlan.PlayerReaction simulate(int fps) {
        var state = new WorldLyricsPlan.SceneState(); Object world = new Object(), doc = new Object();
        state.update(world, doc, "a", 0, true, 0, false, true, .3, true);
        assertThat(state.reaction(true).flight()).isZero();
        for (int frame = 1; frame <= fps; frame++) state.update(world, doc, "a", Math.round(frame * 1000.0 / fps),
                true, Math.round(frame * 1_000_000_000.0 / fps), false, true, .3, true);
        return state.reaction(true);
    }
}
