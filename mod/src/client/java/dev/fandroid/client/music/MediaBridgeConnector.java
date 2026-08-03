package dev.fandroid.client.music;

import dev.fandroid.FanDroidMod;
import dev.fandroid.protocol.ProtocolCodec;
import dev.fandroid.protocol.music.MusicCapabilities;
import dev.fandroid.protocol.music.MusicCommand;
import dev.fandroid.protocol.music.MusicConnector;
import dev.fandroid.protocol.music.PlaybackSnapshot;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
    private final CopyOnWriteArrayList<Consumer<SpicetifyLyricsPayload>> lyricsListeners = new CopyOnWriteArrayList<>();
    private volatile PlaybackSnapshot latest = PlaybackSnapshot.unavailable();
    private volatile SpicetifyLyricsPayload latestLyrics;
    private volatile String pairingCode;
    private volatile int pairingPort;
    private volatile boolean spicetifyConnected;
    private volatile int extensionProtocolVersion;
    private volatile String extensionVersion = "";
    private volatile boolean extensionArtwork;
    private volatile boolean extensionSpicyCache;
    private volatile boolean extensionRawTtml;
    private volatile String spicyLyricsStatus = "";
    private volatile String spicyLyricsDetail = "";
    private volatile String spicyLyricsUri = "";
    private volatile boolean bridgeAlive = true;
    private volatile String startupError;
    private volatile String source = "AUTOMATIC";

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
                if (node.path("type").asText().equals("pairing_code")) {
                    pairingCode = node.path("code").asText();
                    pairingPort = node.path("port").asInt();
                    FanDroidMod.LOGGER.info("FanDroid Spicetify bridge is listening on 127.0.0.1:{}", pairingPort);
                    continue;
                }
                if (node.path("type").asText().equals("bridge_status")) {
                    spicetifyConnected = node.path("spicetifyConnected").asBoolean(false);
                    if (!spicetifyConnected) {
                        extensionProtocolVersion = 0;
                        extensionVersion = "";
                    }
                    continue;
                }
                if (node.path("type").asText().equals("spicetify_client")) {
                    var client = node.path("client");
                    extensionProtocolVersion = client.path("protocolVersion").asInt(0);
                    extensionVersion = client.path("extensionVersion").asText("");
                    var capabilities = client.path("capabilities");
                    extensionArtwork = capabilities.path("artwork").asBoolean(false);
                    extensionSpicyCache = capabilities.path("spicyCache").asBoolean(false);
                    extensionRawTtml = capabilities.path("rawTtml").asBoolean(false);
                    FanDroidMod.LOGGER.info("Connected to FanDroid Spicetify extension v{} (protocol {}, rawTtml={}, spicyCache={}, artwork={})",
                            extensionVersion, extensionProtocolVersion, extensionRawTtml,
                            extensionSpicyCache, extensionArtwork);
                    continue;
                }
                if (node.path("type").asText().equals("lyrics_status")) {
                    var status = node.path("lyricsStatus");
                    spicyLyricsUri = status.path("uri").asText("");
                    spicyLyricsStatus = status.path("status").asText("");
                    spicyLyricsDetail = status.path("detail").asText("");
                    FanDroidMod.LOGGER.info("Spicy Lyrics {} for {}{}", spicyLyricsStatus, spicyLyricsUri,
                            spicyLyricsDetail.isBlank() ? "" : " (" + spicyLyricsDetail + ")");
                    continue;
                }
                if (node.path("type").asText().equals("lyrics_payload")) {
                    var lyrics = node.path("lyrics");
                    java.util.List<String> songwriters = new java.util.ArrayList<>();
                    if (lyrics.path("songwriters").isArray()) {
                        lyrics.path("songwriters").forEach(value -> {
                            if (value.isTextual() && !value.asText().isBlank()) {
                                songwriters.add(value.asText());
                            }
                        });
                    }
                    SpicetifyLyricsPayload payload = new SpicetifyLyricsPayload(
                            lyrics.path("uri").asText(""), lyrics.path("format").asText(""),
                            lyrics.path("payload").asText(""), lyrics.path("source").asText("Spicy Lyrics"),
                            songwriters, lyrics.path("providerCode").asText(""),
                            lyrics.path("communityProvided").asBoolean(false),
                            lyrics.path("makerUsername").asText(""), lyrics.path("makerAvatar").asText(""),
                            lyrics.path("uploaderUsername").asText(""), lyrics.path("uploaderAvatar").asText(""));
                    int maxLength = payload.format().equals("ttml") ? 4 * 1024 * 1024 : 2 * 1024 * 1024;
                    if (payload.uri().startsWith("spotify:track:") && payload.payload().length() <= maxLength) {
                        latestLyrics = payload;
                        for (Consumer<SpicetifyLyricsPayload> listener : lyricsListeners) listener.accept(payload);
                    }
                    continue;
                }
                if (!node.path("type").asText().equals("snapshot")) continue;
                latest = ProtocolCodec.mapper().treeToValue(node.path("snapshot"), PlaybackSnapshot.class);
                for (Consumer<PlaybackSnapshot> listener : listeners) listener.accept(latest);
            }
        } catch (Exception e) {
            if (process.isAlive()) FanDroidMod.LOGGER.warn("Media bridge stopped producing snapshots", e);
        } finally {
            bridgeAlive = false;
            spicetifyConnected = false;
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
    public boolean spicetifyConnected() { return spicetifyConnected; }
    public int extensionProtocolVersion() { return extensionProtocolVersion; }
    public String extensionVersion() { return extensionVersion; }
    public boolean extensionOutdated() { return spicetifyConnected && extensionProtocolVersion < 2; }
    public boolean extensionArtwork() { return extensionArtwork; }
    public boolean extensionSpicyCache() { return extensionSpicyCache; }
    public boolean extensionRawTtml() { return extensionRawTtml; }
    public String spicyLyricsStatus() { return spicyLyricsStatus; }
    public String spicyLyricsDetail() { return spicyLyricsDetail; }
    public String spicyLyricsUri() { return spicyLyricsUri; }
    public boolean bridgeAlive() { return bridgeAlive && process.isAlive(); }
    public String startupError() { return startupError; }

    public void setSource(String source) {
        String next = source == null || source.isBlank() ? "AUTOMATIC" : source;
        if (next.equals(this.source)) return;
        this.source = next;
        send(java.util.Map.of("type", "source", "source", next));
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

    public AutoCloseable subscribeLyrics(Consumer<SpicetifyLyricsPayload> listener) {
        lyricsListeners.add(listener);
        if (latestLyrics != null) listener.accept(latestLyrics);
        return () -> lyricsListeners.remove(listener);
    }

    @Override
    public void close() {
        try { writer.close(); } catch (Exception ignored) { }
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
