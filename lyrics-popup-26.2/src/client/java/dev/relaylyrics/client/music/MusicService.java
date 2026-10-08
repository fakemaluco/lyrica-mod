package dev.relaylyrics.client.music;

import dev.relaylyrics.FanDroidMod;
import dev.relaylyrics.client.ui.LyricsOverlay;
import dev.relaylyrics.client.ClientPreferences;
import dev.relaylyrics.client.MusicSource;
import dev.relaylyrics.protocol.lyrics.LyricsDocument;
import dev.relaylyrics.protocol.lyrics.LyricsMetadata;
import dev.relaylyrics.protocol.lyrics.LyricsSource;
import dev.relaylyrics.protocol.lyrics.LyricsSyncMode;
import dev.relaylyrics.protocol.lyrics.SecureTtmlParser;
import dev.relaylyrics.protocol.lyrics.SpicyLyricsParser;
import dev.relaylyrics.protocol.music.MusicAction;
import dev.relaylyrics.protocol.music.MusicCommand;
import dev.relaylyrics.protocol.music.MusicConnector;
import dev.relaylyrics.protocol.music.PlaybackSnapshot;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public final class MusicService implements AutoCloseable {
    private final MusicConnector connector;
    private final SpicyLyricsApiClient spicyApi = new SpicyLyricsApiClient();
    private final SecureTtmlParser ttmlParser = new SecureTtmlParser();
    private final SpicyLyricsParser spicyParser = new SpicyLyricsParser();
    private final Path importsFolder = FabricLoader.getInstance().getConfigDir().resolve("relaylyrics/lyrics/imports");
    private final ImportedLyricsClient importedLyrics = new ImportedLyricsClient(importsFolder);
    private final Path spicyCache = FabricLoader.getInstance().getConfigDir().resolve("relaylyrics/lyrics/spicy");
    private final CoverArtTexture artwork = new CoverArtTexture();
    private final MusicPlaybackClock clock = new MusicPlaybackClock();
    private final AtomicReference<PlaybackSnapshot> snapshot = new AtomicReference<>(PlaybackSnapshot.unavailable());
    private final LyricsOverlay overlay;
    private final ClientPreferences preferences;
    private final SpotifyAccountClient spotifyAccount;
    private volatile MusicSource lastSyncedSource;
    private volatile String lookupKey;
    private volatile String lookupTrackKey;
    private volatile long lookupCredentialGeneration;
    private volatile long nextLookupRetryMillis;
    private long lookupGeneration;
    private volatile String importsPendingKey;
    private volatile String importedLyricsKey;
    private volatile String spicyLyricsKey;
    private volatile List<ImportedLyricsClient.Candidate> importCandidates = List.of();
    private volatile String lyricsStatus = "Start playback to find lyrics";
    private volatile String artworkTrackKey;
    private volatile String artworkUrl;

    public MusicService(LyricsOverlay overlay, ClientPreferences preferences) {
        this.overlay = overlay;
        this.preferences = preferences;
        String executable = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "relaylyrics-media-bridge.exe" : "relaylyrics-media-bridge";
        Path directory = FabricLoader.getInstance().getConfigDir().resolve("relaylyrics/bin");
        Path path = directory.resolve(executable);
        try {
            Path bundled = BundledMediaBridge.install(directory);
            if (bundled != null) path = bundled;
        } catch (Exception exception) {
            FanDroidMod.LOGGER.warn("Could not install the bundled media bridge", exception);
        }
        Path hotUpdate = directory.resolve("relaylyrics-media-bridge-next.exe");
        Path newestUpdate = directory.resolve("relaylyrics-media-bridge-update.exe");
        Path musicTwoUpdate = directory.resolve("relaylyrics-media-bridge-v2.exe");
        if (System.getProperty("os.name", "").toLowerCase().contains("win")
                && Files.isRegularFile(musicTwoUpdate)) {
            path = musicTwoUpdate;
        } else if (System.getProperty("os.name", "").toLowerCase().contains("win")
                && Files.isRegularFile(newestUpdate)) {
            path = newestUpdate;
        } else if (System.getProperty("os.name", "").toLowerCase().contains("win")
                && Files.isRegularFile(hotUpdate)) {
            path = hotUpdate;
        }
        connector = MediaBridgeConnector.start(path);
        spotifyAccount = new SpotifyAccountClient(preferences, this::update,
                () -> !(connector instanceof MediaBridgeConnector bridge)
                        || (!bridge.spicetifyConnected() && !bridge.youtubeConnected()));
        connector.subscribe(value -> {
            if (!spotifyAccount.shouldOverrideBridge()) update(value);
        });
        if (connector instanceof MediaBridgeConnector bridge) bridge.subscribeLyrics(this::acceptSpicyLyrics);
        syncMusicSource();
        refreshIdleArtworkFallback();
        overlay.setArtworkTexture(artwork);
    }

    public MusicService(LyricsOverlay overlay) {
        this(overlay, ClientPreferences.load());
    }

    public void syncMusicSource() {
        MusicSource selected = preferences.getMusicSource();
        if (connector instanceof MediaBridgeConnector bridge) {
            bridge.setSource(selected == MusicSource.SPOTIFY_ACCOUNT
                    ? MusicSource.WINDOWS_MEDIA.name() : selected.name());
        }
        if (selected != lastSyncedSource) {
            lastSyncedSource = selected;
            spotifyAccount.requestPlaybackRefresh();
        }
    }

    public boolean reloadReactiveAudioOutput() {
        if (!(connector instanceof MediaBridgeConnector bridge) || !bridge.bridgeAlive()) return false;
        bridge.reloadAudioOutput();
        return true;
    }

    public CompletableFuture<java.net.URI> connectSpotifyAccount(String clientId) {
        return spotifyAccount.connect(clientId).whenComplete((ignored, error) -> refreshIdleArtworkFallback());
    }
    public void disconnectSpotifyAccount() {
        spotifyAccount.disconnect();
        refreshIdleArtworkFallback();
    }
    public boolean spotifyAccountConnected() { return spotifyAccount.connected(); }
    /** The selected account may only be used for matching, not for playback. */
    public boolean spotifyPlaybackActive() { return spotifyAccount.shouldOverrideBridge(); }
    public String spotifyTrackMatchStatus() { return spotifyAccount.matchStatus(); }
    public String spotifyAccountStatus() { return spotifyAccount.status(); }

    private void update(PlaybackSnapshot value) {
        snapshot.set(value);
        clock.update(value);
        String currentArtworkKey = value.spotifyUri() == null || value.spotifyUri().isBlank()
                ? String.valueOf(value.title()) + '\n' + value.artist() : value.spotifyUri();
        boolean changedTrack = !currentArtworkKey.equals(artworkTrackKey);
        if (changedTrack) artworkTrackKey = currentArtworkKey;
        String nextArtworkUrl = value.artworkUrl() == null || value.artworkUrl().isBlank()
                ? null : value.artworkUrl().strip();
        if (changedTrack && nextArtworkUrl != null && nextArtworkUrl.equals(artworkUrl)) {
            artwork.transitionCurrent();
        }
        String previousArtworkUrl = artworkUrl;
        artworkUrl = nextArtworkUrl;
        if (nextArtworkUrl != null) {
            refreshIdleArtworkFallback();
            artwork.update(nextArtworkUrl);
        } else if (changedTrack || previousArtworkUrl != null) {
            artwork.update(null);
            if (value.title() == null || value.title().equals("Nothing playing")) {
                refreshIdleArtworkFallback();
            } else {
                refreshIdleArtworkFallback();
            }
        }
        overlay.setPlayback(value);
        if (value.title() == null || value.title().equals("Nothing playing")) {
            lookupKey = null;
            lookupTrackKey = null;
            lookupCredentialGeneration = spotifyAccount.credentialGeneration();
            nextLookupRetryMillis = 0;
            importsPendingKey = null;
            importedLyricsKey = null;
            spicyLyricsKey = null;
            importCandidates = List.of();
            overlay.clearLyrics();
            lyricsStatus = "Start playback to find lyrics";
            return;
        }
        if (isSpotifyDjSpeaking(value)) {
            String djKey = "spotify-dj\n" + value.title() + '\n' + value.artist();
            if (!djKey.equals(lookupTrackKey)) {
                lookupTrackKey = djKey;
                lookupKey = djKey;
                importsPendingKey = null;
                importedLyricsKey = null;
                spicyLyricsKey = null;
                importCandidates = List.of();
                overlay.clearLyrics();
                overlay.markUnavailable("Spotify DJ is talking. Lyrics return with the next song.");
                lyricsStatus = "Spotify DJ voice segment - no lyrics";
            }
            return;
        }
        String trackKey = (value.spotifyUri() == null ? "" : value.spotifyUri()) + '\n'
                + value.title() + '\n' + value.artist() + '\n' + value.album();
        long credentialGeneration = spotifyAccount.credentialGeneration();
        if (trackKey.equals(lookupTrackKey) && lookupCredentialGeneration == credentialGeneration
                && (nextLookupRetryMillis == 0 || System.currentTimeMillis() < nextLookupRetryMillis)) return;
        lookupTrackKey = trackKey;
        lookupCredentialGeneration = credentialGeneration;
        nextLookupRetryMillis = 0;
        String key = trackKey + "\nlookup=" + ++lookupGeneration;
        lookupKey = key;
        importsPendingKey = key;
        importedLyricsKey = null;
        spicyLyricsKey = null;
        importCandidates = List.of();
        overlay.beginLookup();
        lyricsStatus = "Finding lyrics...";
        refreshIdleArtworkFallback();

        String spotifyId = SpicyLyricsApiClient.spotifyTrackId(value.spotifyUri());
        FanDroidMod.LOGGER.info("Looking up synced lyrics for '{}' by '{}'{}", value.title(), value.artist(),
                spotifyId == null ? "" : " (Spotify " + spotifyId + ")");
        CompletableFuture.runAsync(() -> beginSourceLookup(value, key, spotifyId));
    }

    private void beginSourceLookup(PlaybackSnapshot value, String key, String spotifyId) {
        ImportedLyricsClient.SearchResult imported = importedLyrics.find(value);
        if (!key.equals(lookupKey)) return;
        importsPendingKey = null;
        importCandidates = imported.candidates();
        if (imported.match().isPresent()) {
            applyImported(imported.match().get(), key);
            return;
        }
        if (!importCandidates.isEmpty()) {
            lyricsStatus = "Choose an imported TTML match";
        } else {
            lyricsStatus = "Finding lyrics...";
        }
        List<String> resolvedIds = spotifyAccount.resolveTrackIds(value);
        if (!key.equals(lookupKey)) return;
        if (resolvedIds.isEmpty() && spotifyAccount.matchStatus().startsWith("Spotify search HTTP ")) {
            nextLookupRetryMillis = System.currentTimeMillis() + 60_000;
        }
        String resolvedId = resolvedIds.isEmpty() ? null : resolvedIds.getFirst();
        if (spotifyId == null && resolvedId != null) {
            FanDroidMod.LOGGER.info("Resolved music metadata to Spotify track {}", resolvedId);
        }
        String resolvedUri = resolvedId == null ? value.spotifyUri() : "spotify:track:" + resolvedId;
        if (resolvedId != null && prefersSpicetifyLyrics()) {
            loadCachedSpicy(resolvedUri, key, resolvedId);
        }
        beginNetworkLookup(key, resolvedIds, 0);
    }

    private void beginNetworkLookup(String key, List<String> spotifyIds, int index) {
        if (!stillNeedsFallback(key)) return;
        if (index >= spotifyIds.size()) {
            markUnavailable(spotifyIds.isEmpty() ? "Spotify track ID unavailable" : "No lyrics in Spicy Lyrics");
            return;
        }
        lyricsStatus = "Searching Spicy Lyrics...";
        String spotifyId = spotifyIds.get(index);
        spicyApi.find(spotifyId).thenAccept(result -> {
            if (!stillNeedsFallback(key)) return;
            if (result.payload().isPresent()) {
                parseAndApplySpicy(new SpicetifyLyricsPayload("spotify:track:" + spotifyId, "spicy-json",
                        new String(result.payload().get(), StandardCharsets.UTF_8), "Spicy Lyrics API"), key, true);
            } else if (result.status() == SpicyLyricsApiClient.Status.NOT_FOUND
                    && index + 1 < spotifyIds.size()) {
                beginNetworkLookup(key, spotifyIds, index + 1);
            } else if (result.status() == SpicyLyricsApiClient.Status.FAILED
                    || result.status() == SpicyLyricsApiClient.Status.MISSING_KEY) {
                if (result.message().contains("rate limit") || result.message().contains("connection failed")
                        || result.message().contains("HTTP 5")) {
                    nextLookupRetryMillis = System.currentTimeMillis() + 60_000;
                }
                markLookupError(result.message());
            } else {
                markUnavailable(result.message());
            }
        });
    }

    private static boolean isSpotifyDjSpeaking(PlaybackSnapshot value) {
        String title = normalizedDjField(value.title());
        String artist = normalizedDjField(value.artist());
        String album = normalizedDjField(value.album());
        String uri = value.spotifyUri() == null ? "" : value.spotifyUri().toLowerCase(Locale.ROOT);
        boolean djTitle = title.equals("dj") || title.equals("dj x") || title.equals("spotify dj")
                || title.equals("ai dj") || title.equals("x the dj");
        boolean djArtist = artist.equals("dj") || artist.equals("dj x") || artist.equals("spotify dj");
        boolean spotifyIdentity = artist.isBlank() || artist.equals("spotify") || djArtist
                || album.equals("spotify dj") || album.equals("dj");
        boolean voiceLikeUri = uri.isBlank() || !uri.startsWith("spotify:track:");
        return djTitle && spotifyIdentity || djArtist && voiceLikeUri
                && (title.isBlank() || title.contains("dj") || title.contains("welcome"));
    }

    private static String normalizedDjField(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    public PlaybackSnapshot snapshot() { return snapshot.get(); }
    public long positionMillis() { return clock.positionMillis(); }
    public String pairingCode() {
        return connector instanceof MediaBridgeConnector bridge ? bridge.pairingCode() : null;
    }
    public int pairingPort() {
        return connector instanceof MediaBridgeConnector bridge ? bridge.pairingPort() : 0;
    }
    public boolean spicetifyConnected() {
        return connector instanceof MediaBridgeConnector bridge && bridge.spicetifyConnected();
    }
    public boolean youtubeMusicConnected() {
        return connector instanceof MediaBridgeConnector bridge && bridge.youtubeConnected();
    }
    public boolean spicetifyExtensionOutdated() {
        return connector instanceof MediaBridgeConnector bridge && bridge.extensionOutdated();
    }
    public String spicetifyExtensionVersion() {
        return connector instanceof MediaBridgeConnector bridge ? bridge.extensionVersion() : "";
    }
    public String spicyLyricsDiagnostic() {
        if (!(connector instanceof MediaBridgeConnector bridge)) return "";
        PlaybackSnapshot current = snapshot();
        if (current.spotifyUri() == null || !current.spotifyUri().equals(bridge.spicyLyricsUri())) return "";
        String status = bridge.spicyLyricsStatus();
        return bridge.spicyLyricsDetail().isBlank() ? status : status + " - " + bridge.spicyLyricsDetail();
    }
    public boolean bridgeAlive() {
        return connector instanceof MediaBridgeConnector bridge && bridge.bridgeAlive();
    }
    public String bridgeError() {
        return MediaBridgeConnector.startupError(connector);
    }
    public String lyricsStatus() { return lyricsStatus; }
    public String lyricsProvider() {
        return overlay.document().lines().isEmpty() ? "" : overlay.document().metadata().provider();
    }
    public String lyricsStatusDetail() {
        String provider = lyricsProvider();
        return "Lyrics ready".equals(lyricsStatus) && !provider.isBlank()
                ? lyricsStatus + " — " + provider : lyricsStatus;
    }
    public boolean drawArtwork(GuiGraphicsExtractor context, int x, int y, int width, int height) {
        overlay.setArtworkAccent(artwork.accentColor());
        return artwork.draw(context, x, y, width, height);
    }
    public boolean drawArtwork(GuiGraphicsExtractor context, int x, int y, int width, int height,
                               int cornerRadius) {
        overlay.setArtworkAccent(artwork.accentColor());
        return artwork.draw(context, x, y, width, height, cornerRadius);
    }
    public boolean drawArtworkTransition(GuiGraphicsExtractor context, int x, int y, int width, int height) {
        return drawArtworkTransition(context, x, y, width, height, 0);
    }
    public boolean drawArtworkTransition(GuiGraphicsExtractor context, int x, int y, int width, int height,
                                         int cornerRadius) {
        overlay.setArtworkAccent(artwork.accentColor());
        return artwork.drawTransition(context, x, y, width, height, cornerRadius);
    }
    public boolean drawArtworkBackdrop(GuiGraphicsExtractor context, int x, int y, int width, int height, boolean enhanced) {
        overlay.setArtworkAccent(artwork.accentColor());
        return artwork.drawBackdrop(context, x, y, width, height, enhanced ? 2 : 0);
    }
    public boolean drawArtworkBackdrop(GuiGraphicsExtractor context, int x, int y, int width, int height, int blurStrength) {
        overlay.setArtworkAccent(artwork.accentColor());
        return artwork.drawBackdrop(context, x, y, width, height, blurStrength);
    }
    public boolean drawArtworkBackdrop(GuiGraphicsExtractor context, int x, int y, int width, int height,
                                       int blurStrength, double speedMultiplier) {
        return drawArtworkBackdrop(context, x, y, width, height, blurStrength, speedMultiplier, 1.0);
    }
    public boolean drawArtworkBackdrop(GuiGraphicsExtractor context, int x, int y, int width, int height,
                                       int blurStrength, double speedMultiplier, double opacity) {
        overlay.setArtworkAccent(artwork.accentColor());
        return artwork.drawBackdrop(context, x, y, width, height, blurStrength, speedMultiplier, opacity);
    }
    public int artworkAccent() { return artwork.accentColor(); }
    public String artworkStatus() { return artwork.status(); }
    public void retryLyrics() {
        spotifyAccount.requestPlaybackRefresh();
        PlaybackSnapshot current = snapshot();
        lookupTrackKey = null;
        lookupKey = null;
        update(current);
    }
    public List<ImportedLyricsClient.Candidate> importCandidates() { return importCandidates; }
    public Path importsFolder() { return importsFolder; }
    public void reloadImports() { retryLyrics(); }
    public void selectImportCandidate(int index) {
        List<ImportedLyricsClient.Candidate> values = importCandidates;
        if (index < 0 || index >= values.size()) return;
        importedLyrics.load(values.get(index)).ifPresent(match -> applyImported(match, lookupKey));
    }

    private void markUnavailable(String message) {
        lyricsStatus = message;
        refreshIdleArtworkFallback();
        overlay.markUnavailable(message);
        FanDroidMod.LOGGER.info(message);
    }

    private void markLookupError(String message) {
        lyricsStatus = message;
        artwork.setFallback(CoverArtTexture.Fallback.ERROR);
        overlay.markUnavailable(message);
        FanDroidMod.LOGGER.warn(message);
    }

    private void refreshIdleArtworkFallback() {
        boolean connected = spotifyAccount.connected()
                || connector instanceof MediaBridgeConnector bridge
                && (bridge.spicetifyConnected() || bridge.youtubeConnected());
        artwork.setFallback(connected ? CoverArtTexture.Fallback.SONG_NOT_FOUND
                : CoverArtTexture.Fallback.CONNECT_ACCOUNT);
    }

    private boolean stillNeedsFallback(String key) {
        return key != null && key.equals(lookupKey) && !key.equals(importedLyricsKey)
                && !key.equals(spicyLyricsKey);
    }

    private void acceptSpicyLyrics(SpicetifyLyricsPayload payload) {
        if (!prefersSpicetifyLyrics()) return;
        PlaybackSnapshot current = snapshot();
        String key = lookupKey;
        if (key == null || current.spotifyUri() == null || !current.spotifyUri().equals(payload.uri())) return;
        if (key.equals(importsPendingKey) || key.equals(importedLyricsKey)) return;
        CompletableFuture.runAsync(() -> parseAndApplySpicy(payload, key, true));
    }

    private boolean prefersSpicetifyLyrics() {
        MusicSource selected = preferences.getMusicSource();
        return selected == MusicSource.AUTOMATIC || selected == MusicSource.SPICETIFY_BRIDGE
                || selected == MusicSource.YOUTUBE_MUSIC;
    }

    private void loadCachedSpicy(String uri, String key, String trackId) {
        CompletableFuture.runAsync(() -> {
            for (String format : List.of("ttml", "spicy-json")) {
                Path file = spicyCache.resolve(trackId + (format.equals("ttml") ? ".ttml" : ".json"));
                try {
                    if (!Files.isRegularFile(file)) continue;
                    long maximum = format.equals("ttml") ? 4L * 1024 * 1024 : 2L * 1024 * 1024;
                    if (Files.size(file) > maximum) continue;
                    Path writersFile = spicyCache.resolve(trackId + ".writers");
                    List<String> songwriters = Files.isRegularFile(writersFile) && Files.size(writersFile) <= 4_096
                            ? List.of(Files.readString(writersFile, StandardCharsets.UTF_8).strip()) : List.of();
                    parseAndApplySpicy(new SpicetifyLyricsPayload(uri, format,
                            Files.readString(file, StandardCharsets.UTF_8), "Spicy Lyrics local cache",
                            songwriters), key, false);
                    if (key.equals(spicyLyricsKey)) return;
                } catch (Exception error) {
                    FanDroidMod.LOGGER.debug("Could not read cached Spicy Lyrics for {}", trackId, error);
                }
            }
        });
    }

    private void parseAndApplySpicy(SpicetifyLyricsPayload payload, String key, boolean persist) {
        if (!key.equals(lookupKey)) return;
        try {
            byte[] bytes = payload.payload().getBytes(StandardCharsets.UTF_8);
            LyricsDocument document;
            boolean wordSynced;
            if (payload.format().equals("ttml")) {
                document = ttmlParser.parse(bytes);
                wordSynced = hasWordTiming(document);
            } else if (payload.format().equals("spicy-json")) {
                SpicyLyricsParser.ParsedLyrics parsed = spicyParser.parse(bytes);
                document = parsed.document();
                wordSynced = parsed.wordSynced();
            } else {
                return;
            }
            if (!key.equals(lookupKey)) return;
            spicyLyricsKey = key;
            String precision = wordSynced ? "WORD SYNC" : "LINE SYNC";
            LyricsSource source = payload.format().equals("ttml") ? LyricsSource.SPICY_TTML : LyricsSource.SPICY_CACHE;
            LyricsMetadata previous = document.metadata();
            String suppliedSongwriters = String.join(", ", payload.songwriters());
            String songwriters = suppliedSongwriters.isBlank()
                    ? previous.songwriters() : suppliedSongwriters;
            String provider = providerLabel(payload.providerCode());
            if (provider.isBlank()) provider = previous.provider().isBlank()
                    ? LyricsMetadata.providerFor(source) : previous.provider();
            String makerUsername = firstNonBlank(payload.makerUsername(), previous.makerUsername());
            String makerAvatar = firstNonBlank(payload.makerAvatar(), previous.makerAvatar());
            String uploaderUsername = firstNonBlank(payload.uploaderUsername(), previous.uploaderUsername());
            String uploaderAvatar = firstNonBlank(payload.uploaderAvatar(), previous.uploaderAvatar());
            document = document.withMetadata(new LyricsMetadata(source,
                    wordSynced ? LyricsSyncMode.WORD : LyricsSyncMode.LINE,
                    SpicyLyricsApiClient.spotifyTrackId(payload.uri()), previous.title(), previous.artist(), previous.album(),
                    songwriters, provider, payload.communityProvided() || previous.communityProvided(),
                    makerUsername, makerAvatar, uploaderUsername, uploaderAvatar));
            lyricsStatus = "Lyrics ready";
            overlay.setLyrics(document, "LYRICS");
            FanDroidMod.LOGGER.info("Loaded {} lyrics from {} ({} lines)", precision,
                    payload.source(), document.lines().size());
            if (persist) persistSpicy(payload, songwriters);
        } catch (Exception error) {
            artwork.setFallback(CoverArtTexture.Fallback.ERROR);
            lyricsStatus = "Could not read synchronized lyrics";
            overlay.markUnavailable(lyricsStatus);
            FanDroidMod.LOGGER.warn("Could not parse lyrics supplied by Spicy Lyrics", error);
        }
    }

    private void persistSpicy(SpicetifyLyricsPayload payload, String songwriters) {
        String trackId = SpicyLyricsApiClient.spotifyTrackId(payload.uri());
        if (trackId == null) return;
        try {
            Files.createDirectories(spicyCache);
            Path destination = spicyCache.resolve(trackId + (payload.format().equals("ttml") ? ".ttml" : ".json"));
            Files.writeString(destination, payload.payload(), StandardCharsets.UTF_8);
            if (songwriters != null && !songwriters.isBlank() && songwriters.length() <= 4_096) {
                Files.writeString(spicyCache.resolve(trackId + ".writers"), songwriters.strip(),
                        StandardCharsets.UTF_8);
            }
        } catch (Exception error) {
            FanDroidMod.LOGGER.debug("Could not cache Spicy Lyrics for {}", trackId, error);
        }
    }

    private static boolean hasWordTiming(LyricsDocument document) {
        return document.lines().stream().anyMatch(line -> line.words().size() > 1
                && line.words().stream().map(word -> word.startMillis()).distinct().count() > 1);
    }

    private static String providerLabel(String code) {
        if (code == null) return "";
        return switch (code.strip().toLowerCase(Locale.ROOT)) {
            case "spt", "spotify" -> "Spotify";
            case "aml", "apple_music" -> "Apple Music";
            case "spl", "spicy_lyrics" -> "Spicy Lyrics";
            case "ldb" -> "Local DB";
            default -> "";
        };
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback == null ? "" : fallback;
    }

    private void applyImported(ImportedLyricsClient.Match match, String key) {
        if (key == null || !key.equals(lookupKey)) return;
        importedLyricsKey = key;
        spicyLyricsKey = null;
        importCandidates = List.of();
        boolean wordSynced = hasWordTiming(match.document());
        String precision = wordSynced ? "WORD SYNC" : "LINE SYNC";
        lyricsStatus = "Lyrics ready";
        overlay.setLyrics(match.document(), "LYRICS");
        FanDroidMod.LOGGER.info("Loaded imported TTML {} for '{}'", match.path().getFileName(), snapshot().title());
    }
    public void command(MusicAction action) {
        routeCommand(MusicCommand.simple(action));
    }
    public void command(MusicAction action, double value) {
        if (action == MusicAction.SEEK) {
            long position = Math.round(value);
            clock.seekTo(position);
            overlay.seekTo(position);
        }
        routeCommand(new MusicCommand(action, value));
    }

    private void routeCommand(MusicCommand command) {
        syncMusicSource();
        if (spotifyAccount.shouldOverrideBridge()) {
            spotifyAccount.command(command);
        } else {
            connector.command(command);
        }
    }

    @Override
    public void close() {
        artwork.close();
        spotifyAccount.close();
        connector.close();
    }
}
