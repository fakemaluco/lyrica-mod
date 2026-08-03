package dev.fandroid.client.music;

import com.fasterxml.jackson.databind.JsonNode;
import dev.fandroid.protocol.ProtocolCodec;
import dev.fandroid.protocol.lyrics.LyricsDocument;
import dev.fandroid.protocol.lyrics.LyricsMatchScorer;
import dev.fandroid.protocol.lyrics.SecureTtmlParser;
import net.fabricmc.loader.api.FabricLoader;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class AmllLyricsClient {
    private static final String RAW_ROOT = "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main/";
    private static final long INDEX_RECHECK_MILLIS = Duration.ofHours(6).toMillis();
    private static final int MAX_INDEX_BYTES = 16 * 1024 * 1024;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final SecureTtmlParser parser = new SecureTtmlParser();
    private final Path cache = FabricLoader.getInstance().getConfigDir().resolve("fandroid/lyrics");
    private volatile long lastIndexCheck;

    public CompletableFuture<Optional<LyricsDocument>> bySpotifyUri(String uri) {
        String trackId = spotifyTrackId(uri);
        if (trackId == null) return CompletableFuture.completedFuture(Optional.empty());
        return CompletableFuture.supplyAsync(() -> downloadAndParse(
                "spotify-lyrics/" + trackId + ".ttml", cache.resolve(trackId + ".ttml")));
    }

    public CompletableFuture<List<LyricsCandidate>> search(String title, String artist, String album) {
        return search(title, artist, album, null);
    }

    public CompletableFuture<List<LyricsCandidate>> search(String title, String artist, String album, String spotifyId) {
        return CompletableFuture.supplyAsync(() -> searchBlocking(title, artist, album, spotifyId));
    }

    private List<LyricsCandidate> searchBlocking(String title, String artist, String album, String spotifyId) {
        try {
            Path index = cache.resolve("raw-lyrics-index.jsonl");
            Path etag = cache.resolve("raw-lyrics-index.etag");
            Files.createDirectories(cache);
            if (!ensureIndex(index, etag)) return List.of();

            Map<String, LyricsCandidate> deduplicated = new HashMap<>();
            try (var reader = Files.newBufferedReader(index, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    try {
                        JsonNode value = ProtocolCodec.mapper().readTree(line);
                        String candidateTitle = firstNonBlank(metadata(value, "musicName"), text(value, "name", "title", "trackName"));
                        String candidateArtist = firstNonBlank(metadata(value, "artists"), text(value, "artists", "artist", "artistName"));
                        String candidateAlbum = firstNonBlank(metadata(value, "album"), text(value, "album", "albumName"));
                        String candidateSpotify = firstNonBlank(metadata(value, "spotifyId"),
                                text(value, "spotifyId", "spotify_id", "spotifyTrackId"));
                        String path = firstNonBlank(text(value, "path", "ttml", "file"),
                                value.path("rawLyricFile").isTextual() ? "raw-lyrics/" + value.path("rawLyricFile").asText() : null);
                        if (path == null && candidateSpotify != null) path = "spotify-lyrics/" + candidateSpotify + ".ttml";
                        if (!safeLyricsPath(path) || candidateTitle == null) continue;

                        boolean exactSpotify = spotifyId != null && candidateSpotify != null
                                && List.of(candidateSpotify.split("\\s*,\\s*")).contains(spotifyId);
                        double score = exactSpotify ? 1.0 : LyricsMatchScorer.score(title, artist, album,
                                candidateTitle, candidateArtist, candidateAlbum);
                        if (score < .62) continue;
                        LyricsCandidate candidate = new LyricsCandidate(candidateTitle,
                                firstNonBlank(candidateArtist, "Unknown artist"), firstNonBlank(candidateAlbum, ""), path, score);
                        String key = exactSpotify ? "spotify:" + spotifyId
                                : LyricsMatchScorer.canonicalKey(candidateTitle, candidateArtist, candidateAlbum);
                        deduplicated.merge(key, candidate, AmllLyricsClient::preferCandidate);
                    } catch (Exception ignored) {
                        // A malformed third-party index row must not discard all other matches.
                    }
                }
            }
            return deduplicated.values().stream()
                    .sorted(Comparator.comparingDouble(LyricsCandidate::score).reversed()
                            .thenComparing(LyricsCandidate::path, Comparator.reverseOrder()))
                    .limit(8)
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    public CompletableFuture<Optional<LyricsDocument>> load(LyricsCandidate candidate) {
        String safeName = Integer.toHexString(candidate.path().hashCode()) + ".ttml";
        return CompletableFuture.supplyAsync(() -> downloadAndParse(candidate.path(), cache.resolve(safeName)));
    }

    private synchronized boolean ensureIndex(Path index, Path etag) {
        long now = System.currentTimeMillis();
        if (Files.exists(index) && now - lastIndexCheck < INDEX_RECHECK_MILLIS) return true;
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(RAW_ROOT + "metadata/raw-lyrics-index.jsonl"))
                    .timeout(Duration.ofSeconds(20));
            if (Files.exists(etag)) request.header("If-None-Match", Files.readString(etag));
            HttpResponse<byte[]> response = http.send(request.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 200 && response.body().length <= MAX_INDEX_BYTES) {
                writeAtomically(index, response.body());
                response.headers().firstValue("ETag").ifPresent(value -> writeStringQuietly(etag, value));
            } else if (response.statusCode() != 304 && !Files.exists(index)) {
                return false;
            }
            lastIndexCheck = now;
            return Files.exists(index);
        } catch (Exception e) {
            lastIndexCheck = now;
            return Files.exists(index);
        }
    }

    private Optional<LyricsDocument> downloadAndParse(String relativePath, Path target) {
        if (!safeLyricsPath(relativePath)) return Optional.empty();
        try {
            Files.createDirectories(cache);
            Path etag = target.resolveSibling(target.getFileName() + ".etag");
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(RAW_ROOT + relativePath))
                    .timeout(Duration.ofSeconds(15));
            if (Files.exists(etag)) request.header("If-None-Match", Files.readString(etag));
            HttpResponse<byte[]> response = http.send(request.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            byte[] bytes;
            if (response.statusCode() == 200) {
                bytes = response.body();
                writeAtomically(target, bytes);
                response.headers().firstValue("ETag").ifPresent(value -> writeStringQuietly(etag, value));
            } else if (response.statusCode() == 304 && Files.exists(target)) {
                bytes = Files.readAllBytes(target);
            } else if (response.statusCode() == 404) {
                return Optional.empty();
            } else if (Files.exists(target)) {
                bytes = Files.readAllBytes(target);
            } else {
                return Optional.empty();
            }
            LyricsDocument document = parser.parse(bytes);
            return document.lines().isEmpty() ? Optional.empty() : Optional.of(document);
        } catch (Exception e) {
            try {
                if (Files.exists(target)) {
                    LyricsDocument cached = parser.parse(Files.readAllBytes(target));
                    return cached.lines().isEmpty() ? Optional.empty() : Optional.of(cached);
                }
            } catch (Exception ignored) { }
            return Optional.empty();
        }
    }

    static String spotifyTrackId(String uri) {
        if (uri == null) return null;
        String id = null;
        if (uri.startsWith("spotify:track:")) id = uri.substring("spotify:track:".length()).split("[?#]", 2)[0];
        int marker = uri.indexOf("open.spotify.com/track/");
        if (marker >= 0) id = uri.substring(marker + "open.spotify.com/track/".length()).split("[?#/]", 2)[0];
        return id != null && id.matches("[A-Za-z0-9]{10,32}") ? id : null;
    }

    private static LyricsCandidate preferCandidate(LyricsCandidate first, LyricsCandidate second) {
        if (second.score() > first.score() + .0001) return second;
        if (first.score() > second.score() + .0001) return first;
        return second.path().compareTo(first.path()) > 0 ? second : first;
    }

    private static boolean safeLyricsPath(String path) {
        return path != null && !path.contains("..") && !path.contains("\\")
                && path.matches("(?:raw|spotify)-lyrics/[A-Za-z0-9._-]+\\.ttml");
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

    private static void writeStringQuietly(Path target, String value) {
        try { Files.writeString(target, value); } catch (Exception ignored) { }
    }

    private static String text(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.path(name);
            if (value.isTextual()) return value.asText();
            if (value.isArray()) {
                List<String> parts = new ArrayList<>();
                value.forEach(item -> parts.add(item.isTextual() ? item.asText() : item.path("name").asText()));
                return String.join(", ", parts);
            }
        }
        return null;
    }

    private static String metadata(JsonNode node, String wantedKey) {
        JsonNode metadata = node.path("metadata");
        if (!metadata.isArray()) return null;
        for (JsonNode entry : metadata) {
            if (!entry.isArray() || entry.size() < 2 || !entry.get(0).asText().equalsIgnoreCase(wantedKey)) continue;
            JsonNode values = entry.get(1);
            if (!values.isArray()) return values.asText(null);
            List<String> parts = new ArrayList<>();
            values.forEach(value -> parts.add(value.asText()));
            return String.join(", ", parts);
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }

    public record LyricsCandidate(String title, String artist, String album, String path, double score) { }
}
