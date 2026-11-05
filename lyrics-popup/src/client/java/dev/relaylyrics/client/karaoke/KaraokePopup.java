package dev.relaylyrics.client.karaoke;

import dev.relaylyrics.client.RelayLyricsClient;
import dev.relaylyrics.client.music.CoverArtTexture;
import dev.relaylyrics.client.ui.LyricsOverlay;
import dev.relaylyrics.protocol.music.MusicCapabilities;
import dev.relaylyrics.protocol.music.PlaybackSnapshot;

import java.time.Instant;

/** Presentation and input state separate from the player's HUD popup. */
final class KaraokePopup implements AutoCloseable {
    final LyricsOverlay overlay = new LyricsOverlay(RelayLyricsClient.preferences());
    private CoverArtTexture artwork;
    private long revision = -1;

    void update(KaraokeClient.Room room) {
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
        if (revision != room.lyricRevision) {
            overlay.setLyrics(room.document());
            revision = room.lyricRevision;
        }
        if (song == null) {
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
        overlay.setPlayback(new PlaybackSnapshot(song.title(), song.artist(), "", song.cover(), song.uri(), null,
                room.position(), song.duration(), song.playing(), ownPlayback ? local.volume() : 0,
                song.shuffled(), song.repeat(), ownPlayback && local.liked(), 0, 0, 0, .5, new double[0],
                Instant.now(), new MusicCapabilities(true, true, true, song.duration() > 0,
                ownPlayback && local.capabilities().volume(), true, true, !song.uri().isBlank(), false)));
    }

    @Override public void close() {
        overlay.close();
        if (artwork != null) artwork.close();
    }
}
