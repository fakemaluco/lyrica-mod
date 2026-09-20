package dev.relaylyrics.client.music;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SpicyLyricsApiClientTest {
    @Test
    void extractsSpotifyTrackIdsWithoutAcceptingArbitraryValues() {
        assertThat(SpicyLyricsApiClient.spotifyTrackId(
                "spotify:track:1234567890123456789012")).isEqualTo("1234567890123456789012");
        assertThat(SpicyLyricsApiClient.spotifyTrackId(
                "https://open.spotify.com/track/abcdefghijklmnopqrstuv?si=test"))
                .isEqualTo("abcdefghijklmnopqrstuv");
        assertThat(SpicyLyricsApiClient.spotifyTrackId("not-a-track")).isNull();
    }
}
