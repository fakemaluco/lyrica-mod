package dev.relaylyrics.client.music;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class LyricsAnimationTest {
    @Test
    void lineScrollTrailStaggersFollowingRowsAndCapsTheDelayAtFourRows() {
        var trail = new LyricsAnimation.ScrollTrail();
        trail.record(1_000_000_000L, 0, 2, true);
        trail.record(1_050_000_000L, 10, 2, false);
        trail.record(1_100_000_000L, 20, 2, false);
        trail.record(1_200_000_000L, 40, 2, false);
        assertThat(trail.offset(1)).isZero();
        assertThat(trail.offset(2)).isZero();
        assertThat(trail.offset(3)).isCloseTo(10, offset(1E-12));
        assertThat(trail.offset(4)).isCloseTo(20, offset(1E-12));
        assertThat(trail.offset(6)).isCloseTo(40, offset(1E-12));
        assertThat(trail.offset(60)).isEqualTo(trail.offset(6));
    }

    @Test
    void reverseScrollTrailsInTheOppositeDirectionAndManualSeekClearsTheDelay() {
        var trail = new LyricsAnimation.ScrollTrail();
        trail.record(1_000_000_000L, 40, 3, true);
        trail.record(1_050_000_000L, 30, 3, false);
        assertThat(trail.offset(4)).isZero();
        assertThat(trail.offset(2)).isEqualTo(-10);
        trail.record(1_100_000_000L, 200, 10, true);
        assertThat(trail.offset(0)).isZero();
        assertThat(trail.offset(20)).isZero();
    }

    @Test
    void referenceWordAndHeldLetterSplinesKeepTheOriginalKnots() {
        assertThat(LyricsAnimation.spicyScaleTarget(0, false)).isEqualTo(.95);
        assertThat(LyricsAnimation.spicyScaleTarget(.7, false)).isCloseTo(1.0505, offset(1E-12));
        assertThat(LyricsAnimation.spicyScaleTarget(.7, true)).isCloseTo(1.175, offset(1E-12));
        assertThat(LyricsAnimation.spicyScaleTarget(1, true)).isEqualTo(1);
        assertThat(LyricsAnimation.spicyElevationTarget(0, false)).isEqualTo(-.01);
        assertThat(LyricsAnimation.spicyElevationTarget(.9, false)).isCloseTo(1.0 / 60, offset(1E-12));
        assertThat(LyricsAnimation.spicyElevationTarget(.9, true)).isCloseTo(2.0 / 56, offset(1E-12));
        assertThat(LyricsAnimation.spicyElevationTarget(1, true)).isZero();
        for (boolean letter : new boolean[]{false, true}) {
            double knot = .7, delta = 1E-6;
            double centre = LyricsAnimation.spicyScaleTarget(knot, letter);
            double leftSlope = (centre - LyricsAnimation.spicyScaleTarget(knot - delta, letter)) / delta;
            double rightSlope = (LyricsAnimation.spicyScaleTarget(knot + delta, letter) - centre) / delta;
            assertThat(leftSlope).isCloseTo(rightSlope, offset(.0001));
        }
    }

    @Test
    void wordsRiseAndHeldLettersKeepSettlingAfterTheirTimingEnds() {
        var word = new LyricsAnimation.ElementMotion(false);
        var letter = new LyricsAnimation.ElementMotion(true);
        for (int frame = 0; frame < 120; frame++) {
            double progress = frame / 120.0;
            word.update(progress, true, 1 / 60.0);
            letter.update(progress, true, 1 / 60.0);
        }
        assertThat(word.elevation()).isPositive();
        assertThat(letter.elevation()).isGreaterThan(word.elevation());
        double previous = letter.elevation();
        letter.update(1, true, 1 / 60.0);
        assertThat(Math.abs(letter.elevation() - previous)).isLessThan(.01);
        for (int frame = 0; frame < 360; frame++) letter.update(1, true, 1 / 60.0);
        assertThat(letter.elevation()).isCloseTo(0, offset(.0001));
        assertThat(letter.scale()).isCloseTo(1, offset(.0001));
        assertThat(letter.glow()).isCloseTo(0, offset(.0001));
    }

    @Test
    void reducedMotionImmediatelyRestoresGeometryWithoutAHiddenSpringTail() {
        var letter = new LyricsAnimation.ElementMotion(true);
        for (int frame = 0; frame < 60; frame++) letter.update(.7, true, 1 / 60.0);
        assertThat(letter.scale()).isGreaterThan(1);
        letter.update(.7, false, 0);
        assertThat(letter.scale()).isEqualTo(1);
        assertThat(letter.elevation()).isZero();
        assertThat(letter.glow()).isZero();
    }

    @Test
    void seekDropsThePreviousWordVelocityAndSamplesTheDestinationPose() {
        var letter = new LyricsAnimation.ElementMotion(true);
        for (int frame = 0; frame < 60; frame++) letter.update(.7, true, 1 / 60.0);
        letter.reset(0, true);
        assertThat(letter.scale()).isEqualTo(.95);
        assertThat(letter.elevation()).isEqualTo(-.02);
        assertThat(letter.glow()).isZero();
        letter.reset(1, true);
        assertThat(letter.scale()).isEqualTo(1);
        assertThat(letter.elevation()).isZero();
    }

    @Test
    void referencePoseSpringsAreIndependentOfTheSelectedFrameRate() {
        var thirty = new LyricsAnimation.ElementMotion(true);
        var oneTwenty = new LyricsAnimation.ElementMotion(true);
        for (int frame = 0; frame < 30; frame++) thirty.update(.7, true, 1 / 30.0);
        for (int frame = 0; frame < 120; frame++) oneTwenty.update(.7, true, 1 / 120.0);
        assertThat(thirty.scale()).isCloseTo(oneTwenty.scale(), offset(1E-10));
        assertThat(thirty.elevation()).isCloseTo(oneTwenty.elevation(), offset(1E-10));
        assertThat(thirty.glow()).isCloseTo(oneTwenty.glow(), offset(1E-10));
    }

    @Test
    void spicyGlowMatchesTheReferenceKnotsAndHasAContinuousHeldWordCurve() {
        assertThat(LyricsAnimation.spicyGlowTarget(-.5)).isZero();
        assertThat(LyricsAnimation.spicyGlowTarget(0)).isZero();
        assertThat(LyricsAnimation.spicyGlowTarget(.15)).isCloseTo(1, offset(1E-12));
        assertThat(LyricsAnimation.spicyGlowTarget(.6)).isCloseTo(1, offset(1E-12));
        assertThat(LyricsAnimation.spicyGlowTarget(.8)).isCloseTo(.5, offset(1E-12));
        assertThat(LyricsAnimation.spicyGlowTarget(1)).isZero();
        assertThat(LyricsAnimation.spicyGlowTarget(1.5)).isZero();
        // The reference's natural spline deliberately rises above the held keys.
        assertThat(LyricsAnimation.spicyGlowTarget(.34)).isBetween(1.4, 1.5);
        assertThat(LyricsAnimation.spicyGlowTarget(.02))
                .isLessThan(LyricsAnimation.spicyGlowTarget(.10));
        assertThat(LyricsAnimation.spicyGlowTarget(.7))
                .isGreaterThan(LyricsAnimation.spicyGlowTarget(.95));
        for (double key : new double[]{.15, .6}) {
            double delta = 1E-6;
            double center = LyricsAnimation.spicyGlowTarget(key);
            double leftSlope = (center - LyricsAnimation.spicyGlowTarget(key - delta)) / delta;
            double rightSlope = (LyricsAnimation.spicyGlowTarget(key + delta) - center) / delta;
            assertThat(rightSlope).isCloseTo(leftSlope, offset(.0001));
        }
    }

    @Test
    void spicyProfileUsesOneCenteredHaloAndKeepsIntensitySeparateFromBlurSize() {
        var normal = LyricsAnimation.activeGlowProfile(1, 1, false);
        var dimmed = LyricsAnimation.activeGlowProfile(1, .5, false);
        var emphasized = LyricsAnimation.activeGlowProfile(1, 1, true);
        assertThat(normal.coreOpacity()).isCloseTo(.35, offset(1E-12));
        assertThat(normal.coreRadius()).isCloseTo(6 * 9.0 / 28, offset(1E-12));
        assertThat(emphasized.coreOpacity()).isEqualTo(1);
        assertThat(emphasized.coreRadius()).isCloseTo(16 * 9.0 / 28, offset(1E-12));
        assertThat(dimmed.coreOpacity()).isCloseTo(normal.coreOpacity() / 2, offset(1E-12));
        assertThat(dimmed.coreRadius()).isEqualTo(normal.coreRadius());
        for (var profile : new LyricsAnimation.ActiveGlowProfile[]{normal, dimmed, emphasized}) {
            assertThat(profile.outerOpacity()).isZero();
            assertThat(profile.outerRadius()).isEqualTo(profile.coreRadius());
            assertThat(profile.renderPlan().baseTextLayers()).isZero();
            assertThat(profile.renderPlan().activeGlowTextLayers()).isEqualTo(1);
        }
        assertThat(LyricsAnimation.activeGlowProfile(0, 1).needsBloom()).isFalse();
        assertThat(LyricsAnimation.activeGlowProfile(-.1, 1).needsBloom()).isFalse();
        assertThat(LyricsAnimation.activeGlowProfile(1, 0).needsBloom()).isFalse();
        assertThat(LyricsAnimation.activeGlowProfile(2, 20, true).coreRadius()).isLessThanOrEqualTo(9);
    }

    @Test
    void glowSpringFadesAtTheStartAndContinuesSettlingAfterTheWordEnds() {
        SpringValue spring = new SpringValue(0, 1.18, .56);
        spring.setGoal(LyricsAnimation.spicyGlowTarget(.15));
        double firstFrame = spring.step(1 / 60.0);
        assertThat(firstFrame).isBetween(0.0, .05);
        for (int frame = 0; frame < 30; frame++) spring.step(1 / 60.0);
        assertThat(spring.position()).isGreaterThan(.8);
        spring.setGoal(LyricsAnimation.spicyGlowTarget(1));
        double beforeExit = spring.position();
        double firstExitFrame = spring.step(1 / 60.0);
        assertThat(firstExitFrame).isGreaterThan(.5);
        assertThat(Math.abs(firstExitFrame - beforeExit)).isLessThan(.1);
        double fadeStartOpacity = LyricsAnimation.activeGlowProfile(firstExitFrame, 1).coreOpacity();
        for (int frame = 0; frame < 12; frame++) spring.step(1 / 60.0);
        assertThat(LyricsAnimation.activeGlowProfile(spring.position(), 1).coreOpacity())
                .isLessThan(fadeStartOpacity);
        for (int frame = 0; frame < 150; frame++) spring.step(1 / 60.0);
        assertThat(LyricsAnimation.activeGlowProfile(spring.position(), 1).needsBloom()).isFalse();
    }

    @Test
    void glowSpringProducesTheSameFadeAtDifferentAnimationFrameRates() {
        SpringValue thirtyFps = new SpringValue(0, 1.18, .56);
        SpringValue oneTwentyFps = new SpringValue(0, 1.18, .56);
        thirtyFps.setGoal(1);
        oneTwentyFps.setGoal(1);
        for (int frame = 0; frame < 30; frame++) thirtyFps.step(1 / 30.0);
        for (int frame = 0; frame < 120; frame++) oneTwentyFps.step(1 / 120.0);
        assertThat(thirtyFps.position()).isCloseTo(oneTwentyFps.position(), offset(1E-10));
        thirtyFps.setGoal(0);
        oneTwentyFps.setGoal(0);
        for (int frame = 0; frame < 6; frame++) thirtyFps.step(1 / 30.0);
        for (int frame = 0; frame < 24; frame++) oneTwentyFps.step(1 / 120.0);
        assertThat(LyricsAnimation.activeGlowProfile(thirtyFps.position(), 1).coreOpacity())
                .isCloseTo(LyricsAnimation.activeGlowProfile(oneTwentyFps.position(), 1).coreOpacity(), offset(1E-10));
    }

    @Test
    void activeWordGlowFadesInAndOutEvenForQuickWords() {
        assertThat(LyricsAnimation.activeGlyphGlow(0, 350, 0, 0)).isZero();
        assertThat(LyricsAnimation.activeGlyphGlow(0, 350, .08, 0))
                .isLessThan(LyricsAnimation.activeGlyphGlow(0, 350, .30, 0));
        assertThat(LyricsAnimation.activeGlyphGlow(0, 350, .50, 0))
                .isGreaterThan(LyricsAnimation.activeGlyphGlow(0, 350, .88, 0));
        assertThat(LyricsAnimation.activeGlyphGlow(0, 350, 1, 0)).isZero();
    }

    @Test
    void onlySungGlyphsCastAShadow() {
        assertThat(LyricsAnimation.sungShadowOpacity(1, 0)).isZero();
        assertThat(LyricsAnimation.sungShadowOpacity(1, .45)).isZero();
        assertThat(LyricsAnimation.sungShadowOpacity(1, .72))
                .isBetween(.1, .9);
        assertThat(LyricsAnimation.sungShadowOpacity(1, 1)).isEqualTo(1);
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
