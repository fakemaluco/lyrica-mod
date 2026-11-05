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
import dev.lyrica.engine.DesktopMotionArtworkEngine;
import dev.lyrica.engine.DesktopArtistArtworkEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public final class MusicService implements AutoCloseable {
    private final MusicConnector connector;
    private final DesktopMotionArtworkEngine motionEngine;
    private final DesktopArtistArtworkEngine artistEngine;
    private CompletableFuture<String> artistLookup;
    private String artistTrackKey;
    private long artistGeneration;
    private volatile CompletableFuture<DesktopMotionArtworkEngine.Result> motionLookup;
    private String motionTrackKey;
    private static final long[] MOTION_RETRY_DELAYS = {15_000, 45_000, 120_000, 120_000};
    private long motionNextRetryMillis;
    private int motionRetryCount;
    private boolean motionMissing, motionForceRefresh;
    private String motionCanvasUrl, motionCoverUrl;
    private long motionGeneration;
    private volatile String motionDiagnostics = "";
    private MultiSourceLyricsClient lyricsClient;
    private volatile String sourceDiagnostics = "";
    private volatile boolean closed;
    private volatile CompletableFuture<MultiSourceLyricsClient.Result> remoteLookup;
    private final LinkedHashMap<String, MultiSourceLyricsClient.Result> modelSyncByTrack = new LinkedHashMap<>();
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
    private final GeminiShaderSession geminiShaders;
    private final GeminiLyricsSession geminiLyrics;
    private final LyricsTranslationSession translations;
    private final ReleaseSupport support;
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
    private volatile String preferredLyricsKey;
    private String lrcRedWordKey;
    private volatile List<ImportedLyricsClient.Candidate> importCandidates = List.of();
    private volatile String lyricsStatus = "Start playback to find lyrics";
    private volatile String artworkTrackKey;
    private volatile String artworkUrl;

    public MusicService(LyricsOverlay overlay, ClientPreferences preferences) {
        this.overlay = overlay;
        this.preferences = preferences;
        translations = new LyricsTranslationSession(FabricLoader.getInstance().getConfigDir().resolve("lyrica/translation"), preferences);
        support = new ReleaseSupport(preferences);
        overlay.setTranslationRequest(translations::request);
        geminiShaders = new GeminiShaderSession(preferences, FabricLoader.getInstance().getConfigDir().resolve("lyrica/gemini-shaders"),
                task -> net.minecraft.client.Minecraft.getInstance().execute(task));
        geminiLyrics = new GeminiLyricsSession(preferences, FabricLoader.getInstance().getConfigDir().resolve("lyrica/gemini-lyrics"));
        if (preferences.isLyricsRomanization()) LyricsRomanizer.warmup();
        lyricsClient = new MultiSourceLyricsClient(importsFolder.resolveSibling("sources"), SpicyLyricsApiClient.configuredKey(preferences.getSpicyLyricsApiKey()));
        motionEngine = new DesktopMotionArtworkEngine(importsFolder.resolveSibling("motion").toFile());
        artistEngine = new DesktopArtistArtworkEngine(importsFolder.resolveSibling("artist-artwork").toFile());
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
        connector = MediaBridgeConnector.start(path);
        spotifyAccount = new SpotifyAccountClient(preferences, this::update,
                () -> !hasPlayingSystemMedia());
        connector.subscribe(value -> {
            if (!spotifyAccount.shouldOverrideBridge()) update(value);
        });
        if (connector instanceof MediaBridgeConnector bridge) {
            bridge.subscribeLyrics(this::acceptSpicyLyrics);
            bridge.subscribeAudio(overlay::setIslandAudioLevels);
            overlay.setIslandAudioDemand(bridge::setAudioLevelsEnabled);
        }
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

    private synchronized void update(PlaybackSnapshot value) {
        if (closed) return;
        snapshot.set(value);
        clock.update(value);
        updateMotionArtwork(value);
        updateArtistArtwork(value);
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
            if (remoteLookup != null) remoteLookup.cancel(true);
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
        CompletableFuture<?> previousLookup = remoteLookup;
        if (previousLookup != null) previousLookup.cancel(true);
        sourceDiagnostics = "";
        overlay.beginLookup();
        lyricsStatus = "Finding lyrics...";
        refreshIdleArtworkFallback();

        String spotifyId = SpicyLyricsApiClient.spotifyTrackId(value.spotifyUri());
        FanDroidMod.LOGGER.info("Looking up synced lyrics for '{}' by '{}'{}", value.title(), value.artist(),
                spotifyId == null ? "" : " (Spotify " + spotifyId + ")");
        CompletableFuture.runAsync(() -> beginSourceLookup(value, key, spotifyId));
    }

    /** Settings changes reuse the current song; no playback or lyric lookup is restarted. */
    public synchronized void refreshMotionArtwork() {
        motionForceRefresh = true;
        motionTrackKey = null;
        updateMotionArtwork(snapshot());
    }

    public synchronized void refreshBackgroundArtwork() {
        artistTrackKey = null; updateArtistArtwork(snapshot());
    }
    private void updateArtistArtwork(PlaybackSnapshot track) {
        boolean wanted = preferences.isLyricsDynamicBackground() && preferences.isLyricsArtistBackground();
        boolean meaningful = track.title() != null && !track.title().isBlank() && !track.title().equals("Nothing playing");
        String key = wanted && meaningful ? String.join("\n", String.valueOf(track.spotifyUri()), track.title(), String.valueOf(track.artist())) : "off";
        if (key.equals(artistTrackKey)) return;
        artistTrackKey = key; long token = ++artistGeneration;
        if (artistLookup != null) artistLookup.cancel(true);
        artwork.configureArtist(null);
        if (!wanted || !meaningful || closed) return;
        artistLookup = artistEngine.find(track.title(), java.util.Objects.toString(track.artist(), ""),
                java.util.Objects.toString(track.album(), ""), track.durationMillis(), SpicyLyricsApiClient.spotifyTrackId(track.spotifyUri()));
        artistLookup.thenAccept(url -> {
            synchronized (this) {
                if (!closed && token == artistGeneration) artwork.configureArtist(url);
            }
        });
    }

    public String motionArtworkStatus() {
        String decoder = artwork.motionStatus();
        return motionDiagnostics.isBlank() ? decoder : decoder + " — " + motionDiagnostics;
    }

    private synchronized void updateMotionArtwork(PlaybackSnapshot track) {
        artwork.setMotionPlaying(track.playing());
        boolean allow = preferences.isMotionVideos();
        boolean canvas = allow && preferences.isSpotifyCanvas();
        boolean cover = allow && preferences.isAnimatedCovers();
        boolean background = allow && preferences.isCanvasBackground();
        boolean island = allow && preferences.isIslandVideoCover();
        artwork.setIslandMotionEnabled(island);
        boolean meaningful = track.title() != null && !track.title().isBlank()
                && !track.title().equals("Nothing playing") && !isSpotifyDjSpeaking(track);
        String key = meaningful ? String.join("\n", String.valueOf(track.spotifyUri()), track.title(),
                String.valueOf(track.artist()), String.valueOf(track.album()), Long.toString(track.durationMillis()),
                Boolean.toString(canvas), Boolean.toString(cover), Boolean.toString(background), Boolean.toString(island),
                Boolean.toString(preferences.isPauseMotionWithMusic())) : "idle";
        boolean retry = key.equals(motionTrackKey);
        if (retry && (motionRetryCount >= 3 || motionLookup != null && !motionLookup.isDone()
                || !(motionMissing || artwork.canvasFailed() || artwork.animatedCoverFailed())
                || System.currentTimeMillis() < motionNextRetryMillis)) return;
        if (retry) {
            motionRetryCount++;
            if (artwork.canvasFailed()) motionCanvasUrl = null;
            if (artwork.animatedCoverFailed()) motionCoverUrl = null;
            artwork.configureMotion(motionCanvasUrl, motionCoverUrl, canvas, cover, background, preferences.isPauseMotionWithMusic());
        } else {
            motionRetryCount = 0; motionCanvasUrl = null; motionCoverUrl = null; motionMissing = false;
            artwork.clearMotion();
        }
        motionTrackKey = key;
        motionNextRetryMillis = Long.MAX_VALUE;
        long generation = ++motionGeneration;
        if (motionLookup != null) motionLookup.cancel(true);
        motionDiagnostics = "";
        if (!meaningful || !(canvas || cover || background || island) || closed) return;
        motionDiagnostics = "Finding Canvas and animated cover";
        boolean forceRefresh = retry || motionForceRefresh; motionForceRefresh = false;
        motionLookup = motionEngine.find(track.title(), java.util.Objects.toString(track.artist(), ""),
                java.util.Objects.toString(track.album(), ""), track.durationMillis(),
                SpicyLyricsApiClient.spotifyTrackId(track.spotifyUri()), canvas || background || island, cover || island, forceRefresh,
                result -> applyMotionArtwork(generation, result, canvas, cover, background));
        motionLookup.thenAccept(result -> {
            synchronized (this) {
                applyMotionArtwork(generation, result, canvas, cover, background);
                if (!closed && generation == motionGeneration) motionNextRetryMillis = System.currentTimeMillis() + MOTION_RETRY_DELAYS[motionRetryCount];
            }
        })
                .exceptionally(error -> {
                    synchronized (this) {
                        if (!closed && generation == motionGeneration) { motionMissing = true; motionNextRetryMillis = System.currentTimeMillis() + MOTION_RETRY_DELAYS[motionRetryCount]; motionDiagnostics = "Video lookup unavailable; retry scheduled"; }
                    }
                    return null;
                });
    }

    private synchronized void applyMotionArtwork(long generation, DesktopMotionArtworkEngine.Result result,
                                                 boolean canvas, boolean cover, boolean background) {
        if (closed || generation != motionGeneration) return;
        motionDiagnostics = result.getDiagnostics();
        if (result.getCanvasUrl() != null) motionCanvasUrl = result.getCanvasUrl();
        if (result.getAnimatedCoverUrl() != null) motionCoverUrl = result.getAnimatedCoverUrl();
        motionMissing = (canvas || background) && motionCanvasUrl == null || cover && motionCoverUrl == null;
        motionMissing |= artwork.isIslandMotionEnabled() && motionCanvasUrl==null && motionCoverUrl==null;
        artwork.configureMotion(motionCanvasUrl, motionCoverUrl, canvas, cover, background,
                preferences.isPauseMotionWithMusic());
        artwork.setMotionPlaying(snapshot().playing());
    }

    private void beginSourceLookup(PlaybackSnapshot value, String key, String spotifyId) {
        ImportedLyricsClient.SearchResult imported = importedLyrics.find(value);
        synchronized (this) {
            if (!isCurrentLookup(key)) return;
            importsPendingKey = null;
            importCandidates = imported.candidates();
            if (imported.match().isPresent()) {
                applyImported(imported.match().get(), key);
                return;
            }
            lyricsStatus = importCandidates.isEmpty() ? "Finding lyrics..." : "Choose an imported TTML match";
        }
        // Metadata-based sources run without a Spotify account or Spotify ID.
        // The engine resolves anonymous Spotify matches only for sources that need one.
        List<String> ids = spotifyId == null ? List.of() : List.of(spotifyId);
        startRemoteLookup(value, key, ids);
    }

    private synchronized void startRemoteLookup(PlaybackSnapshot value, String key, List<String> ids) {
        if (!isCurrentLookup(key)) return;
        remoteLookup = lyricsClient.find(value, ids, preferences.getLyricsSources(), preferences.getLyricsSourceOrder(), preferences.isPreferSpicyLyrics(), result -> applySourceResult(key, result));
        remoteLookup.thenAccept(result -> finishSourceLookup(value, key, result)).exceptionally(error -> {
            failSourceLookup(key);
            return null;
        });
    }

    private synchronized void finishSourceLookup(PlaybackSnapshot track, String key, MultiSourceLyricsClient.Result result) {
        if (!isCurrentLookup(key)) return;
        applySourceResult(key, result);
        if (preferences.getLyricsSources().contains("lrc_red")
                && !hasWordTiming(overlay.document()) && !result.wordTimingAvailable()
                && !result.document().map(MusicService::hasWordTiming).orElse(false)) {
            var cached = modelSyncByTrack.get(lookupTrackKey);
            if (cached != null) {
                applySourceResult(key, cached, true);
                return;
            }
            if (!modelSyncByTrack.containsKey(lookupTrackKey)) {
                modelSyncByTrack.put(lookupTrackKey, null);
                if (modelSyncByTrack.size() > 128) modelSyncByTrack.pollFirstEntry();
                remoteLookup = lyricsClient.requestLrcRedSync(track);
                remoteLookup.thenAccept(generated -> finishModelSync(key, generated));
                return;
            }
        }
        reportMissingLyrics(result);
    }

    private synchronized void finishModelSync(String key, MultiSourceLyricsClient.Result result) {
        if (!isCurrentLookup(key) || !preferences.getLyricsSources().contains("lrc_red") || hasWordTiming(overlay.document())) return;
        sourceDiagnostics += "\n" + result.diagnostics();
        if (result.document().filter(MusicService::hasWordTiming).isPresent()) {
            modelSyncByTrack.put(lookupTrackKey, result);
            applySourceResult(key, result, true);
        }
        else reportMissingLyrics(result);
    }

    private void reportMissingLyrics(MultiSourceLyricsClient.Result result) {
        if (result.document().isEmpty() && overlay.document().lines().isEmpty()) {
            markUnavailable(preferences.getLyricsSources().isEmpty()
                    ? "No lyrics sources enabled" : "No matching lyrics in enabled sources");
            nextLookupRetryMillis = System.currentTimeMillis() + 60_000;
        }
    }

    private synchronized void failSourceLookup(String key) {
        if (isCurrentLookup(key) && overlay.document().lines().isEmpty()) {
            markLookupError("Lyrics sources unavailable; retry shortly");
            nextLookupRetryMillis = System.currentTimeMillis() + 60_000;
        }
    }

    private boolean isCurrentLookup(String key) {
        return !closed && key != null && key.equals(lookupKey) && !key.equals(importedLyricsKey);
    }

    private synchronized void applySourceResult(String key, MultiSourceLyricsClient.Result result) {
        applySourceResult(key, result, false);
    }

    private synchronized void applySourceResult(String key, MultiSourceLyricsClient.Result result, boolean modelUpgrade) {
        if (!isCurrentLookup(key)) return;
        sourceDiagnostics = result.diagnostics();
        if (result.document().isEmpty()) return;
        LyricsDocument document = result.document().get();
        boolean preferred = preferences.isPreferSpicyLyrics() && "spicy_lyrics".equals(result.sourceId());
        boolean lrcRedWord = "lrc_red".equals(result.sourceId()) && hasWordTiming(document);
        if (preferences.isPreferSpicyLyrics() && key.equals(preferredLyricsKey) && !preferred
                && (!(modelUpgrade || lrcRedWord) || hasWordTiming(overlay.document()) || !hasWordTiming(document))) return;
        if (preferred && key.equals(lrcRedWordKey) && !hasWordTiming(document)) return;
        if (preferred && key.equals(preferredLyricsKey) && hasWordTiming(overlay.document()) && !hasWordTiming(document)) return;
        if (preferred) preferredLyricsKey = key;
        // A late line-only source must not replace word-synced companion/cache lyrics.
        if (!(preferences.isPreferSpicyLyrics() && "spicy_lyrics".equals(result.sourceId()))
                && key.equals(spicyLyricsKey) && hasWordTiming(overlay.document()) && !hasWordTiming(document)) return;
        if (overlay.document().equals(document)) return;
        lrcRedWordKey = lrcRedWord ? key : null;
        spicyLyricsKey = key;
        lyricsStatus = "Lyrics ready";
        overlay.setLyrics(document, "LYRICS");
        FanDroidMod.LOGGER.info("Loaded lyrics from {} ({} lines)", result.provider(), document.lines().size());
    }

    public String lyricsSourceDiagnostics() { return sourceDiagnostics; }

    public String playbackMethod() {
        if (spotifyAccount.shouldOverrideBridge()) return "account";
        if (connector instanceof MediaBridgeConnector bridge) return switch (bridge.activeSource()) {
            case "YOUTUBE_MUSIC" -> "youtube";
            case "SPICETIFY_BRIDGE" -> "spicetify";
            default -> "bridge";
        };
        return "bridge";
    }

    private boolean hasPlayingSystemMedia() {
        if (connector instanceof MediaBridgeConnector bridge && !bridge.bridgeAlive()) return false;
        PlaybackSnapshot media = connector.snapshot().getNow(PlaybackSnapshot.unavailable());
        return media.playing() && media.title() != null && !media.title().isBlank()
                && !media.title().equals("Nothing playing") && media.observedAt() != null
                && java.time.Duration.between(media.observedAt(), java.time.Instant.now()).abs().toSeconds() < 5;
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
    public GeminiShaderSession geminiShaders() { return geminiShaders; }
    public GeminiLyricsSession geminiLyrics() { return geminiLyrics; }
    public LyricsTranslationSession translations() { return translations; }
    public ReleaseSupport support() { return support; }
    public String diagnosticReport() { return support.diagnosticReport(preferences, overlay.document(), bridgeAlive()); }
    public void tickGeminiShaders() {
        var current = overlay.document();
        var translated = translations.tick(current);
        if (translated != current && overlay.document() == current) overlay.setLyrics(translated);
        support.tick(); geminiShaders.tick(snapshot.get(), clock.positionMillis(), artwork.accentColor());
        geminiLyrics.tick(snapshot.get(), overlay.document(), overlay.worldPlayback().positionMillis()); }
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
    public synchronized void refreshLyricsApiKey() {
        if (remoteLookup != null) remoteLookup.cancel(true);
        lookupKey = null;
        lookupGeneration++;
        lyricsClient.close();
        lyricsClient = new MultiSourceLyricsClient(importsFolder.resolveSibling("sources"),
                SpicyLyricsApiClient.configuredKey(preferences.getSpicyLyricsApiKey()));
        retryLyrics();
    }
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
        boolean connected = bridgeAlive() || spotifyAccount.connected()
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
        if (!preferences.isLyricsSourceEnabled("spicy_lyrics")) return;
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

    private synchronized void parseAndApplySpicy(SpicetifyLyricsPayload payload, String key, boolean persist) {
        if (!isCurrentLookup(key)) return;
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
            if (!key.equals(lookupKey) || document.lines().isEmpty()) return;
            if (key.equals(lrcRedWordKey) && !wordSynced) return;
            if (wordSynced) lrcRedWordKey = null;
            if (preferences.isPreferSpicyLyrics() && key.equals(preferredLyricsKey) && hasWordTiming(overlay.document()) && !wordSynced) return;
            if (preferences.isPreferSpicyLyrics()) preferredLyricsKey = key;
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

    private synchronized void applyImported(ImportedLyricsClient.Match match, String key) {
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
        closed = true;
        lookupKey = null;
        if (remoteLookup != null) remoteLookup.cancel(true);
        if (motionLookup != null) motionLookup.cancel(true);
        if (artistLookup != null) artistLookup.cancel(true);
        artistEngine.close();
        motionEngine.close();
        lyricsClient.close();
        geminiShaders.close();
        geminiLyrics.close();
        translations.close();
        support.close();
        artwork.close();
        spotifyAccount.close();
        connector.close();
    }
}
