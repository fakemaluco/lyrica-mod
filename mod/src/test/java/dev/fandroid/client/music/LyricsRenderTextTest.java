package dev.fandroid.client.music;

import dev.fandroid.protocol.lyrics.LyricLine;
import dev.fandroid.protocol.lyrics.TimedWord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LyricsRenderTextTest {
    @Test
    void keepsTimedSyllablesInsideOneStableVisualWord() {
        LyricLine line = line("heavydirtysoul", List.of(
                new TimedWord(1_000, 1_250, "hea"),
                new TimedWord(1_250, 1_500, "vy", true),
                new TimedWord(1_500, 1_750, "dir", true),
                new TimedWord(1_750, 2_000, "ty", true),
                new TimedWord(2_000, 2_600, "soul", true)));

        assertThat(LyricsRenderText.visualWords(line)).singleElement().satisfies(word -> {
            assertThat(word.text()).isEqualTo("heavydirtysoul");
            assertThat(word.fragments()).extracting(LyricsRenderText.Fragment::text)
                    .containsExactly("hea", "vy", "dir", "ty", "soul");
            assertThat(word.fragments()).extracting(fragment -> fragment.timing().startMillis())
                    .containsExactly(1_000L, 1_250L, 1_500L, 1_750L, 2_000L);
            assertThat(word.fragments()).extracting(fragment -> fragment.timing().endMillis())
                    .containsExactly(1_250L, 1_500L, 1_750L, 2_000L, 2_600L);
            assertThat(word.startMillis()).isEqualTo(1_000);
            assertThat(word.endMillis()).isEqualTo(2_600);
        });
    }

    @Test
    void repairsMissingJoinFlagsFromTheVisibleLine() {
        LyricLine line = line("an infestation", List.of(
                new TimedWord(0, 200, "an"),
                new TimedWord(200, 400, " in"),
                new TimedWord(400, 600, "fes"),
                new TimedWord(600, 900, "tation")));

        assertThat(LyricsRenderText.visualWords(line)).extracting(LyricsRenderText.VisualWord::text)
                .containsExactly("an", "infestation");
    }

    @Test
    void treatsCombiningCharactersAsOneAnimatedGrapheme() {
        var spans = LyricsRenderText.timedGraphemes("a\u0301b", 1_000, 2_000);

        assertThat(spans).extracting(LyricsRenderText.GraphemeSpan::text)
                .containsExactly("a\u0301", "b");
        assertThat(spans.getFirst().startIndex()).isZero();
        assertThat(spans.getFirst().endIndex()).isEqualTo(2);
        assertThat(spans.getLast().endMillis()).isEqualTo(2_000);
    }

    @Test
    void distributesCharacterMotionInsideItsOwnSyllableTiming() {
        var spans = LyricsRenderText.timedGraphemes("soul", 2_000, 7_000);

        assertThat(spans).allSatisfy(span -> {
            assertThat(span.startMillis()).isBetween(2_000L, 7_000L);
            assertThat(span.endMillis()).isBetween(span.startMillis(), 7_000L);
        });
        assertThat(spans.getFirst().startMillis()).isEqualTo(2_000);
        assertThat(spans.getLast().endMillis()).isEqualTo(7_000);
        assertThat(spans).extracting(LyricsRenderText.GraphemeSpan::startMillis).isSorted();
    }

    @Test
    void splitsLineTimingIntoStableWords() {
        LyricLine line = line("hello brave world", List.of());

        assertThat(LyricsRenderText.visualWords(line)).extracting(LyricsRenderText.VisualWord::text)
                .containsExactly("hello", "brave", "world");
    }

    @Test
    void removesFakeBlankGlyphsFromEveryLyricsSource() {
        LyricLine line = line("And\u2800that\u3164was\u200B normal life",
                List.of(new TimedWord(1_000, 3_000, "And\u2800that\u3164was\u200B normal life")));

        assertThat(LyricsRenderText.visualWords(line))
                .extracting(LyricsRenderText.VisualWord::text)
                .containsExactly("And", "that", "was", "normal", "life");
    }

    @Test
    void replacesSyncedFragmentsWithRomanizationWithoutLosingTiming() {
        LyricLine line = new LyricLine(1_000, 2_500, "今日は 世界", List.of(
                new TimedWord(1_000, 1_250, "今", false, "kon"),
                new TimedWord(1_250, 1_500, "日", true, "ni"),
                new TimedWord(1_500, 1_750, "は", true, "chiwa"),
                new TimedWord(1_900, 2_500, " 世界", false, " sekai")),
                "v1", false, null, "konnichiwa sekai");

        assertThat(LyricsRenderText.visualWords(line, true)).satisfiesExactly(
                word -> {
                    assertThat(word.text()).isEqualTo("konnichiwa");
                    assertThat(word.fragments()).extracting(LyricsRenderText.Fragment::text)
                            .containsExactly("kon", "ni", "chiwa");
                    assertThat(word.fragments()).extracting(fragment -> fragment.timing().startMillis())
                            .containsExactly(1_000L, 1_250L, 1_500L);
                },
                word -> {
                    assertThat(word.text()).isEqualTo("sekai");
                    assertThat(word.startMillis()).isEqualTo(1_900);
                    assertThat(word.endMillis()).isEqualTo(2_500);
                });
        assertThat(LyricsRenderText.visualWords(line, false))
                .extracting(LyricsRenderText.VisualWord::text).containsExactly("今日は", "世界");
    }

    @Test
    void alignsLineOnlyRomanizationToExistingWordTiming() {
        LyricLine line = new LyricLine(1_000, 3_000, "今日 世界", List.of(
                new TimedWord(1_000, 1_650, "今日"),
                new TimedWord(1_900, 3_000, " 世界")),
                "v1", false, null, "kyou sekai");

        assertThat(LyricsRenderText.visualWords(line, true)).satisfiesExactly(
                word -> {
                    assertThat(word.text()).isEqualTo("kyou");
                    assertThat(word.startMillis()).isEqualTo(1_000);
                    assertThat(word.endMillis()).isEqualTo(1_650);
                },
                word -> {
                    assertThat(word.text()).isEqualTo("sekai");
                    assertThat(word.startMillis()).isEqualTo(1_900);
                    assertThat(word.endMillis()).isEqualTo(3_000);
                });
    }

    @Test
    void replacesMainTextFromFragmentOnlyRomanizationAndKeepsSync() {
        LyricLine line = new LyricLine(1_000, 2_400, "今日は", List.of(
                new TimedWord(1_000, 1_350, "今", false, "kon"),
                new TimedWord(1_350, 1_700, "日", true, "ni"),
                new TimedWord(1_700, 2_400, "は", true, "chiwa")),
                "v1", false, null, null);

        assertThat(LyricsRenderText.hasRomanization(line)).isTrue();
        assertThat(LyricsRenderText.visualWords(line, true)).satisfiesExactly(word -> {
            assertThat(word.text()).isEqualTo("konnichiwa");
            assertThat(word.fragments()).extracting(fragment -> fragment.timing().startMillis())
                    .containsExactly(1_000L, 1_350L, 1_700L);
            assertThat(word.fragments()).extracting(fragment -> fragment.timing().endMillis())
                    .containsExactly(1_350L, 1_700L, 2_400L);
        });
    }

    @Test
    void usesRomanizedWordBoundariesInsteadOfUnspacedJapaneseGeometry() {
        LyricLine line = new LyricLine(1_000, 3_000, "\u4ECA\u65E5\u306F\u4E16\u754C", List.of(
                new TimedWord(1_000, 1_350, "\u4ECA", false, "kon"),
                new TimedWord(1_350, 1_700, "\u65E5", true, "ni"),
                new TimedWord(1_700, 2_000, "\u306F", true, "chiwa"),
                new TimedWord(2_100, 3_000, "\u4E16\u754C", true, "sekai")),
                "v1", false, null, "konnichiwa sekai");

        assertThat(LyricsRenderText.visualWords(line, true)).satisfiesExactly(
                word -> {
                    assertThat(word.text()).isEqualTo("konnichiwa");
                    assertThat(word.startMillis()).isEqualTo(1_000);
                    assertThat(word.endMillis()).isEqualTo(2_000);
                    assertThat(word.fragments()).extracting(fragment -> fragment.timing().startMillis())
                            .containsExactly(1_000L, 1_350L, 1_700L);
                },
                word -> {
                    assertThat(word.text()).isEqualTo("sekai");
                    assertThat(word.startMillis()).isEqualTo(2_100);
                    assertThat(word.endMillis()).isEqualTo(3_000);
                });
    }

    private static LyricLine line(String text, List<TimedWord> words) {
        return new LyricLine(1_000, 3_000, text, words, "v1", false, null, null);
    }
}
