package dev.relaylyrics.client.music;

import com.fasterxml.jackson.databind.JsonNode;
import dev.relaylyrics.protocol.ProtocolCodec;
import dev.relaylyrics.client.MusicSource;
import dev.relaylyrics.protocol.music.MusicCapabilities;
import dev.relaylyrics.protocol.music.PlaybackSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SpotifyAccountClientTest {
    private static final String EXACT_ID = "1234567890123456789012";

    @Test
    void accountPlaybackIsPreferredInAutomaticAndExplicitAccountModes() {
        assertThat(SpotifyAccountClient.preferredSource(MusicSource.AUTOMATIC, true)).isTrue();
        assertThat(SpotifyAccountClient.preferredSource(MusicSource.AUTOMATIC, false)).isFalse();
        assertThat(SpotifyAccountClient.preferredSource(MusicSource.SPOTIFY_ACCOUNT, false)).isTrue();
        assertThat(SpotifyAccountClient.preferredSource(MusicSource.WINDOWS_MEDIA, true)).isFalse();
        assertThat(SpotifyAccountClient.preferredSource(MusicSource.SPICETIFY_BRIDGE, true)).isFalse();
    }

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
    void acceptsEquivalentCatalogDuplicatesButRejectsWeakMatches() throws Exception {
        JsonNode weak = ProtocolCodec.mapper().readTree("""
                [{"id":"1234567890123456789012","name":"Dynamite Remix","duration_ms":199054,
                  "artists":[{"name":"Unrelated Artist"}],"album":{"name":"Other"}}]
                """);
        JsonNode equivalentDuplicates = ProtocolCodec.mapper().readTree("""
                [
                  {"id":"1234567890123456789012","name":"Dynamite","duration_ms":199054,
                   "artists":[{"name":"BTS"}],"album":{"name":"BE"}},
                  {"id":"abcdefghijklmnopqrstuv","name":"Dynamite","duration_ms":199054,
                   "artists":[{"name":"BTS"}],"album":{"name":"BE"}}
                ]
                """);

        PlaybackSnapshot track = snapshot("Dynamite", "BTS", "BE", 199_054);
        assertThat(SpotifyAccountClient.bestTrackId(track, weak)).isNull();
        assertThat(SpotifyAccountClient.bestTrackId(track, equivalentDuplicates)).isEqualTo(EXACT_ID);
        assertThat(SpotifyAccountClient.bestTrackIds(track, equivalentDuplicates))
                .containsExactly(EXACT_ID, "abcdefghijklmnopqrstuv");
    }

    @Test
    void resolvesAmiantoDespiteDuplicateOriginalRecording() throws Exception {
        JsonNode items = ProtocolCodec.mapper().readTree("""
                [
                  {"id":"3SliIEDG2MqiurzdU60QRq","name":"Amianto","duration_ms":257252,
                   "artists":[{"name":"Supercombo"}],"album":{"name":"Amianto"}},
                  {"id":"5IFNS2BcvVkJjF9q3XvNSK","name":"Amianto - Acústica","duration_ms":169779,
                   "artists":[{"name":"Supercombo"}],"album":{"name":"Amianto"}},
                  {"id":"0Uv33NbC03tRQxpkYY87TV","name":"Amianto","duration_ms":240267,
                   "artists":[{"name":"Supercombo"},{"name":"Liniker e os Caramelows"}],
                   "album":{"name":"Session da Tarde: 1ª Temporada"}},
                  {"id":"3utVM4ldh78ta02hLOpFeF","name":"Amianto","duration_ms":257252,
                   "artists":[{"name":"Supercombo"}],"album":{"name":"Amianto"}}
                ]
                """);

        assertThat(SpotifyAccountClient.bestTrackId(
                snapshot("Amianto", "Supercombo", "Amianto", 257_252), items))
                .isEqualTo("3SliIEDG2MqiurzdU60QRq");
    }

    @Test
    void searchesCleanYouTubeMetadataAndAcceptsTheMatchingSpotifyTrack() throws Exception {
        PlaybackSnapshot youtube = snapshot("BTS - Dynamite (Official Video)",
                "BTS - Topic", "", 199_054);
        assertThat(SpotifyAccountClient.searchQueries(youtube)).containsExactly(
                "track:Dynamite artist:BTS", "Dynamite BTS", "Dynamite");
        JsonNode items = ProtocolCodec.mapper().readTree("""
                [{"id":"1234567890123456789012","name":"Dynamite","duration_ms":199054,
                  "artists":[{"name":"BTS"}],"album":{"name":"BE"}}]
                """);
        assertThat(SpotifyAccountClient.bestTrackId(youtube, items)).isEqualTo(EXACT_ID);
    }

    private static PlaybackSnapshot snapshot(String title, String artist, String album, long duration) {
        return new PlaybackSnapshot(title, artist, album, null, null, null, 0, duration,
                true, .5, false, "off", false, 0, 0, 0, .5, new double[0],
                Instant.parse("2026-01-01T00:00:00Z"), MusicCapabilities.unavailable());
    }
}
