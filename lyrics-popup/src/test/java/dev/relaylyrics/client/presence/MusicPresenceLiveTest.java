package dev.relaylyrics.client.presence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.assertj.core.api.Assertions.assertThat;

class MusicPresenceLiveTest {
    @Test void javaClientWithOfflineAccountReceivesRealSupabaseUpdates() throws Exception {
        String qaPath = System.getProperty("lyrica.presence.liveFile");
        assumeTrue(qaPath != null && !qaPath.isBlank(), "Explicit live QA session required");
        var json = new ObjectMapper(); var qa = json.readTree(Path.of(qaPath).toFile());
        UUID original = UUID.fromString(qa.path("a").path("id").asText());
        UUID offline = UUID.fromString(qa.path("b").path("id").asText());
        String sender = qa.path("a").path("token").asText(); String room = qa.path("room").asText();
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
        var client = new MusicPresenceClient(new MusicPresenceClient.Identity(offline, offline, "OfflineQA", room),
                challenge -> { throw new IllegalStateException("No verified Minecraft session"); });
        try {
            send(http, json, "sync", sender, Map.of("visible",List.of(original.toString()),"write",true,"sharing",true,"title","Live song A","artist","QA","cover_url",""));
            client.update(new MusicPresenceClient.Playing("Local private song", "Local artist", "", true), Set.of(original, offline)); client.start();
            await(() -> client.track(original) != null && client.status() == MusicPresenceClient.Status.VIEW_ONLY);
            assertThat(client.track(original).title()).isEqualTo("Live song A");
            Thread.sleep(2000); // allow the actual Realtime subscription to acknowledge before changing track
            send(http, json, "sync", sender, Map.of("visible",List.of(original.toString()),"write",true,"sharing",true,"title","Live song B","artist","QA","cover_url",""));
            await(() -> client.track(original) != null && client.track(original).title().equals("Live song B"));
        } finally {
            client.close();
            for (String who : List.of("a", "b", "c")) try { send(http,json,"leave",qa.path(who).path("token").asText(),Map.of()); } catch (Exception ignored) { }
            http.shutdownNow();
        }
    }
    private static void send(HttpClient http, ObjectMapper json, String route, String token, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(MusicPresenceClient.PROJECT + "/functions/v1/music-presence/" + route)).timeout(Duration.ofSeconds(10))
                .header("apikey", MusicPresenceClient.PUBLISHABLE).header("x-lyrica-session", token).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        assertThat(http.send(request,HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);
    }
    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(20);
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
