package dev.relaylyrics.client.karaoke;

import dev.relaylyrics.client.RelayLyricsClient;
import dev.relaylyrics.client.music.CoverArtTexture;
import dev.relaylyrics.client.ui.LyricsOverlay;
import dev.relaylyrics.client.ui.KaraokePanelMotion;
import dev.relaylyrics.karaoke.KaraokeWire.Preview;
import dev.relaylyrics.protocol.music.MusicCapabilities;
import dev.relaylyrics.protocol.music.PlaybackSnapshot;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import dev.lyrica.engine.DesktopMotionArtworkEngine;
import dev.relaylyrics.protocol.lyrics.LyricsDocument;

import static dev.relaylyrics.karaoke.KaraokeWire.HEIGHT;
import static dev.relaylyrics.karaoke.KaraokeWire.WIDTH;

/** Presentation and input state separate from the player's HUD popup. */
final class KaraokePopup implements AutoCloseable {
    final LyricsOverlay overlay = new LyricsOverlay(RelayLyricsClient.preferences());
    int width = WIDTH, height = HEIGHT;
    int leftWing, rightWing;
    double drawerProgress;
    KaraokeQueueView queue;
    boolean invitation, spotifySync, motionEnabled;
    Preview preview;
    final KaraokePanelMotion.Buttons buttons = new KaraokePanelMotion.Buttons();

    KaraokeInvitationLayout invitationLayout() { return new KaraokeInvitationLayout(width, height); }
    double pointerX(double centeredX) { return (centeredX / WIDTH + .5) * width; }
    double pointerY(double centeredY) { return (centeredY / HEIGHT + .5) * height; }
    int wingPixels(int wing) { return (int) Math.round(wing * width / (double) WIDTH); }
    int textureWidth() { return width + wingPixels(leftWing) + wingPixels(rightWing); }

    private CoverArtTexture artwork;
    private long generation = -1;
    private CompletableFuture<LyricsDocument> lyricsLookup;
    private CompletableFuture<DesktopMotionArtworkEngine.Result> artworkLookup;

    void update(KaraokeClient.Room room) {
        motionEnabled = KaraokeSurfaces.spatialMotionEnabled();
        invitation = !room.accepted || invitation && motionEnabled && buttons.pressingAccept(System.nanoTime());
        queue = room.queue;
        boolean hasQueue = !invitation && KaraokeClient.hasQueue(room);
        var pose = queue.drawer.sample(hasQueue ? queue.side : 0, System.nanoTime(), motionEnabled && hasQueue);
        queue.displaySide = pose.side();
        drawerProgress = pose.progress();
        leftWing = KaraokeQueueView.wing(hasQueue, pose.side(), -1);
        rightWing = KaraokeQueueView.wing(hasQueue, pose.side(), 1);
        if (invitation) {
            preview = room.preview;
            spotifySync = KaraokeClient.canSyncSpotify();
            return;
        }
        overlay.setWorldPopupLyricsZoom(room.lyricsZoom);
        var source = RelayLyricsClient.overlay();
        if (room.owned() && source != null && source.hasCurrentSong()) {
            overlay.usePopupMediaFrom(source);
            return;
        }
        if (artwork == null) {
            artwork = new CoverArtTexture();
            overlay.setArtworkTexture(artwork);
        }
        var song = room.song;
        if (song == null) {
            if (generation != room.generation) {
                resetMedia();
                generation = room.generation;
            }
            artwork.update(null);
            overlay.setArtworkAccent(0);
            overlay.setPlayback(PlaybackSnapshot.unavailable());
            return;
        }
        artwork.update(song.cover());
        overlay.setArtworkAccent(artwork.accentColor());
        var music = RelayLyricsClient.music();
        var local = music == null ? PlaybackSnapshot.unavailable() : music.snapshot();
        boolean ownPlayback = room.owned() || room.isListeningOnSpotify();
        var track = new PlaybackSnapshot(song.title(), song.artist(), song.album(), song.cover(), song.uri(), null,
                room.position(), song.duration(), song.playing(), ownPlayback ? local.volume() : 0,
                song.shuffled(), song.repeat(), ownPlayback && local.liked(), 0, 0, 0, .5, new double[0],
                Instant.now(), new MusicCapabilities(true, true, true, song.duration() > 0,
                ownPlayback && local.capabilities().volume(), true, true, !song.uri().isBlank(), false));
        overlay.setPlayback(track);
        artwork.setMotionPlaying(song.playing());
        if (music == null) return;
        var preferences = RelayLyricsClient.preferences();
        boolean canvas = preferences.isMotionVideos() && preferences.isSpotifyCanvas();
        boolean cover = preferences.isMotionVideos() && preferences.isAnimatedCovers();
        boolean background = preferences.isMotionVideos() && preferences.isCanvasBackground();
        if (generation != room.generation) {
            resetMedia();
            generation = room.generation;
            overlay.beginLookup();
            lyricsLookup = music.lookupKaraokeLyrics(track);
            if (canvas || cover || background) artworkLookup = music.lookupKaraokeArtwork(track, canvas || background, cover);
        }
        if (lyricsLookup != null && lyricsLookup.isDone()) {
            var lyrics = lyricsLookup.isCompletedExceptionally() ? null : lyricsLookup.getNow(null);
            if (lyrics == null || lyrics.lines().isEmpty()) overlay.markUnavailable("Lyrics unavailable");
            else overlay.setLyrics(lyrics);
            lyricsLookup = null;
        }
        if (artworkLookup != null && artworkLookup.isDone()) {
            var result = artworkLookup.isCompletedExceptionally() ? null : artworkLookup.getNow(null);
            if (result != null) artwork.configureMotion(result.getCanvasUrl(), result.getAnimatedCoverUrl(),
                    canvas, cover, background, preferences.isPauseMotionWithMusic());
            artworkLookup = null;
        }
    }

    private void resetMedia() {
        if (lyricsLookup != null) lyricsLookup.cancel(true);
        if (artworkLookup != null) artworkLookup.cancel(true);
        lyricsLookup = null;
        artworkLookup = null;
        overlay.clearLyrics();
        artwork.clearMotion();
    }

    @Override public void close() {
        if (artwork != null) resetMedia();
        overlay.close();
        if (artwork != null) artwork.close();
    }
}
