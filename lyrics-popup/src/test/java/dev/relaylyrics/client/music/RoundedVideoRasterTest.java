package dev.relaylyrics.client.music;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RoundedVideoRasterTest {
    @Test void integratedCanvasSoftensDetailAndFeathersIntoTheLyricsWhileKeepingOnlyOuterCorners() {
        int[] source = new int[128 * 128];
        for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) source[y * 128 + x] = ((x + y) & 1) == 0 ? 0xFF000000 : 0xFFFFFFFF;
        var plan = new RoundedVideoRaster(128, 128, 128, 128, 12, 1, true);
        int[] output = new int[128 * 128]; plan.render(source, output);
        assertThat(output[20 * 128 + 32] & 255).isBetween(40, 210);
        assertThat(output[0] >>> 24).isZero();
        assertThat(output[24] >>> 24).isEqualTo(255);
        assertThat(output[64 * 128] >>> 24).isEqualTo(255);
        assertThat(output[64 * 128 + 104] >>> 24).isBetween(1, 200);
        assertThat(output[64 * 128 + 127] >>> 24).isZero();
    }
    @Test void coverCropFillsEveryRowOfATallCanvasEvenForLandscapeVideo() {
        int[] source = new int[160 * 96]; Arrays.fill(source, 0xFFBB8866);
        var plan = new RoundedVideoRaster(160, 96, 96, 600, 12, 1, true);
        int[] output = new int[plan.width() * plan.height()]; plan.render(source, output);
        for (int y = 0; y < plan.height(); y++) assertThat(output[y * plan.width() + plan.width() / 2] >>> 24).isEqualTo(255);
    }
    @Test void eachFrameHasTransparentCornersAndAntialiasedEdgesAtDisplayDensity() {
        var plan = new RoundedVideoRaster(80, 120, 48, 60, 8, 3);
        int[] source = new int[80 * 120], output = new int[plan.width() * plan.height()];
        Arrays.fill(source, 0xFFFF2020); plan.render(source, output);
        assertThat(output[0] >>> 24).isZero();
        assertThat(output[output.length - 1] >>> 24).isZero();
        assertThat(output[plan.width() * (plan.height() / 2) + plan.width() / 2]).isEqualTo(0xFFFF2020);
        assertThat(Arrays.stream(output).filter(c -> c >>> 24 > 0 && c >>> 24 < 255).count()).isGreaterThan(20);
        assertThat(plan.width()).isEqualTo(144);
    }
    @Test void reusingTheMaskUpdatesVideoPixelsWithoutChangingTheCornerShape() {
        var plan = new RoundedVideoRaster(80, 120, 48, 60, 8, 1);
        int[] source = new int[80 * 120], a = new int[plan.width() * plan.height()], b = new int[a.length];
        Arrays.fill(source, 0xFF2040FF); plan.render(source, a);
        Arrays.fill(source, 0xFF20FF40); plan.render(source, b);
        assertThat(Arrays.equals(a, b)).isFalse();
        for (int i = 0; i < a.length; i++) assertThat(a[i] >>> 24).isEqualTo(b[i] >>> 24);
    }
}
