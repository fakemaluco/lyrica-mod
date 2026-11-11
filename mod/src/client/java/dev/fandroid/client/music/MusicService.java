package dev.fandroid.client.music;

import dev.fandroid.FanDroidMod;
import dev.fandroid.client.ui.LyricsOverlay;
import dev.fandroid.client.ClientPreferences;
import dev.fandroid.client.MusicSource;
import dev.fandroid.protocol.lyrics.LyricsDocument;
import dev.fandroid.protocol.lyrics.LyricsMetadata;
import dev.fandroid.protocol.lyrics.LyricsSource;
import dev.fandroid.protocol.lyrics.LyricsSyncMode;
import dev.fandroid.protocol.music.MusicAction;
import dev.fandroid.protocol.music.MusicCommand;
import dev.fandroid.protocol.music.MusicConnector;
import dev.fandroid.protocol.music.PlaybackSnapshot;
import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import net.minecraft.client.gui.DrawContext;

import dev.lyrica.engine.DesktopMotionArtworkEngine;
import dev.lyrica.engine.DesktopArtistArtworkEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public final class MusicService implements AutoCloseable {
    private final MusicConnector connector;
    private final AudioDucking audioDucking = new AudioDucking();
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
    private final Path importsFolder = FabricLoader.getInstance().getConfigDir().resolve("fandroid/lyrics/imports");
    private final ImportedLyricsClient importedLyrics = new ImportedLyricsClient(importsFolder);
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
    private CompletableFuture<TtmlImportResult> ttmlImport;
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
                task -> net.minecraft.client.MinecraftClient.getInstance().execute(task));
        geminiLyrics = new GeminiLyricsSession(preferences, FabricLoader.getInstance().getConfigDir().resolve("lyrica/gemini-lyrics"));
        if (preferences.isLyricsRomanization()) LyricsRomanizer.warmup();
        lyricsClient = new MultiSourceLyricsClient(importsFolder.resolveSibling("sources"), SpicyLyricsApiClient.configuredKey(preferences.getSpicyLyricsApiKey()));
        motionEngine = new DesktopMotionArtworkEngine(importsFolder.resolveSibling("motion").toFile());
        artistEngine = new DesktopArtistArtworkEngine(importsFolder.resolveSibling("artist-artwork").toFile());
        String executable = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "fandroid-media-bridge.exe" : "fandroid-media-bridge";
        Path directory = FabricLoader.getInstance().getConfigDir().resolve("fandroid/bin");
        Path path = directory.resolve(executable);
        try {
            Path bundled = BundledMediaBridge.install(directory);
            if (bundled != null) path = bundled;
        } catch (Exception exception) {
            FanDroidMod.LOGGER.warn("Could not install the bundled media bridge", exception);
        }
        connector = MediaBridgeConnector.start(path);
        spotifyAccount = new SpotifyAccountClient(preferences, this::update,
                () -> !hasPlayingNonSpotifyMedia());
        connector.subscribe(value -> {
            if (!spotifyAccount.shouldOverrideBridge()) update(value);
        });
        if (connector instanceof MediaBridgeConnector bridge) {
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
    public PlaybackSnapshot karaokeSpotifySnapshot() { return spotifyAccount.karaokeSnapshot(); }
    public void setKaraokeSpotifyWatching(boolean watching) { spotifyAccount.setKaraokeWatching(watching); }
    public CompletableFuture<String> resolveKaraokeSpotify(String title, String artist, long duration) {
        return CompletableFuture.supplyAsync(() -> {
            String id = spotifyAccount.resolveTrackId(new PlaybackSnapshot(title, artist, "", null, null, null,
                    0, duration, false, 0, false, "off", false, 0, 0, 0, 0, new double[0],
                    java.time.Instant.now(), dev.fandroid.protocol.music.MusicCapabilities.unavailable()));
            return id == null ? null : "spotify:track:" + id;
        });
    }
    public CompletableFuture<Void> syncKaraokeSpotify(String uri, long position, boolean playing,
                                                     boolean loadTrack, boolean seek, java.util.function.BooleanSupplier active) {
        return spotifyAccount.syncKaraoke(uri, position, playing, loadTrack, seek, active);
    }

    public boolean spotifyAccountConnected() { return spotifyAccount.connected(); }
    public CompletableFuture<SpotifyAccountClient.QueueReply> karaokeQueue(String action, String value, int index,
            int version, String current, int limit, java.util.function.BooleanSupplier active) {
        return spotifyAccount.karaokeQueue(action, value, index, version, current, limit, active);
    }
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

    public CompletableFuture<LyricsDocument> lookupKaraokeLyrics(PlaybackSnapshot track) {
        var result = new CompletableFuture<LyricsDocument>();
        CompletableFuture.runAsync(() -> {
            var imported = importedLyrics.find(track).match();
            if (result.isDone()) return;
            if (imported.isPresent()) {
                result.complete(imported.get().document());
                return;
            }
            String id = SpicyLyricsApiClient.spotifyTrackId(track.spotifyUri());
            var lookup = lyricsClient.find(track, id == null ? List.of() : List.of(id),
                    preferences.getLyricsSources(), preferences.getLyricsSourceOrder(),
                    preferences.isPreferSpicyLyrics(), ignored -> { });
            result.whenComplete((ignored, failure) -> { if (result.isCancelled()) lookup.cancel(true); });
            lookup.whenComplete((found, failure) -> {
                if (failure != null) result.completeExceptionally(failure);
                else result.complete(found.document().orElseGet(() -> new LyricsDocument(List.of())));
            });
        }).exceptionally(failure -> { result.completeExceptionally(failure); return null; });
        return result;
    }

    public CompletableFuture<DesktopMotionArtworkEngine.Result> lookupKaraokeArtwork(
            PlaybackSnapshot track, boolean canvas, boolean cover) {
        return motionEngine.find(track.title(), track.artist(), track.album(), track.durationMillis(),
                SpicyLyricsApiClient.spotifyTrackId(track.spotifyUri()), canvas, cover, ignored -> { });
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
            default -> "bridge";
        };
        return "bridge";
    }

    private boolean hasPlayingNonSpotifyMedia() {
        if (connector instanceof MediaBridgeConnector bridge && !bridge.bridgeAlive()) return false;
        PlaybackSnapshot media = connector.snapshot().getNow(PlaybackSnapshot.unavailable());
        String app = java.util.Objects.toString(media.deviceName(), "").toLowerCase(Locale.ROOT);
        boolean spotify = app.equals("spotify") || app.endsWith("spotify.exe") || app.startsWith("spotifyab.spotifymusic_");
        return !spotify && media.playing() && media.title() != null && !media.title().isBlank()
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
    public void tickAudioDucking(boolean inWorld) {
        if (connector instanceof MediaBridgeConnector bridge)
            audioDucking.tick(bridge, preferences, snapshot(), playbackMethod(), inWorld);
    }
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
    public boolean youtubeMusicConnected() {
        return connector instanceof MediaBridgeConnector bridge && bridge.youtubeConnected();
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
    public boolean drawArtwork(DrawContext context, int x, int y, int width, int height) {
        overlay.setArtworkAccent(artwork.accentColor());
        return artwork.draw(context, x, y, width, height);
    }
    public boolean drawArtwork(DrawContext context, int x, int y, int width, int height,
                               int cornerRadius) {
        overlay.setArtworkAccent(artwork.accentColor());
        return artwork.draw(context, x, y, width, height, cornerRadius);
    }
    public boolean drawArtworkTransition(DrawContext context, int x, int y, int width, int height) {
        return drawArtworkTransition(context, x, y, width, height, 0);
    }
    public boolean drawArtworkTransition(DrawContext context, int x, int y, int width, int height,
                                         int cornerRadius) {
        overlay.setArtworkAccent(artwork.accentColor());
        return artwork.drawTransition(context, x, y, width, height, cornerRadius);
    }
    public boolean drawArtworkBackdrop(DrawContext context, int x, int y, int width, int height, boolean enhanced) {
        overlay.setArtworkAccent(artwork.accentColor());
        return artwork.drawBackdrop(context, x, y, width, height, enhanced ? 2 : 0);
    }
    public boolean drawArtworkBackdrop(DrawContext context, int x, int y, int width, int height, int blurStrength) {
        overlay.setArtworkAccent(artwork.accentColor());
        return artwork.drawBackdrop(context, x, y, width, height, blurStrength);
    }
    public boolean drawArtworkBackdrop(DrawContext context, int x, int y, int width, int height,
                                       int blurStrength, double speedMultiplier) {
        return drawArtworkBackdrop(context, x, y, width, height, blurStrength, speedMultiplier, 1.0);
    }
    public boolean drawArtworkBackdrop(DrawContext context, int x, int y, int width, int height,
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

    public enum TtmlImportResult { SAVED, CANCELLED, NO_TRACK, INVALID_FILE, FAILED }

    public synchronized CompletableFuture<TtmlImportResult> importTtml(String dialogTitle) {
        if (ttmlImport != null && !ttmlImport.isDone()) return ttmlImport;
        PlaybackSnapshot target = snapshot();
        if (closed || target.title() == null || target.title().isBlank()
                || "Nothing playing".equals(target.title()) || isSpotifyDjSpeaking(target)) {
            return CompletableFuture.completedFuture(TtmlImportResult.NO_TRACK);
        }
        ttmlImport = CompletableFuture.supplyAsync(() -> {
            try {
                String file;
                try (var stack = MemoryStack.stackPush()) {
                    var patterns = stack.pointers(stack.UTF8("*.ttml"), stack.UTF8("*.TTML"));
                    file = TinyFileDialogs.tinyfd_openFileDialog(dialogTitle + " — " + target.title(),
                            "", patterns, "TTML", false);
                }
                if (file == null) return TtmlImportResult.CANCELLED;
                var match = importedLyrics.saveForTrack(target, Path.of(file));
                synchronized (this) {
                    // Keep the original target even if playback changed while the dialog was open.
                    if (!closed && ImportedLyricsClient.trackKey(target).equals(ImportedLyricsClient.trackKey(snapshot()))) {
                        applyImported(match, lookupKey);
                    }
                }
                return TtmlImportResult.SAVED;
            } catch (IllegalArgumentException error) {
                return TtmlImportResult.INVALID_FILE;
            } catch (java.io.IOException | RuntimeException | LinkageError error) {
                FanDroidMod.LOGGER.warn("Could not import local TTML", error);
                return TtmlImportResult.FAILED;
            }
        });
        return ttmlImport;
    }

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
        boolean connected = bridgeAlive() || spotifyAccount.connected();
        artwork.setFallback(connected ? CoverArtTexture.Fallback.SONG_NOT_FOUND
                : CoverArtTexture.Fallback.CONNECT_ACCOUNT);
    }

    private boolean stillNeedsFallback(String key) {
        return key != null && key.equals(lookupKey) && !key.equals(importedLyricsKey)
                && !key.equals(spicyLyricsKey);
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
        importsPendingKey = null;
        nextLookupRetryMillis = 0;
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
