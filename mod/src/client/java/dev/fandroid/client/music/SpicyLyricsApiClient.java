package dev.fandroid.client.music;

import com.fasterxml.jackson.databind.JsonNode;
import dev.fandroid.protocol.ProtocolCodec;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Minimal client for the official Spicy Lyrics track endpoint. */
public final class SpicyLyricsApiClient {
    private static final String API = "https://api.spicylyrics.org/v1/lyrics/";
    private static final String DEFAULT_PUBLISHABLE_KEY =
            "sl_pk_Ki5Z2YKN9BmEz70y5Pj7KWw0m_YIa5eFaWkBHrZeGVI";
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public CompletableFuture<Result> find(String trackId) {
        String key = configuredKey();
        if (trackId == null || !trackId.matches("[A-Za-z0-9]{22}")) {
            return CompletableFuture.completedFuture(Result.notFound("Spotify track ID unavailable"));
        }
        if (key.isBlank()) {
            return CompletableFuture.completedFuture(Result.missingKey());
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(API + trackId))
                .timeout(Duration.ofSeconds(8))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + key)
                .header("User-Agent", "Lyrica/0.8")
                .GET().build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(SpicyLyricsApiClient::decode)
                .exceptionally(error -> Result.failed("Spicy Lyrics connection failed"));
    }

    static String spotifyTrackId(String uri) {
        if (uri == null) return null;
        String value = uri.strip();
        if (value.matches("[A-Za-z0-9]{22}")) return value;
        String prefix = "spotify:track:";
        if (value.startsWith(prefix)) {
            String id = value.substring(prefix.length());
            return id.matches("[A-Za-z0-9]{22}") ? id : null;
        }
        try {
            URI parsed = URI.create(value);
            if (parsed.getHost() != null && parsed.getHost().equalsIgnoreCase("open.spotify.com")) {
                String[] parts = parsed.getPath().split("/");
                for (int index = 0; index + 1 < parts.length; index++) {
                    if (parts[index].equals("track") && parts[index + 1].matches("[A-Za-z0-9]{22}")) {
                        return parts[index + 1];
                    }
                }
            }
        } catch (IllegalArgumentException ignored) {
        }
        return null;
    }

    private static Result decode(HttpResponse<byte[]> response) {
        if (response.statusCode() == 404) return Result.notFound("No lyrics in Spicy Lyrics");
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            return Result.failed("Spicy Lyrics key was rejected");
        }
        if (response.statusCode() == 429) return Result.failed("Spicy Lyrics rate limit reached");
        if (response.statusCode() != 200 || response.body().length > MAX_RESPONSE_BYTES) {
            return Result.failed("Spicy Lyrics request failed (HTTP " + response.statusCode() + ")");
        }
        try {
            JsonNode root = ProtocolCodec.mapper().readTree(response.body());
            JsonNode body = root.path("Body");
            if (!body.isObject()) return Result.failed("Invalid Spicy Lyrics response");
            return Result.found(ProtocolCodec.mapper().writeValueAsBytes(body));
        } catch (Exception error) {
            return Result.failed("Invalid Spicy Lyrics response");
        }
    }

    static String configuredKey() {
        String property = System.getProperty("lyrica.spicyLyricsKey", "").strip();
        if (!property.isBlank()) return validKey(property);
        String publishable = System.getenv("SPICY_LYRICS_PUBLISHABLE_KEY");
        if (publishable != null && !publishable.isBlank()) return validKey(publishable.strip());
        // Local development only. Secret keys must never be embedded in a distributed client.
        String secret = System.getenv("SPICY_LYRICS_SECRET_KEY");
        if (secret != null && !secret.isBlank()) return validKey(secret.strip());
        return DEFAULT_PUBLISHABLE_KEY;
    }

    private static String validKey(String value) {
        return value.length() <= 256 && (value.startsWith("sl_pk_") || value.startsWith("sl_sk_"))
                ? value : "";
    }

    public record Result(Status status, Optional<byte[]> payload, String message) {
        static Result found(byte[] payload) { return new Result(Status.FOUND, Optional.of(payload), ""); }
        static Result notFound(String message) { return new Result(Status.NOT_FOUND, Optional.empty(), message); }
        static Result missingKey() {
            return new Result(Status.MISSING_KEY, Optional.empty(),
                    "Configure a Spicy Lyrics publishable key");
        }
        static Result failed(String message) { return new Result(Status.FAILED, Optional.empty(), message); }
    }

    public enum Status { FOUND, NOT_FOUND, MISSING_KEY, FAILED }
}
