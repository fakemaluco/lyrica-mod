package dev.relaylyrics.client.music;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ResponsiveLyricsLayoutTest {
    @Test void tinyPopupsShrinkTheirContentsWithoutLosingTheLyricsArea() {
        double scale = ResponsiveLyricsLayout.contentScale(96, 64);
        assertThat(scale).isEqualTo(.4);
        assertThat(96 / scale).isEqualTo(240);
        assertThat(64 / scale).isEqualTo(160);
        assertThat(ResponsiveLyricsLayout.contentScale(420, 260)).isEqualTo(1);
    }
    @Test void largeGuiViewportsKeepSavedSizeAndSmallViewportsShrinkTheWholeSurface() {
        var desktop = ResponsiveLyricsLayout.popup(1280, 720, 24, 24, 420, 260);
        var highGui = ResponsiveLyricsLayout.popup(426, 240, 24, 24, 420, 260);
        assertThat(desktop.width()).isEqualTo(420);
        assertThat(highGui.width()).isBetween(180, 190);
        assertThat(highGui.height()).isBetween(110, 120);
        assertThat(highGui.width()).isLessThan(426 / 2);
        assertThat(highGui.height()).isLessThan(240 / 2);
    }
    @Test void resizingTheWindowDoesNotChangeTheSavedCoordinateSystem() {
        double scale = ResponsiveLyricsLayout.scale(426, 240);
        assertThat(ResponsiveLyricsLayout.design(420 * scale, scale)).isEqualTo(420);
        assertThat(ResponsiveLyricsLayout.popup(1280, 720, 100, 80, 300, 200))
                .isEqualTo(new ResponsiveLyricsLayout.Bounds(100, 80, 300, 200));
    }
    @Test void extremeSizesStayInsideTheViewport() {
        for (int w : new int[]{100, 200, 320, 426, 640, 1280}) {
            int h = w * 9 / 16;
            var box = ResponsiveLyricsLayout.popup(w, h, 4000, 4000, 900, 600);
            assertThat(box.x()).isGreaterThanOrEqualTo(0);
            assertThat(box.y()).isGreaterThanOrEqualTo(0);
            assertThat(box.x() + box.width()).isLessThanOrEqualTo(w);
            assertThat(box.y() + box.height()).isLessThanOrEqualTo(h);
        }
    }
}
