package dev.relaylyrics.client.presence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MusicPresenceClientTest {
    @Test void unverifiedAccountCanReadPresenceButNeverPublishesItsOwnMusic() throws Exception {
        var json = new ObjectMapper(); UUID own = UUID.randomUUID(), peer = UUID.randomUUID();
        String session = "a".repeat(64); var bodies = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String input = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8); bodies.add(input);
            String route = exchange.getRequestURI().getPath(); Object reply = switch (route) {
                case "/challenge" -> Map.of("challenge", UUID.randomUUID().toString(), "server_id", "b".repeat(40));
                case "/viewer" -> Map.of("token", session, "topic", "lyrica-music-" + UUID.randomUUID(), "can_publish", false);
                case "/sync" -> Map.of("peers", List.of(Map.of("player_id", peer.toString(), "player_name", "Friend", "title", "Their song", "artist", "Artist", "cover_url", "", "expires_in_ms", 75000)));
                default -> Map.of("ok", true);
            };
            byte[] output = json.writeValueAsBytes(reply); exchange.sendResponseHeaders(route.startsWith("/realtime") ? 400 : 200, output.length);
            exchange.getResponseBody().write(output); exchange.close();
        });
        server.start();
        var client = new MusicPresenceClient(URI.create("http://localhost:" + server.getAddress().getPort() + "/"), "publishable-test",
                new MusicPresenceClient.Identity(own, own, "OfflinePlayer", PresenceScope.serverHash("localhost")), challenge -> { throw new IllegalStateException("No account token"); });
        try {
            client.update(new MusicPresenceClient.Playing("My private song", "Private artist", "", true), Set.of(own, peer)); client.start();
            await(() -> client.track(peer) != null && client.status() == MusicPresenceClient.Status.VIEW_ONLY);
            assertThat(client.status()).isEqualTo(MusicPresenceClient.Status.VIEW_ONLY);
            assertThat(bodies).noneMatch(body -> body.contains("My private song") || body.contains("Private artist") || body.contains("\"sharing\":true"));
        } finally { client.close(); server.stop(0); }
    }
    @Test void publishesOffThreadClearsOnPauseFiltersOtherPlayersAndLeavesWithoutExposingAccountTokens() throws Exception {
        var json = new ObjectMapper(); UUID own = UUID.randomUUID(), peer = UUID.randomUUID(), stranger = UUID.randomUUID();
        String session = "a".repeat(64); var bodies = new CopyOnWriteArrayList<String>();
        var writes = new AtomicInteger(); var leaves = new AtomicInteger(); var privateWrites = new AtomicInteger();
        var proofThread = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String input = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8); bodies.add(input);
            String route = exchange.getRequestURI().getPath(); Object reply;
            switch (route) {
                case "/challenge" -> reply = Map.of("challenge", UUID.randomUUID().toString(), "server_id", "b".repeat(40));
                case "/authenticate" -> reply = Map.of("token", session, "topic", "lyrica-music-" + UUID.randomUUID());
                case "/sync" -> {
                    if (!session.equals(exchange.getRequestHeaders().getFirst("x-lyrica-session"))) throw new AssertionError("Session missing");
                    var body = json.readTree(input);
                    if (body.path("write").asBoolean()) { writes.incrementAndGet(); if (!body.path("sharing").asBoolean() && body.path("title").asText().isEmpty()) privateWrites.incrementAndGet(); }
                    reply = Map.of("peers", List.of(Map.of("player_id", peer.toString(), "player_name", "Friend", "title", "Song", "artist", "Artist", "cover_url", "", "expires_in_ms", 75000),
                            Map.of("player_id", stranger.toString(), "player_name", "OtherServer", "title", "Hidden", "expires_in_ms", 75000)));
                }
                case "/leave" -> { leaves.incrementAndGet(); reply = Map.of("ok", true); }
                default -> reply = Map.of();
            }
            byte[] output = json.writeValueAsBytes(reply); exchange.sendResponseHeaders(route.startsWith("/realtime") ? 400 : 200, output.length);
            exchange.getResponseBody().write(output); exchange.close();
        });
        server.start();
        var client = new MusicPresenceClient(URI.create("http://localhost:" + server.getAddress().getPort() + "/"), "publishable-test",
                new MusicPresenceClient.Identity(own, own, "Player", PresenceScope.serverHash("example.test")), challenge -> {
                    assertThat(challenge).isEqualTo("b".repeat(40)); proofThread.set(Thread.currentThread().getName());
                });
        try {
            client.update(new MusicPresenceClient.Playing("Own song", "Artist", "", true), Set.of(own, peer)); client.start();
            await(() -> client.track(peer) != null);
            assertThat(client.track(stranger)).isNull(); assertThat(proofThread.get()).isEqualTo("Lyrica music presence");
            client.update(new MusicPresenceClient.Playing("Private song", "Artist", "", false), Set.of(own, peer));
            await(() -> privateWrites.get() > 0);
            assertThat(bodies).noneMatch(body -> body.contains("Private song") || body.contains("access_token") || body.contains("accessToken"));
            client.update(new MusicPresenceClient.Playing("", "", "", false), Set.of(own));
            assertThat(client.track(peer)).isNull();
            client.close(); await(() -> leaves.get() > 0); assertThat(client.status()).isEqualTo(MusicPresenceClient.Status.CLOSED);
        } finally { client.close(); server.stop(0); }
    }
    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
