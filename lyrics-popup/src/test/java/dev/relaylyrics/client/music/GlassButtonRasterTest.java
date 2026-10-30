package dev.relaylyrics.client.music;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.assertj.core.api.Assertions.assertThat;

class GlassButtonRasterTest {
    @Test void aNarrowVolumeCapsuleCastsItsShadowSymmetricallyInsteadOfSmearingToOneSide() {
        var surface = GlassButtonRaster.render(10, 110, 5, 3);
        int row = surface.height() / 2;
        int left = (int) (surface.padding() * surface.density()) - 6;
        int right = surface.width() - 1 - left;
        assertThat(Math.abs((surface.base()[row * surface.width() + left] >>> 24)
                - (surface.base()[row * surface.width() + right] >>> 24))).isLessThanOrEqualTo(1);
    }
    @Test void glassStaysTranslucentAndItsTopAndBottomCatchDifferentLight() {
        var surface = GlassButtonRaster.render(42, 42, 21, 3);
        int center = pixel(surface, 21, 21) >>> 24;
        int top = pixel(surface, 21, .2) >>> 24;
        int bottom = pixel(surface, 21, 41.8) >>> 24;
        assertThat(center).isBetween(20, 135);
        assertThat(top).isGreaterThan(bottom);
        assertThat(top).isGreaterThan(center);
        assertThat(pixel(surface, -surface.padding() + .1, -surface.padding() + .1) >>> 24).isZero();
    }

    @Test void denseRasterHasSmoothFractionalEdgesWithoutAnOpaqueRectangle() {
        var surface = GlassButtonRaster.render(31, 31, 15, 3.5);
        assertThat(Arrays.stream(surface.light()).map(c -> c >>> 24).distinct().count()).isGreaterThan(8);
        assertThat(surface.light()[0] >>> 24).isZero();
        assertThat(surface.light()[surface.light().length - 1] >>> 24).isZero();
    }

    @Test void frostRemovesFineDetailAndDoesNotMutateTheOriginalArtwork() {
        int[] source = new int[64 * 64];
        for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++)
            source[y * 64 + x] = (x + y) % 2 == 0 ? 0xFFFFFFFF : 0xFF000000;
        int[] copy = source.clone(), blurred = GlassButtonRaster.frost(source, 64, 64);
        assertThat(source).containsExactly(copy);
        assertThat(blurred[32 * 64 + 32] & 255).isBetween(110, 140);
        assertThat(blurred[32 * 64 + 33] & 255).isBetween(110, 140);
    }

    private static int pixel(GlassButtonRaster.Surface surface, double x, double y) {
        int px = Math.clamp((int) ((x + surface.padding()) * surface.density()), 0, surface.width() - 1);
        int py = Math.clamp((int) ((y + surface.padding()) * surface.density()), 0, surface.height() - 1);
        return surface.base()[py * surface.width() + px];
    }
}
