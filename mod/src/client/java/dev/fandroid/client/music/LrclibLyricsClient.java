package dev.fandroid.client.music;

import com.fasterxml.jackson.databind.JsonNode;
import dev.fandroid.FanDroidMod;
import dev.fandroid.protocol.ProtocolCodec;
import dev.fandroid.protocol.lyrics.LyricsDocument;
import dev.fandroid.protocol.lyrics.LyricsMatchScorer;
import dev.fandroid.protocol.lyrics.SecureLrcParser;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Line-synchronised fallback backed by LRCLIB's documented public API. */
public final class LrclibLyricsClient {
    private static final String API = "https://lrclib.net/api/";
    private static final int MAX_RESPONSE_BYTES = 3 * 1024 * 1024;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(7))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final SecureLrcParser parser = new SecureLrcParser();
    private final Path cache = FabricLoader.getInstance().getConfigDir().resolve("fandroid/lyrics/lrclib");

    public CompletableFuture<Optional<LyricsResult>> find(String title, String artist, String album,
                                                            long durationMillis) {
        return CompletableFuture.supplyAsync(() -> findBlocking(title, artist, album, durationMillis));
    }

    private Optional<LyricsResult> findBlocking(String title, String artist, String album, long durationMillis) {
        if (title == null || title.isBlank()) return Optional.empty();
        Path cached = cache.resolve(cacheKey(title, artist, album) + ".lrc");
        try {
            // Search is normally served directly from LRCLIB's catalogue. The exact
            // signature endpoint may consult external sources and is intentionally
            // kept as the slower second attempt.
            List<Record> records = search(title, artist, album, durationMillis);
            for (Record record : records) {
                Optional<LyricsResult> result = parseAndCache(record, cached);
                if (result.isPresent()) return result;
            }
            Optional<Record> exact = exact(title, artist, album, durationMillis);
            if (exact.isPresent()) return parseAndCache(exact.get(), cached);
        } catch (Exception e) {
            FanDroidMod.LOGGER.warn("LRCLIB lookup failed for '{}': {}", title, e.getMessage());
        }
        return parseCached(cached);
    }

    private Optional<Record> exact(String title, String artist, String album, long durationMillis) throws Exception {
        if (artist == null || artist.isBlank() || album == null || album.isBlank() || durationMillis <= 0) {
            return Optional.empty();
        }
        String query = "track_name=" + encode(title)
                + "&artist_name=" + encode(artist)
                + "&album_name=" + encode(album)
                + "&duration=" + Math.max(1, Math.round(durationMillis / 1_000.0));
        HttpResponse<byte[]> response = get("get?" + query);
        if (response.statusCode() == 404) return Optional.empty();
        if (response.statusCode() != 200) throw new IllegalStateException("exact endpoint returned " + response.statusCode());
        JsonNode node = parseBody(response.body());
        return record(node, title, artist, album, durationMillis, true);
    }

    private List<Record> search(String title, String artist, String album, long durationMillis) throws Exception {
        String query = "track_name=" + encode(title);
        if (artist != null && !artist.isBlank()) query += "&artist_name=" + encode(artist);
        if (album != null && !album.isBlank()) query += "&album_name=" + encode(album);
        HttpResponse<byte[]> response = get("search?" + query);
        if (response.statusCode() != 200) throw new IllegalStateException("search endpoint returned " + response.statusCode());
        JsonNode root = parseBody(response.body());
        if (!root.isArray()) return List.of();
        List<Record> result = new ArrayList<>();
        for (JsonNode node : root) record(node, title, artist, album, durationMillis, false).ifPresent(result::add);
        return result.stream().sorted(Comparator.comparingDouble(Record::score).reversed()).limit(8).toList();
    }

    private Optional<Record> record(JsonNode node, String title, String artist, String album,
                                    long durationMillis, boolean exact) {
        String synced = node.path("syncedLyrics").asText("");
        if (synced.isBlank() || node.path("instrumental").asBoolean(false)) return Optional.empty();
        String candidateTitle = node.path("trackName").asText("");
        String candidateArtist = node.path("artistName").asText("");
        String candidateAlbum = node.path("albumName").asText("");
        double score = exact ? 1 : LyricsMatchScorer.score(title, artist, album,
                candidateTitle, candidateArtist, candidateAlbum);
        long candidateDuration = Math.round(node.path("duration").asDouble(0) * 1_000);
        if (!exact && durationMillis > 0 && candidateDuration > 0) {
            long difference = Math.abs(durationMillis - candidateDuration);
            if (difference <= 2_000) score = Math.min(1, score + .05);
            else if (difference > 15_000) score *= .76;
        }
        if (score < .62) return Optional.empty();
        return Optional.of(new Record(node.path("id").asLong(-1), synced, score));
    }

    private Optional<LyricsResult> parseAndCache(Record record, Path target) {
        try {
            LyricsDocument document = parser.parse(record.syncedLyrics().getBytes(StandardCharsets.UTF_8));
            if (document.lines().isEmpty()) return Optional.empty();
            Files.createDirectories(cache);
            writeAtomically(target, record.syncedLyrics().getBytes(StandardCharsets.UTF_8));
            return Optional.of(new LyricsResult(document, parser.hasWordTiming(document), record.id(), record.score()));
        } catch (Exception e) {
            FanDroidMod.LOGGER.debug("Ignored malformed LRCLIB record {}", record.id(), e);
            return Optional.empty();
        }
    }

    private Optional<LyricsResult> parseCached(Path target) {
        try {
            if (!Files.isRegularFile(target)) return Optional.empty();
            LyricsDocument document = parser.parse(Files.readAllBytes(target));
            return document.lines().isEmpty() ? Optional.empty()
                    : Optional.of(new LyricsResult(document, parser.hasWordTiming(document), -1, 1));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private HttpResponse<byte[]> get(String relative) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(API + relative))
                .timeout(Duration.ofSeconds(12))
                .header("Accept", "application/json")
                .header("User-Agent", "FanDroid v0.1.0 (https://github.com/fandroid/fandroid)")
                .GET().build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.body().length > MAX_RESPONSE_BYTES) throw new IllegalArgumentException("LRCLIB response too large");
        return response;
    }

    private static JsonNode parseBody(byte[] body) throws Exception {
        return ProtocolCodec.mapper().readTree(body);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
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

    private record Record(long id, String syncedLyrics, double score) { }
    public record LyricsResult(LyricsDocument document, boolean wordSynced, long recordId, double score) { }
}
