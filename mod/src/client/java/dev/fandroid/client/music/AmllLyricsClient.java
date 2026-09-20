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
    private static final String API_ROOT = "https://api.amll.dev/v1/lyrics/";
    private static final String RAW_ROOT = "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main/";
    private static final int MAX_API_BYTES = 5 * 1024 * 1024;
    private static final long INDEX_RECHECK_MILLIS = Duration.ofHours(6).toMillis();
    private static final int MAX_INDEX_BYTES = 16 * 1024 * 1024;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final SecureTtmlParser parser = new SecureTtmlParser();
    private final Path cache = FabricLoader.getInstance().getConfigDir().resolve("fandroid/lyrics");
    private volatile long lastIndexCheck;

    public CompletableFuture<Optional<LyricsDocument>> bySpotifyUri(String uri) {
        String trackId = spotifyTrackId(uri);
        if (trackId == null) return CompletableFuture.completedFuture(Optional.empty());
        return CompletableFuture.supplyAsync(() -> getFromApi("spotifyId=" + encode(trackId),
                cache.resolve("api-spotify-" + trackId + ".ttml")));
    }

    public CompletableFuture<List<LyricsCandidate>> search(String title, String artist, String album) {
        return search(title, artist, album, null);
    }

    public CompletableFuture<List<LyricsCandidate>> search(String title, String artist, String album, String spotifyId) {
        return CompletableFuture.supplyAsync(() -> searchBlocking(title, artist, album, spotifyId));
    }

    private List<LyricsCandidate> searchBlocking(String title, String artist, String album, String spotifyId) {
        List<LyricsCandidate> official = searchApi(title, artist, album, spotifyId);
        if (!official.isEmpty()) return official;
        // Do not download the legacy multi-megabyte GitHub index here. A miss must
        // advance immediately to Better Lyrics instead of looking frozen for tens
        // of seconds. Existing raw-path candidates remain loadable for compatibility.
        return List.of();
    }

    public CompletableFuture<Optional<LyricsDocument>> load(LyricsCandidate candidate) {
        if (candidate.path().startsWith("api:")) {
            String id = candidate.path().substring("api:".length());
            return CompletableFuture.supplyAsync(() -> getFromApi("id=" + encode(id),
                    cache.resolve("api-id-" + id + ".ttml")));
        }
        String safeName = Integer.toHexString(candidate.path().hashCode()) + ".ttml";
        return CompletableFuture.supplyAsync(() -> downloadAndParse(candidate.path(), cache.resolve(safeName)));
    }

    private List<LyricsCandidate> searchApi(String title, String artist, String album, String spotifyId) {
        try {
            // The native AMLL search endpoint provides ranked metadata matches. Spotify IDs
            // are handled by /get first; metadata search covers tracks without a DB platform ID.
            String baseQuery = "musicName=" + encode(title) + "&pageSize=8";
            if (artist != null && !artist.isBlank()) baseQuery += "&artistName=" + encode(artist);
            // Album participates in local scoring but is intentionally not an AND
            // filter: Spotify editions often use a different album name than AMLL.
            List<LyricsCandidate> matches = searchApiRequest(baseQuery, title, artist, album, spotifyId);
            String primaryArtist = primaryArtist(artist);
            if (!matches.isEmpty() || primaryArtist.equals(artist)) return matches;
            return searchApiRequest("musicName=" + encode(title) + "&pageSize=8&artistName="
                    + encode(primaryArtist), title, artist, album, spotifyId);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<LyricsCandidate> searchApiRequest(String query, String title, String artist,
                                                   String album, String spotifyId) throws Exception {
        HttpResponse<byte[]> response = apiGet("search?" + query);
        if (response.statusCode() != 200) return List.of();
        JsonNode items = ProtocolCodec.mapper().readTree(response.body()).path("data").path("items");
        return candidatesFromApi(items, title, artist, album, spotifyId);
    }

    static List<LyricsCandidate> candidatesFromApi(JsonNode items, String title, String artist,
                                                    String album, String spotifyId) {
        if (!items.isArray()) return List.of();
        List<LyricsCandidate> matches = new ArrayList<>();
        try {
            for (JsonNode item : items) {
                String candidateTitle = firstArrayText(item.path("musicNames"));
                String candidateArtist = joinArray(item.path("artistNames"));
                String candidateAlbum = firstArrayText(item.path("albumNames"));
                boolean exactSpotify = spotifyId != null && arrayContains(item.path("spotifyIds"), spotifyId);
                double score = exactSpotify ? 1.0 : LyricsMatchScorer.score(title, artist, album,
                        candidateTitle, candidateArtist, candidateAlbum);
                if (score < .62 || !item.path("id").canConvertToLong()) continue;
                matches.add(new LyricsCandidate(candidateTitle,
                        firstNonBlank(candidateArtist, "Unknown artist"),
                        firstNonBlank(candidateAlbum, ""), "api:" + item.path("id").asText(), score));
            }
            return matches.stream().sorted(Comparator.comparingDouble(LyricsCandidate::score).reversed())
                    .limit(8).toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private Optional<LyricsDocument> getFromApi(String query, Path target) {
        try {
            Files.createDirectories(cache);
            HttpResponse<byte[]> response = apiGet("get?" + query);
            if (response.statusCode() == 200) {
                JsonNode root = ProtocolCodec.mapper().readTree(response.body());
                String lyrics = root.path("data").path("lyrics").asText("");
                if (!lyrics.isBlank()) {
                    byte[] bytes = lyrics.getBytes(StandardCharsets.UTF_8);
                    LyricsDocument document = parser.parse(bytes);
                    if (!document.lines().isEmpty()) {
                        writeAtomically(target, bytes);
                        return Optional.of(document);
                    }
                }
            }
        } catch (Exception ignored) { }
        try {
            if (Files.isRegularFile(target)) {
                LyricsDocument cached = parser.parse(Files.readAllBytes(target));
                if (!cached.lines().isEmpty()) return Optional.of(cached);
            }
        } catch (Exception ignored) { }
        return Optional.empty();
    }

    private HttpResponse<byte[]> apiGet(String relative) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(API_ROOT + relative))
                .timeout(Duration.ofSeconds(8))
                .header("Accept", "application/json")
                .header("User-Agent", "FanDroid v0.1.0")
                .GET().build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.body().length > MAX_API_BYTES) throw new IllegalArgumentException("AMLL response too large");
        return response;
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

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    static String primaryArtist(String artist) {
        if (artist == null) return "";
        int separator = artist.indexOf(',');
        return (separator < 0 ? artist : artist.substring(0, separator)).strip();
    }

    private static String firstArrayText(JsonNode values) {
        return values.isArray() && !values.isEmpty() ? values.get(0).asText("") : "";
    }

    private static String joinArray(JsonNode values) {
        if (!values.isArray()) return "";
        List<String> result = new ArrayList<>();
        values.forEach(value -> { if (value.isTextual() && !value.asText().isBlank()) result.add(value.asText()); });
        return String.join(", ", result);
    }

    private static boolean arrayContains(JsonNode values, String expected) {
        if (!values.isArray()) return false;
        for (JsonNode value : values) if (expected.equals(value.asText())) return true;
        return false;
    }

    public record LyricsCandidate(String title, String artist, String album, String path, double score) { }
}
