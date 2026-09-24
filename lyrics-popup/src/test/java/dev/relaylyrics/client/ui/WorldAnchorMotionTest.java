package dev.relaylyrics.client.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class WorldAnchorMotionTest {
    private static WorldLyricsRenderer.Anchor anchor(double x, double y, double z, float yaw) {
        return new WorldLyricsRenderer.Anchor(x, y, z, Math.sin(yaw), 0, Math.cos(yaw),
                Math.cos(yaw), -Math.sin(yaw), yaw, false, 8, 3, 1, 6);
    }

    @Test void phraseMovesContinuouslyAndKeepsItsFloorClearanceArc() {
        var start = anchor(0, 4, 0, 0);
        var end = anchor(20, 6, 30, .5f);
        assertThat(WorldLyricsRenderer.interpolateAnchor(start, end, 0, 4)).isSameAs(start);
        assertThat(WorldLyricsRenderer.interpolateAnchor(start, end, 1, 4)).isSameAs(end);
        var middle = WorldLyricsRenderer.interpolateAnchor(start, end, .5, 4);
        assertThat(middle.x()).isCloseTo(10, within(.0001));
        assertThat(middle.y()).isCloseTo(9, within(.0001));
        assertThat(middle.z()).isCloseTo(15, within(.0001));
    }

    @Test void orientationTakesTheShortRouteAcrossTheAngleBoundary() {
        var start = anchor(0, 0, 0, (float) Math.toRadians(179));
        var end = anchor(1, 0, 0, (float) Math.toRadians(-179));
        var middle = WorldLyricsRenderer.interpolateAnchor(start, end, .5, 0);
        assertThat(middle.normalZ()).isLessThan(-.99);
    }

    @Test void pausedMusicHoldsThePositionAndDisablingAnimationsFinishesTheMove() {
        var start = anchor(0, 0, 0, 0);
        var end = anchor(10, 0, 0, 0);
        var scene = new WorldLyricsRenderer.SceneLine(end, 0, null, 1000);
        scene.from = start;
        scene.moveStartedAt = 1000;
        scene.moveDuration = 800;
        var paused = scene.display(1400, true);
        assertThat(scene.display(1400, true)).isEqualTo(paused);
        assertThat(paused.x()).isCloseTo(5, within(.0001));
        assertThat(scene.display(1400, false)).isSameAs(end);
    }
}
