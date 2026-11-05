package dev.relaylyrics.client.music;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CinematicLyricsCameraTest {
    @Test void cinematicPlacementLeavesRoomForLetterboxAndCaption() {
        for (var position : WorldLyricsSpace.Position.values()) {
            var right = WorldLyricsSpace.cinematicOffset(position, 10, 4.2, 1);
            var left = WorldLyricsSpace.cinematicOffset(position, 10, 4.2, -1);
            assertThat(right.side()).isEqualTo(-left.side());
            assertThat(right.up()).isBetween(0.0, .8);
            assertThat(Math.atan2(right.side() + 4.2 / 2, right.forward()))
                    .isLessThan(Math.toRadians(35));
        }
    }

    @Test void entryCutsAndReturnReframeWithoutDependingOnPlaybackTime() {
        var camera = new WorldLyricsSpace.CinematicCamera();
        assertThat(camera.update(false, 0, 0, 0, 0, 0, 1, 1)).isFalse();
        assertThat(camera.update(true, 0, 0, 0, 0, 0, 1, 2)).isTrue();
        assertThat(camera.update(true, .01, 0, 0, 0, 0, 1, 3)).isFalse();
        assertThat(camera.update(true, 20, 5, 0, 1, 0, 0, 4)).isTrue();
        assertThat(camera.update(true, 20, 5, 0, -1, 0, 0, 5)).isTrue();
        assertThat(camera.update(false, 0, 0, 0, 0, 0, 1, 6)).isTrue();
        assertThat(camera.update(false, 0, 0, 0, 0, 0, 1, 7)).isFalse();
        camera.reset();
        assertThat(camera.update(true, 0, 0, 0, 0, 0, 1, 8)).isTrue();
    }

    @Test void gradualOrbitReframesWithHysteresisAndCutsRemainImmediate() {
        var camera = new WorldLyricsSpace.CinematicCamera();
        camera.update(true, 0, 0, 0, 0, 0, 1, 1);
        assertThat(camera.update(true, 3.1, 0, 0, 0, 0, 1, 100_000_001)).isFalse();
        assertThat(camera.update(true, 3.1, 0, 0, 0, 0, 1, 250_000_001)).isTrue();
        double angle = Math.toRadians(35);
        assertThat(camera.update(true, 3.1, 0, 0, Math.sin(angle), 0, Math.cos(angle), 500_000_001)).isTrue();
        assertThat(camera.update(true, 3.2, 0, 0, Math.sin(angle), 0, Math.cos(angle), 500_000_002)).isFalse();
        assertThat(camera.update(true, 3.2, 0, 0, 0, 0, -1, 500_000_003)).isTrue();
    }
}
