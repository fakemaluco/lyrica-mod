package dev.fandroid.client.music;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.fandroid.FanDroidMod;
import dev.fandroid.client.ClientPreferences;
import dev.fandroid.client.MusicSource;
import dev.fandroid.protocol.ProtocolCodec;
import dev.fandroid.protocol.lyrics.LyricsMatchScorer;
import dev.fandroid.protocol.music.MusicAction;
import dev.fandroid.protocol.music.MusicCapabilities;
import dev.fandroid.protocol.music.MusicCommand;
import dev.fandroid.protocol.music.PlaybackSnapshot;
import net.fabricmc.loader.api.FabricLoader;

import java.awt.Desktop;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Spotify Web API account connector using desktop-safe OAuth Authorization Code + PKCE. */
public final class SpotifyAccountClient implements AutoCloseable {
    public static final String REDIRECT_URI = "http://127.0.0.1:43871/callback";
    private static final String SCOPES = "user-read-playback-state user-read-currently-playing "
            + "user-modify-playback-state user-library-read user-library-modify";
    private final ClientPreferences preferences;
    private final Consumer<PlaybackSnapshot> snapshots;
    private final BooleanSupplier automaticAccountAllowed;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Path tokenFile = FabricLoader.getInstance().getConfigDir()
            .resolve("fandroid/spotify-account.json");
    private volatile boolean closed;
    private volatile String accessToken = "";
    private volatile String refreshToken = "";
    private volatile long expiresAtMillis;
    private volatile PlaybackSnapshot latest = PlaybackSnapshot.unavailable();
    private volatile String status = "Not connected";
    private volatile HttpServer callbackServer;
    private volatile String verifier;
    private volatile String oauthState;
    private volatile String likedUri = "";
    private volatile boolean liked;

    public SpotifyAccountClient(ClientPreferences preferences, Consumer<PlaybackSnapshot> snapshots) {
        this(preferences, snapshots, () -> true);
    }

    public SpotifyAccountClient(ClientPreferences preferences, Consumer<PlaybackSnapshot> snapshots,
                                BooleanSupplier automaticAccountAllowed) {
        this.preferences = preferences;
        this.snapshots = snapshots;
        this.automaticAccountAllowed = automaticAccountAllowed;
        loadTokens();
        Thread.ofVirtual().name("relay-spotify-account").start(this::pollLoop);
    }

    public boolean connected() { return !refreshToken.isBlank() || !accessToken.isBlank(); }
    public String status() { return status; }
    public boolean hasActivePlayback() { return meaningful(latest); }
    public boolean shouldOverrideBridge() {
        return shouldUseAccountSource() && hasActivePlayback();
    }

    static boolean preferredSource(MusicSource source, boolean automaticAllowed) {
        return source == MusicSource.SPOTIFY_ACCOUNT
                || source == MusicSource.AUTOMATIC && automaticAllowed;
    }

    private boolean shouldUseAccountSource() {
        return preferredSource(preferences.getMusicSource(), automaticAccountAllowed.getAsBoolean());
    }

    /** Resolves metadata supplied by Windows Media to a conservative Spotify track match. */
    public String resolveTrackId(PlaybackSnapshot track) {
        return resolveTrackId(track, true);
    }

    private String resolveTrackId(PlaybackSnapshot track, boolean allowRefresh) {
        String existing = SpicyLyricsApiClient.spotifyTrackId(track.spotifyUri());
        if (existing != null) return existing;
        if (!connected() || !meaningful(track)) return null;
        try {
            String token = validToken();
            if (token == null) return null;
            String query = "track:" + safeSearchTerm(track.title());
            if (track.artist() != null && !track.artist().isBlank()) {
                query += " artist:" + safeSearchTerm(track.artist());
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(
                            "https://api.spotify.com/v1/search?q=" + encode(query)
                                    + "&type=track&limit=8"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + token).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401 && allowRefresh && refresh()) {
                return resolveTrackId(track, false);
            }
            if (response.statusCode() != 200) return null;
            JsonNode items = ProtocolCodec.mapper().readTree(response.body()).path("tracks").path("items");
            return bestTrackId(track, items);
        } catch (Exception error) {
            FanDroidMod.LOGGER.debug("Could not resolve Spotify track ID from media metadata: {}",
                    error.toString());
            return null;
        }
    }

