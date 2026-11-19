package dev.relaylyrics.client.music;

import dev.relaylyrics.protocol.lyrics.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static dev.relaylyrics.client.music.WorldLyricsPresentation.*;
import static org.assertj.core.api.Assertions.*;

class WorldLyricsPresentationTest {
    @Test void discreteDirectionIsPlanarAndRevealsChunksAtTheirOwnOnset() {
        var line = words(1_000, "one", "two", "three", "four");
        var timeline = new Timeline(new LyricsDocument(List.of(line)));
        var cue = timeline.local(Intensity.DISCREET).values().iterator().next();
        assertThat(cue.volumetric()).isFalse();
        assertThat(cue.composition()).isEqualTo(Composition.PROGRESSIVE);
        assertThat(sample(cue, 0, 999, true).alpha()).isZero();
        assertThat(sample(cue, 0, 1_250, true).alpha()).isEqualTo(1);
        assertThat(sample(cue, 1, 1_250, true).alpha()).isEqualTo(1);
        assertThat(sample(cue, 2, 1_250, true).alpha()).isEqualTo(1);
        assertThat(sample(cue, 3, 2_499, true).alpha()).isZero();
        assertThat(sample(cue, 3, 2_750, true).alpha()).isEqualTo(1);
        assertThat(sample(cue, 0, 2_000, true).alpha()).isEqualTo(1);
    }

    @Test void shortLinesFadeQuicklyAndLongLinesGetMoreReadingTime() {
        var timeline = new Timeline(new LyricsDocument(List.of(words(0, "hold", "me"))));
        var cue = timeline.local(Intensity.DISCREET).values().iterator().next();
        assertThat(cue.source().line().endMillis()).isEqualTo(1_000);
        assertThat(lineVisibility(cue, 1_140)).isEqualTo(1);
        assertThat(lineVisibility(cue, 1_250)).isEqualTo(.5);
        assertThat(timeline.visible(1_359, Map.of(), Intensity.DISCREET)).hasSize(1);
        assertThat(timeline.visible(1_360, Map.of(), Intensity.DISCREET)).isEmpty();
        var longLine = new Timeline(new LyricsDocument(List.of(words(0, "this", "line", "takes", "a", "little", "longer", "to", "read"))));
        assertThat(longLine.local(Intensity.DISCREET).values().iterator().next().holdMillis()).isGreaterThan(cue.holdMillis());
    }

    @Test void backingVocalsFallAsTheyFadeOnlyWhenMotionIsEnabled() {
        var line = words(0, "hold");
        var source = new WorldLyricsPlan.WorldLine(line, LyricAlignment.NORMAL, true);
        var cue = new Cue("backing", source, Composition.PROGRESSIVE, Region.BOTTOM, Entrance.FADE,
                false, 260, List.of(), List.of(1));
        var exiting = sample(cue, 0, 670, true);
        assertThat(exiting.alpha()).isEqualTo(.5);
        var early = exitMotion(cue, exitProgress(cue, 670), 0);
        var late = exitMotion(cue, exitProgress(cue, 670), 1);
        assertThat(early.offsetY()).isNegative().isLessThan(late.offsetY());
        assertThat(early.scale()).isBetween(.75, 1.0).isLessThan(late.scale());
        assertThat(sample(cue, 0, 670, false).offsetY()).isZero();
    }

    @Test void reconstructsIdenticallyAfterPauseAndForwardBackwardSeek() {
        var timeline = new Timeline(new LyricsDocument(List.of(words(0, "first"), words(1_000, "second"), words(2_000, "third"))));
        var before = timeline.visible(1_250, Map.of(), Intensity.DISCREET);
        var pose = sample(before.getLast(), 0, 1_250, true);
        timeline.visible(2_300, Map.of(), Intensity.DISCREET);
        assertThat(timeline.visible(1_250, Map.of(), Intensity.DISCREET)).isEqualTo(before);
        assertThat(sample(before.getLast(), 0, 1_250, true)).isEqualTo(pose);
        assertThat(timeline.visible(1_250, Map.of(), Intensity.DISCREET)).isEqualTo(before);
    }

