package dev.fandroid.client.music;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RoundedCoverResolutionTest {
    @Test
    void rasterizesAtPhysicalGuiResolution() {
        assertThat(CoverArtTexture.roundedCompositeSize(46, 1, 1024)).isEqualTo(46);
        assertThat(CoverArtTexture.roundedCompositeSize(46, 2, 1024)).isEqualTo(92);
        assertThat(CoverArtTexture.roundedCompositeSize(46, 3, 1024)).isEqualTo(138);
        assertThat(CoverArtTexture.roundedCompositeSize(82, 2.5, 1024)).isEqualTo(205);
    }

    @Test
    void scalesCornerRadiusWithTheComposite() {
        assertThat(CoverArtTexture.roundedCompositeRadius(9, 1)).isEqualTo(9);
        assertThat(CoverArtTexture.roundedCompositeRadius(9, 2)).isEqualTo(18);
        assertThat(CoverArtTexture.roundedCompositeRadius(9, 2.5)).isEqualTo(23);
    }

    @Test
    void keepsTheExistingSafetyLimit() {
        assertThat(CoverArtTexture.roundedCompositeSize(600, 4, 1024)).isEqualTo(1024);
    }
}
