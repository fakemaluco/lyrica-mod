package dev.fandroid.client.music;

import dev.fandroid.FanDroidMod;
import dev.fandroid.protocol.ProtocolCodec;
import dev.fandroid.protocol.music.MusicCapabilities;
import dev.fandroid.protocol.music.MusicCommand;
import dev.fandroid.protocol.music.MusicConnector;
import dev.fandroid.protocol.music.PlaybackSnapshot;
import dev.fandroid.protocol.music.MusicAudioLevels;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class MediaBridgeConnector implements MusicConnector {
    private final Process process;
    private final BufferedWriter writer;
    private final CopyOnWriteArrayList<Consumer<PlaybackSnapshot>> listeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<MusicAudioLevels>> audioListeners = new CopyOnWriteArrayList<>();
    private volatile boolean audioLevelsEnabled;
    private volatile String activeMediaApp;
    private boolean duckingUsed;
    private volatile PlaybackSnapshot latest = PlaybackSnapshot.unavailable();
    private volatile String pairingCode;
    private volatile int pairingPort;
    private volatile boolean youtubeConnected;
    private volatile boolean bridgeAlive = true;
    private volatile String startupError;
    private volatile String source = "AUTOMATIC";
    private volatile String activeSource = "WINDOWS_MEDIA";

    private MediaBridgeConnector(Process process) {
        this.process = process;
        this.writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        Thread.ofVirtual().name("fandroid-media-bridge-reader").start(() -> read(process));
        Thread.ofVirtual().name("fandroid-media-bridge-errors").start(() -> readErrors(process));
    }

    public static MusicConnector start(Path configuredPath) {
        try {
            String override = System.getProperty("fandroid.mediaBridge");
            Path executable = override == null || override.isBlank() ? configuredPath : Path.of(override);
            if (!Files.isRegularFile(executable)) {
                return new UnavailableMusicConnector("No media bridge is available for this platform");
            }
            verifyChecksumIfPresent(executable);
            Process process = new ProcessBuilder(executable.toAbsolutePath().toString())
                    .start();
            FanDroidMod.LOGGER.info("Started FanDroid media bridge from {}", executable.toAbsolutePath());
            return new MediaBridgeConnector(process);
        } catch (Exception e) {
            FanDroidMod.LOGGER.warn("Media bridge is unavailable", e);
            String detail = e instanceof SecurityException
                    ? "Media bridge update is incomplete - reinstall it and restart Minecraft"
                    : "Media bridge failed to start - restart Minecraft";
            return new UnavailableMusicConnector(detail);
        }
    }

    public static String startupError(MusicConnector connector) {
        if (connector instanceof MediaBridgeConnector bridge) return bridge.startupError();
        if (connector instanceof UnavailableMusicConnector unavailable) return unavailable.startupError();
        return null;
    }

    private static void verifyChecksumIfPresent(Path executable) throws Exception {
        Path checksum = executable.resolveSibling(executable.getFileName() + ".sha256");
        if (!Files.exists(checksum)) return;
        String expected = Files.readString(checksum).strip().split("\\s+", 2)[0]
                .toLowerCase(Locale.ROOT);
        if (expected.length() != 64 || !expected.chars().allMatch(value ->
                value >= '0' && value <= '9' || value >= 'a' && value <= 'f')) {
            throw new SecurityException("invalid media bridge checksum file");
        }
        String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(executable)));
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII))) {
            throw new SecurityException("media bridge checksum mismatch");
        }
    }

    private void read(Process process) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.length() > 10 * 1024 * 1024) continue;
                var node = ProtocolCodec.mapper().readTree(line);
                if (node.path("type").asText().equals("audio_levels")) {
                    var levels=node.path("levels");
                    if(levels.isArray() && levels.size()==5 && audioLevelsEnabled) {
                        MusicAudioLevels sample=MusicAudioLevels.fromMeter(levels.get(0).asInt(),levels.get(1).asInt(),levels.get(2).asInt(),
                                levels.get(3).asInt(),levels.get(4).asInt(),System.nanoTime());
                        for(var listener:audioListeners) listener.accept(sample);
                    }
                    continue;
                }
                if (node.path("type").asText().equals("pairing_code")) {
                    pairingCode = node.path("code").asText();
                    pairingPort = node.path("port").asInt();
                    FanDroidMod.LOGGER.info("YouTube Music companion is listening on 127.0.0.1:{}", pairingPort);
                    continue;
                }
                if (node.path("type").asText().equals("youtube_status")) {
                    youtubeConnected = node.path("youtubeConnected").asBoolean(false);
                    continue;
                }
                if (!node.path("type").asText().equals("snapshot")) continue;
                activeSource = node.path("activeSource").asText("WINDOWS_MEDIA");
                activeMediaApp = node.path("activeMediaApp").asText(null);
                latest = ProtocolCodec.mapper().treeToValue(node.path("snapshot"), PlaybackSnapshot.class);
                for (Consumer<PlaybackSnapshot> listener : listeners) listener.accept(latest);
            }
        } catch (Exception e) {
            if (process.isAlive()) FanDroidMod.LOGGER.warn("Media bridge stopped producing snapshots", e);
        } finally {
            bridgeAlive = false;
            youtubeConnected = false;
        }
    }

    private void readErrors(Process process) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                startupError = line.length() > 180 ? line.substring(0, 180) : line;
                FanDroidMod.LOGGER.warn("Media bridge: {}", startupError);
            }
        } catch (Exception ignored) { }
    }

    @Override
    public String id() { return "system-media"; }

    public String pairingCode() { return pairingCode; }
    public int pairingPort() { return pairingPort; }
    public boolean youtubeConnected() { return youtubeConnected; }
    public String activeSource() { return activeSource; }
    public boolean bridgeAlive() { return bridgeAlive && process.isAlive(); }
    public String startupError() { return startupError; }

    public AutoCloseable subscribeAudio(Consumer<MusicAudioLevels> listener) {
        audioListeners.add(listener);
        return () -> audioListeners.remove(listener);
    }
    /** Changed only when the compact island needs audio; never polled per rendered frame. */
    public void setAudioLevelsEnabled(boolean enabled) {
        if(enabled==audioLevelsEnabled || !bridgeAlive()) return;
        audioLevelsEnabled=enabled;
        try { send(java.util.Map.of("type","audio_levels_enabled","enabled",enabled)); }
        catch(IllegalStateException stopped) { audioLevelsEnabled=false; }
        if(!enabled) for(var listener:audioListeners) listener.accept(MusicAudioLevels.SILENT);
    }

    public void setSource(String source) {
        String next = source == null || source.isBlank() ? "AUTOMATIC" : source;
        if (next.equals(this.source)) return;
        this.source = next;
        send(java.util.Map.of("type", "source", "source", next));
    }

    public String activeMediaApp() { return activeMediaApp; }

    public void updateDucking(AudioDucking.Request request) {
        if (!bridgeAlive()) return;
        try {
            send(java.util.Map.of("type", "ducking", "request", ProtocolCodec.mapper().valueToTree(request)));
            duckingUsed |= request != null;
        } catch (IllegalStateException ignored) { }
    }

    /** Reopens WASAPI loopback against the current Windows default output. */
    public void reloadAudioOutput() {
        send(java.util.Map.of("type", "reload_audio_output"));
    }

    @Override
    public MusicCapabilities capabilities() { return latest.capabilities(); }

    @Override
    public CompletableFuture<PlaybackSnapshot> snapshot() { return CompletableFuture.completedFuture(latest); }

    @Override
    public CompletableFuture<Void> command(MusicCommand command) {
        return CompletableFuture.runAsync(() -> send(java.util.Map.of(
                "type", "command", "command", command, "source", source)));
    }

    private void send(java.util.Map<String, ?> value) {
        synchronized (writer) {
            try {
                writer.write(ProtocolCodec.mapper().writeValueAsString(value));
                writer.newLine();
                writer.flush();
            } catch (Exception e) {
                throw new IllegalStateException("media bridge command failed", e);
            }
        }
    }

    @Override
    public AutoCloseable subscribe(Consumer<PlaybackSnapshot> listener) {
        listeners.add(listener);
        listener.accept(latest);
        return () -> listeners.remove(listener);
    }

    @Override
    public void close() {
        try { writer.close(); } catch (Exception ignored) { }
        if (duckingUsed) {
            try {
                if (process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) return;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        process.destroy();
        if (process.isAlive()) process.destroyForcibly();
    }

    private static final class UnavailableMusicConnector implements MusicConnector {
        private final String startupError;

        private UnavailableMusicConnector(String startupError) {
            this.startupError = startupError;
        }

        private String startupError() { return startupError; }
        @Override public String id() { return "unavailable"; }
        @Override public MusicCapabilities capabilities() { return MusicCapabilities.unavailable(); }
        @Override public CompletableFuture<PlaybackSnapshot> snapshot() { return CompletableFuture.completedFuture(PlaybackSnapshot.unavailable()); }
        @Override public CompletableFuture<Void> command(MusicCommand command) { return CompletableFuture.failedFuture(new IllegalStateException("music connector unavailable")); }
        @Override public AutoCloseable subscribe(Consumer<PlaybackSnapshot> listener) { listener.accept(PlaybackSnapshot.unavailable()); return () -> { }; }
        @Override public void close() { }
    }
}
