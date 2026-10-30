package dev.relaylyrics.client.ui;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SettingsTextRasterTest {
    @Test void labelsContainPhysicalPixelCoverageRatherThanBinaryNearestSampledEdges() {
        var raster = SettingsTextRaster.render("Configurações — Aparência", false, false, 1, 1, 180);
        int partial = 0, solid = 0;
        for (byte b : raster.alpha()) {
            int a = b & 255;
            if (a > 0 && a < 255) partial++;
            if (a == 255) solid++;
        }
        assertThat(partial).isGreaterThan(50);
        assertThat(solid).isGreaterThan(10);
    }
    @Test void guiDensityAndLocalHeadingScaleIncreaseTheRasterResolution() {
        var normal = SettingsTextRaster.render("Aparência", true, false, 1, 1, 70);
        var dense = SettingsTextRaster.render("Aparência", true, false, 1, 3, 70);
        var heading = SettingsTextRaster.render("Aparência", true, false, 1.65, 3, 115.5);
        assertThat(dense.width()).isGreaterThan(normal.width() * 2);
        assertThat(dense.height()).isGreaterThan(normal.height() * 2);
        assertThat(heading.height()).isGreaterThan(dense.height());
        assertThat(dense.density()).isEqualTo(3);
    }
    @Test void blankStringsAreCheapAndLongLabelsStayWithinTextureLimits() {
        assertThat(SettingsTextRaster.render(" ", false, false, 1, 3, 20).alpha()).containsOnly((byte) 0);
        var longLabel = SettingsTextRaster.render("Configuração ".repeat(80), false, false, 1, 4, 6000);
        assertThat(longLabel.width()).isLessThanOrEqualTo(4096);
    }
}
