package dev.relaylyrics.client.music;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static dev.relaylyrics.client.music.WorldLyricsExperience.*;
import static dev.relaylyrics.client.music.WorldLyricsPresentation.Intensity.*;

class WorldLyricsSpicyMotionTest {
    @Test void discreetSpicyUsesTheFullSharedWordAndGlyphMotion() {
        double strength = spicyStrength(Style.SPICY, DISCREET, 1, true);
        assertThat(strength).isEqualTo(1);
        var word = new Motion(false);
        var glyph = new Motion(true);
        word.sample(0, 2_000, 1_400, 1_000_000_000L, true);
        glyph.sample(0, 2_000, 1_400, 1_000_000_000L, true);
        assertThat(1 + (word.scale() - 1) * strength).isGreaterThan(1.04);
        assertThat(1 + (glyph.scale() - 1) * strength).isGreaterThan(1.15);
        assertThat(word.elevation() * strength).isPositive();
        assertThat(glyph.elevation() * strength).isGreaterThan(word.elevation());
    }

    @Test void stylesAndIntensityKeepVisibleMotionWithoutOverridingExplicitDisable() {
        for (var style : Style.values()) {
            assertThat(spicyStrength(style, DISCREET, 1, true)).isPositive();
            assertThat(spicyStrength(style, BALANCED, 1, true)).isGreaterThanOrEqualTo(spicyStrength(style, DISCREET, 1, true));
            assertThat(spicyStrength(style, EXPRESSIVE, 1, true)).isGreaterThanOrEqualTo(spicyStrength(style, BALANCED, 1, true));
            assertThat(spicyStrength(style, EXPRESSIVE, 1, false)).isZero();
            assertThat(spicyStrength(style, EXPRESSIVE, 0, true)).isZero();
        }
        assertThat(spicyStrength(Style.SPICY, DISCREET, Double.NaN, true)).isEqualTo(1);
        assertThat(spicyStrength(Style.SPICY, EXPRESSIVE, 10, true)).isEqualTo(2);
    }

    @Test void pauseKeepsTheSpringPoseWhileRepeatedRenderFramesAndRealTimeAdvance() {
        var word = new Motion(false);
        word.sample(0, 2_000, 400, 1_000_000_000L, true);
        word.sample(0, 2_000, 416, 1_016_000_000L, true);
        double scale = word.scale(), elevation = word.elevation();
        for (int frame = 1; frame < 50; frame++)
            word.sample(0, 2_000, 416, 1_016_000_000L + frame * 16_000_000L, true);
        assertThat(word.scale()).isEqualTo(scale);
        assertThat(word.elevation()).isEqualTo(elevation);
    }

    @Test void seeksReconstructTheNewPoseWithoutCarryingPreviousSpringMomentum() {
        var glyph = new Motion(true);
        glyph.sample(0, 2_000, 1_600, 1_000_000_000L, true);
        glyph.sample(0, 2_000, 1_616, 1_016_000_000L, true);
        glyph.sample(0, 2_000, 600, 1_032_000_000L, true);
        assertThat(glyph.scale()).isEqualTo(LyricsAnimation.spicyScaleTarget(.3, true));
        assertThat(glyph.elevation()).isEqualTo(LyricsAnimation.spicyElevationTarget(.3, true));
        glyph.sample(0, 2_000, 1_700, 1_048_000_000L, true);
        assertThat(glyph.scale()).isEqualTo(LyricsAnimation.spicyScaleTarget(.85, true));
    }

    @Test void shortWordsUseTheWholeWordSpringAndHeldFragmentsArticulateGlyphs() {
        assertThat(LyricsAnimation.tokenMotionPlan(700, 1, false).wholeWordMotion()).isTrue();
        assertThat(LyricsAnimation.tokenMotionPlan(700, 1, false).glyphMotionForFragment(700)).isFalse();
        assertThat(LyricsAnimation.tokenMotionPlan(2_000, 1, false).glyphMotionForFragment(2_000)).isTrue();
        assertThat(LyricsAnimation.tokenMotionPlan(2_000, 2, false).glyphMotionForFragment(700)).isFalse();
        assertThat(LyricsAnimation.tokenMotionPlan(2_000, 2, false).glyphMotionForFragment(1_300)).isTrue();
        var disabled = new Motion(true);
        disabled.sample(0, 2_000, 1_400, 1_000_000_000L, false);
        assertThat(disabled.scale()).isEqualTo(1);
        assertThat(disabled.elevation()).isZero();
    }
}
