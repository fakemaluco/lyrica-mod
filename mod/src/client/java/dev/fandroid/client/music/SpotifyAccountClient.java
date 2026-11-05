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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
    private final HttpClient http;
    private final Path tokenFile;
    private final Object pollLock = new Object();
    private final Object pollWake = new Object();
    private final Object authorizationLock = new Object();
    private final AtomicLong playbackRevision = new AtomicLong();
    private final AtomicLong likedRevision = new AtomicLong();
    private final AtomicInteger pendingCommands = new AtomicInteger();
    private volatile boolean refreshRequested;
    private volatile long nextPollAllowedMillis;
    private volatile long lastPlaybackResponseNanos;
    private volatile boolean closed;
    private volatile String accessToken = "";
    private volatile String refreshToken = "";
    private volatile long expiresAtMillis;
    private volatile boolean karaokeWatching;
    private volatile PlaybackSnapshot latest = PlaybackSnapshot.unavailable();
    private volatile boolean automaticPlaybackOwned;
    private volatile String status = "Not connected";
    private volatile String matchStatus = "Not searched";
    private volatile long credentialGeneration;
    private volatile HttpServer callbackServer;
    private volatile String likedUri = "";
    private volatile boolean liked;
    private final Map<String, List<String>> matchCache = Collections.synchronizedMap(
            new LinkedHashMap<>() {
                @Override protected boolean removeEldestEntry(Map.Entry<String, List<String>> eldest) {
                    return size() > 128;
                }
            });

    public SpotifyAccountClient(ClientPreferences preferences, Consumer<PlaybackSnapshot> snapshots) {
        this(preferences, snapshots, () -> true);
    }

    public SpotifyAccountClient(ClientPreferences preferences, Consumer<PlaybackSnapshot> snapshots,
                                BooleanSupplier automaticAccountAllowed) {
        this(preferences, snapshots, automaticAccountAllowed,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                FabricLoader.getInstance().getConfigDir().resolve("fandroid/spotify-account.json"), true);
    }

    SpotifyAccountClient(ClientPreferences preferences, Consumer<PlaybackSnapshot> snapshots,
                         BooleanSupplier automaticAccountAllowed, HttpClient http, Path tokenFile,
                         boolean startPolling) {
        this.preferences = preferences;
        this.snapshots = snapshots;
        this.automaticAccountAllowed = automaticAccountAllowed;
        this.http = http;
        this.tokenFile = tokenFile;
        loadTokens();
        if (startPolling) Thread.ofVirtual().name("relay-spotify-account").start(this::pollLoop);
    }

    public boolean connected() { return !refreshToken.isBlank() || !accessToken.isBlank(); }
    public String status() { return status; }
    public String matchStatus() { return matchStatus; }
    public long credentialGeneration() { return credentialGeneration; }
    public boolean hasActivePlayback() {
        return meaningful(latest) && lastPlaybackResponseNanos != 0
                && System.nanoTime() - lastPlaybackResponseNanos < 8_000_000_000L;
    }
    public boolean shouldOverrideBridge() {
        return !closed && shouldUseAccountSource() && hasActivePlayback()
                && (preferences.getMusicSource() == MusicSource.SPOTIFY_ACCOUNT || automaticPlaybackOwned);
    }

    static boolean playbackEligible(MusicSource source, PlaybackSnapshot snapshot) {
        return meaningful(snapshot) && (source == MusicSource.SPOTIFY_ACCOUNT || snapshot.playing());
    }

    static boolean preferredSource(MusicSource source, boolean automaticAllowed) {
        return source == MusicSource.SPOTIFY_ACCOUNT
                || source == MusicSource.AUTOMATIC && automaticAllowed;
    }

    private boolean shouldUseAccountSource() {
        MusicSource source = preferences.getMusicSource();
        boolean automaticAllowed = automaticAccountAllowed.getAsBoolean();
        if (source != MusicSource.AUTOMATIC || !automaticAllowed) automaticPlaybackOwned = false;
        return preferredSource(source, automaticAllowed);
    }

    /** Resolves metadata supplied by another music source to a Spotify track. */
    public String resolveTrackId(PlaybackSnapshot track) {
        List<String> ids = resolveTrackIds(track);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    public List<String> resolveTrackIds(PlaybackSnapshot track) {
        return resolveTrackIds(track, true);
    }

    private List<String> resolveTrackIds(PlaybackSnapshot track, boolean allowRefresh) {
        String existing = SpicyLyricsApiClient.spotifyTrackId(track.spotifyUri());
        if (existing != null) {
            matchStatus = "Spotify track ID: " + existing;
            return List.of(existing);
        }
        if (!meaningful(track)) return List.of();
        String cacheKey = LyricsMatchScorer.canonicalKey(
                cleanTitle(track.title(), track.artist()), cleanArtist(track.artist()), track.album())
                + '\n' + Math.round(track.durationMillis() / 2_000.0);
        List<String> cached = matchCache.get(cacheKey);
        if (cached != null) {
            matchStatus = "Spotify track ID: " + cached.getFirst();
            return cached;
        }
        if (!connected()) {
            matchStatus = "Connect Spotify for track matching";
            return List.of();
        }
        try {
            String token = validToken();
            if (token == null) {
                matchStatus = "Spotify authorization expired";
                return List.of();
            }
            matchStatus = "Searching Spotify catalog";
            for (String query : searchQueries(track)) {
                HttpRequest request = HttpRequest.newBuilder(URI.create(
                                "https://api.spotify.com/v1/search?q=" + encode(query)
                                        + "&type=track&limit=10"))
                        .timeout(Duration.ofSeconds(10))
                        .header("Authorization", "Bearer " + token).GET().build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 401 && allowRefresh && refresh()) {
                    return resolveTrackIds(track, false);
                }
                if (response.statusCode() != 200) {
                    matchStatus = "Spotify search HTTP " + response.statusCode();
                    if (response.statusCode() == 400) continue;
                    return List.of();
                }
                JsonNode items = ProtocolCodec.mapper().readTree(response.body()).path("tracks").path("items");
                List<String> ids = bestTrackIds(track, items);
                if (!ids.isEmpty()) {
                    matchCache.put(cacheKey, ids);
                    matchStatus = "Spotify track ID: " + ids.getFirst();
                    return ids;
                }
            }
            matchStatus = "No matching Spotify track";
            return List.of();
        } catch (Exception error) {
            matchStatus = "Spotify track search failed";
            FanDroidMod.LOGGER.warn("Could not resolve Spotify track ID from media metadata: {}",
                    error.toString());
            return List.of();
        }
    }

    static List<String> searchQueries(PlaybackSnapshot track) {
        String title = cleanTitle(track.title(), track.artist());
        String artist = cleanArtist(track.artist());
        if (title.isBlank()) return List.of();
        List<String> queries = new ArrayList<>();
        if (!artist.isBlank()) queries.add("track:" + title + " artist:" + firstArtist(artist));
        if (!artist.isBlank()) queries.add(title + " " + firstArtist(artist));
        queries.add(title);
        return queries.stream().distinct().toList();
    }

    private static String cleanTitle(String value, String artist) {
        String result = safeSearchTerm(value);
        String prefix = safeSearchTerm(firstArtist(cleanArtist(artist)));
        if (!prefix.isBlank() && result.regionMatches(true, 0, prefix + " - ", 0, prefix.length() + 3)) {
            result = result.substring(prefix.length() + 3);
        }
        return result.replaceFirst("(?i)\\s*[-–|]?\\s*[\\[(]?(?:official\\s+)?(?:music\\s+video|lyric\\s+video|official\\s+video|official\\s+audio|visualizer)[\\])]?$", "").strip();
    }

    private static String cleanArtist(String value) {
        return safeSearchTerm(value).replaceFirst("(?i)\\s*[-–]\\s*(?:topic|vevo)$", "").strip();
    }

    private static String firstArtist(String value) {
        return value.replaceFirst("(?i)\\s*(?:,|;|&|\\bfeat\\.?|\\bft\\.?)\\s+.*$", "").strip();
    }

    static String bestTrackId(PlaybackSnapshot track, JsonNode items) {
        List<String> ids = bestTrackIds(track, items);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    static List<String> bestTrackIds(PlaybackSnapshot track, JsonNode items) {
        if (track == null || items == null || !items.isArray()) return List.of();
        List<SearchCandidate> matches = new ArrayList<>();
        for (JsonNode item : items) {
            String id = item.path("id").asText("");
            if (!id.matches("[A-Za-z0-9]{22}")) continue;
            List<String> artists = new ArrayList<>();
            item.path("artists").forEach(artist -> {
                String name = artist.path("name").asText("");
                if (!name.isBlank()) artists.add(name);
            });
            double metadata = LyricsMatchScorer.score(cleanTitle(track.title(), track.artist()),
                    cleanArtist(track.artist()), track.album(),
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
        if (matches.isEmpty() || matches.getFirst().score() < .86) return List.of();
        SearchCandidate best = matches.getFirst();
        for (int index = 1; index < matches.size(); index++) {
            SearchCandidate other = matches.get(index);
            if (other.signature().equals(best.signature())) continue;
            if (best.score() - other.score() < .018) return List.of();
            break;
        }
        return matches.stream().filter(candidate -> candidate.signature().equals(best.signature())
                        && candidate.score() >= best.score() - .04)
                .map(SearchCandidate::id).distinct().limit(3).toList();
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
                String verifier = randomUrlToken(64);
                String oauthState = randomUrlToken(24);
                String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                        MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
                HttpServer server = HttpServer.create(new InetSocketAddress(
                        InetAddress.getByName("127.0.0.1"), 43871), 0);
                server.createContext("/callback", exchange -> oauthCallback(exchange, server, oauthState, verifier, id));
                server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
                server.start();
                synchronized (authorizationLock) { callbackServer = server; }
                URI link = URI.create("https://accounts.spotify.com/authorize?" + form(Map.of(
                        "client_id", id,
                        "response_type", "code",
                        "redirect_uri", REDIRECT_URI,
                        "scope", SCOPES,
                        "code_challenge_method", "S256",
                        "code_challenge", challenge,
                        "state", oauthState)));
                status = "Waiting for Spotify authorization";
                // A valid OAuth link remains usable when Java cannot launch this system's browser.
                // The connection screen offers copying and reopening it.
                try {
                    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                        Desktop.getDesktop().browse(link);
                    }
                } catch (Exception browserError) {
                    FanDroidMod.LOGGER.debug("Spotify authorization link is ready; browser launch failed: {}",
                            browserError.toString());
                }
                return link;
            } catch (Exception error) {
                status = "Could not start Spotify authorization";
                throw new IllegalStateException(error);
            }
        });
    }

    public void disconnect() {
        playbackRevision.incrementAndGet();
        likedRevision.incrementAndGet();
        stopCallbackServer();
        accessToken = "";
        refreshToken = "";
        expiresAtMillis = 0;
        latest = PlaybackSnapshot.unavailable();
        automaticPlaybackOwned = false;
        lastPlaybackResponseNanos = 0;
        likedUri = "";
        liked = false;
        nextPollAllowedMillis = 0;
        status = "Not connected";
        try { Files.deleteIfExists(tokenFile); } catch (Exception ignored) { }
    }

    public CompletableFuture<Void> command(MusicCommand command) {
        pendingCommands.incrementAndGet();
        playbackRevision.incrementAndGet();
        if (command.action() == MusicAction.TOGGLE_HEART) likedRevision.incrementAndGet();
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
                } else if (action == MusicAction.SET_VOLUME) {
                    method = "PUT";
                    double volume = command.value() == null ? latest.volume() : command.value();
                    int percent = (int) Math.round(Math.clamp(Double.isFinite(volume) ? volume : 0, 0, 1) * 100);
                    endpoint = "/me/player/volume?volume_percent=" + percent;
                } else if (action == MusicAction.TOGGLE_HEART && latest.spotifyUri() != null) {
                    method = latest.liked() ? "DELETE" : "PUT";
                    endpoint = "/me/library?uris=" + encode(latest.spotifyUri());
                } else if (action == MusicAction.TOGGLE_SHUFFLE) {
                    method = "PUT";
                    endpoint = "/me/player/shuffle?state=" + !latest.shuffled();
                } else if (action == MusicAction.CYCLE_REPEAT) {
                    method = "PUT";
                    String repeat = switch (latest.repeatMode()) {
                        case "context" -> "track";
                        case "track" -> "off";
                        default -> "context";
                    };
                    endpoint = "/me/player/repeat?state=" + repeat;
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
            } catch (Exception error) {
                status = "Spotify command failed";
                throw new IllegalStateException(error);
            } finally {
                playbackRevision.incrementAndGet();
                pendingCommands.decrementAndGet();
                requestPlaybackRefresh();
            }
        });
    }

    public PlaybackSnapshot karaokeSnapshot() { return latest; }
    public void setKaraokeWatching(boolean watching) {
        if (karaokeWatching == watching) return;
        karaokeWatching = watching;
        requestPlaybackRefresh();
    }

    public CompletableFuture<Void> syncKaraoke(String uri, long position, boolean playing,
                                               boolean loadTrack, boolean seek, BooleanSupplier active) {
        pendingCommands.incrementAndGet();
        playbackRevision.incrementAndGet();
        return CompletableFuture.runAsync(() -> {
            try {
                if (!active.getAsBoolean()) return;
                if (uri == null || !uri.matches("spotify:track:[A-Za-z0-9]{22}")) throw new IllegalArgumentException("Invalid Spotify track");
                String token = validToken();
                if (token == null) throw new IllegalStateException("Spotify account is not connected");
                if (!active.getAsBoolean()) return;
                if (loadTrack) {
                    var body = ProtocolCodec.mapper().createObjectNode();
                    body.putArray("uris").add(uri);
                    body.put("position_ms", Math.max(0, position));
                    karaokePut(token, "/play", body.toString());
                } else {
                    if (!uri.equals(latest.spotifyUri())) return;
                    if (seek) karaokePut(token, "/seek?position_ms=" + Math.max(0, position), null);
                }
                if (active.getAsBoolean() && (loadTrack ? !playing : playing != latest.playing()))
                    karaokePut(token, playing ? "/play" : "/pause", null);
            } catch (Exception error) {
                throw new IllegalStateException(error);
            } finally {
                playbackRevision.incrementAndGet();
                pendingCommands.decrementAndGet();
                requestPlaybackRefresh();
            }
        });
    }

    private void karaokePut(String token, String path, String json) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("https://api.spotify.com/v1/me/player" + path))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + token);
        if (json != null) request.header("Content-Type", "application/json");
        var response = http.send(request.PUT(json == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new IllegalStateException("Spotify returned HTTP " + response.statusCode());
    }

    public void requestPlaybackRefresh() {
        synchronized (pollWake) {
            refreshRequested = true;
            pollWake.notifyAll();
        }
    }

    private void pollLoop() {
        while (!closed) {
            refreshRequested = false;
            long waitMillis = latest.playing() ? 1_000 : 2_000;
            try {
                if ((shouldUseAccountSource() || karaokeWatching) && connected()) poll();
            } catch (Exception error) {
                status = "Spotify temporarily unavailable";
                waitMillis = 3_000;
            }
            try {
                synchronized (pollWake) {
                    if (!closed && !refreshRequested) pollWake.wait(waitMillis);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    void poll() throws Exception {
        synchronized (pollLock) {
            pollPlayback();
        }
    }

    private void pollPlayback() throws Exception {
        if (closed || System.currentTimeMillis() < nextPollAllowedMillis) return;
        String token = validToken();
        if (token == null) return;
        long revision = playbackRevision.get();
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.spotify.com/v1/me/player"))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + token).GET().build();
        long sentNanos = System.nanoTime();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        long receivedNanos = System.nanoTime();
        Instant observedAt = playbackObservedAt(Instant.now(), sentNanos, receivedNanos);
        // Do not let a request started before a seek/pause overwrite its immediate local feedback.
        if (revision != playbackRevision.get() || pendingCommands.get() > 0 || closed) return;
        if (response.statusCode() == 429) {
            long retryMillis = retryAfterMillis(response.headers().firstValue("Retry-After").orElse(""));
            nextPollAllowedMillis = System.currentTimeMillis() + retryMillis;
            status = "Spotify rate limited — retrying automatically";
            return;
        }
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
        String uri = item.path("uri").asText(null);
        if (uri != null && !uri.equals(likedUri)) {
            likedUri = uri;
            liked = false;
            refreshLiked(token, uri, likedRevision.incrementAndGet());
        }
        // Publish playback before artwork/library requests. Those must never delay the lyric clock.
        accept(playbackSnapshot(root, liked, observedAt));
        status = "Spotify account connected";
    }

    static Instant playbackObservedAt(Instant receivedAt, long sentNanos, long receivedNanos) {
        // Spotify's `timestamp` is the last state change, not when `progress_ms` was sampled.
        // The request midpoint is a bounded estimate that does not depend on server/client clock skew.
        long halfRoundTrip = Math.clamp((receivedNanos - sentNanos) / 2, 0, 2_000_000_000L);
        return receivedAt.minusNanos(halfRoundTrip);
    }

    static long retryAfterMillis(String seconds) {
        try {
            double value = Double.parseDouble(seconds);
            return Double.isFinite(value) ? (long) Math.clamp(value * 1_000, 1_000, 3_600_000) : 5_000;
        } catch (NumberFormatException ignored) {
            return 5_000;
        }
    }

    static PlaybackSnapshot playbackSnapshot(JsonNode root, boolean liked, Instant observedAt) {
        JsonNode item = root.path("item");
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
        boolean playing = root.path("is_playing").asBoolean(false);
        MusicCapabilities capabilities = new MusicCapabilities(
                playing ? !actions.path("pausing").asBoolean(false)
                        : !actions.path("resuming").asBoolean(false),
                !actions.path("skipping_prev").asBoolean(false),
                !actions.path("skipping_next").asBoolean(false),
                !actions.path("seeking").asBoolean(false), true, true, true, true, true);
        return new PlaybackSnapshot(item.path("name").asText("Nothing playing"),
                String.join(", ", artists), album.path("name").asText(""), artwork,
                uri, root.path("device").path("name").asText(null),
                root.path("progress_ms").asLong(0), item.path("duration_ms").asLong(0),
                playing,
                root.path("device").path("volume_percent").asDouble(0) / 100.0,
                root.path("shuffle_state").asBoolean(false), root.path("repeat_state").asText("off"),
                liked, 0, 0, 0, 0.5, new double[0], observedAt, capabilities);
    }

    private void refreshLiked(String token, String uri, long revision) {
        Thread.ofVirtual().name("relay-spotify-library").start(() -> {
            try {
                boolean result = checkLiked(token, uri);
                if (!closed && revision == likedRevision.get() && uri.equals(likedUri)) liked = result;
            } catch (Exception ignored) {
                // Library status is optional; a failed request must not stop playback or lyric lookup.
            }
        });
    }

    void accept(PlaybackSnapshot value) {
        boolean wasOwned = automaticPlaybackOwned;
        latest = value;
        lastPlaybackResponseNanos = System.nanoTime();
        // An empty Web API poll must not erase a valid Windows Media fallback.
        if (shouldUseAccountSource() && (playbackEligible(preferences.getMusicSource(), value)
                || automaticPlaybackOwned && meaningful(value))) {
            if (preferences.getMusicSource() == MusicSource.AUTOMATIC) automaticPlaybackOwned = true;
            snapshots.accept(value);
        } else if (!meaningful(value)) {
            automaticPlaybackOwned = false;
            if (wasOwned && shouldUseAccountSource()) snapshots.accept(value);
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

    private void oauthCallback(HttpExchange exchange, HttpServer server, String oauthState,
                               String verifier, String clientId) {
        String responseText;
        int responseCode = 200;
        try {
            Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
            if (callbackServer != server) throw new SecurityException("Authorization was replaced or cancelled");
            if (!oauthState.equals(query.get("state"))) throw new SecurityException("OAuth state mismatch");
            String code = query.get("code");
            if (code == null || code.isBlank()) throw new IllegalStateException(query.getOrDefault("error", "missing code"));
            exchangeCode(code, server, verifier, clientId);
            // Authorization also serves metadata matching for YouTube Music and
            // Windows Media. Never replace the source the player selected.
            responseText = "<h2>Relay conectado ao Spotify.</h2><p>Você já pode voltar ao Minecraft.</p>";
        } catch (Exception error) {
            responseCode = 400;
            if (callbackServer == server) status = "Spotify authorization failed";
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
                .execute(() -> {
                    synchronized (authorizationLock) {
                        if (callbackServer == server) callbackServer = null;
                    }
                    // Retire this attempt only. A newer connection may already own the callback port.
                    server.stop(0);
                });
    }

    private void exchangeCode(String code, HttpServer server, String verifier, String clientId) throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("grant_type", "authorization_code");
        fields.put("code", code);
        fields.put("redirect_uri", REDIRECT_URI);
        fields.put("client_id", clientId);
        fields.put("code_verifier", verifier);
        JsonNode response = tokenRequest(fields);
        synchronized (authorizationLock) {
            if (callbackServer != server || closed) throw new SecurityException("Authorization was cancelled");
            applyTokenResponse(response, true);
            credentialGeneration++;
            status = "Spotify account connected";
        }
        requestPlaybackRefresh();
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
        HttpServer server;
        synchronized (authorizationLock) {
            server = callbackServer;
            callbackServer = null;
        }
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
        requestPlaybackRefresh();
        stopCallbackServer();
    }
}
