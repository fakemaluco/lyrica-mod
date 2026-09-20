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

    @Test
    void smallVividDetailsEnterThePalette() {
        // 50% preto, 40% branco, 2% vermelho, 1% azul
        int[] counts = new int[4_096];
        long[] red = new long[4_096];
        long[] green = new long[4_096];
        long[] blue = new long[4_096];
        addBucket(counts, red, green, blue, 0, 0, 0, 5_000);
        addBucket(counts, red, green, blue, 255, 255, 255, 4_000);
        addBucket(counts, red, green, blue, 255, 0, 0, 200);
        addBucket(counts, red, green, blue, 0, 0, 255, 100);

        int[] palette = CoverArtTexture.selectPaletteColors(counts, red, green, blue);
        int accent = palette[0];

        int ar = red(accent);
        int ag = green(accent);
        int ab = blue(accent);
        assertThat(Math.max(ar, Math.max(ag, ab)) - Math.min(ar, Math.min(ag, ab)))
                .isGreaterThan(60);
        assertThat(ar).isGreaterThan(ag);
        boolean blueDetailPresent = false;
        for (int color : palette) {
            if (blue(color) > red(color) && blue(color) > green(color)) {
                blueDetailPresent = true;
                break;
            }
        }
        assertThat(blueDetailPresent).isTrue();
    }

    @Test
    void neutralCoversKeepANeutralAccent() {
        int[] counts = new int[4_096];
        long[] red = new long[4_096];
        long[] green = new long[4_096];
        long[] blue = new long[4_096];
        addBucket(counts, red, green, blue, 64, 64, 64, 3_000);
        addBucket(counts, red, green, blue, 128, 128, 128, 4_000);
        addBucket(counts, red, green, blue, 192, 192, 192, 2_000);

        int accent = CoverArtTexture.selectPaletteColors(counts, red, green, blue)[0];
        assertThat(red(accent)).isEqualTo(green(accent)).isEqualTo(blue(accent));
    }

    private static void addBucket(int[] counts, long[] red, long[] green, long[] blue,
            int r, int g, int b, int amount) {
        int bucket = (r >> 4) << 8 | (g >> 4) << 4 | b >> 4;
        counts[bucket] = amount;
        red[bucket] = (long) r * amount;
        green[bucket] = (long) g * amount;
        blue[bucket] = (long) b * amount;
    }

    private static int red(int color) { return color >>> 16 & 255; }
    private static int green(int color) { return color >>> 8 & 255; }
    private static int blue(int color) { return color & 255; }
}
