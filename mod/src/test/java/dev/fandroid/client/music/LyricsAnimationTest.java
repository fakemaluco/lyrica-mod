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
    void timingProgressIsClampedAndHandlesZeroLengthFragments() {
        assertThat(LyricsAnimation.timedProgress(900, 1_000, 2_000)).isZero();
        assertThat(LyricsAnimation.timedProgress(1_500, 1_000, 2_000)).isEqualTo(.5);
        assertThat(LyricsAnimation.timedProgress(2_100, 1_000, 2_000)).isEqualTo(1);
        assertThat(LyricsAnimation.timedProgress(1_000, 1_000, 1_000)).isEqualTo(1);
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