    @Test void keepsOnlyOnePreviousPhraseAndPrefersTheSingingLead() {
        var timeline = new Timeline(new LyricsDocument(List.of(words(0, "first"), words(200, "second"), words(700, "singing"))));
        assertThat(timeline.visible(1_000, Map.of(), Intensity.DISCREET))
                .extracting(cue -> cue.source().line().text()).containsExactly("second", "singing");
    }

    @Test void capsLongPhrasesTo48WordsAndFourGroupsWhileKeepingTheCurrentWord() {
        String[] values = new String[75]; Arrays.fill(values, "word");
        var timeline = new Timeline(new LyricsDocument(List.of(words(0, values))));
        var base = timeline.cues().getFirst();
        var cue = new Cue(base.id(), base.source(), Composition.FALLING_WORDS, Region.AUTO, Entrance.FALL,
                false, DEFAULT_HOLD_MILLIS, List.of(), List.of(values.length));
        var tokens = timeline.tokens(base);
        var window = window(cue, tokens, 36_500);
        long visible = java.util.stream.IntStream.range(0, tokens.size())
                .filter(i -> sample(cue, tokens, window, i, 36_500, false).alpha() > 0).count();
        assertThat(visible).isEqualTo(48);
        assertThat(sample(cue, tokens, window, 73, 36_500, false).alpha()).isPositive();
        var stair = new Cue(base.id(), base.source(), Composition.STAIRCASE, Region.AUTO, Entrance.RISE,
                false, DEFAULT_HOLD_MILLIS, List.of(), java.util.stream.IntStream.rangeClosed(1, values.length).boxed().toList());
        long stairs = java.util.stream.IntStream.range(0, tokens.size())
                .filter(i -> sample(stair, tokens, i, 36_500, false).alpha() > 0).count();
        assertThat(stairs).isEqualTo(4);
        for (int i = 0; i < tokens.size(); i++)
            assertThat(sample(stair, tokens, i, 36_500, false).offsetY()).isBetween(-1.35, 0.0);
    }

    @Test void cueIdsAreStableAcrossDocumentReloadAndDistinctForDuets() {
        var first = words(0, "same");
        var second = new LyricLine(0, 500, "same", first.words(), "v2", false, null, null);
        var document = new LyricsDocument(List.of(
                new LyricVocalGroup(0, 500, first, List.of(), LyricAlignment.NORMAL),
                new LyricVocalGroup(0, 500, second, List.of(), LyricAlignment.OPPOSITE)), LyricsMetadata.unknown());
        var timeline = new Timeline(document);
        assertThat(timeline.cues()).extracting(Cue::id).doesNotHaveDuplicates();
        assertThat(new Timeline(document).cues()).extracting(Cue::id).containsExactlyElementsOf(timeline.cues().stream().map(Cue::id).toList());
        assertThat(timeline.visible(250, Map.of(), Intensity.DISCREET)).hasSize(2);
    }

    @Test void lineOnlyLyricsDoNotGainInventedWordTimesAndStaticLyricsStayAbsent() {
        var line = new LyricLine(1_000, 2_000, "a whole phrase", List.of(), "v1", false, null, null);
        var timeline = new Timeline(new LyricsDocument(List.of(line)));
        var cue = timeline.cues().getFirst();
        assertThat(timeline.tokens(cue)).hasSize(1);
        assertThat(sample(cue, 0, 999, false).alpha()).isZero();
        assertThat(sample(cue, 0, 1_000, false).alpha()).isEqualTo(1);
        var staticDoc = new LyricsDocument(List.of(line)).withMetadata(new LyricsMetadata(LyricsSource.UNKNOWN,
                LyricsSyncMode.STATIC, "", "", "", ""));
        assertThat(new Timeline(staticDoc).cues()).isEmpty();
    }

