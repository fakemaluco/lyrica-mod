package dev.fandroid.client.music;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LyricsAnimationTest {
    @Test
    void sustainedWordsReceiveMoreGlowThanShortFragments() {
        assertThat(LyricsAnimation.sustainedGlowStrength(500)).isZero();
        assertThat(LyricsAnimation.sustainedGlowStrength(2_500))
                .isGreaterThan(LyricsAnimation.sustainedGlowStrength(1_200));
        assertThat(LyricsAnimation.sustainedGlowStrength(6_000)).isEqualTo(1);
    }

    @Test
    void emphasisKeepsRestingGeometryBeforeAndAfterTheWord() {
        assertThat(LyricsAnimation.wordEmphasis(-.2)).isZero();
        assertThat(LyricsAnimation.wordEmphasis(.5)).isGreaterThan(.9);
        assertThat(LyricsAnimation.wordEmphasis(1.2)).isZero();
    }

    @Test
    void quickWordsLiftAsAWholeAndHeldWordsUsePerGlyphMotion() {
        assertThat(LyricsAnimation.usesPerGlyphMotion(999)).isFalse();
        assertThat(LyricsAnimation.usesPerGlyphMotion(1_000)).isTrue();
        assertThat(LyricsAnimation.usesPerGlyphMotion(1_001)).isTrue();
        assertThat(LyricsAnimation.shortWordLift(500, .5)).isGreaterThan(.9);
        assertThat(LyricsAnimation.shortWordLift(500, 0)).isZero();
        assertThat(LyricsAnimation.shortWordLift(500, 1)).isGreaterThan(1);
        assertThat(LyricsAnimation.shortWordLift(500, 1.5)).isBetween(.4, .8);
        assertThat(LyricsAnimation.shortWordLift(500, 2.1)).isZero();
        assertThat(LyricsAnimation.shortWordLift(2_000, .5)).isZero();
        assertThat(LyricsAnimation.shortWordLift(500, .10))
                .isLessThan(LyricsAnimation.shortWordLift(500, .25));
        assertThat(LyricsAnimation.shortWordLift(500, .25))
                .isLessThan(LyricsAnimation.shortWordLift(500, .5));
        assertThat(LyricsAnimation.shortWordLift(500, .75))
                .isEqualTo(LyricsAnimation.shortWordLift(500, 1));
        assertThat(LyricsAnimation.shortWordLift(999, .75))
                .isLessThan(LyricsAnimation.shortWordLift(800, .75));
    }

    @Test
    void individualMotionStartsVisibleAndBuildsSmoothlyForHeldWords() {
        assertThat(LyricsAnimation.individualMotionStrength(999)).isZero();
        assertThat(LyricsAnimation.individualMotionStrength(1_000)).isEqualTo(.55);
        assertThat(LyricsAnimation.individualMotionStrength(1_001)).isGreaterThanOrEqualTo(.55);
        assertThat(LyricsAnimation.individualMotionStrength(1_750)).isBetween(.55, 1.0);
        assertThat(LyricsAnimation.individualMotionStrength(2_500)).isEqualTo(1);
    }

    @Test
    void timingProgressIsClampedAndHandlesZeroLengthFragments() {
        assertThat(LyricsAnimation.timedProgress(900, 1_000, 2_000)).isZero();
        assertThat(LyricsAnimation.timedProgress(1_500, 1_000, 2_000)).isEqualTo(.5);
        assertThat(LyricsAnimation.timedProgress(2_100, 1_000, 2_000)).isEqualTo(1);
        assertThat(LyricsAnimation.timedProgress(1_000, 1_000, 1_000)).isEqualTo(1);
    }

    @Test
    void focusProfileKeepsActiveAndNeighboursCrispWhileDistantLinesBlur() {
        assertThat(LyricsAnimation.ACTIVE_TEXT).isEqualTo(0xFFFFFFFF);
        assertThat(LyricsAnimation.UNSYNCED_TEXT).isEqualTo(0x33FFFFFF);
        LyricsAnimation.FocusProfile active = LyricsAnimation.focusProfile(0, false, false);
        LyricsAnimation.FocusProfile neighbour = LyricsAnimation.focusProfile(1, false, false);
        LyricsAnimation.FocusProfile distant = LyricsAnimation.focusProfile(4, false, false);

        assertThat(active.opacity()).isEqualTo(1);
        assertThat(active.scale()).isEqualTo(1);
        assertThat(active.blurRadius()).isZero();
        assertThat(active.textBrightness()).isEqualTo(1);
        assertThat(neighbour.opacity()).isEqualTo(.52);
        assertThat(neighbour.scale()).isEqualTo(.970);
        assertThat(neighbour.textBrightness()).isEqualTo(.80);
        assertThat(neighbour.opacity()).isLessThan(active.opacity());
        assertThat(neighbour.scale()).isLessThan(active.scale());
        assertThat(neighbour.blurRadius()).isZero();
        assertThat(distant.opacity()).isLessThan(neighbour.opacity());
        assertThat(distant.scale()).isLessThan(neighbour.scale());
        assertThat(distant.blurRadius()).isPositive();
    }

    @Test
    void progressiveFillRespectsTextDirection() {
        assertThat(LyricsAnimation.fillInterval(100, .25, false))
                .isEqualTo(new LyricsAnimation.FillInterval(0, 25));
        assertThat(LyricsAnimation.fillInterval(100, .25, true))
                .isEqualTo(new LyricsAnimation.FillInterval(75, 100));
        assertThat(LyricsAnimation.isRightToLeft("hello ")).isFalse();
        assertThat(LyricsAnimation.isRightToLeft("مرحبا")).isTrue();
    }

    @Test
    void focusProfileIsMonotonicSymmetricAndHasABoundedBlur() {
        LyricsAnimation.FocusProfile previous = LyricsAnimation.focusProfile(0, false, false);
        for (int distance = 1; distance <= 20; distance++) {
            LyricsAnimation.FocusProfile next = LyricsAnimation.focusProfile(distance, false, false);
            assertThat(next.opacity()).isLessThanOrEqualTo(previous.opacity());
            assertThat(next.scale()).isLessThanOrEqualTo(previous.scale());
            assertThat(next.textBrightness()).isLessThanOrEqualTo(previous.textBrightness());
            assertThat(next.blurRadius()).isGreaterThanOrEqualTo(previous.blurRadius());
            assertThat(next.blurRadius()).isLessThanOrEqualTo(3.6);
            previous = next;
        }
    }

    @Test
    void latestFocusAnchorKeepsOverlappingPhrasesOutOfTheActiveProfile() {
        assertThat(LyricsAnimation.focusDistance(6, 6)).isZero();
        assertThat(LyricsAnimation.focusDistance(5, 6)).isEqualTo(1);
        assertThat(LyricsAnimation.focusDistance(7, 6)).isEqualTo(1);
        assertThat(LyricsAnimation.focusDistance(2, -1)).isZero();
        assertThat(LyricsAnimation.focusDistance(4, 4, 5)).isZero();
        assertThat(LyricsAnimation.focusDistance(5, 4, 5)).isZero();
        assertThat(LyricsAnimation.focusDistance(3, 4, 5)).isEqualTo(1);
        assertThat(LyricsAnimation.focusDistance(6, 4, 5)).isEqualTo(1);
    }

    @Test
    void renderPlanKeepsInactiveLinesGlowFree() {
        LyricsAnimation.RenderPlan active = LyricsAnimation.focusProfile(0, false, false).renderPlan();
        LyricsAnimation.RenderPlan neighbour = LyricsAnimation.focusProfile(1, false, false).renderPlan();
        LyricsAnimation.RenderPlan distant = LyricsAnimation.focusProfile(2, false, false).renderPlan();

        assertThat(active.baseTextLayers()).isEqualTo(1);
        assertThat(active.distantBloomTextLayers()).isZero();
        assertThat(active.activeGlowTextLayers()).isZero();
        assertThat(neighbour.baseTextLayers()).isEqualTo(1);
        assertThat(neighbour.distantBloomTextLayers()).isZero();
        assertThat(neighbour.activeGlowTextLayers()).isZero();
        assertThat(distant.baseTextLayers()).isEqualTo(1);
        assertThat(distant.distantBloomTextLayers()).isZero();
        assertThat(distant.activeGlowTextLayers()).isZero();
    }

    @Test
    void manualReadingTemporarilyRemovesFadeScaleAndBlur() {
        ManualLyricsScroll scroll = new ManualLyricsScroll();
        long now = 5_000_000_000L;
        scroll.nudge(100, 24, 0, 400, now);
        LyricsAnimation.FocusProfile reading = LyricsAnimation.focusProfile(8, false, scroll.active(now));
        LyricsAnimation.FocusProfile simple = LyricsAnimation.focusProfile(8, true, false);

        assertThat(reading).isEqualTo(new LyricsAnimation.FocusProfile(1, 1, 0, 0, 0, 1));
        assertThat(simple).isEqualTo(reading);
        assertThat(reading.renderPlan().needsBloom()).isFalse();
    }

    @Test
    void activeGlowIsBoundedAndFollowsTheExistingIntensityLevels() {
        LyricsAnimation.ActiveGlowProfile none = LyricsAnimation.activeGlowProfile(0, 1);
        LyricsAnimation.ActiveGlowProfile soft = LyricsAnimation.activeGlowProfile(1.65, .55);
        LyricsAnimation.ActiveGlowProfile normal = LyricsAnimation.activeGlowProfile(1.65, 1);
        LyricsAnimation.ActiveGlowProfile strong = LyricsAnimation.activeGlowProfile(1.65, 1.45);

        assertThat(none.needsBloom()).isFalse();
        assertThat(soft.coreOpacity()).isEqualTo(.28);
        assertThat(soft.outerOpacity()).isEqualTo(.10);
        assertThat(normal.coreOpacity()).isEqualTo(.44);
        assertThat(normal.outerOpacity()).isEqualTo(.18);
        assertThat(strong.coreOpacity()).isEqualTo(.58);
        assertThat(strong.outerOpacity()).isEqualTo(.24);
        assertThat(soft.coreRadius()).isEqualTo(1.4);
        assertThat(normal.coreRadius()).isEqualTo(1.7);
        assertThat(strong.coreRadius()).isEqualTo(2.0);
        assertThat(soft.outerRadius()).isEqualTo(2.8);
        assertThat(normal.outerRadius()).isEqualTo(3.5);
        assertThat(strong.outerRadius()).isEqualTo(4.2);
        assertThat(none.renderPlan().activeGlowTextLayers()).isZero();
        assertThat(normal.renderPlan().baseTextLayers()).isZero();
        assertThat(normal.renderPlan().distantBloomTextLayers()).isZero();
        assertThat(normal.renderPlan().activeGlowTextLayers()).isEqualTo(2);
    }

    @Test
    void sustainedWordsDriveMoreActiveGlowThanShortFragments() {
        double shortGlow = LyricsAnimation.sustainedWordGlow(1_100, .5);
        double sustainedGlow = LyricsAnimation.sustainedWordGlow(5_000, .5);

        assertThat(LyricsAnimation.activeGlowProfile(sustainedGlow, 1).coreOpacity())
                .isGreaterThan(LyricsAnimation.activeGlowProfile(shortGlow, 1).coreOpacity());
        assertThat(LyricsAnimation.sustainedWordGlow(5_000, 0)).isZero();
        assertThat(LyricsAnimation.sustainedWordGlow(5_000, 1)).isZero();
    }

    @Test
    void onlyPerGlyphWordsHaveAHaloBedAndNoResidueOutsideItsInterval() {
        assertThat(LyricsAnimation.activeWordGlow(400, .5)).isZero();
        assertThat(LyricsAnimation.activeWordGlow(999, .5)).isZero();
        assertThat(LyricsAnimation.activeWordGlow(1_000, .5)).isPositive();
        assertThat(LyricsAnimation.activeWordGlow(5_000, .5)).isEqualTo(.85);
        assertThat(LyricsAnimation.activeWordGlow(5_000, 0)).isZero();
        assertThat(LyricsAnimation.activeWordGlow(5_000, 1)).isZero();
        assertThat(LyricsAnimation.activeWordGlow(5_000, .02))
                .isLessThan(LyricsAnimation.activeWordGlow(5_000, .5));
    }

    @Test
    void leadingEdgeAddsAtMostTwentyFivePercentAndNeverLeavesResidue() {
        double bed = LyricsAnimation.activeGlyphGlow(0, 5_000, .5, 0);
        double edge = LyricsAnimation.activeGlyphGlow(0, 5_000, .5, 1);

        assertThat(edge - bed).isCloseTo(.15, org.assertj.core.data.Offset.offset(.000001));
        assertThat(LyricsAnimation.activeGlyphGlow(1, 400, .5, 1)).isZero();
        assertThat(LyricsAnimation.activeGlyphGlow(1, 5_000, 0, 1)).isZero();
        assertThat(LyricsAnimation.activeGlyphGlow(1, 5_000, 1, 1)).isZero();
        assertThat(LyricsAnimation.activeGlyphGlow(1, 5_000, .5, 1)).isEqualTo(1);
    }

    @Test
    void activeGlowEntersAndLeavesGradually() {
        assertThat(LyricsAnimation.activeGlowEnvelope(0)).isZero();
        assertThat(LyricsAnimation.activeGlowEnvelope(.03)).isBetween(0.0, .10);
        assertThat(LyricsAnimation.activeGlowEnvelope(.12))
                .isGreaterThan(LyricsAnimation.activeGlowEnvelope(.03));
        assertThat(LyricsAnimation.activeGlowEnvelope(.5)).isEqualTo(1);
        assertThat(LyricsAnimation.activeGlowEnvelope(.94)).isBetween(0.0, .20);
        assertThat(LyricsAnimation.activeGlowEnvelope(1)).isZero();
    }

    @Test
    void boundaryGlowPeaksOnTheSungEdgeAndFadesBehindIt() {
        assertThat(LyricsAnimation.boundaryGlow(0)).isEqualTo(1);
        assertThat(LyricsAnimation.boundaryGlow(1)).isGreaterThan(LyricsAnimation.boundaryGlow(3));
        assertThat(LyricsAnimation.boundaryGlow(6)).isLessThan(.005);
    }

    @Test
    void activeGlowNeverLeaksToNeighboursOrManualReading() {
        assertThat(LyricsAnimation.activeGlowEligible(4, 4, true, false, false)).isTrue();
        assertThat(LyricsAnimation.activeGlowEligible(3, 4, true, false, false)).isFalse();
        assertThat(LyricsAnimation.activeGlowEligible(4, 4, false, false, false)).isFalse();
        assertThat(LyricsAnimation.activeGlowEligible(4, 4, true, true, false)).isFalse();
        assertThat(LyricsAnimation.activeGlowEligible(4, 4, true, false, true)).isFalse();
    }

    @Test
    void interludeDotsPulseInSequenceAndLoop() {
        assertThat(LyricsAnimation.interludePulse(250, 0)).isGreaterThan(.8);
        assertThat(LyricsAnimation.interludePulse(250, 1)).isZero();
        assertThat(LyricsAnimation.interludePulse(750, 1)).isGreaterThan(.8);
        assertThat(LyricsAnimation.interludePulse(1_250, 2)).isGreaterThan(.8);
        assertThat(LyricsAnimation.interludePulse(1_750, 0))
                .isEqualTo(LyricsAnimation.interludePulse(250, 0));
    }

    @Test
    void interludeDotsFillAndLiftOnlyFromLeftToRight() {
        assertThat(LyricsAnimation.interludeDotFill(.20, 0)).isPositive();
        assertThat(LyricsAnimation.interludeDotFill(.20, 1)).isZero();
        assertThat(LyricsAnimation.interludeDotFill(.20, 2)).isZero();
        assertThat(LyricsAnimation.interludeDotLift(.20, 0)).isPositive();
        assertThat(LyricsAnimation.interludeDotLift(.20, 1)).isZero();

        assertThat(LyricsAnimation.interludeDotFill(.52, 0)).isEqualTo(1);
        assertThat(LyricsAnimation.interludeDotLift(.52, 0)).isZero();
        assertThat(LyricsAnimation.interludeDotLift(.52, 1)).isPositive();
        assertThat(LyricsAnimation.interludeDotLift(.52, 2)).isZero();

        assertThat(LyricsAnimation.interludeDotFill(.90, 0)).isEqualTo(1);
        assertThat(LyricsAnimation.interludeDotFill(.90, 1)).isEqualTo(1);
        assertThat(LyricsAnimation.interludeDotLift(.90, 2)).isPositive();
    }
}