    static String bestTrackId(PlaybackSnapshot track, JsonNode items) {
        if (track == null || items == null || !items.isArray()) return null;
        List<SearchCandidate> matches = new ArrayList<>();
        for (JsonNode item : items) {
            String id = item.path("id").asText("");
            if (!id.matches("[A-Za-z0-9]{22}")) continue;
            List<String> artists = new ArrayList<>();
            item.path("artists").forEach(artist -> {
                String name = artist.path("name").asText("");
                if (!name.isBlank()) artists.add(name);
            });
            double metadata = LyricsMatchScorer.score(track.title(), track.artist(), track.album(),
                    item.path("name").asText(""), String.join(", ", artists),
                    item.path("album").path("name").asText(""));
            if (metadata < .78) continue;
            long candidateDuration = item.path("duration_ms").asLong(0);
            double duration = durationSimilarity(track.durationMillis(), candidateDuration);
            double score = metadata * .88 + duration * .12;
            // The same recording is frequently listed once per album/compilation. Album must not
            // make those catalog aliases ambiguous; title, artists and duration still separate versions.
            String signature = LyricsMatchScorer.canonicalKey(item.path("name").asText(""),
                    String.join(", ", artists), "")
                    + '\n' + Math.round(candidateDuration / 2_000.0);
            matches.add(new SearchCandidate(id, score, signature));
        }
        matches.sort(Comparator.comparingDouble(SearchCandidate::score).reversed());
        if (matches.isEmpty() || matches.getFirst().score() < .86) return null;
        SearchCandidate best = matches.getFirst();
        for (int index = 1; index < matches.size(); index++) {
            SearchCandidate other = matches.get(index);
            if (other.signature().equals(best.signature())) continue;
            if (best.score() - other.score() < .018) return null;
            break;
        }
        return best.id();
    }

    private record SearchCandidate(String id, double score, String signature) { }

    private static double durationSimilarity(long expected, long candidate) {
        if (expected <= 0 || candidate <= 0) return .5;
        double difference = Math.abs(expected - candidate) / (double) Math.max(expected, candidate);
        return Math.clamp(1 - difference / .12, 0, 1);
    }

    private static boolean meaningful(PlaybackSnapshot value) {
        return value != null && value.title() != null && !value.title().isBlank()
                && !value.title().equals("Nothing playing");
    }

    private static String safeSearchTerm(String value) {
        if (value == null) return "";
        return value.replace(':', ' ').replace('"', ' ')
                .replaceAll("[\\p{Cntrl}]+", " ").strip();
    }

