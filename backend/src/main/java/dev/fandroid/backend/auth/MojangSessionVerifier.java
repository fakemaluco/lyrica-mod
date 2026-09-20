package dev.fandroid.backend.auth;

import com.fasterxml.jackson.databind.JsonNode;
import dev.fandroid.protocol.ProtocolCodec;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

public interface MojangSessionVerifier {
    Optional<VerifiedProfile> verify(String username, String serverId);

    record VerifiedProfile(UUID uuid, String username) {
    }

    final class Http implements MojangSessionVerifier {
        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        @Override
        public Optional<VerifiedProfile> verify(String username, String serverId) {
            try {
                String query = "username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                        + "&serverId=" + URLEncoder.encode(serverId, StandardCharsets.UTF_8);
                HttpRequest request = HttpRequest.newBuilder(
                                URI.create("https://sessionserver.mojang.com/session/minecraft/hasJoined?" + query))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();
                HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() == 204 || response.statusCode() == 404) {
                    return Optional.empty();
                }
                if (response.statusCode() != 200) {
                    throw new IllegalStateException("Mojang session server returned " + response.statusCode());
                }
                JsonNode json = ProtocolCodec.mapper().readTree(response.body());
                return Optional.of(new VerifiedProfile(parseUuid(json.get("id").asText()), json.get("name").asText()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Mojang verification was interrupted", e);
            } catch (Exception e) {
                throw new IllegalStateException("Mojang verification failed", e);
            }
        }

        private static UUID parseUuid(String compact) {
            return UUID.fromString(compact.replaceFirst(
                    "([0-9a-fA-F]{8})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{12})",
                    "$1-$2-$3-$4-$5"));
        }
    }
}
