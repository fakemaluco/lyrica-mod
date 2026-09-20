package dev.fandroid.client.music;

import dev.fandroid.protocol.ProtocolCodec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

final class LyricsProviderClientTest {
    @Test
    void readsRankedAmllNativeApiCandidates() throws Exception {
        var root = ProtocolCodec.mapper().readTree("""
                [{"id":638303942317135,"musicNames":["Shape of You"],
                  "artistNames":["Ed Sheeran"],"albumNames":["Divide"],
                  "spotifyIds":["7qiZfU4dY1lWllzX7mPBI3"]}]
                """);

        var candidates = AmllLyricsClient.candidatesFromApi(root, "Shape of You",
                "Ed Sheeran", "Divide", "7qiZfU4dY1lWllzX7mPBI3");

        assertThat(candidates).singleElement().satisfies(candidate -> {
            assertThat(candidate.path()).isEqualTo("api:638303942317135");
            assertThat(candidate.score()).isEqualTo(1);
        });
    }

    @Test
    void readsBetterLyricsTtmlAndNormalizesScore() throws Exception {
        var root = ProtocolCodec.mapper().readTree("""
                {"ttml":"<tt><body/></tt>","score":95}
                """);

        var payload = BetterLyricsClient.payload(root);

        assertThat(payload.ttml()).isEqualTo("<tt><body/></tt>");
        assertThat(payload.score()).isEqualTo(.95);
    }

    @Test
    void extractsPrimaryArtistFromSpotifyCombinedArtists() {
        assertThat(AmllLyricsClient.primaryArtist("PSYQUI, Such")).isEqualTo("PSYQUI");
        assertThat(AmllLyricsClient.primaryArtist("Earth, Wind & Fire")).isEqualTo("Earth");
        assertThat(AmllLyricsClient.primaryArtist("Ed Sheeran")).isEqualTo("Ed Sheeran");
    }
}
