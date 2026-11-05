package dev.relaylyrics.client.compat;

import dev.relaylyrics.protocol.music.MusicCapabilities;
import dev.relaylyrics.protocol.music.PlaybackSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JiAfkPlaybackTest {
    @Test
    void restoresOnlyPlaybackStartedByLyrica() {
        var session = new JiAfkPlayback.Session();
        assertFalse(session.begin(track(true, "Spotify"), "bridge", 0));
        assertFalse(session.shouldPause(track(true, "Spotify"), "bridge", false, 1));

        assertTrue(session.begin(track(false, "Spotify"), "bridge", 2));
        assertTrue(session.awaitingResume(track(false, "Spotify"), "bridge", 3));
        assertFalse(session.shouldPause(track(true, "Spotify"), "bridge", true, 4));
        assertTrue(session.shouldPause(track(true, "Spotify"), "bridge", false, 5));
        assertFalse(session.shouldPause(track(true, "Spotify"), "bridge", false, 6));
    }

    @Test
    void restoresALateResumeAfterThePlayerReturns() {
        var session = new JiAfkPlayback.Session();
        assertTrue(session.begin(track(false, "Spotify"), "bridge", 0));
        assertFalse(session.shouldPause(track(false, "Spotify"), "bridge", false, 1));
        assertTrue(session.shouldPause(track(true, "Spotify"), "bridge", false, 2));
    }

    @Test
    void leavesAnotherPlayerAndAnExpiredResumeAlone() {
        var session = new JiAfkPlayback.Session();
        assertTrue(session.begin(track(false, "Spotify"), "bridge", 0));
        assertFalse(session.shouldPause(track(true, "Firefox"), "bridge", false, 1));

        assertTrue(session.begin(track(false, "Spotify"), "bridge", 2));
        assertFalse(session.shouldPause(track(true, "Spotify"), "bridge", false, TimeUnit.SECONDS.toNanos(9)));
    }

    @Test
    void givesUpOwnershipIfPlaybackIsPausedDuringAfk() {
        var session = new JiAfkPlayback.Session();
        assertTrue(session.begin(track(false, "Spotify"), "bridge", 0));
        assertFalse(session.shouldPause(track(true, "Spotify"), "bridge", true, 1));
        assertFalse(session.shouldPause(track(false, "Spotify"), "bridge", true, 2));
        assertFalse(session.shouldPause(track(true, "Spotify"), "bridge", false, 3));
    }

    private static PlaybackSnapshot track(boolean playing, String device) {
        return new PlaybackSnapshot("Song", "Artist", "Album", null, "spotify:track:fixture", device,
                3_000, 120_000, playing, .6, false, "off", false, 0, 0, 0, 0, new double[0],
                Instant.now(), new MusicCapabilities(true, false, false, false, false, false, false, true, true));
    }
}
