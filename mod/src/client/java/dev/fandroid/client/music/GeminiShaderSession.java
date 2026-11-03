package dev.fandroid.client.music;

import dev.fandroid.client.ClientPreferences;
import dev.fandroid.protocol.music.PlaybackSnapshot;
import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.LongSupplier;

/** Track-scoped asynchronous requests; old responses cannot replace the current song's shader. */
public final class GeminiShaderSession implements AutoCloseable {
    @FunctionalInterface public interface Generator {
        GeminiShaderSpec generate(String key, String model, GeminiShaderClient.Track track, String direction) throws Exception;
    }
    private final ClientPreferences p;
    private final GeminiShaderStore store;
    private final Generator generator;
    private final LongSupplier time;
    private final Executor mainThread;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "lyrica-gemini"); t.setDaemon(true); return t; });
    private PlaybackSnapshot playback = PlaybackSnapshot.unavailable();
    private long position, positionNanos, revision, stableSince, nextRequest, lastManual = Long.MIN_VALUE / 2;
    private String identity = "", attempted = "", deferred = "", active = "", saved = "", status = "disabled";
    private GeminiShaderSpec shader;
    private Future<?> request;
    private String renderFailure = "";
    private boolean closed, force;
    private long interpretation;
    private final java.util.ArrayDeque<String> recentLooks=new java.util.ArrayDeque<>();
    private int accent = 0x9F86ED;
    public GeminiShaderSession(ClientPreferences p, Path root, Executor mainThread) {
        this(p, root, new GeminiShaderClient()::generate, System::nanoTime, mainThread);
    }
    GeminiShaderSession(ClientPreferences p, Path root, Generator generator, LongSupplier time, Executor mainThread) {
        this.p = p; this.store = new GeminiShaderStore(root); this.generator = generator; this.time = time; this.mainThread = mainThread;
    }
    public static GeminiShaderSession offlineProbe(ClientPreferences p, Path root, Generator generator, LongSupplier time) {
        if (!Boolean.getBoolean("lyrica.geminiWorldSelfTest")) throw new IllegalStateException("Probe is opt-in");
        return new GeminiShaderSession(p, root, generator, time, Runnable::run);
    }
    public synchronized boolean keyConfigured() { return store.configured(); }
    public synchronized boolean saveKey(String value) {
        try { store.saveKey(value.strip()); invalidate(); return true; }
        catch (Exception ignored) { status = "key_invalid"; return false; }
    }
    public synchronized void removeKey() {
        try { store.removeKey(); p.setGeminiShaderEnabled(false); invalidate(); status = "disabled"; }
        catch (Exception ignored) { status = "storage"; }
    }
    public synchronized void clearCache() {
        saved = active;
        io.submit(() -> {
            try { store.clear(); }
            catch (Exception ignored) { synchronized (this) { status = "storage"; } }
        });
    }
    public synchronized void regenerate() {
        long now = time.getAsLong();
        if (now - lastManual < 5_000_000_000L) { status = "cooldown"; return; }
        lastManual = now; invalidate(); force = true;
    }
    public synchronized String status() {
        if (!p.isGeminiShaderEnabled()) return "disabled";
        if (!renderFailure.isEmpty()) return renderFailure;
        return status;
    }
    public synchronized String shaderName() { return shader == null ? "" : shader.name(); }
    public synchronized String shaderDescription() { return shader == null ? "" : shader.description(); }
    public int brightness() { return p.getGeminiShaderBrightness(); }
    synchronized String activeKey() { return active; }
    synchronized Future<?> pending() { return request; }
    private void invalidate() {
        revision++; if (request != null) request.cancel(true); request = null;
        attempted = ""; identity = ""; shader = null; active = ""; saved = ""; renderFailure = "";
    }
    public synchronized void tick(PlaybackSnapshot playback, long position, int accent) {
        if (closed) return;
        this.playback = playback; this.position = position; positionNanos = time.getAsLong(); if (accent != 0) this.accent = accent;
        if (!p.isGeminiShaderEnabled()) {
            if (!identity.isEmpty() || request != null) invalidate();
            status = "disabled"; return;
        }
        if (!store.configured()) { status = "key_missing"; return; }
        String model;
        try { model = GeminiShaderClient.model(p.getGeminiShaderModel()); }
        catch (IllegalArgumentException invalid) { invalidate(); status = "model"; return; }
        var track = GeminiShaderClient.Track.from(playback);
        if (track.title().isBlank() || track.title().equals("Nothing playing")) { invalidate(); status = "waiting"; return; }
        String next = GeminiShaderSpec.hash(GeminiShaderSpec.CONTRACT + "\n" + model + "\n" + track.title() + "\n"
                + track.artist() + "\n" + track.album() + "\n" + track.durationMillis() + "\n"
                + String.valueOf(playback.spotifyUri()) + "\n" + GeminiArtLibrary.direction(p));
        long now = time.getAsLong();
        if (!next.equals(identity)) {
            invalidate(); identity = next; stableSince = now; status = "waiting";
        }
        if (attempted.equals(identity) || request != null) return;
        if (!p.isGeminiShaderAutomatic() && !force) { status = "manual"; return; }
        if (!playback.playing() && !force) { status = "waiting"; return; }
        if (!force && now - stableSince < 2_000_000_000L) return;
        if (now < nextRequest && identity.equals(deferred)) { status = "cooldown"; return; }
        String id = identity, secret = store.key();
        String direction = GeminiArtLibrary.generationDirection(GeminiArtLibrary.direction(p),++interpretation,java.util.List.copyOf(recentLooks));
        boolean fresh = force; force = false; attempted = id; long generation = revision;
        status = "generating";
        // Disk cache and HTTP both stay off the render thread. Never automatically retry a failed API call.
        request = io.submit(() -> {
            try {
                GeminiShaderSpec result = fresh ? null : store.read(id);
                boolean cached = result != null;
                if (!cached) {
                    synchronized (this) {
                        if (closed || generation != revision) return;
                        if (time.getAsLong() < nextRequest) {
                            deferred = id; attempted = ""; force = fresh; status = "cooldown"; return;
                        }
                        deferred = "";
                        nextRequest = time.getAsLong() + 20_000_000_000L;
                    }
                    result = generator.generate(secret, model, track, direction);
                }
                synchronized (this) {
                    if (closed || generation != revision || !p.isGeminiShaderEnabled() || !id.equals(identity)) return;
                    shader = result; active = id + ":" + GeminiShaderSpec.hash(result.glsl());
                    saved = cached ? active : ""; status = cached ? "cached" : "ready";
                    String look=result.name()+": "+result.description();
                    recentLooks.remove(look);recentLooks.addLast(look);while(recentLooks.size()>6)recentLooks.removeFirst();
                }
            } catch (Exception error) {
                synchronized (this) {
                    if (closed || generation != revision) return;
                    status = error instanceof GeminiShaderClient.Failure f ? switch (f.code) {
                        case "AUTH" -> "auth"; case "MODEL" -> "model"; case "QUOTA" -> "quota"; default -> "response";
                    } : "network";
                    if (status.equals("quota")) nextRequest = time.getAsLong() + 120_000_000_000L;
                }
            } finally {
                synchronized (this) { if (generation == revision) request = null; }
            }
        });
    }
    public record WorldEffect(String key, GeminiShaderSpec shader, float time, float trackTime,
                              float energy, float beat, float bass, float mid, float treble,
                              float red, float green, float blue, float strength) {
        public java.nio.ByteBuffer uniforms(int width, int height, boolean reversedDepth) {
            var out = java.nio.ByteBuffer.allocateDirect(64).order(java.nio.ByteOrder.nativeOrder());
            out.putFloat(time).putFloat(trackTime).putFloat(energy).putFloat(beat);
            out.putFloat(bass).putFloat(mid).putFloat(treble).putFloat(strength);
            out.putFloat(red).putFloat(green).putFloat(blue).putFloat(reversedDepth ? 1 : 0);
            out.putFloat(width).putFloat(height).putFloat(0).putFloat(0).flip();
            return out;
        }
    }
    /** The renderer consumes the REAL world color and depth textures before hands and GUI. */
    public synchronized WorldEffect worldEffect() {
        if (closed || !p.isGeminiShaderEnabled() || shader == null || active.isEmpty() || !renderFailure.isEmpty()) return null;
        long extrapolated = position + (playback.playing() ? Math.max(0, time.getAsLong() - positionNanos) / 1_000_000L : 0);
        boolean animate = p.isLyricsAnimations(), playing = animate && playback.playing();
        float seconds = animate ? Math.max(0, extrapolated) / 1000f : 0;
        double[] bands = playback.intensityBands();
        return new WorldEffect(active, shader, seconds, seconds, playing && p.isGeminiArtAudio() ? unit(playback.intensity()) : 0,
                playing && p.isGeminiArtAudio() ? unit(playback.beatPhase()) : 0, playing && p.isGeminiArtAudio() ? band(bands, 0) : 0,
                playing && p.isGeminiArtAudio() ? band(bands, 1) : 0, playing && p.isGeminiArtAudio() ? band(bands, 2) : 0,
                (accent >>> 16 & 255) / 255f, (accent >>> 8 & 255) / 255f, (accent & 255) / 255f,
                p.getGeminiWorldStrength() / 100f);
    }
    public synchronized void rendered(String key) {
        if (closed || !active.equals(key) || !renderFailure.isEmpty()) return;
        if (!saved.equals(active)) {
            saved = active; String id = identity; GeminiShaderSpec validated = shader;
            io.submit(() -> store.put(id, validated));
        }
    }
    public synchronized void renderFailed(String key, String reason) {
        if (active.equals(key)) renderFailure = reason;
    }
    private static float unit(double value) { return Double.isFinite(value) ? (float) Math.clamp(value, 0, 1) : 0; }
    private static float band(double[] values, int third) {
        if (values == null || values.length == 0) return 0;
        int first = third * values.length / 3, end = Math.max(first + 1, (third + 1) * values.length / 3);
        double sum = 0; for (int i = first; i < Math.min(end, values.length); i++) sum += unit(values[i]);
        return unit(sum / (end - first));
    }
    @Override public synchronized void close() {
        if (closed) return; closed = true; invalidate();
        io.shutdownNow();
    }
}
