package dev.relaylyrics.client.music;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PopupResizeGestureTest {
    @Test void everyEdgeAndCornerCanResizeWithoutTreatingTheCenterAsAHandle() {
        assertThat(PopupResizeGesture.edgesAt(100, 150, 100, 100, 300, 200, 6)).isEqualTo(PopupResizeGesture.LEFT);
        assertThat(PopupResizeGesture.edgesAt(250, 100, 100, 100, 300, 200, 6)).isEqualTo(PopupResizeGesture.TOP);
        assertThat(PopupResizeGesture.edgesAt(400, 300, 100, 100, 300, 200, 6)).isEqualTo(PopupResizeGesture.RIGHT | PopupResizeGesture.BOTTOM);
        assertThat(PopupResizeGesture.edgesAt(100, 100, 100, 100, 300, 200, 6)).isEqualTo(PopupResizeGesture.LEFT | PopupResizeGesture.TOP);
        assertThat(PopupResizeGesture.edgesAt(250, 200, 100, 100, 300, 200, 6)).isZero();
        assertThat(PopupResizeGesture.edgesAt(90, 150, 100, 100, 300, 200, 6)).isZero();
    }
    @Test void topLeftResizeKeepsTheOppositeCornerAnchoredAndAllowsAFreeAspectRatio() {
        var start = new PopupResizeGesture.Start(100, 100, 300, 200, 100, 100, PopupResizeGesture.LEFT | PopupResizeGesture.TOP);
        var frame = start.resize(40, 80, 0, 0, 960, 540);
        assertThat(frame).isEqualTo(new ResponsiveLyricsLayout.Bounds(40, 80, 360, 220));
        var smallest = start.resize(900, 900, 0, 0, 960, 540);
        assertThat(smallest).isEqualTo(new ResponsiveLyricsLayout.Bounds(304, 236, 96, 64));
        assertThat(start.resize(100, 100, 0, 0, 960, 540)).isEqualTo(new ResponsiveLyricsLayout.Bounds(100, 100, 300, 200));
    }
    @Test void bottomRightTracksPointerAndStopsAtViewportWithoutChangingOtherAxes() {
        var start = new PopupResizeGesture.Start(100, 100, 300, 200, 400, 300, PopupResizeGesture.RIGHT | PopupResizeGesture.BOTTOM);
        assertThat(start.resize(480, 320, 0, 0, 960, 540)).isEqualTo(new ResponsiveLyricsLayout.Bounds(100, 100, 380, 220));
        assertThat(start.resize(1200, 800, 0, 0, 960, 540)).isEqualTo(new ResponsiveLyricsLayout.Bounds(100, 100, 860, 440));
        var edge = new PopupResizeGesture.Start(100, 100, 300, 200, 400, 200, PopupResizeGesture.RIGHT);
        assertThat(edge.resize(500, 400, 0, 0, 960, 540)).isEqualTo(new ResponsiveLyricsLayout.Bounds(100, 100, 400, 200));
    }
}
