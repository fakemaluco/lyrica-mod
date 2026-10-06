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
    void activeHaloCoversQuickAndHeldWordsWithoutLingering() {
        double quick = LyricsAnimation.activeGlyphGlow(0, 400, .5, .25);
        double held = LyricsAnimation.activeGlyphGlow(0, 5_000, .5, .25);
        assertThat(quick).isPositive();
        assertThat(held).isGreaterThan(quick);
        assertThat(LyricsAnimation.activeGlyphGlow(0, 400, 0, .25)).isZero();
        assertThat(LyricsAnimation.activeGlyphGlow(0, 400, 1, .25)).isZero();
        var soft = LyricsAnimation.activeGlowProfile(quick, .55);
        var strong = LyricsAnimation.activeGlowProfile(quick, 1.45);
        assertThat(strong.coreOpacity()).isGreaterThan(soft.coreOpacity());
        assertThat(strong.outerRadius()).isGreaterThan(soft.outerRadius());
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
        LyricsAnimation.TokenMotionPlan almostHeldWord = LyricsAnimation.tokenMotionPlan(999, 1, false);
        LyricsAnimation.TokenMotionPlan heldWord = LyricsAnimation.tokenMotionPlan(1_000, 1, false);
        LyricsAnimation.TokenMotionPlan syllableWord = LyricsAnimation.tokenMotionPlan(500, 3, false);
        LyricsAnimation.TokenMotionPlan heldSyllableWord = LyricsAnimation.tokenMotionPlan(2_000, 3, false);

        assertThat(quickWord.wholeWordMotion()).isTrue();
        assertThat(almostHeldWord.wholeWordMotion()).isTrue();
        assertThat(heldWord.glyphMotion()).isTrue();
        assertThat(syllableWord.syllableMotion()).isTrue();
        assertThat(syllableWord.wholeWordMotion()).isFalse();
        assertThat(syllableWord.glyphMotion()).isFalse();
        assertThat(syllableWord.glyphMotionForFragment(999)).isFalse();
        assertThat(syllableWord.glyphMotionForFragment(1_000)).isTrue();
        assertThat(syllableWord.glyphMotionForFragment(1_001)).isTrue();
        assertThat(heldSyllableWord.syllableMotion()).isTrue();
        assertThat(heldSyllableWord.glyphMotion()).isFalse();
        assertThat(heldSyllableWord.glyphMotionForFragment(500)).isFalse();
        assertThat(LyricsAnimation.tokenMotionPlan(2_000, 3, true))
                .isEqualTo(new LyricsAnimation.TokenMotionPlan(false, false, false));
    }

    @Test
    void oneSecondHandoffIsVisibleAndDoesNotSnapFromWholeWordMotion() {
        assertThat(LyricsAnimation.individualMotionStrength(999)).isZero();
        assertThat(LyricsAnimation.individualMotionStrength(1_000)).isEqualTo(.55);
        assertThat(LyricsAnimation.individualMotionStrength(1_001)).isGreaterThanOrEqualTo(.55);
        assertThat(LyricsAnimation.individualMotionStrength(1_750)).isBetween(.55, 1.0);
        assertThat(LyricsAnimation.individualMotionStrength(2_500)).isEqualTo(1);
        assertThat(LyricsAnimation.shortWordLift(999, .75))
                .isLessThan(LyricsAnimation.shortWordLift(800, .75));
        assertThat(LyricsAnimation.shortWordLift(1_000, .75)).isZero();
    }

    @Test
    void interludeMatchesTheThreeSecondWaitingWindow() {
        assertThat(LyricsAnimation.interludeVisibility(500, 2_999)).isZero();
        assertThat(LyricsAnimation.interludeVisibility(70, 3_000)).isBetween(0.0, 1.0);
        assertThat(LyricsAnimation.interludeVisibility(140, 3_000)).isEqualTo(1);
        assertThat(LyricsAnimation.interludeVisibility(2_700, 3_000)).isBetween(0.0, 1.0);
        assertThat(LyricsAnimation.interludeVisibility(2_900, 3_000)).isZero();
        assertThat(LyricsAnimation.interludeProgress(1_225, 3_000)).isEqualTo(.5);
        assertThat(LyricsAnimation.interludeProgress(2_450, 3_000)).isEqualTo(1);
    }
}
