package dev.relaylyrics.client.music;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.assertj.core.api.Assertions.assertThat;

class KawarpRasterTest {
    private static float[] source(boolean swapped) {
        int[] pixels = new int[128 * 128];
        for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++)
            pixels[y * 128 + x] = (x < 64 ^ swapped) ? 0xFFFF3020 : 0xFF2040FF;
        return KawarpRaster.prepare(pixels, 128, 128, 8);
    }
    @Test void spatialCoverInformationIsPreservedInsteadOfReducingTheImageToAPalette() {
        int[] a = new int[128 * 128], b = new int[a.length];
        KawarpRaster.render(null, source(false), 1, 0, a);
        KawarpRaster.render(null, source(true), 1, 0, b);
        assertThat(Arrays.equals(a, b)).isFalse();
        assertThat(Arrays.stream(a).distinct().count()).isGreaterThan(100);
    }
    @Test void warpMovesAndCrossfadeEndpointsAreExact() {
        float[] first = source(false), second = source(true);
        int[] a = new int[128 * 128], b = new int[a.length], blended = new int[a.length];
        KawarpRaster.render(null, first, 1, 5, a);
        KawarpRaster.render(first, second, 0, 5, blended);
        assertThat(blended).isEqualTo(a);
        KawarpRaster.render(null, second, 1, 5, b);
        KawarpRaster.render(first, second, 1, 5, blended);
        assertThat(blended).isEqualTo(b);
        KawarpRaster.render(null, first, 1, 20, b);
        assertThat(Arrays.equals(a, b)).isFalse();
    }
    @Test void neutralArtworkDoesNotAcquireInventedColorsAndOutputIsOpaque() {
        int[] pixels = new int[128 * 128]; Arrays.fill(pixels, 0xFFCCCCCC);
        float[] prepared = KawarpRaster.prepare(pixels, 128, 128, 8);
        KawarpRaster.render(null, prepared, 1, 3, pixels);
        for (int c : pixels) {
            assertThat(c >>> 24).isEqualTo(255);
            assertThat(c >>> 16 & 255).isEqualTo(c >>> 8 & 255).isEqualTo(c & 255);
        }
    }
    @Test void unavailableAnalysisStaysFiniteAndPauseSlowsTheWarp() {
        assertThat(KawarpRaster.speed(false, 120, 1, 0)).isEqualTo(.1);
        assertThat(KawarpRaster.speed(true, 120, 1, 0)).isGreaterThan(KawarpRaster.speed(true, 120, 1, .8));
        assertThat(Double.isFinite(KawarpRaster.speed(true, Double.NaN, Double.NaN, Double.NaN))).isTrue();
        assertThat(KawarpRaster.speed(true, 240, 1, 0)).isBetween(.1, 3.);
    }
}
