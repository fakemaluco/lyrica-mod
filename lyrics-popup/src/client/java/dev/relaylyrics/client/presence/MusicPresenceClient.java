package dev.relaylyrics.client.presence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One connection per multiplayer session. All HTTP, Mojang proof and WebSocket work is off the render thread. */
public final class MusicPresenceClient implements AutoCloseable {
    public record Identity(UUID accountId, UUID playerId, String name, String serverHash) { }
    public record Playing(String title, String artist, String cover, boolean sharing) {
        public Playing {
            title = PresenceTrack.clean(title, 256); artist = PresenceTrack.clean(artist, 256);
            cover = cover != null && cover.length() <= 6_000_000 ? cover : ""; sharing = sharing && !title.isBlank();
            if (!sharing) { title = artist = cover = ""; }
        }
    }
    @FunctionalInterface public interface Proof { void join(String challenge) throws Exception; }
    public enum Status { CONNECTING, CONNECTED, VIEW_ONLY, RETRYING, ACCOUNT_REQUIRED, CLOSED }
    public static final String PROJECT = "https://thfukfqgjmgelikrdeti.supabase.co";
    public static final String PUBLISHABLE = "sb_publishable_t8VixNoVtAslOR40K6mNKA_EKtikJf4";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final URI endpoint;
    private final String key;
    private final Identity identity;
    private final Proof proof;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Lyrica music presence"); t.setDaemon(true); return t;
    });
    private volatile Playing playing = new Playing("", "", "", false);
    private volatile Set<UUID> visible = Set.of();
    private volatile Map<UUID, PresenceTrack> peers = Map.of();
    private volatile boolean closed;
    private volatile Status status = Status.CONNECTING;
    private volatile WebSocket socket;
    private final AtomicBoolean refresh = new AtomicBoolean(true);
    private Playing published;
    private String token, topic;
    private boolean canPublish = true;
    private long nextAttempt, lastWrite, lastRead, lastHeartbeat, socketAttempt, socketRef;
    private int failures;
    private String sourceCover = "", preparedCover = "";
    private volatile long lastSocketMessage;
    public MusicPresenceClient(Identity identity, Proof proof) { this(URI.create(PROJECT + "/functions/v1/music-presence/"), PUBLISHABLE, identity, proof); }
    public MusicPresenceClient(URI endpoint, String key, Identity identity, Proof proof) {
        this.endpoint = endpoint; this.key = key; this.identity = identity; this.proof = proof;
    }
    public void start() { worker.scheduleWithFixedDelay(this::step, 0, 500, TimeUnit.MILLISECONDS); }
    public Identity identity() { return identity; }
    public Status status() { return status; }
    public void update(Playing value, Set<UUID> online) {
        playing = value;
        Set<UUID> next = Set.copyOf(online.stream().limit(512).toList());
        if (!visible.equals(next)) { visible = next; refresh.set(true); }
    }
    public PresenceTrack track(UUID id) {
        PresenceTrack track = peers.get(id);
        return visible.contains(id) && track != null && track.alive(System.nanoTime()) ? track : null;
    }
    private void step() {
        if (closed || System.nanoTime() < nextAttempt) return;
        try {
            if (token == null) authenticate();
            if (closed) return;
            long now = System.nanoTime();
            if (socket == null && now >= socketAttempt) connectSocket();
            if (socket != null && now - lastHeartbeat > 25_000_000_000L) {
                send(socket, "phoenix", "heartbeat", Map.of()); lastHeartbeat = now;
                if (now - lastSocketMessage > 65_000_000_000L) { socket.abort(); socket = null; }
            }
            Playing value = canPublish ? playing : new Playing("", "", "", false);
            boolean write = canPublish && (!value.equals(published) || now - lastWrite > 30_000_000_000L);
            if (now - lastRead < 1_000_000_000L || !write && !refresh.get() && now - lastRead < 30_000_000_000L) return;
            refresh.set(false);
            Map<String, Object> body = new HashMap<>();
            body.put("visible", visible.stream().map(UUID::toString).toList()); body.put("write", write);
            if (write && !Objects.equals(sourceCover, value.cover())) {
                sourceCover = value.cover(); preparedCover = PresenceArtwork.prepare(sourceCover);
            }
            body.put("sharing", value.sharing()); body.put("title", value.title()); body.put("artist", value.artist());
            body.put("cover_url", write ? preparedCover : "");
            JsonNode data = request("sync", body, token);
            Map<UUID, PresenceTrack> fresh = new HashMap<>(); long received = System.nanoTime();
            for (JsonNode row : data.path("peers")) {
                try {
                    UUID id = UUID.fromString(row.path("player_id").asText());
                    long ttl = Math.clamp(row.path("expires_in_ms").asLong(), 0, 75_000);
                    if (visible.contains(id) && ttl > 0) fresh.put(id, new PresenceTrack(id, row.path("player_name").asText(),
                            row.path("title").asText(), row.path("artist").asText(), row.path("cover_url").asText(), received + ttl * 1_000_000));
                } catch (IllegalArgumentException ignored) { }
            }
            if (closed) return;
            peers = Map.copyOf(fresh); lastRead = now;
            if (write) { published = value; lastWrite = now; }
            failures = 0; status = canPublish ? Status.CONNECTED : Status.VIEW_ONLY;
        } catch (Exception error) {
            if (closed) return;
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            if (error instanceof ResponseFailure e && e.code == 401) { token = null; published = null; peers = Map.of(); if (socket != null) { socket.abort(); socket = null; } }
            if (status != Status.ACCOUNT_REQUIRED) status = Status.RETRYING;
            failures = Math.min(6, failures + 1);
            nextAttempt = System.nanoTime() + Math.min(60, 1L << failures) * 1_000_000_000L;
        }
    }
    private void authenticate() throws Exception {
        status = Status.CONNECTING;
        JsonNode result;
        boolean verified = true;
        try {
            JsonNode c = request("challenge", Map.of("account_id", identity.accountId().toString(), "player_id", identity.playerId().toString(),
                    "player_name", identity.name(), "server_hash", identity.serverHash()), null);
            String serverId = c.path("server_id").asText();
            if (!serverId.matches("[0-9a-f]{40}")) throw new IllegalArgumentException("Invalid challenge");
            proof.join(serverId);
            result = request("authenticate", Map.of("challenge", c.path("challenge").asText()), null);
        } catch (Exception error) {
            if (error instanceof InterruptedException) throw error;
            if (error instanceof ResponseFailure response && response.code != 400 && response.code != 401) throw error;
            if (closed) return;
            // The server enforces read-only access independently of this flag.
            verified = false;
            result = request("viewer", Map.of("player_id", identity.playerId().toString(), "server_hash", identity.serverHash()), null);
        }
        if (closed) {
            leave(result.path("token").asText()); return;
        }
        String next = result.path("token").asText(), channel = result.path("topic").asText();
        if (!next.matches("[0-9a-f]{64}") || !channel.matches("lyrica-music-[a-f0-9-]{36}")) throw new IllegalArgumentException("Invalid presence session");
        token = next; topic = channel; canPublish = verified && result.path("can_publish").asBoolean(true); refresh.set(true);
    }
    private JsonNode request(String route, Object body, String session) throws Exception {
        var builder = HttpRequest.newBuilder(endpoint.resolve(route)).timeout(Duration.ofSeconds(12))
                .header("apikey", key).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        if (session != null) builder.header("x-lyrica-session", session);
        HttpResponse<String> r = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (r.statusCode() != 200) throw new ResponseFailure(r.statusCode());
        if (r.body().length() > 16_000_000) throw new IllegalArgumentException("Oversized presence snapshot");
        return JSON.readTree(r.body());
    }
    private void connectSocket() {
        socketAttempt = System.nanoTime() + 10_000_000_000L;
        URI base = endpoint.resolve("/");
        URI url = URI.create(("https".equals(base.getScheme()) ? "wss" : "ws") + "://" + base.getRawAuthority()
                + "/realtime/v1/websocket?vsn=1.0.0&apikey=" + URLEncoder.encode(key, StandardCharsets.UTF_8));
        http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(8)).buildAsync(url, new WebSocket.Listener() {
            final StringBuilder pending = new StringBuilder();
            public void onOpen(WebSocket ws) {
                if (closed) { ws.abort(); return; }
                socket = ws; lastSocketMessage = System.nanoTime();
                send(ws, "realtime:" + topic, "phx_join", Map.of("config", Map.of("broadcast", Map.of("ack", false, "self", false),
                        "presence", Map.of("enabled", false), "private", false)));
                ws.request(1);
            }
            public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                if (closed || socket != ws) return null;
                lastSocketMessage = System.nanoTime();
                if (pending.length() + data.length() > 16384) { ws.abort(); socket = null; return null; }
                pending.append(data);
                if (last) {
                    try {
                        JsonNode event = JSON.readTree(pending.toString());
                        if (("broadcast".equals(event.path("event").asText()) || "phx_reply".equals(event.path("event").asText()))
                                && ("realtime:" + topic).equals(event.path("topic").asText())) refresh.set(true);
                        if ("phx_error".equals(event.path("event").asText()) || "error".equals(event.path("payload").path("status").asText())) { ws.abort(); socket = null; }
                    } catch (Exception ignored) { }
                    pending.setLength(0);
                }
                ws.request(1); return null;
            }
            public CompletionStage<?> onClose(WebSocket ws, int code, String reason) { if (socket == ws) socket = null; return null; }
            public void onError(WebSocket ws, Throwable error) { if (socket == ws) socket = null; }
        }).exceptionally(error -> null);
    }
    private synchronized void send(WebSocket ws, String channel, String event, Object payload) {
        try { ws.sendText(JSON.writeValueAsString(Map.of("topic", channel, "event", event, "payload", payload, "ref", Long.toString(++socketRef), "join_ref", "1")), true); }
        catch (Exception ignored) { ws.abort(); if (socket == ws) socket = null; }
    }
    private CompletableFuture<?> leave(String session) {
        if (session == null || session.isBlank()) return CompletableFuture.completedFuture(null);
        try {
            var request = HttpRequest.newBuilder(endpoint.resolve("leave")).timeout(Duration.ofSeconds(4)).header("apikey", key)
                    .header("Content-Type", "application/json").header("x-lyrica-session", session).POST(HttpRequest.BodyPublishers.ofString("{}"));
            return http.sendAsync(request.build(), HttpResponse.BodyHandlers.discarding());
        } catch (RuntimeException ignored) { return CompletableFuture.completedFuture(null); }
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true; status = Status.CLOSED; peers = Map.of(); visible = Set.of();
        WebSocket ws = socket; socket = null; if (ws != null) ws.abort();
        var cleanup = leave(token); token = null; worker.shutdownNow();
        cleanup.orTimeout(5, TimeUnit.SECONDS).whenComplete((unused, error) -> http.shutdownNow());
    }
    private static final class ResponseFailure extends Exception {
        final int code; ResponseFailure(int code) { super("Presence HTTP " + code); this.code = code; }
    }
}
