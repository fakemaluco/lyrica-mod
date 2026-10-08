package dev.relaylyrics.client.music;

import dev.relaylyrics.client.ClientPreferences;
import dev.relaylyrics.protocol.ProtocolCodec;
import dev.relaylyrics.protocol.music.MusicAction;
import dev.relaylyrics.protocol.music.MusicCommand;
import dev.relaylyrics.protocol.music.PlaybackSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class SpotifyPlaybackSyncTest {
    @TempDir Path temporary;

    @Test
    void latencyEstimateUsesRequestMidpointAndPlaybackClockAppliesItOnce() throws Exception {
        Instant received = Instant.parse("2026-01-01T00:00:10Z");
        Instant sampled = SpotifyAccountClient.playbackObservedAt(received, 1_000_000_000L, 1_400_000_000L);
        assertThat(sampled).isEqualTo(received.minusMillis(200));
        PlaybackSnapshot snapshot = SpotifyAccountClient.playbackSnapshot(
                ProtocolCodec.mapper().readTree(playback(5_000, true)), false, sampled);
        MusicPlaybackClock clock = new MusicPlaybackClock(() -> 0, () -> received);
        clock.update(snapshot);
        assertThat(clock.positionMillis()).isEqualTo(5_200);
        clock.update(snapshot);
        assertThat(clock.positionMillis()).isEqualTo(5_200);
        // A stale server state-change timestamp must never become the progress timestamp.
        assertThat(snapshot.observedAt()).isEqualTo(sampled);
    }

    @Test
    void pausedSnapshotDoesNotAdvanceForNetworkLatency() throws Exception {
        Instant received = Instant.parse("2026-01-01T00:00:10Z");
        PlaybackSnapshot snapshot = SpotifyAccountClient.playbackSnapshot(
                ProtocolCodec.mapper().readTree(playback(5_000, false)), false, received.minusMillis(300));
        MusicPlaybackClock clock = new MusicPlaybackClock(() -> 0, () -> received);
        clock.update(snapshot);
        assertThat(clock.positionMillis()).isEqualTo(5_000);
    }

    @Test
    void slowLibraryRequestDoesNotHoldBackPlaybackOrLyrics() throws Exception {
        CountDownLatch libraryStarted = new CountDownLatch(1);
        CountDownLatch releaseLibrary = new CountDownLatch(1);
        StubHttpClient http = new StubHttpClient(request -> {
            if (request.uri().getPath().contains("library")) {
                libraryStarted.countDown();
                if (!releaseLibrary.await(5, TimeUnit.SECONDS)) throw new IOException("Library request timed out");
                return new Reply(200, "[true]");
            }
            return new Reply(200, playback(5_000, true));
        });
        List<PlaybackSnapshot> snapshots = new CopyOnWriteArrayList<>();
        try (SpotifyAccountClient client = client(http, snapshots)) {
            assertTimeoutPreemptively(Duration.ofSeconds(2), client::poll);
            assertThat(libraryStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(snapshots).hasSize(1);
            assertThat(snapshots.getFirst().positionMillis()).isEqualTo(5_000);
            assertThat(client.shouldOverrideBridge()).isTrue();
        } finally {
            releaseLibrary.countDown();
        }
    }

    @Test
    void responseStartedBeforeSeekCannotRestoreTheOldPosition() throws Exception {
        CountDownLatch pollStarted = new CountDownLatch(1);
        CountDownLatch releasePoll = new CountDownLatch(1);
        AtomicInteger polls = new AtomicInteger();
        StubHttpClient http = new StubHttpClient(request -> {
            if (!request.method().equals("GET")) return new Reply(204, "");
            if (request.uri().getPath().contains("library")) return new Reply(200, "[false]");
            if (polls.incrementAndGet() == 1) {
                pollStarted.countDown();
                if (!releasePoll.await(5, TimeUnit.SECONDS)) throw new IOException("Poll timed out");
                return new Reply(200, playback(1_000, true));
            }
            return new Reply(200, playback(9_000, true));
        });
        List<PlaybackSnapshot> snapshots = new CopyOnWriteArrayList<>();
        try (SpotifyAccountClient client = client(http, snapshots)) {
            CompletableFuture<Void> inFlight = CompletableFuture.runAsync(() -> {
                try { client.poll(); } catch (Exception error) { throw new IllegalStateException(error); }
            });
            assertThat(pollStarted.await(2, TimeUnit.SECONDS)).isTrue();
            client.command(new MusicCommand(MusicAction.SEEK, 9_000.0)).get(2, TimeUnit.SECONDS);
            releasePoll.countDown();
            inFlight.get(2, TimeUnit.SECONDS);
            assertThat(snapshots).isEmpty();
            client.poll();
            assertThat(snapshots).hasSize(1);
            assertThat(snapshots.getFirst().positionMillis()).isEqualTo(9_000);
        } finally {
            releasePoll.countDown();
        }
    }

    @Test
    void rateLimitHonorsRetryAfterEvenWhenRefreshIsRequested() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        StubHttpClient http = new StubHttpClient(request -> {
            calls.incrementAndGet();
            return new Reply(429, "", Map.of("Retry-After", List.of("60")));
        });
        try (SpotifyAccountClient client = client(http, new CopyOnWriteArrayList<>())) {
            client.poll();
            client.requestPlaybackRefresh();
            client.poll();
            assertThat(calls.get()).isEqualTo(1);
            assertThat(client.status()).contains("rate limited");
        }
        assertThat(SpotifyAccountClient.retryAfterMillis("1.5")).isEqualTo(1_500);
        assertThat(SpotifyAccountClient.retryAfterMillis("bad")).isEqualTo(5_000);
        assertThat(SpotifyAccountClient.retryAfterMillis("NaN")).isEqualTo(5_000);
        assertThat(SpotifyAccountClient.retryAfterMillis("-2")).isEqualTo(1_000);
    }

    @Test
    void shuffleAndRepeatCommandsUseTheSpotifyAccountEndpoints() throws Exception {
        List<String> mutations = new CopyOnWriteArrayList<>();
        StubHttpClient http = new StubHttpClient(request -> {
            if (request.method().equals("GET")) return new Reply(200,
                    request.uri().getPath().contains("library") ? "[false]" : playback(5_000, true));
            mutations.add(request.uri().getPath() + "?" + request.uri().getRawQuery());
            return new Reply(204, "");
        });
        try (SpotifyAccountClient client = client(http, new CopyOnWriteArrayList<>())) {
            client.poll();
            client.command(MusicCommand.simple(MusicAction.TOGGLE_SHUFFLE)).get(2, TimeUnit.SECONDS);
            client.command(MusicCommand.simple(MusicAction.CYCLE_REPEAT)).get(2, TimeUnit.SECONDS);
            assertThat(mutations).containsExactly("/v1/me/player/shuffle?state=true", "/v1/me/player/repeat?state=context");
        }
    }

    private SpotifyAccountClient client(HttpClient http, List<PlaybackSnapshot> snapshots) throws Exception {
        Path tokens = temporary.resolve("account.json");
        Files.write(tokens, ProtocolCodec.mapper().writeValueAsBytes(Map.of(
                "accessToken", "test-token", "refreshToken", "test-refresh", "expiresAtMillis", Long.MAX_VALUE)));
        return new SpotifyAccountClient(new ClientPreferences(), snapshots::add, () -> true, http, tokens, false);
    }

    private static String playback(long position, boolean playing) {
        return """
                {"timestamp":1,"progress_ms":%d,"is_playing":%s,"shuffle_state":false,"repeat_state":"off",
                 "device":{"name":"Test player","volume_percent":50},"actions":{},
                 "item":{"name":"Track","uri":"spotify:track:1234567890123456789012","duration_ms":20000,
                         "artists":[{"name":"Artist"}],"album":{"name":"Album","images":[]}}}
                """.formatted(position, playing);
    }

    @FunctionalInterface
    private interface Exchange { Reply send(HttpRequest request) throws IOException, InterruptedException; }

    private record Reply(int status, String body, Map<String, List<String>> headers) {
        private Reply(int status, String body) { this(status, body, Map.of()); }
    }

    private static final class StubHttpClient extends HttpClient {
        private final Exchange exchange;
        private StubHttpClient(Exchange exchange) { this.exchange = exchange; }
        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.empty(); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() {
            try { return SSLContext.getDefault(); } catch (Exception error) { throw new IllegalStateException(error); }
        }
        @Override public SSLParameters sslParameters() { return new SSLParameters(); }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }
        @Override @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
                throws IOException, InterruptedException {
            Reply reply = exchange.send(request);
            return new HttpResponse<>() {
                @Override public int statusCode() { return reply.status(); }
                @Override public HttpRequest request() { return request; }
                @Override public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }
                @Override public HttpHeaders headers() { return HttpHeaders.of(reply.headers(), (name, value) -> true); }
                @Override public T body() { return (T) reply.body(); }
                @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
                @Override public URI uri() { return request.uri(); }
                @Override public Version version() { return Version.HTTP_1_1; }
            };
        }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                                         HttpResponse.BodyHandler<T> handler) {
            throw new UnsupportedOperationException();
        }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> push) {
            throw new UnsupportedOperationException();
        }
    }
}
