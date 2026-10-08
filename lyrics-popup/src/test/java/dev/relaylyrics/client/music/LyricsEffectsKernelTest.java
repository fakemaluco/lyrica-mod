package dev.relaylyrics.client.music;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LyricsEffectsKernelTest {
    @Test
    void shadowSoftnessKeepsOneCenterLayerAndBoundedEdges() {
        assertThat(LyricsShadowKernel.taps(0)).hasSize(1);
        assertThat(LyricsShadowKernel.taps(1)).hasSize(5);
        assertThat(LyricsShadowKernel.taps(2)).hasSize(9);
        assertThat(LyricsShadowKernel.taps(2).stream().filter(tap -> tap.x() == 0 && tap.y() == 0))
                .hasSize(1);
        assertThat(LyricsShadowKernel.safeAlpha(3.49)).isZero();
        assertThat(LyricsShadowKernel.safeAlpha(4)).isEqualTo(4);
        assertThat(LyricsShadowKernel.taps(2).stream().mapToDouble(LyricsShadowKernel.Tap::opacity).sum())
                .isLessThanOrEqualTo(.55);
    }
}
