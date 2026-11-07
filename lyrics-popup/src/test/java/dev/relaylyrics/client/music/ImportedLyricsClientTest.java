package dev.relaylyrics.client.music;

import dev.relaylyrics.protocol.lyrics.LyricsSource;
import dev.relaylyrics.protocol.music.MusicCapabilities;
import dev.relaylyrics.protocol.music.PlaybackSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImportedLyricsClientTest {
    @TempDir Path directory;

    @Test void manualTtmlStaysWithItsTrackUntilTheCacheIsDeleted() throws Exception {
        Path imports = directory.resolve("lyrics/imports");
        var client = new ImportedLyricsClient(imports);
        var selected = track("Song", "Artist", "Album", null);
        Path chosen = directory.resolve("arbitrary name.TTML");
        Files.writeString(chosen, """
                <tt><body><p begin="1s" end="2s">My lyrics</p></body></tt>
                """);

        var saved = client.saveForTrack(selected, chosen);
        Files.delete(chosen);
        var restarted = new ImportedLyricsClient(imports);
        var loaded = restarted.find(selected).match().orElseThrow().document();
        assertThat(loaded.lines().getFirst().text()).isEqualTo("My lyrics");
        assertThat(loaded.metadata().source()).isEqualTo(LyricsSource.IMPORTED);
        assertThat(restarted.find(track("Song", "Other artist", "Album", null)).match()).isEmpty();
        assertThat(restarted.find(track("Song", "Artist", "Other album", null)).match()).isEmpty();

        Files.writeString(chosen, "<tt/>");
        assertThatThrownBy(() -> restarted.saveForTrack(selected, chosen)).isInstanceOf(IllegalArgumentException.class);
        assertThat(restarted.find(selected).match()).contains(saved);
        Files.delete(saved.path());
        assertThat(restarted.find(selected).match()).isEmpty();
    }

    @Test void spotifyIdIdentifiesTheRecordingEvenWhenMetadataChanges() throws Exception {
        var client = new ImportedLyricsClient(directory.resolve("lyrics/imports"));
        String id = "0123456789ABCDEFGHIJKL";
        Path chosen = directory.resolve("lyrics.ttml");
        Files.writeString(chosen, "<tt><body><p begin='1s' end='2s'>Imported</p></body></tt>");
        var saved = client.saveForTrack(track("Original title", "Artist", "Album", "spotify:track:" + id), chosen);

        assertThat(client.find(track("Updated title", "Artist", "Other album", id)).match()).contains(saved);
        assertThat(client.find(track("Original title", "Artist", "Album", "spotify:track:0123456789aBCDEFGHIJKL")).match()).isEmpty();
    }

    private static PlaybackSnapshot track(String title, String artist, String album, String uri) {
        return new PlaybackSnapshot(title, artist, album, null, uri, null, 0, 10_000, true,
                1, false, "off", false, 0, 0, 0, 0, new double[0],
                Instant.EPOCH, MusicCapabilities.unavailable());
    }
}
