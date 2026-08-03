package dev.fandroid.client.music;

import org.junit.jupiter.api.Test;

import java.awt.Color;

import static org.assertj.core.api.Assertions.assertThat;

class CoverArtTransitionTest {
    @Test
    void oldCoverStaysWhileNewCoverEntersFromRight() {
        assertThat(CoverArtTexture.transitionOffsets(0, 82)).containsExactly(0, 82);
        assertThat(CoverArtTexture.transitionOffsets(.5, 82)).containsExactly(0, 41);
        assertThat(CoverArtTexture.transitionOffsets(1, 82)).containsExactly(0, 0);
    }

    @Test
    void transitionProgressIsClampedToOneCoverWidth() {
        assertThat(CoverArtTexture.transitionOffsets(-4, 64)).containsExactly(0, 64);
        assertThat(CoverArtTexture.transitionOffsets(5, 64)).containsExactly(0, 0);
    }

    @Test
    void incomingCoverUsesEaseInOutQuint() {
        assertThat(CoverArtTexture.easeInOutQuint(0)).isZero();
        assertThat(CoverArtTexture.easeInOutQuint(.25)).isEqualTo(.015625);
        assertThat(CoverArtTexture.easeInOutQuint(.5)).isEqualTo(.5);
        assertThat(CoverArtTexture.easeInOutQuint(.75)).isEqualTo(.984375);
        assertThat(CoverArtTexture.easeInOutQuint(1)).isEqualTo(1);
    }

    @Test
    void grayscaleArtworkNeverInventsColoredPaletteEntries() {
        int[] shades = {0, 24, 96, 160, 224, 255};
        for (int shade : shades) {
            int seed = CoverArtTexture.vibrant(shade, shade, shade);
            assertThat(red(seed)).isEqualTo(green(seed)).isEqualTo(blue(seed));
            for (int variant = 0; variant < 6; variant++) {
                int derived = CoverArtTexture.familyVariant(seed, variant);
                assertThat(red(derived)).isEqualTo(green(derived)).isEqualTo(blue(derived));
            }
        }
    }

    @Test
    void generatedVariantsStayInTheSourceColorFamily() {
        int seed = CoverArtTexture.vibrant(42, 118, 211);
        float sourceHue = Color.RGBtoHSB(red(seed), green(seed), blue(seed), null)[0];
        for (int variant = 0; variant < 6; variant++) {
            int derived = CoverArtTexture.familyVariant(seed, variant);
            float derivedHue = Color.RGBtoHSB(red(derived), green(derived), blue(derived), null)[0];
            float difference = Math.abs(sourceHue - derivedHue);
            assertThat(Math.min(difference, 1 - difference)).isLessThan(.01F);
        }
    }

    @Test
    void neutralPaletteIsDetectedSoOldTrackColorsDoNotBleedIntoIt() {
        assertThat(CoverArtTexture.isNeutralPalette(new int[]{
                0xFF050505, 0xFF555555, 0xFFAAAAAA, 0xFFF5F5F5
        })).isTrue();
        assertThat(CoverArtTexture.isNeutralPalette(new int[]{0xFF777777, 0xFFD44F87})).isFalse();
    }

    @Test
    void fluidBackdropUsesTheWholePaletteInsteadOfBlackForEmptyAreas() {
        int coloredBase = CoverArtTexture.backdropBaseColor(new int[]{
                0xFFE7A03B, 0xFFC56E31, 0xFFF0C96B, 0xFF9B5128
        });
        assertThat(Math.max(red(coloredBase), Math.max(green(coloredBase), blue(coloredBase))))
                .isGreaterThan(90);
        assertThat(red(coloredBase)).isGreaterThan(green(coloredBase));
        assertThat(green(coloredBase)).isGreaterThan(blue(coloredBase));

        int neutralBase = CoverArtTexture.backdropBaseColor(new int[]{
                0xFF111111, 0xFF666666, 0xFFBBBBBB, 0xFFF4F4F4
        });
        assertThat(red(neutralBase)).isEqualTo(green(neutralBase)).isEqualTo(blue(neutralBase));
        assertThat(red(neutralBase)).isGreaterThan(55);
    }

    private static int red(int color) { return color >>> 16 & 255; }
    private static int green(int color) { return color >>> 8 & 255; }
    private static int blue(int color) { return color & 255; }
}