    @Test void syllableFragmentsKeepOriginalTimesAndDoNotAlterSource() {
        var line = new LyricLine(0, 1_500, "hello world", List.of(new TimedWord(0, 500, "hel"),
                new TimedWord(500, 1_000, "lo", true), new TimedWord(1_000, 1_500, " world")), "v1", false, null, null);
        var timeline = new Timeline(new LyricsDocument(List.of(line)));
        var cue = demonstration(timeline.cues().getFirst().source(), Composition.FALLING_WORDS);
        assertThat(timeline.tokens(cue)).extracting(WorldLyricsPlan.Token::startMillis).containsExactly(0L, 500L, 1_000L);
        assertThat(sample(cue, 1, 499, false).alpha()).isZero();
        assertThat(sample(cue, 1, 500, false).alpha()).isEqualTo(1);
        assertThat(cue.source().line()).isSameAs(line);
    }

    @Test void fallingWordsSettleAndWaitForTheirOwnOnset() {
        var source = new WorldLyricsPlan.WorldLine(words(0, "look", "here"), LyricAlignment.NORMAL, false);
        var falling = demonstration(source, Composition.FALLING_WORDS);
        assertThat(sample(falling, 0, 50, true).offsetY()).isPositive();
        assertThat(sample(falling, 0, 500, true).offsetY()).isZero();
        assertThat(sample(falling, 1, 499, true).alpha()).isZero();
    }

    @Test void fullPhraseAndCompactGroupsStaggerLettersWithoutChangingTheirSettledAppearance() {
        var timeline = new Timeline(new LyricsDocument(List.of(words(1_000, "hello", "world"))));
        var cue = demonstration(timeline.cues().getFirst().source(), Composition.COEXIST);
        var tokens = timeline.tokens(cue);
        var opening = window(cue, tokens, 1_040);
        var first = samplePhraseLetter(cue, tokens, opening, 0, 1_040, true, 0);
        assertThat(first.alpha()).isPositive();
        assertThat(first.offsetY()).isPositive();
        assertThat(samplePhraseLetter(cue, tokens, opening, 1, 1_040, true, .9).alpha()).isZero();
        assertThat(samplePhraseLetter(cue, tokens, opening, 1, 1_040, false, .9).alpha()).isEqualTo(1);
        assertThat(samplePhraseLetter(cue, tokens, window(cue, tokens, 1_350), 1, 1_350, true, .9).offsetY()).isZero();

        for (List<Integer> groups : List.of(List.<Integer>of(), List.of(2))) {
            var compact = readingWindow(tokens, groups, 1_035, true, Long.MAX_VALUE);
            double[] offsets = {0, 30};
            var head = sampleReadingLetter(cue, compact, 0, 1_035, true, true, offsets, 60, 0);
            var tail = sampleReadingLetter(cue, compact, 0, 1_035, true, true, offsets, 60, 26);
            assertThat(head.alpha()).isPositive();
            assertThat(head.offsetY()).isPositive();
            assertThat(tail.alpha()).isZero();
            var settled = readingWindow(tokens, groups, 1_300, true, Long.MAX_VALUE);
            assertThat(sampleReadingLetter(cue, settled, 0, 1_300, true, true, offsets, 60, 26))
                    .isEqualTo(sampleReading(cue, settled, 0, 1_300, true, true));
        }
    }

