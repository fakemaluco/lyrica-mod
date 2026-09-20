package dev.relaylyrics.client.music;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LyricsAnimationTest {
    @Test
    void syllableTimedWordsAnimateTheirSyllablesInsteadOfTheWholeWord() {
        LyricsAnimation.TokenMotionPlan quickWord = LyricsAnimation.tokenMotionPlan(500, 1, false);
        LyricsAnimation.TokenMotionPlan heldWord = LyricsAnimation.tokenMotionPlan(2_000, 1, false);
        LyricsAnimation.TokenMotionPlan syllableWord = LyricsAnimation.tokenMotionPlan(500, 3, false);
        LyricsAnimation.TokenMotionPlan heldSyllableWord = LyricsAnimation.tokenMotionPlan(2_000, 3, false);

        assertThat(quickWord.wholeWordMotion()).isTrue();
        assertThat(heldWord.glyphMotion()).isTrue();
        assertThat(syllableWord.syllableMotion()).isTrue();
        assertThat(syllableWord.wholeWordMotion()).isFalse();
        assertThat(syllableWord.glyphMotion()).isFalse();
        assertThat(heldSyllableWord.syllableMotion()).isTrue();
        assertThat(heldSyllableWord.glyphMotion()).isFalse();
        assertThat(LyricsAnimation.tokenMotionPlan(2_000, 3, true))
                .isEqualTo(new LyricsAnimation.TokenMotionPlan(false, false, false));
    }
}
