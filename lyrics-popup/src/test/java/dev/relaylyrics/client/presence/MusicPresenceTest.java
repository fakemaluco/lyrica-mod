package dev.relaylyrics.client.presence;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;

class MusicPresenceTest {
    @Test void roomHashCanonicalizesPortsAndHostNamesWithoutCollidingServers() {
        assertThat(PresenceScope.serverHash("PLAY.Example.com.")).isEqualTo(PresenceScope.serverHash("play.example.com:25565"));
        assertThat(PresenceScope.serverHash("[::1]")).isEqualTo(PresenceScope.serverHash("[::1]:25565"));
        assertThat(PresenceScope.serverHash("localhost:25565")).isEqualTo(PresenceScope.serverHash("127.0.0.1:25565"));
        assertThat(PresenceScope.serverHash("localhost:25565")).isEqualTo(PresenceScope.serverHash(PresenceScope.localInterface() + ":25565"));
        assertThat(PresenceScope.serverHash("play.example.com:25566")).isNotEqualTo(PresenceScope.serverHash("play.example.com"));
        assertThatThrownBy(() -> PresenceScope.serverHash("")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void gazeRequiresThreeContinuousSecondsOnOnePlayerAndExpiresAfterLookingAway() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(); var gaze = new PresenceGaze();
        gaze.update(a, 0); gaze.update(a, PresenceGaze.DWELL - 1); assertThat(gaze.pinned()).isNull();
        gaze.update(b, PresenceGaze.DWELL); gaze.update(b, PresenceGaze.DWELL * 2 - 1); assertThat(gaze.pinned()).isNull();
        gaze.update(b, PresenceGaze.DWELL * 2); assertThat(gaze.pinned()).isEqualTo(b);
        gaze.update(null, PresenceGaze.DWELL * 2 + PresenceGaze.GRACE + 1); assertThat(gaze.pinned()).isNull();
        gaze.update(a, 20_000_000_000L); gaze.clear(); assertThat(gaze.progress(30_000_000_000L)).isZero();
    }
    @Test void worldAnchorProjectsThroughTheCameraAndHidesBehindOrOutsideTheViewport() {
        assertThat(PresenceProjection.project(0, 0, -5, 0, 0, 0, 1, 70, 800, 600)).isEqualTo(new PresenceProjection.Point(400, 300));
        assertThat(PresenceProjection.project(0, 0, 5, 0, 0, 0, 1, 70, 800, 600)).isNull();
        assertThat(PresenceProjection.project(500, 0, -5, 0, 0, 0, 1, 70, 800, 600)).isNull();
        assertThat(PresenceProjection.project(0, 0, 5, 0, 1, 0, 0, 70, 800, 600)).isEqualTo(new PresenceProjection.Point(400, 300));
    }
    @Test void metadataCannotInjectFormattingOrDownloadUntrustedArtwork() {
        var track = new PresenceTrack(UUID.randomUUID(), "Player", "§cSong\n", "Artist", "https://i.scdn.co.evil.test/image", 100);
        assertThat(track.title()).isEqualTo("Song"); assertThat(track.coverUrl()).isEmpty(); assertThat(track.alive(100)).isFalse();
        assertThat(PresenceTrack.safeCover("https://user@i.scdn.co/x")).isEmpty();
        assertThat(PresenceTrack.safeCover("https://i.scdn.co/image/x")).isEqualTo("https://i.scdn.co/image/x");
        var privateSong = new MusicPresenceClient.Playing("Secret", "Artist", "https://i.scdn.co/x", false);
        assertThat(privateSong.title()).isEmpty(); assertThat(privateSong.cover()).isEmpty();
    }
    @Test void systemArtworkIsReducedToABoundedThumbnailAndNotSharedAtFullResolution() throws Exception {
        var source = new BufferedImage(512, 256, BufferedImage.TYPE_INT_RGB);
        var out = new ByteArrayOutputStream(); ImageIO.write(source, "png", out);
        String original = "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        assertThat(PresenceArtwork.thumbnail(original)).isFalse();
        String small = PresenceArtwork.prepare(original);
        assertThat(PresenceArtwork.thumbnail(small)).isTrue(); assertThat(PresenceTrack.safeCover(small)).isEqualTo(small);
        var decoded = ImageIO.read(new java.io.ByteArrayInputStream(Base64.getDecoder().decode(small.substring(small.indexOf(',') + 1))));
        assertThat(decoded.getWidth()).isEqualTo(256); assertThat(decoded.getHeight()).isEqualTo(256);
    }
    @Test void returningToThePinnedCardImmediatelyCancelsClosing() {
        UUID player = UUID.randomUUID(); var gaze = new PresenceGaze();
        gaze.update(player, 0); gaze.update(player, PresenceGaze.DWELL);
        gaze.update(null, PresenceGaze.DWELL + 1_800_000_000L);
        assertThat(gaze.opacity(PresenceGaze.DWELL + 1_800_000_000L)).isBetween(0.0, 1.0);
        gaze.update(player, PresenceGaze.DWELL + 1_900_000_000L);
        gaze.update(player, PresenceGaze.DWELL + 3_000_000_000L);
        assertThat(gaze.pinned()).isEqualTo(player);
        assertThat(gaze.opacity(PresenceGaze.DWELL + 3_000_000_000L)).isEqualTo(1.0);
    }
}
