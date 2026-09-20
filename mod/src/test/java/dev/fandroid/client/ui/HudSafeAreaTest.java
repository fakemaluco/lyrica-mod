package dev.fandroid.client.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

final class HudSafeAreaTest {
    @Test
    void keepsPopupAwayFromEitherXaeroCorner() {
        HudSafeArea.Position left = HudSafeArea.adjust(8, 8, 240, 120,
                1920, 1080, true, false);
        HudSafeArea.Position right = HudSafeArea.adjust(1920 - 248, 8, 240, 120,
                1920, 1080, true, false);

        assertThat(left.x()).isGreaterThanOrEqualTo(180);
        assertThat(right.x() + 240).isLessThanOrEqualTo(1920 - 180);
    }

    @Test
    void keepsPopupAboveAppleSkinHudArea() {
        HudSafeArea.Position adjusted = HudSafeArea.adjust(1920 - 260, 1080 - 80,
                250, 70, 1920, 1080, false, true);

        assertThat(adjusted.y() + 70).isLessThanOrEqualTo(1080 - 50);
    }

    @Test
    void preservesPositionWhenNoCompatibilityObstacleIntersects() {
        HudSafeArea.Position adjusted = HudSafeArea.adjust(500, 300, 250, 150,
                1920, 1080, true, true);

        assertThat(adjusted).isEqualTo(new HudSafeArea.Position(500, 300));
    }
}
