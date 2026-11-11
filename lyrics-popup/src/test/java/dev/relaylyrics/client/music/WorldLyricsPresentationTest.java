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
}
