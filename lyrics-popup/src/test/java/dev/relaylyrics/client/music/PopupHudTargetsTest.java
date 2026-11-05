package dev.relaylyrics.client.music;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

public class PopupHudTargetsTest {
    public static final class Context {
        public int x = 40, y = 60, w = 256, h = 300, screenWidth = 2000, screenHeight = 1200;
    }
    @AfterEach void clear() { PopupHudTargets.clear(); }

    @Test void capturedCoordinatesConvertFromTheModsRenderViewportToTheCurrentGui() {
        PopupHudTargets.captureXaero(new Context());
        var target = PopupHudTargets.targets(1000, 600, true, System.nanoTime()).getFirst();
        assertThat(target.bounds()).isEqualTo(new PopupAlignment.Rect(20, 30, 128, 150));
        assertThat(target.label()).contains("Xaero");
    }

    @Test void editingCanUseTheLastHudFrameButHiddenHudsStopBeingObstacles() {
        PopupHudTargets.recordXaero(new PopupAlignment.Rect(10, 20, 128, 128), 1000, 600, 1);
        assertThat(PopupHudTargets.targets(1000, 600, true, 600_000_001)).hasSize(1);
        assertThat(PopupHudTargets.targets(1000, 600, false, 600_000_001)).isEmpty();
        assertThat(PopupHudTargets.targets(1000, 600, true, 600_000_001)).isEmpty();
        PopupHudTargets.recordXaero(new PopupAlignment.Rect(800, 50, 180, 180), 1000, 600, 600_000_001);
        assertThat(PopupHudTargets.targets(1000, 600, true, 600_000_002).getFirst().bounds())
                .isEqualTo(new PopupAlignment.Rect(800, 50, 180, 180));
        PopupHudTargets.captureXaero(new Object());
        assertThat(PopupHudTargets.targets(1000, 600, true, 600_000_002)).hasSize(1);
    }
}
