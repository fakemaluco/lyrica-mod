package dev.relaylyrics.client.music;

import dev.relaylyrics.protocol.lyrics.LyricLine;
import dev.relaylyrics.protocol.lyrics.TimedWord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LyricsRenderTextRomanizationTest {
    @Test
    void aConcatenatedLineAlternativeCannotGlueDistinctTimedRomanizedWordsTogether() {
        LyricLine line = new LyricLine(1_000, 4_000, "안녕 세계", List.of(
                new TimedWord(1_000, 2_000, "안녕", false, "annyeong"),
                new TimedWord(3_000, 4_000, " 세계", false, "segye")),
                "v1", false, null, "annyeongsegye");
        assertThat(LyricsRenderText.visualWords(line, true))
                .extracting(LyricsRenderText.VisualWord::text).containsExactly("annyeong", "segye");
    }

    @Test
    void whitespaceAroundOneSyncedWordDoesNotDiscardItsProviderRomanization() {
        LyricLine line = new LyricLine(1_000, 4_000, "声", List.of(
                new TimedWord(1_000, 4_000, " 声 ", false, "koe")), "v1", true, null, "koe");
        assertThat(LyricsRenderText.visualWords(line, true)).satisfiesExactly(word -> {
            assertThat(word.text()).isEqualTo("koe");
            assertThat(word.startMillis()).isEqualTo(1_000);
            assertThat(word.endMillis()).isEqualTo(4_000);
        });
    }

    @Test
    void lineOnlyProviderSpellingCanStillUseCorrespondingOriginalWordIntervals() {
        LyricLine line = new LyricLine(1_000, 4_000, "愛 世界", List.of(
                new TimedWord(1_000, 2_000, "愛"),
                new TimedWord(3_000, 4_000, " 世界")), "v1", false, null, "ai sekai");
        assertThat(LyricsRenderText.visualWords(line, true)).satisfiesExactly(word -> {
            assertThat(word.text()).isEqualTo("ai");
            assertThat(word.endMillis()).isEqualTo(2_000);
        }, word -> {
            assertThat(word.text()).isEqualTo("sekai");
            assertThat(word.startMillis()).isEqualTo(3_000);
        });
    }

    @Test
    void aDifferentWholeLineSpellingNeverReplacesProviderSyllableTimings() {
        LyricLine line = new LyricLine(1_000, 5_000, "안녕 세계", List.of(
                new TimedWord(1_000, 1_400, "안", false, "an"),
                new TimedWord(1_700, 2_000, "녕", true, "nyeong"),
                new TimedWord(4_000, 4_800, " 세계", false, " se-gye")),
                "v1", false, null, "annyeongsegye");

        var words = LyricsRenderText.visualWords(line, true);
        assertThat(words).extracting(LyricsRenderText.VisualWord::text).containsExactly("annyeong", "se-gye");
        assertThat(words.getFirst().fragments()).extracting(fragment -> fragment.timing().startMillis())
                .containsExactly(1_000L, 1_700L);
        assertThat(words.getLast().startMillis()).isEqualTo(4_000);
        assertThat(words.getLast().endMillis()).isEqualTo(4_800);
    }

    @Test
    void romanizedWordSeparatorsRemainIndependentFromUnspacedJapaneseText() {
        LyricLine line = new LyricLine(1_000, 4_000, "私は", List.of(
                new TimedWord(1_000, 1_500, "私", false, "watashi"),
                new TimedWord(3_000, 4_000, "は", true, " wa")),
                "v1", false, null, null);
        assertThat(LyricsRenderText.visualWords(line, true)).satisfiesExactly(
                word -> assertThat(word.text()).isEqualTo("watashi"),
                word -> {
                    assertThat(word.text()).isEqualTo("wa");
                    assertThat(word.startMillis()).isEqualTo(3_000);
                });
    }

    @Test
    void localRomanizationDoesNotGlueSeparatelyTimedJapaneseWordsIntoOne() {
        LyricLine line = new LyricLine(1_000, 4_000, "こんにちは世界", List.of(
                new TimedWord(1_000, 2_000, "こんにちは"),
                new TimedWord(3_000, 4_000, "世界")), "v1", false, null, null);
        assertThat(LyricsRenderText.visualWords(line, true)).satisfiesExactly(
                word -> {
                    assertThat(word.startMillis()).isEqualTo(1_000);
                    assertThat(word.endMillis()).isEqualTo(2_000);
                }, word -> {
                    assertThat(word.startMillis()).isEqualTo(3_000);
                    assertThat(word.endMillis()).isEqualTo(4_000);
                });
    }

    @Test
    void locallyRomanizesKoreanWhenTheProviderOmittedItWithoutLosingSync() {
        LyricLine line = new LyricLine(1_000, 2_800, "안녕 세계", List.of(
                new TimedWord(1_000, 1_650, "안녕"),
                new TimedWord(1_900, 2_800, " 세계")),
                "v1", false, null, null);

        assertThat(LyricsRenderText.hasRomanization(line)).isTrue();
        assertThat(LyricsRenderText.visualWords(line, true)).satisfiesExactly(
                word -> {
                    assertThat(word.text()).doesNotContain("안", "녕");
                    assertThat(word.startMillis()).isEqualTo(1_000);
                    assertThat(word.endMillis()).isEqualTo(1_650);
                },
                word -> {
                    assertThat(word.text()).doesNotContain("세", "계");
                    assertThat(word.startMillis()).isEqualTo(1_900);
                    assertThat(word.endMillis()).isEqualTo(2_800);
                });
    }

    @Test
    void keepsProviderRomanizationAndOnlyGeneratesMissingFragments() {
        LyricLine line = new LyricLine(1_000, 2_800, "안녕 세계", List.of(
                new TimedWord(1_000, 1_650, "안녕", false, "provider-choice"),
                new TimedWord(1_900, 2_800, " 세계")),
                "v1", false, null, null);

        assertThat(LyricsRenderText.visualWords(line, true)).satisfiesExactly(
                word -> assertThat(word.text()).isEqualTo("provider-choice"),
                word -> {
                    assertThat(word.text()).doesNotContain("세", "계");
                    assertThat(word.startMillis()).isEqualTo(1_900);
                    assertThat(word.endMillis()).isEqualTo(2_800);
                });
    }

    @Test
    void doesNotInventRomanizationForAlreadyLatinLyrics() {
        LyricLine line = new LyricLine(1_000, 3_000, "already latin", List.of(),
                "v1", false, null, null);

        assertThat(LyricsRomanizer.romanize(line.text())).isNull();
        assertThat(LyricsRenderText.hasRomanization(line)).isFalse();
    }

    @Test
    void alsoRomanizesOtherUnicodeScriptsWhenTheProviderHasNoAlternativeText() {
        String romanized = LyricsRomanizer.romanize("Привет κόσμε");

        assertThat(romanized).isNotBlank();
        assertThat(romanized).doesNotContain("П", "р", "κ", "ό");
    }
}
