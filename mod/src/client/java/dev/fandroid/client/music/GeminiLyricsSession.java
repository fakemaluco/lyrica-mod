package dev.fandroid.client.music;

import dev.fandroid.client.ClientPreferences;
import dev.fandroid.protocol.lyrics.LyricsDocument;
import dev.fandroid.protocol.lyrics.LyricsSyncMode;
import dev.fandroid.protocol.music.PlaybackSnapshot;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** Track-scoped, asynchronous art direction. Tick owns scheduling; renderers only read immutable overrides. */
public final class GeminiLyricsSession implements AutoCloseable {
    @FunctionalInterface public interface Generator {
        String generate(String key, String model, GeminiLyricsClient.Commission commission, String direction) throws Exception;
    }
    private final ClientPreferences preferences;
    private final GeminiLyricsStore store;
    private final Generator generator;
    private final LongSupplier clock;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "lyrica-lyrics-direction"); thread.setDaemon(true); return thread;
    });
    private LyricsDocument previousDocument;
    private String configuration = "", identity = "", attempted = "", status = "disabled";
    private GeminiLyricsClient.Commission commission;
    private GeminiLyricsSpec result;
    private Map<String, WorldLyricsPresentation.Cue> overrides = Map.of();
    private Future<?> request;
    private long generation, stableSince, nextRequest, position, previousPosition, activateAfter = Long.MAX_VALUE;
    private long lastManual = Long.MIN_VALUE / 2;
    private boolean closed, force;

    public GeminiLyricsSession(ClientPreferences preferences, Path root) {
        this(preferences, root, new GeminiLyricsClient()::generate, System::nanoTime);
    }
    GeminiLyricsSession(ClientPreferences preferences, Path root, Generator generator, LongSupplier clock) {
        this.preferences = preferences; store = new GeminiLyricsStore(root); this.generator = generator; this.clock = clock;
    }
    public synchronized boolean keyConfigured() { return store.configured(); }
    public synchronized boolean saveKey(String key) {
        try { store.saveKey(key == null ? "" : key.strip()); reset(true); return true; }
        catch (Exception ignored) { status = "key_invalid"; return false; }
    }
    public synchronized void removeKey() {
        try { store.removeKey(); preferences.setWorldLyricsDirection(WorldLyricsPresentation.Direction.AUTOMATIC); reset(true); status = "disabled"; }
        catch (Exception ignored) { status = "storage"; }
    }
    public synchronized void regenerate() {
        if (closed) return;
        long now = clock.getAsLong();
        if (now - lastManual < 5_000_000_000L) { status = "cooldown"; return; }
        lastManual = now; generation++;
        if (request != null) request.cancel(true);
        request = null; attempted = ""; force = true;
    }
    public synchronized String status() {
        return enabled() ? status : "disabled";
    }
    public synchronized Map<String, WorldLyricsPresentation.Cue> overrides() {
        return !closed && enabled() ? overrides : Map.of();
    }
    synchronized Future<?> pending() { return request; }
    synchronized String cacheKey() { return identity; }
    private boolean enabled() { return preferences.getWorldLyricsDirection() == WorldLyricsPresentation.Direction.GEMINI; }
    private void reset(boolean clear) {
        generation++; if (request != null) request.cancel(true); request = null;
        identity = ""; attempted = ""; previousDocument = null; configuration = ""; commission = null;
        if (clear) { result = null; overrides = Map.of(); activateAfter = Long.MAX_VALUE; }
    }

    public synchronized void tick(PlaybackSnapshot playback, LyricsDocument document, long positionMillis) {
        if (closed) return;
        position = positionMillis;
        if (!enabled() || !preferences.isWorldLyrics()) {
            if (!identity.isEmpty() || request != null || result != null) reset(true);
            status = "disabled"; return;
        }
        if (!store.configured()) { status = "key_missing"; return; }
        if (document == null || document.lines().isEmpty() || document.metadata().syncMode() == LyricsSyncMode.STATIC
                || playback.title() == null || playback.title().isBlank() || playback.title().equals("Nothing playing")) {
            if (!identity.isEmpty()) reset(true);
            status = "waiting"; return;
        }
        String model;
        try { model = GeminiShaderClient.model(preferences.getWorldLyricsGeminiModel()); }
        catch (IllegalArgumentException ignored) { reset(true); status = "model"; return; }
        String direction = preferences.getWorldLyricsGeminiDirection();
        String nextConfiguration = model + "\n" + preferences.isWorldLyricsGeminiFont() + "\n" + direction + "\n" + playback.title() + "\n" + playback.artist()
                + "\n" + playback.album() + "\n" + playback.spotifyUri() + "\n" + playback.durationMillis();
        long now = clock.getAsLong();
        if (document != previousDocument || !nextConfiguration.equals(configuration)) {
            GeminiLyricsClient.Commission next;
            try { next = GeminiLyricsClient.commission(playback, document, preferences.isWorldLyricsGeminiFont()); }
            catch (Exception ignored) { reset(true); status = "response"; return; }
            String nextIdentity = GeminiLyricsSpec.hash(GeminiLyricsSpec.CONTRACT + "\n" + nextConfiguration + "\n" + next.json());
            // Translation can replace the document object without changing the source text or timestamps.
            if (!nextIdentity.equals(identity)) {
                reset(true); identity = nextIdentity; stableSince = now; status = "waiting";
            } else if (result != null) {
                try {
                    result = GeminiLyricsSpec.parse(result.json(), next.timeline());
                    rebuildOverrides();
                } catch (Exception ignored) { result = null; overrides = Map.of(); }
            }
            previousDocument = document; configuration = nextConfiguration; commission = next;
        }
        if (result != null && position < previousPosition - 500) {
            // Seeking reconstructs the current scene; the prepared direction is now available for every phrase.
            activateAfter = Long.MIN_VALUE; overrides = result.cues();
        }
        previousPosition = position;
        if (attempted.equals(identity) || request != null) return;
        if (!playback.playing() && !force) return;
        if (!force && now - stableSince < 1_000_000_000L) return;
        final String id = identity, secret = store.key();
        final var input = commission;
        final long revision = generation;
        final boolean fresh = force;
        force = false; attempted = id; status = "generating";
        request = io.submit(() -> {
            try {
                var validated = fresh ? null : store.read(id, input.timeline());
                boolean cached = validated != null;
                if (!cached) {
                    synchronized (this) {
                        if (closed || revision != generation) return;
                        if (clock.getAsLong() < nextRequest) { attempted = ""; force = fresh; status = "cooldown"; return; }
                        nextRequest = clock.getAsLong() + 20_000_000_000L;
                    }
                    String answer = generator.generate(secret, model, input, direction);
                    try { validated = GeminiLyricsSpec.parse(answer, input.timeline()); }
                    catch (Exception rejected) { throw new GeminiShaderClient.Failure("RESPONSE"); }
                }
                synchronized (this) {
                    if (closed || revision != generation || !enabled() || !id.equals(identity)) return;
                    // Rebind source records after a translation refresh that retained the same source fingerprint.
                    result = commission == input ? validated : GeminiLyricsSpec.parse(validated.json(), commission.timeline());
                    // Keep every phrase already in progress intact. New choices begin with the next phrase.
                    activateAfter = position;
                    rebuildOverrides(); status = cached ? "cached" : "ready";
                }
                if (!cached) store.put(id, validated);
            } catch (Exception error) {
                synchronized (this) {
                    if (closed || revision != generation) return;
                    status = error instanceof GeminiShaderClient.Failure failure ? switch (failure.code) {
                        case "AUTH" -> "auth"; case "MODEL" -> "model"; case "QUOTA" -> "quota";
                        case "REQUEST" -> "request"; case "UNAVAILABLE" -> "unavailable"; default -> "response";
                    } : "network";
                    if (status.equals("quota")) nextRequest = clock.getAsLong() + 120_000_000_000L;
                }
            } finally { synchronized (this) { if (revision == generation) request = null; } }
        });
    }
    private void rebuildOverrides() {
        var future = new LinkedHashMap<String, WorldLyricsPresentation.Cue>();
        for (var entry : result.cues().entrySet())
            if (entry.getValue().source().line().startMillis() > activateAfter) future.put(entry.getKey(), entry.getValue());
        var look = result.look();
        if (!look.equals(WorldLyricsPresentation.Look.DEFAULT)) {
            var local = commission.timeline().local(preferences.getWorldLyricsPresentationIntensity());
            for (var entry : local.entrySet())
                if (entry.getValue().source().line().startMillis() > activateAfter)
                    future.putIfAbsent(entry.getKey(), entry.getValue().withLook(look));
        }
        overrides = Map.copyOf(future);
    }
    @Override public synchronized void close() { if (closed) return; closed = true; reset(true); io.shutdownNow(); }
}
