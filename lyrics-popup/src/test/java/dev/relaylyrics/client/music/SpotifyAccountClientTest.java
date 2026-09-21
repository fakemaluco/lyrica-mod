package dev.relaylyrics.client.music;

import com.fasterxml.jackson.databind.JsonNode;
import dev.relaylyrics.protocol.ProtocolCodec;
import dev.relaylyrics.protocol.music.MusicCapabilities;
import dev.relaylyrics.protocol.music.PlaybackSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SpotifyAccountClientTest {
    private static final String EXACT_ID = "1234567890123456789012";

    @Test
    void selectsExactMetadataAndDurationMatch() throws Exception {
        JsonNode items = ProtocolCodec.mapper().readTree("""
                [
                  {"id":"abcdefghijklmnopqrstuv","name":"Dynamite","duration_ms":199000,
                   "artists":[{"name":"Somebody Else"}],"album":{"name":"Dynamite"}},
                  {"id":"1234567890123456789012","name":"Dynamite","duration_ms":199054,
                   "artists":[{"name":"BTS"}],"album":{"name":"BE"}}
                ]
                """);

        assertThat(SpotifyAccountClient.bestTrackId(snapshot("Dynamite", "BTS", "BE", 199_054), items))
                .isEqualTo(EXACT_ID);
    }

    @Test
    void rejectsWeakOrAmbiguousCatalogMatches() throws Exception {
        JsonNode weak = ProtocolCodec.mapper().readTree("""
                [{"id":"1234567890123456789012","name":"Dynamite Remix","duration_ms":199054,
                  "artists":[{"name":"Unrelated Artist"}],"album":{"name":"Other"}}]
                """);
        JsonNode ambiguous = ProtocolCodec.mapper().readTree("""
                [
                  {"id":"1234567890123456789012","name":"Dynamite","duration_ms":199054,
                   "artists":[{"name":"BTS"}],"album":{"name":"BE"}},
                  {"id":"abcdefghijklmnopqrstuv","name":"Dynamite","duration_ms":199054,
                   "artists":[{"name":"BTS"}],"album":{"name":"BE"}}
                ]
                """);

        PlaybackSnapshot track = snapshot("Dynamite", "BTS", "BE", 199_054);
        assertThat(SpotifyAccountClient.bestTrackId(track, weak)).isNull();
        assertThat(SpotifyAccountClient.bestTrackId(track, ambiguous)).isNull();
    }

    private static PlaybackSnapshot snapshot(String title, String artist, String album, long duration) {
        return new PlaybackSnapshot(title, artist, album, null, null, null, 0, duration,
                true, .5, false, "off", false, 0, 0, 0, .5, new double[0],
                Instant.parse("2026-01-01T00:00:00Z"), MusicCapabilities.unavailable());
    }
}
