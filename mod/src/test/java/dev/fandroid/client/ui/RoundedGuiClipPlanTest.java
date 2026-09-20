package dev.fandroid.client.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RoundedGuiClipPlanTest {
    @Test
    void reducesModernCornerCallbacksAndStaysWithinOnePixel() {
        for (int radius = 9; radius <= 16; radius++) {
            var plan = RoundedGui.roundedClipPlan(radius);
            assertThat(RoundedGui.roundedClipPlan(radius)).isSameAs(plan);
            assertThat(1 + plan.size() * 2).isLessThanOrEqualTo(11);

            int coveredRows = 0;
            for (RoundedGui.RoundedClipBand band : plan) {
                assertThat(band.startRow()).isEqualTo(coveredRows);
                for (int row = band.startRow(); row < band.endRow(); row++) {
                    assertThat(Math.abs(band.inset() - exactInset(radius, row))).isLessThanOrEqualTo(1);
                }
                coveredRows = band.endRow();
            }
            assertThat(coveredRows).isEqualTo(radius);
        }
    }

    private static int exactInset(int radius, int row) {
        double dy = radius - row - .5;
        return radius - (int) Math.floor(Math.sqrt(Math.max(0, radius * radius - dy * dy)));
    }
}
