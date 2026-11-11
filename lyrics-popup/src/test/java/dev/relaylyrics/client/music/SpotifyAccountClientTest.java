package dev.relaylyrics.client.music;

import com.fasterxml.jackson.databind.JsonNode;
import dev.relaylyrics.protocol.ProtocolCodec;
import dev.relaylyrics.client.MusicSource;
import dev.relaylyrics.client.ClientPreferences;
import dev.relaylyrics.protocol.music.MusicCapabilities;
import dev.relaylyrics.protocol.music.PlaybackSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.time.Instant;
import java.net.http.HttpClient;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;

class SpotifyAccountClientTest {
    @TempDir Path directory;
    private static final String EXACT_ID = "1234567890123456789012";

    @Test
    void accountPlaybackIsPreferredInAutomaticAndExplicitAccountModes() {
        assertThat(SpotifyAccountClient.preferredSource(MusicSource.AUTOMATIC, true)).isTrue();
        assertThat(SpotifyAccountClient.preferredSource(MusicSource.AUTOMATIC, false)).isFalse();
        assertThat(SpotifyAccountClient.preferredSource(MusicSource.SPOTIFY_ACCOUNT, false)).isTrue();
        assertThat(SpotifyAccountClient.preferredSource(MusicSource.WINDOWS_MEDIA, true)).isFalse();
    }

    @Test
    void pausedAccountDoesNotHideSystemPlaybackButRemainsAvailableWhenExplicit() throws Exception {
        PlaybackSnapshot paused = ProtocolCodec.mapper().treeToValue(ProtocolCodec.mapper().readTree("""
                {"title":"Paused track","artist":"Artist","playing":false}
                """), PlaybackSnapshot.class);
        assertThat(SpotifyAccountClient.playbackEligible(MusicSource.AUTOMATIC, paused)).isFalse();
        assertThat(SpotifyAccountClient.playbackEligible(MusicSource.SPOTIFY_ACCOUNT, paused)).isTrue();
        assertThat(SpotifyAccountClient.playbackEligible(MusicSource.AUTOMATIC,
                snapshot("Playing track", "Artist", "Album", 120000))).isTrue();
    }

    @Test
    void automaticAccountKeepsOwnershipThroughPauseAndReleasesItToSystemPlayback() throws Exception {
        ClientPreferences preferences = new ClientPreferences();
        preferences.setMusicSource(MusicSource.AUTOMATIC);
        AtomicBoolean allowed = new AtomicBoolean(true);
        var received = new ArrayList<PlaybackSnapshot>();
        PlaybackSnapshot playing = snapshot("Track", "Artist", "Album", 120000);
        PlaybackSnapshot paused = paused(playing);
        try (var client = new SpotifyAccountClient(preferences, received::add, allowed::get,
                HttpClient.newHttpClient(), directory.resolve("tokens.json"), false)) {
            client.accept(paused);
            assertThat(client.shouldOverrideBridge()).isFalse();
            assertThat(received).isEmpty();
            client.accept(playing);
            client.accept(paused);
            assertThat(client.shouldOverrideBridge()).isTrue();
            assertThat(received).containsExactly(playing, paused);
            allowed.set(false);
            assertThat(client.shouldOverrideBridge()).isFalse();
            client.accept(playing); // An HTTP response arriving after OS takeover must not own the UI.
            allowed.set(true);
            assertThat(client.shouldOverrideBridge()).isFalse();
            client.accept(paused);
            assertThat(client.shouldOverrideBridge()).isFalse();
            assertThat(received).containsExactly(playing, paused);
        }
    }

    @Test
    void emptyAccountResponseClearsOnlyPreviouslyOwnedPlayback() {
        ClientPreferences preferences = new ClientPreferences();
        preferences.setMusicSource(MusicSource.AUTOMATIC);
        var received = new ArrayList<PlaybackSnapshot>();
        try (var client = new SpotifyAccountClient(preferences, received::add, () -> true,
                HttpClient.newHttpClient(), directory.resolve("tokens.json"), false)) {
            client.accept(PlaybackSnapshot.unavailable());
            assertThat(received).isEmpty();
            client.accept(snapshot("Track", "Artist", "Album", 120000));
            client.accept(PlaybackSnapshot.unavailable());
            assertThat(received).hasSize(2);
            assertThat(received.getLast().title()).isEqualTo("Nothing playing");
            assertThat(client.shouldOverrideBridge()).isFalse();
        }
    }

