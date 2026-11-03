package dev.relaylyrics.client.ui;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SettingsIconRasterTest {
    @Test void officialAssetsCoverEverySettingsCategoryAndScaleToTheFramebuffer() {
        for(String name:new String[]{"GENERAL","APPEARANCE","CUSTOM","CONNECTION","SOURCES","ADVANCED","WORLD","close","search","eye","chevron:0","chevron:16"}) {
            var raster=SettingsIconRaster.render(name,18,3);
            assertThat(raster.width()).isEqualTo(54);
            int partial=0,visible=0;
            for(byte value:raster.alpha()) {int alpha=value&255;if(alpha>0) visible++;if(alpha>0 && alpha<255) partial++;}
            assertThat(visible).as(name).isGreaterThan(40);
            assertThat(partial).as(name).isGreaterThan(10);
        }
    }
}
