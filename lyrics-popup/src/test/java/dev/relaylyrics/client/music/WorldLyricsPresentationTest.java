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

    @Test void singingEndsBeforeVisibilityAndTheLast350MillisecondsFade() {
        var timeline = new Timeline(new LyricsDocument(List.of(words(0, "hold", "me"))));
        var cue = timeline.cues().getFirst();
        assertThat(cue.source().line().endMillis()).isEqualTo(1_000);
        assertThat(lineVisibility(cue, 1_500)).isEqualTo(1);
        assertThat(lineVisibility(cue, 2_150)).isEqualTo(1);
        assertThat(lineVisibility(cue, 2_325)).isEqualTo(.5);
        assertThat(timeline.visible(2_499, Map.of(), Intensity.DISCREET)).hasSize(1);
        assertThat(timeline.visible(2_500, Map.of(), Intensity.DISCREET)).isEmpty();
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

    @Test void expressiveSequenceDemonstratesAllFiveCompositionsAndFallingSettles() {
        var timeline = new Timeline(new LyricsDocument(java.util.stream.IntStream.range(0, 5)
                .mapToObj(i -> words(i * 2_000, "look", "here")).toList()));
        assertThat(timeline.local(Intensity.EXPRESSIVE).values()).extracting(Cue::composition)
                .containsExactly(Composition.values());
        var falling = timeline.local(Intensity.EXPRESSIVE).values().iterator().next();
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

    @Test void centerRequiresCinematicModeAndLocalPhrasesAlternateRegions() {
        assertThat(safeRegion(Region.CENTER, false)).isEqualTo(Region.RIGHT);
        assertThat(safeRegion(Region.CENTER, true)).isEqualTo(Region.CENTER);
        assertThat(safeRegion(Region.LEFT, false)).isEqualTo(Region.LEFT);
        var timeline = new Timeline(new LyricsDocument(List.of(words(0, "first"), words(1_000, "next"))));
        assertThat(timeline.local(Intensity.DISCREET).values()).extracting(Cue::region)
                .containsExactly(Region.LEFT, Region.RIGHT);
        for (Composition composition : Composition.values()) {
            var demo = demonstration(timeline.cues().getFirst().source(), composition);
            assertThat(demo.composition()).isEqualTo(composition);
        }
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
        assertThat(timeline.visible(3_100, Map.of(), Intensity.DISCREET)).hasSize(2);
    }

    private static LyricLine words(long start, String... text) {
        var words = java.util.stream.IntStream.range(0, text.length)
                .mapToObj(i -> new TimedWord(start + i * 500, start + (i + 1) * 500, text[i])).toList();
        return new LyricLine(start, start + text.length * 500, String.join(" ", text), words, "v1", false, null, null);
    }
}