    @Test void invalidCueGroupsAndAccentReferencesAreRejected() {
        var base = new Timeline(new LyricsDocument(List.of(words(0, "a", "b")))).cues().getFirst();
        assertThatThrownBy(() -> new Cue(base.id(), base.source(), base.composition(), base.region(), base.entrance(),
                false, 1_500, List.of(), List.of(2, 1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Cue(base.id(), base.source(), base.composition(), base.region(), base.entrance(),
                false, 1_500, List.of(new Accent(3, 1, -1, false)), List.of(2))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void centerRequiresCinematicModeAndDemoSupportsEveryComposition() {
        assertThat(safeRegion(Region.CENTER, false)).isEqualTo(Region.RIGHT);
        assertThat(safeRegion(Region.CENTER, true)).isEqualTo(Region.CENTER);
        assertThat(safeRegion(Region.LEFT, false)).isEqualTo(Region.LEFT);
        var timeline = new Timeline(new LyricsDocument(List.of(words(0, "first"), words(1_000, "next"))));
        for (Composition composition : Composition.values()) {
            var demo = demonstration(timeline.cues().getFirst().source(), composition);
            assertThat(demo.composition()).isEqualTo(composition);
        }
    }

    @Test void timedLettersEnterInOrderAndFinishWithinShortWords() {
        var timeline = new Timeline(new LyricsDocument(List.of(words(100, "stay", "here"))));
        var cue = timeline.local(Intensity.DISCREET).values().iterator().next();
        var tokens = timeline.tokens(cue);
        var token = tokens.getFirst();
        var glyphs = LyricsRenderText.timedGraphemes(token.text(), token.startMillis(), token.endMillis());
        long first = letterStart(token, glyphs.getFirst().startMillis(), true, 0);
        long last = letterStart(token, glyphs.getLast().startMillis(), true, 0);
        var during = window(cue, tokens, last - 1);
        assertThat(sampleLetter(cue, during, 0, last - 1, first, token.endMillis(), true).alpha()).isEqualTo(1);
        assertThat(sampleLetter(cue, during, 0, last - 1, last, token.endMillis(), true).alpha()).isZero();
        var fast = new WorldLyricsPlan.Token("stay", 100, 160, true);
        long fastLast = letterStart(fast, 135, true, 0);
        assertThat(fastLast).isBetween(101L, 159L);
        var depth = new Cue(cue.id(), cue.source(), cue.composition(), cue.region(), Entrance.DEPTH,
                false, cue.holdMillis(), List.of(), cue.groupEnds());
        assertThat(sampleLetter(depth, during, 0, fastLast + 1, fastLast, fast.endMillis(), true).offsetDepth()).isNegative();
        assertThat(sampleLetter(depth, during, 0, fastLast + 1, fastLast, fast.endMillis(), false).offsetDepth()).isZero();
        assertThat(sampleLetter(depth, during, 0, fast.endMillis(), fastLast, fast.endMillis(), true).alpha()).isEqualTo(1);
        assertThat(letterStart(new WorldLyricsPlan.Token("a line", 100, 4_000, true), 100, false, 1))
                .isBetween(101L, 350L);
    }

    @Test void aBackingVocalKeepsItsOwnPanelAndIsStillVisibleOnItsOwn() {
        var lead = words(0, "one", "two", "three", "four", "five", "six", "seven", "eight");
        var backing = words(1_500, "sing", "with", "me");
        var orphan = new LyricLine(5_000, 5_500, "oh", List.of(), "v2", true, null, null);
        var document = new LyricsDocument(List.of(lead, backing, orphan),
                List.of(new LyricVocalGroup(0, 4_000, lead, List.of(backing), LyricAlignment.NORMAL)), null);
        var timeline = new Timeline(document);
        var overlap = timeline.visible(2_700, Map.of(), Intensity.EXPRESSIVE);
        assertThat(overlap).extracting(cue -> cue.source().line()).containsExactly(lead, backing);
        assertThat(overlap).allSatisfy(cue -> assertThat(cue.groupEnds()).hasSize(1));
        assertThat(timeline.leadFor(overlap.getLast().source())).isEqualTo(overlap.getFirst().source());
        assertThat(timeline.visible(5_200, Map.of(), Intensity.DISCREET)).anySatisfy(cue -> {
            assertThat(cue.source().line()).isSameAs(orphan);
            assertThat(cue.source().backing()).isTrue();
            assertThat(timeline.leadFor(cue.source())).isNull();
        });
    }

    @Test void coexistRevealsAWholePhraseWhileFallingWordsKeepTheirOwnEntryTimes() {
        var source = new Timeline(new LyricsDocument(List.of(words(1_000, "all", "these", "words")))).cues().getFirst().source();
        var phrase = demonstration(source, Composition.COEXIST);
        for (int i = 0; i < 3; i++) assertThat(sample(phrase, i, 1_250, true).alpha()).isEqualTo(1);
        var falling = demonstration(source, Composition.FALLING_WORDS);
        assertThat(sample(falling, 0, 1_250, true).alpha()).isEqualTo(1);
        assertThat(sample(falling, 1, 1_499, true).alpha()).isZero();
        assertThat(sample(falling, 2, 1_999, true).alpha()).isZero();
        assertThat(source.line().words()).extracting(TimedWord::startMillis).containsExactly(1_000L, 1_500L, 2_000L);
    }

    @Test void oversizedPhraseRevealRetainsTheSingingWordWithinThe48WordBudget() {
        String[] text = new String[60]; Arrays.fill(text, "word");
        var timeline = new Timeline(new LyricsDocument(List.of(words(0, text))));
        var cue = demonstration(timeline.cues().getFirst().source(), Composition.COEXIST);
        for (long now : new long[]{250, 25_250}) {
            var window = window(cue, timeline.tokens(cue), now);
            long visible = java.util.stream.IntStream.range(0, text.length)
                    .filter(i -> sample(cue, timeline.tokens(cue), window, i, now, false).alpha() > 0).count();
            assertThat(visible).isEqualTo(48);
            assertThat(sample(cue, (int) (now / 500), now, false).alpha()).isPositive();
        }
    }

    @Test void explicitGeminiRetentionCanKeepSeveralPhrasesEvenAtDiscreetIntensity() {
        var timeline = new Timeline(new LyricsDocument(java.util.stream.IntStream.range(0, 4)
                .mapToObj(i -> words(i * 1_000, "phrase")).toList()));
        var directed = new LinkedHashMap<String, Cue>();
        for (var cue : timeline.cues()) directed.put(cue.id(), new Cue(cue.id(), cue.source(),
                Composition.COEXIST, cue.region(), Entrance.FADE, false, 6_000, List.of(), List.of()));
        assertThat(timeline.visible(3_100, directed, Intensity.DISCREET)).hasSize(4);
        assertThat(timeline.visible(3_100, Map.of(), Intensity.DISCREET)).hasSize(1);
    }

    private static LyricLine words(long start, String... text) {
        var words = java.util.stream.IntStream.range(0, text.length)
                .mapToObj(i -> new TimedWord(start + i * 500, start + (i + 1) * 500, text[i])).toList();
        return new LyricLine(start, start + text.length * 500, String.join(" ", text), words, "v1", false, null, null);
    }

    @Test void pauseExitUsesWallTimeAndStartsFreshAfterResume() {
        var exit = new WorldLyricsPresentation.PauseExit();
        assertThat(exit.progress(100_000_000L, false, true)).isZero();
        assertThat(exit.progress(275_000_000L, false, true)).isEqualTo(.5);
        assertThat(exit.progress(450_000_000L, false, true)).isEqualTo(1);
        assertThat(exit.progress(500_000_000L, true, true)).isZero();
        assertThat(exit.progress(600_000_000L, false, true)).isZero();
        assertThat(exit.progress(600_000_000L, false, false)).isEqualTo(1);
    }

    @Test void wordByWordModeOverridesEarlyPhraseRevealWithoutChangingTiming() {
        var line = new LyricLine(100, 2000, "one two", List.of(new TimedWord(100, 700, "one"),
                new TimedWord(800, 2000, " two")), null, false, null, null);
        var source = new WorldLyricsPlan.WorldLine(line, LyricAlignment.NORMAL, false);
        var mixed = WorldLyricsPresentation.demonstration(source, WorldLyricsPresentation.Composition.COEXIST);
        var words = WorldLyricsPresentation.withReveal(mixed, WorldLyricsPresentation.Reveal.WORD_BY_WORD);
        assertThat(WorldLyricsPresentation.withReveal(mixed, WorldLyricsPresentation.Reveal.PHRASE_AND_WORD)).isSameAs(mixed);
        assertThat(WorldLyricsPresentation.sample(mixed, 1, 300, false).alpha()).isPositive();
        assertThat(WorldLyricsPresentation.sample(words, 1, 799, false).alpha()).isZero();
        assertThat(WorldLyricsPresentation.sample(words, 1, 800, false).alpha()).isPositive();
        assertThat(WorldLyricsPresentation.sample(words, 0, 800, false).alpha()).isZero();
        assertThat(WorldLyricsPresentation.sample(words, 0, 750, false).alpha()).isZero();
        assertThat(words.source()).isSameAs(source);
    }
    @Test void wordByWordKeepsTimedSyllablesTogetherAndSplitsLineSyncedText() {
        var line = new LyricLine(0, 3000, "uma frase", List.of(new TimedWord(0, 400, "u"),
                new TimedWord(400, 1000, "ma", true), new TimedWord(1000, 3000, " frase")), null, false, null, null);
        var source = new WorldLyricsPlan.WorldLine(line, LyricAlignment.NORMAL, false);
        var words = WorldLyricsPresentation.demonstration(source, WorldLyricsPresentation.Composition.WORD_BY_WORD);
        assertThat(WorldLyricsPresentation.sample(words, 1, 200, false).alpha()).isPositive();
        assertThat(WorldLyricsPresentation.sample(words, 1, 1200, false).alpha()).isZero();

        var untimed = new LyricLine(0, 3000, "uma frase curta", List.of(), null, false, null, null);
        var untimedSource = new WorldLyricsPlan.WorldLine(untimed, LyricAlignment.NORMAL, false);
        var estimated = WorldLyricsPresentation.withReveal(WorldLyricsPresentation.demonstration(untimedSource,
                WorldLyricsPresentation.Composition.COEXIST), WorldLyricsPresentation.Reveal.WORD_BY_WORD);
        var tokens = WorldLyricsPlan.wordTokens(untimed, false);
        assertThat(tokens.stream().map(WorldLyricsPlan.Token::text)).containsExactly("uma", "frase", "curta");
        assertThat(WorldLyricsPresentation.window(estimated, tokens, 1500))
                .isEqualTo(new WorldLyricsPresentation.Window(1, 1, 1));
        assertThat(WorldLyricsRecovery.compactWindow(estimated, tokens, 2000))
                .isEqualTo(new WorldLyricsPresentation.Window(2, 2, 2));
    }
    @Test void animatedWordsFadeThroughGapsAndRetainTheirTailAtWordAndLineChanges() {
        var line = new LyricLine(0, 1500, "one two", List.of(new TimedWord(0, 600, "one"),
                new TimedWord(750, 1500, " two")), null, false, null, null);
        var cue = WorldLyricsPresentation.demonstration(new WorldLyricsPlan.WorldLine(line, LyricAlignment.NORMAL, false),
                WorldLyricsPresentation.Composition.WORD_BY_WORD);
        assertThat(WorldLyricsPresentation.sample(cue, 0, 620, true).alpha()).isGreaterThan(.8);
        assertThat(WorldLyricsPresentation.sample(cue, 0, 750, true).alpha()).isBetween(.65, .85);
        assertThat(WorldLyricsPresentation.sample(cue, 1, 750, true).alpha()).isBetween(.15, .35);
        assertThat(WorldLyricsPresentation.sample(cue, 1, 900, true).alpha()).isGreaterThan(.9);
        assertThat(WorldLyricsPresentation.sample(cue, 0, 960, true).alpha()).isBetween(.45, .65);
        assertThat(WorldLyricsPresentation.sample(cue, 0, 2100, true).alpha()).isZero();
        assertThat(WorldLyricsPresentation.sample(cue, 0, 620, false).alpha()).isZero();

        var tokens = WorldLyricsPlan.wordTokens(line, false);
        var window = WorldLyricsPresentation.window(cue, tokens, 1900, true, 1400);
        assertThat(WorldLyricsPresentation.sample(cue, tokens, window, 1, 1900, true).alpha()).isBetween(.35, .5);
        var endOfTail = WorldLyricsPresentation.window(cue, tokens, 2700, true, 1400);
        assertThat(WorldLyricsPresentation.sample(cue, tokens, endOfTail, 1, 2700, true).alpha()).isZero();
        var slow = readingWindow(tokens, List.of(), 2700, true, 1400, .65);
        assertThat(sampleReading(cue, slow, 1, 2700, true).alpha()).isGreaterThan(.1);
        var earlyNextLine = WorldLyricsPresentation.window(cue, tokens, 800, true, 700);
        assertThat(WorldLyricsPresentation.sample(cue, tokens, earlyNextLine, 1, 800, true).alpha()).isZero();
    }

    @Test void denseWordTimingCanHoldMoreThanThreeWholeWords() {
        var line = new LyricLine(0, 300, "one two three four", List.of(
                new TimedWord(0, 35, "one"), new TimedWord(35, 55, " two"),
                new TimedWord(55, 70, " th"), new TimedWord(70, 90, "ree", true),
                new TimedWord(90, 300, " four")), null, false, null, null);
        var cue = demonstration(new WorldLyricsPlan.WorldLine(line, LyricAlignment.NORMAL, false),
                Composition.WORD_BY_WORD);
        var tokens = WorldLyricsPlan.wordTokens(line, false);
        var window = window(cue, tokens, 100, true);
        assertThat(window.newestGroup()).isEqualTo(3);
        assertThat(sample(cue, tokens, window, 0, 100, true).alpha()).isPositive();
        assertThat(sample(cue, tokens, window, 1, 100, true).alpha()).isPositive();
        assertThat(sample(cue, tokens, window, 2, 100, true).alpha())
                .isPositive().isEqualTo(sample(cue, tokens, window, 3, 100, true).alpha());
        assertThat(sample(cue, tokens, window, 4, 100, true).alpha()).isPositive();
    }
    @Test void shaderExitShrinksGlyphsBeforeTheyBecomeTransparent() {
        var line = words(0, "one");
        var cue = demonstration(new WorldLyricsPlan.WorldLine(line, LyricAlignment.NORMAL, false),
                Composition.WORD_BY_WORD);
        var tokens = WorldLyricsPlan.wordTokens(line, false);
        var window = window(cue, tokens, 1500, true);
        var normal = sampleReading(cue, window, 0, 1500, true);
        var shader = sampleReading(cue, window, 0, 1500, true, true);
        assertThat(shader.alpha()).isGreaterThan(normal.alpha());
        assertThat(shader.scale()).isLessThan(.2);
        assertThat(sample(cue, 0, 1800, true).alpha()).isZero();
    }

    @Test void wordsAndChunksUseTheSameMotionAtTheSameExitPhase() {
        var line = words(0, "one");
        var source = new WorldLyricsPlan.WorldLine(line, LyricAlignment.NORMAL, false);
        var word = demonstration(source, Composition.WORD_BY_WORD);
        var phrase = demonstration(source, Composition.COEXIST);
        var tokens = WorldLyricsPlan.wordTokens(line, false);
        long wordTime = 500 + readingTailMillis(true, 1) / 2;
        long chunkTime = 500 + readingTailMillis(false, 1) / 2;
        var wordWindow = readingWindow(tokens, List.of(), wordTime, true, Long.MAX_VALUE);
        var chunkWindow = readingWindow(tokens, List.of(1), chunkTime, true, Long.MAX_VALUE);
        assertThat(sampleReading(word, wordWindow, 0, wordTime, true))
                .isEqualTo(sampleReading(phrase, chunkWindow, 0, chunkTime, true));
    }
    @Test void phraseModeKeepsAllTimedWordsInOnePanelFromItsEntrance() {
        var line = words(1000, "Though", "it's", "the", "end", "of", "the", "world,");
        var source = new WorldLyricsPlan.WorldLine(line, LyricAlignment.NORMAL, false);
        var scattered = demonstration(source, Composition.PROGRESSIVE);
        var phrase = withReveal(scattered, Reveal.PHRASE_AND_WORD);
        var tokens = WorldLyricsPlan.tokens(line, false);
        assertThat(phrase.composition()).isEqualTo(Composition.COEXIST);
        assertThat(phrase.groupEnds()).containsExactly(tokens.size());
        var running = new WorldLyricsContext.Snapshot(WorldLyricsContext.Mode.SPRINT, 6, 0, 0, 0, 1);
        assertThat(WorldLyricsStage.groups(phrase, tokens, Intensity.EXPRESSIVE, running)).hasSize(1);
        var window = fullPhraseWindow(tokens);
        for (int index = 0; index < tokens.size(); index++)
            assertThat(sample(phrase, tokens, window, index, 1200, true).alpha()).isPositive();
    }
}
