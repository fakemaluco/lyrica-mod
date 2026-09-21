package dev.relaylyrics.client.music;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LyricsAnimationTest {
    @Test
    void shadowStaysMutedUntilMostOfTheGlyphIsFilled() {
        double future = .20;
        assertThat(LyricsAnimation.progressiveShadowOpacity(future, 1, 0)).isEqualTo(future);
        assertThat(LyricsAnimation.progressiveShadowOpacity(future, 1, .72)).isEqualTo(future);
        assertThat(LyricsAnimation.progressiveShadowOpacity(future, 1, .86))
                .isBetween(future, 1.0);
        assertThat(LyricsAnimation.progressiveShadowOpacity(future, 1, 1)).isEqualTo(1);
    }

    @Test
    void liftUsesASlowerRoundedEntranceAndExit() {
        assertThat(LyricsAnimation.liftKeyframe(0)).isZero();
        assertThat(LyricsAnimation.liftKeyframe(.12)).isLessThan(.5);
        assertThat(LyricsAnimation.liftKeyframe(.24)).isLessThan(1);
        assertThat(LyricsAnimation.liftKeyframe(.30)).isEqualTo(1);
        assertThat(LyricsAnimation.liftKeyframe(.82)).isGreaterThan(0);
        assertThat(LyricsAnimation.liftKeyframe(1)).isZero();
        assertThat(LyricsAnimation.shortWordLift(500, .5))
                .isLessThan(1.08);
    }

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
