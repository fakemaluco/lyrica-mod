package dev.fandroid.protocol.lyrics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LyricsMatchScorerTest {
    @Test
    void handlesAccentsFeaturesAndNonLatinTitles() {
        assertThat(LyricsMatchScorer.score("Coração (feat. Ana)", "João & Ana", "Verão",
                "Coracao", "Joao, Ana", "Verao")).isGreaterThan(.85);
        assertThat(LyricsMatchScorer.score("ラグトレイン", "稲葉曇", "ウェザーステーション",
                "ラグトレイン", "稲葉曇", "ウェザーステーション")).isEqualTo(1);
    }

    @Test
    void penalizesDifferentVersionsAndArtists() {
        double correct = LyricsMatchScorer.score("Midnight", "Example Artist", "Blue",
                "Midnight", "Example Artist", "Blue");
        double live = LyricsMatchScorer.score("Midnight", "Example Artist", "Blue",
                "Midnight (Live)", "Example Artist", "Live at Home");
        double wrongArtist = LyricsMatchScorer.score("Midnight", "Example Artist", "Blue",
                "Midnight", "Someone Else", "Blue");
        assertThat(correct).isGreaterThan(live);
        assertThat(correct).isGreaterThan(wrongArtist);
        assertThat(live).isLessThan(.84);
    }

    @Test
    void canonicalKeyDeduplicatesFormattingVariants() {
        assertThat(LyricsMatchScorer.canonicalKey("Song (Remastered)", "A & B", "Album"))
                .isEqualTo(LyricsMatchScorer.canonicalKey("Song", "B, A", "Album"));
    }
}