    public CompletableFuture<URI> connect(String clientId) {
        String id = clientId == null ? "" : clientId.strip();
        if (!id.matches("[A-Za-z0-9]{16,64}")) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid Spotify client ID"));
        }
        preferences.setSpotifyClientId(id);
        preferences.save();
        return CompletableFuture.supplyAsync(() -> {
            try {
                stopCallbackServer();
                verifier = randomUrlToken(64);
                oauthState = randomUrlToken(24);
                String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                        MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
                HttpServer server = HttpServer.create(new InetSocketAddress(
                        InetAddress.getLoopbackAddress(), 43871), 0);
                server.createContext("/callback", this::oauthCallback);
                server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
                server.start();
                callbackServer = server;
                URI link = URI.create("https://accounts.spotify.com/authorize?" + form(Map.of(
                        "client_id", id,
                        "response_type", "code",
                        "redirect_uri", REDIRECT_URI,
                        "scope", SCOPES,
                        "code_challenge_method", "S256",
                        "code_challenge", challenge,
                        "state", oauthState)));
                status = "Waiting for Spotify authorization";
                if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(link);
                return link;
            } catch (Exception error) {
                status = "Could not start Spotify authorization";
                throw new IllegalStateException(error);
            }
        });
    }

    public void disconnect() {
        accessToken = "";
        refreshToken = "";
        expiresAtMillis = 0;
        latest = PlaybackSnapshot.unavailable();
        status = "Not connected";
        try { Files.deleteIfExists(tokenFile); } catch (Exception ignored) { }
    }

    public CompletableFuture<Void> command(MusicCommand command) {
        return CompletableFuture.runAsync(() -> {
            try {
                String token = validToken();
                if (token == null) throw new IllegalStateException("Spotify account is not connected");
                MusicAction action = command.action();
                String method;
                String endpoint;
                if (action == MusicAction.PLAY_PAUSE) {
                    method = "PUT";
                    endpoint = latest.playing() ? "/me/player/pause" : "/me/player/play";
                } else if (action == MusicAction.NEXT) {
                    method = "POST"; endpoint = "/me/player/next";
                } else if (action == MusicAction.PREVIOUS) {
                    method = "POST"; endpoint = "/me/player/previous";
                } else if (action == MusicAction.SEEK) {
                    method = "PUT";
                    endpoint = "/me/player/seek?position_ms=" + Math.max(0, Math.round(
                            command.value() == null ? 0 : command.value()));
                } else if (action == MusicAction.TOGGLE_HEART && latest.spotifyUri() != null) {
                    method = latest.liked() ? "DELETE" : "PUT";
                    endpoint = "/me/library?uris=" + encode(latest.spotifyUri());
                } else {
                    throw new IllegalArgumentException("Unsupported Spotify account command: " + action);
                }
                HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.spotify.com/v1" + endpoint))
                        .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + token)
                        .method(method, HttpRequest.BodyPublishers.noBody()).build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException("Spotify returned HTTP " + response.statusCode());
                }
                if (action == MusicAction.TOGGLE_HEART) {
                    liked = !latest.liked();
                    likedUri = latest.spotifyUri() == null ? "" : latest.spotifyUri();
                }
                poll();
            } catch (Exception error) {
                status = "Spotify command failed";
                throw new IllegalStateException(error);
            }
        });
    }

    private void pollLoop() {
        while (!closed) {
            try {
                if (shouldUseAccountSource() && connected()) poll();
                Thread.sleep(500);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception error) {
                status = "Spotify temporarily unavailable";
            }
        }
    }

    private void poll() throws Exception {
        String token = validToken();
        if (token == null) return;
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.spotify.com/v1/me/player"))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + token).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 204) {
            accept(PlaybackSnapshot.unavailable());
            status = "Connected — no active Spotify device";
            return;
        }
        if (response.statusCode() == 401 && refresh()) return;
        if (response.statusCode() != 200) throw new IllegalStateException("Spotify HTTP " + response.statusCode());
        JsonNode root = ProtocolCodec.mapper().readTree(response.body());
        JsonNode item = root.path("item");
        if (item.isMissingNode() || item.isNull()) {
            accept(PlaybackSnapshot.unavailable());
            return;
        }
        List<String> artists = new ArrayList<>();
        item.path("artists").forEach(node -> {
            String name = node.path("name").asText("");
            if (!name.isBlank()) artists.add(name);
        });
        JsonNode album = item.path("album");
        String artwork = null;
        if (album.path("images").isArray() && !album.path("images").isEmpty()) {
            artwork = album.path("images").get(0).path("url").asText(null);
        }
        JsonNode actions = root.path("actions");
        String uri = item.path("uri").asText(null);
        if (uri != null && !uri.equals(likedUri)) {
            liked = checkLiked(token, uri);
            likedUri = uri;
        }
        boolean playing = root.path("is_playing").asBoolean(false);
        MusicCapabilities capabilities = new MusicCapabilities(
                playing ? !actions.path("pausing").asBoolean(false)
                        : !actions.path("resuming").asBoolean(false),
                !actions.path("skipping_prev").asBoolean(false),
                !actions.path("skipping_next").asBoolean(false),
                !actions.path("seeking").asBoolean(false), true, true, true, true, true);
        accept(new PlaybackSnapshot(item.path("name").asText("Nothing playing"),
                String.join(", ", artists), album.path("name").asText(""), artwork,
                uri, root.path("device").path("name").asText(null),
                root.path("progress_ms").asLong(0), item.path("duration_ms").asLong(0),
                playing,
                root.path("device").path("volume_percent").asDouble(0) / 100.0,
                root.path("shuffle_state").asBoolean(false), root.path("repeat_state").asText("off"),
                liked, 0, 0, 0, 0.5, new double[0], Instant.now(), capabilities));
        status = "Spotify account connected";
    }

    private void accept(PlaybackSnapshot value) {
        latest = value;
        // An empty Web API poll must not erase a valid Windows Media fallback.
        if (shouldUseAccountSource() && meaningful(value)) {
            snapshots.accept(value);
        }
    }

    private boolean checkLiked(String token, String uri) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        "https://api.spotify.com/v1/me/library/contains?uris=" + encode(uri)))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + token).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) return false;
        JsonNode values = ProtocolCodec.mapper().readTree(response.body());
        return values.isArray() && !values.isEmpty() && values.get(0).asBoolean(false);
    }

    private void oauthCallback(HttpExchange exchange) {
        String responseText;
        int responseCode = 200;
        try {
            Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
            if (!oauthState.equals(query.get("state"))) throw new SecurityException("OAuth state mismatch");
            String code = query.get("code");
            if (code == null || code.isBlank()) throw new IllegalStateException(query.getOrDefault("error", "missing code"));
            exchangeCode(code);
            preferences.setMusicSource(MusicSource.SPOTIFY_ACCOUNT);
            preferences.save();
            responseText = "<h2>Relay conectado ao Spotify.</h2><p>Você já pode voltar ao Minecraft.</p>";
        } catch (Exception error) {
            responseCode = 400;
            status = "Spotify authorization failed";
            responseText = "<h2>Não foi possível conectar o Relay.</h2><p>Volte ao Minecraft e tente novamente.</p>";
        }
        try {
            byte[] bytes = ("<!doctype html><meta charset=utf-8>" + responseText)
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(responseCode, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        } catch (Exception ignored) { }
        CompletableFuture.delayedExecutor(1, java.util.concurrent.TimeUnit.SECONDS)
                .execute(this::stopCallbackServer);
    }

    private void exchangeCode(String code) throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("grant_type", "authorization_code");
        fields.put("code", code);
        fields.put("redirect_uri", REDIRECT_URI);
        fields.put("client_id", preferences.getSpotifyClientId());
        fields.put("code_verifier", verifier);
        applyTokenResponse(tokenRequest(fields), true);
        status = "Spotify account connected";
        poll();
    }

    private synchronized String validToken() throws Exception {
        if (accessToken.isBlank()) {
            if (!refresh()) return null;
        } else if (System.currentTimeMillis() >= expiresAtMillis - 60_000) {
            if (!refresh()) return null;
        }
        return accessToken;
    }

    private synchronized boolean refresh() throws Exception {
        if (refreshToken.isBlank() || preferences.getSpotifyClientId().isBlank()) return false;
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("grant_type", "refresh_token");
        fields.put("refresh_token", refreshToken);
        fields.put("client_id", preferences.getSpotifyClientId());
        applyTokenResponse(tokenRequest(fields), false);
        return true;
    }

    private JsonNode tokenRequest(Map<String, String> fields) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://accounts.spotify.com/api/token"))
                .timeout(Duration.ofSeconds(12)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(fields))).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Spotify token HTTP " + response.statusCode());
        }
        return ProtocolCodec.mapper().readTree(response.body());
    }

    private void applyTokenResponse(JsonNode token, boolean requireRefresh) throws Exception {
        accessToken = token.path("access_token").asText("");
        String nextRefresh = token.path("refresh_token").asText("");
        if (!nextRefresh.isBlank()) refreshToken = nextRefresh;
        if (accessToken.isBlank() || requireRefresh && refreshToken.isBlank()) {
            throw new IllegalStateException("Spotify returned incomplete credentials");
        }
        expiresAtMillis = System.currentTimeMillis() + token.path("expires_in").asLong(3600) * 1000;
        saveTokens();
    }

    private void loadTokens() {
        try {
            if (!Files.isRegularFile(tokenFile)) return;
            JsonNode node = ProtocolCodec.mapper().readTree(Files.readAllBytes(tokenFile));
            accessToken = node.path("accessToken").asText("");
            refreshToken = node.path("refreshToken").asText("");
            expiresAtMillis = node.path("expiresAtMillis").asLong(0);
            if (connected()) status = "Spotify account connected";
        } catch (Exception error) {
            FanDroidMod.LOGGER.warn("Could not load Spotify account token", error);
        }
    }

    private void saveTokens() throws Exception {
        Files.createDirectories(tokenFile.getParent());
        Files.write(tokenFile, ProtocolCodec.mapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(Map.of(
                "accessToken", accessToken, "refreshToken", refreshToken,
                "expiresAtMillis", expiresAtMillis)));
    }

    private void stopCallbackServer() {
        HttpServer server = callbackServer;
        callbackServer = null;
        if (server != null) server.stop(0);
    }

    private static String randomUrlToken(int bytes) {
        byte[] value = new byte[bytes];
        new SecureRandom().nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static String form(Map<String, String> values) {
        return values.entrySet().stream().map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(java.util.stream.Collectors.joining("&"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static Map<String, String> query(String query) {
        Map<String, String> result = new LinkedHashMap<>();
        if (query == null) return result;
        for (String item : query.split("&")) {
            String[] pair = item.split("=", 2);
            result.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                    pair.length > 1 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "");
        }
        return result;
    }

    @Override
    public void close() {
        closed = true;
        stopCallbackServer();
    }
}