    private static PlaybackSnapshot paused(PlaybackSnapshot value) throws Exception {
        var json = ProtocolCodec.mapper().valueToTree(value);
        ((com.fasterxml.jackson.databind.node.ObjectNode) json).put("playing", false);
        return ProtocolCodec.mapper().treeToValue(json, PlaybackSnapshot.class);
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

    @Test
    void searchesAddsAndAdvancesTheActualQueueOnlyFromItsCurrentVersion() throws Exception {
        String current = "spotify:track:" + EXACT_ID;
        String first = "spotify:track:" + "a".repeat(22);
        String target = "spotify:track:" + "b".repeat(22);
        String addition = "spotify:track:" + "c".repeat(22);
        var playing = new AtomicReference<>(current);
        var tracks = new ArrayDeque<>(List.of(first, target));
        var skips = new AtomicInteger();
        var added = new AtomicReference<String>();
        var staleReadsAfterAdd = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1", exchange -> {
            try (exchange) {
                var uri = exchange.getRequestURI();
                if (exchange.getRequestMethod().equals("POST")) {
                    if (uri.getPath().endsWith("/next")) {
                        skips.incrementAndGet();
                        playing.set(tracks.removeFirst());
                    } else {
                        added.set(uri.getQuery());
                        tracks.add(addition);
                        staleReadsAfterAdd.set(1);
                    }
                    exchange.sendResponseHeaders(204, -1);
                } else {
                    boolean staleRead = staleReadsAfterAdd.getAndUpdate(count -> Math.max(0, count - 1)) > 0;
                    Object body = uri.getPath().endsWith("/search")
                            ? Map.of("tracks", Map.of("items", List.of(Map.of("uri", addition, "name", "New track"))))
                            : Map.of("currently_playing", Map.of("uri", playing.get()),
                                    "queue", tracks.stream().filter(track -> !staleRead || !addition.equals(track))
                                            .map(track -> Map.of("uri", track, "name", "Track")).toList());
                    byte[] bytes = ProtocolCodec.mapper().writeValueAsBytes(body);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            }
        });
        server.start();
        Path tokens = directory.resolve("queue-tokens.json");
        Files.write(tokens, ProtocolCodec.mapper().writeValueAsBytes(Map.of("accessToken", "fixture",
                "expiresAtMillis", System.currentTimeMillis() + 600_000)));
        URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        try (var client = new SpotifyAccountClient(new ClientPreferences(), ignored -> { }, () -> true,
                HttpClient.newHttpClient(), tokens, false, endpoint)) {
            var search = client.karaokeQueue("search", "New track", 0, 0, current, 12, () -> true).get(5, TimeUnit.SECONDS);
            assertThat(search.entries()).extracting(SpotifyAccountClient.QueueEntry::uri).containsExactly(addition);
            var original = client.karaokeQueue("list", "", 0, 0, current, 12, () -> true).get(5, TimeUnit.SECONDS);
            var appended = client.karaokeQueue("add", addition, 0, 0, current, 12, () -> true).get(5, TimeUnit.SECONDS);
            assertThat(added.get()).isEqualTo("uri=" + addition);
            assertThat(appended.status()).isEqualTo("added");
            var stale = client.karaokeQueue("jump", target, 1, original.version(), current, 12, () -> true).get(5, TimeUnit.SECONDS);
            assertThat(stale.status()).isEqualTo("changed");
            assertThat(skips.get()).isZero();
            var jumped = client.karaokeQueue("jump", target, 1, appended.version(), current, 12, () -> true).get(5, TimeUnit.SECONDS);
            assertThat(jumped.status()).isEqualTo("jumped");
            assertThat(skips.get()).isEqualTo(2);
            assertThat(playing.get()).isEqualTo(target);
            assertThat(jumped.entries()).extracting(SpotifyAccountClient.QueueEntry::uri).containsExactly(addition);
        } finally { server.stop(0); }
    }

    private static PlaybackSnapshot snapshot(String title, String artist, String album, long duration) {
        return new PlaybackSnapshot(title, artist, album, null, null, null, 0, duration,
                true, .5, false, "off", false, 0, 0, 0, .5, new double[0],
                Instant.parse("2026-01-01T00:00:00Z"), MusicCapabilities.unavailable());
    }
}
