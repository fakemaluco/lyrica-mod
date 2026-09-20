package dev.fandroid.client.music;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BackdropSamplingPlanTest {
    @Test
    void cachesTheOriginalBilinearCoordinatesAndCornerAlpha() {
        int width = 317;
        int height = 181;
        int radius = 16;
        var plan = new CoverArtTexture.BackdropSamplingPlan(width, height, radius, 96);

        for (int x : new int[]{0, 1, 47, 158, 315, 316}) {
            double sample = x * (95.0 / (width - 1));
            int lower = Math.min(94, (int) Math.floor(sample));
            assertThat(plan.sourceX(x)).isEqualTo(lower);
            assertThat(plan.fractionX(x)).isEqualTo(sample - lower);
        }
        for (int y : new int[]{0, 1, 90, 179, 180}) {
            double sample = y * (95.0 / (height - 1));
            int lower = Math.min(94, (int) Math.floor(sample));
            assertThat(plan.sourceY(y)).isEqualTo(lower);
            assertThat(plan.fractionY(y)).isEqualTo(sample - lower);
        }
        assertThat(plan.alpha(width / 2, height / 2)).isEqualTo(255);
        assertThat(plan.alpha(0, 0)).isZero();
        assertThat(plan.alpha(width - 1, 0)).isZero();
    }

    @Test
    void cachedSamplingMatchesTheLegacyCompositeWithinOneChannelLevel() {
        int width = 239;
        int height = 137;
        var plan = new CoverArtTexture.BackdropSamplingPlan(width, height, 13, 96);
        for (int y = 0; y < height; y++) {
            double legacyY = y * (95.0 / (height - 1));
            int legacyY0 = Math.min(94, (int) Math.floor(legacyY));
            double legacyTy = legacyY - legacyY0;
            for (int x = 0; x < width; x++) {
                double legacyX = x * (95.0 / (width - 1));
                int legacyX0 = Math.min(94, (int) Math.floor(legacyX));
                double legacyTx = legacyX - legacyX0;
                int legacy = sampleChannel(legacyX0, legacyY0, legacyTx, legacyTy);
                int cached = sampleChannel(plan.sourceX(x), plan.sourceY(y),
                        plan.fractionX(x), plan.fractionY(y));
                assertThat(Math.abs(cached - legacy)).isLessThanOrEqualTo(1);
            }
        }
    }

    private static int sampleChannel(int x, int y, double tx, double ty) {
        double top = channel(x, y) + (channel(x + 1, y) - channel(x, y)) * tx;
        double bottom = channel(x, y + 1) + (channel(x + 1, y + 1) - channel(x, y + 1)) * tx;
        return (int) Math.round(top + (bottom - top) * ty);
    }

    private static int channel(int x, int y) {
        return (x * 37 + y * 71 + x * y * 3) & 0xFF;
    }
}
