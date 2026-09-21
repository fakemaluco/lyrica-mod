package dev.relaylyrics.client.music;

import dev.relaylyrics.protocol.lyrics.LyricLine;
import dev.relaylyrics.protocol.lyrics.TimedWord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LyricsRenderTextRomanizationTest {
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
