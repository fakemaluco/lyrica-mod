package dev.fandroid.client.music;

import com.fasterxml.jackson.databind.JsonNode;
import dev.fandroid.FanDroidMod;
import dev.fandroid.protocol.ProtocolCodec;
import dev.fandroid.protocol.lyrics.LyricsDocument;
import dev.fandroid.protocol.lyrics.LyricsMatchScorer;
import dev.fandroid.protocol.lyrics.SecureTtmlParser;
import net.fabricmc.loader.api.FabricLoader;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Word/syllable-synchronised fallback backed by the public Better Lyrics TTML API. */
public final class BetterLyricsClient {
    private static final String API = "https://lyrics-api.boidu.dev/getLyrics";
    private static final int MAX_RESPONSE_BYTES = 5 * 1024 * 1024;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(7))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final SecureTtmlParser parser = new SecureTtmlParser();
    private final Path cache = FabricLoader.getInstance().getConfigDir().resolve("fandroid/lyrics/better-lyrics");

    public CompletableFuture<Optional<LyricsResult>> find(String title, String artist, String album,
                                                           long durationMillis) {
        return CompletableFuture.supplyAsync(() -> findBlocking(title, artist, album, durationMillis));
    }

    private Optional<LyricsResult> findBlocking(String title, String artist, String album,
                                                 long durationMillis) {
        if (title == null || title.isBlank() || artist == null || artist.isBlank()) return Optional.empty();
        Path target = cache.resolve(cacheKey(title, artist, album) + ".ttml");
        try {
            String primaryArtist = AmllLyricsClient.primaryArtist(artist);
            String[] artists = primaryArtist.equals(artist)
                    ? new String[]{artist} : new String[]{artist, primaryArtist};
            for (String lookupArtist : artists) {
                HttpResponse<byte[]> response = request(title, lookupArtist, album, durationMillis);
                if (response.statusCode() != 200) continue;
                ApiPayload payload = payload(ProtocolCodec.mapper().readTree(response.body()));
                if (payload.ttml().isBlank() || payload.score() < .60) continue;
                byte[] bytes = payload.ttml().getBytes(StandardCharsets.UTF_8);
                LyricsDocument document = parser.parse(bytes);
                if (!document.lines().isEmpty()) {
                    Files.createDirectories(cache);
                    writeAtomically(target, bytes);
                    return Optional.of(new LyricsResult(document, payload.score()));
                }
            }
        } catch (Exception error) {
            FanDroidMod.LOGGER.warn("Better Lyrics lookup failed for '{}': {}", title, error.getMessage());
        }
        return parseCached(target);
    }

    private HttpResponse<byte[]> request(String title, String artist, String album,
                                         long durationMillis) throws Exception {
        String query = "?s=" + encode(title) + "&a=" + encode(artist);
        if (album != null && !album.isBlank()) query += "&al=" + encode(album);
        if (durationMillis > 0) query += "&d=" + Math.max(1, Math.round(durationMillis / 1_000.0));
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(API + query))
                .timeout(Duration.ofSeconds(8))
                .header("Accept", "application/json")
                .header("User-Agent", "FanDroid v0.1.0");
        String apiKey = System.getProperty("fandroid.betterLyricsApiKey", "").strip();
        if (!apiKey.isEmpty()) request.header("X-API-Key", apiKey);
        HttpResponse<byte[]> response = http.send(request.GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (response.body().length > MAX_RESPONSE_BYTES) {
            throw new IllegalArgumentException("Better Lyrics response too large");
        }
        return response;
    }

    private Optional<LyricsResult> parseCached(Path target) {
        try {
            if (!Files.isRegularFile(target)) return Optional.empty();
            LyricsDocument document = parser.parse(Files.readAllBytes(target));
            return document.lines().isEmpty() ? Optional.empty()
                    : Optional.of(new LyricsResult(document, 1));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static ApiPayload payload(JsonNode root) {
        return new ApiPayload(root.path("ttml").asText(""),
                Math.clamp(root.path("score").asDouble(0) / 100.0, 0, 1));
    }

    private static String cacheKey(String title, String artist, String album) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    LyricsMatchScorer.canonicalKey(title, artist, album).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (Exception impossible) {
            return Integer.toHexString((title + artist + album).hashCode());
        }
    }

    private static void writeAtomically(Path target, byte[] value) throws Exception {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(temporary, value);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public record LyricsResult(LyricsDocument document, double score) { }
    record ApiPayload(String ttml, double score) { }
}
