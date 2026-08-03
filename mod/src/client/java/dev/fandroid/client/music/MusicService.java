package dev.fandroid.client.music;

import dev.fandroid.FanDroidMod;
import dev.fandroid.client.ui.LyricsOverlay;
import dev.fandroid.client.ClientPreferences;
import dev.fandroid.client.MusicSource;
import dev.fandroid.protocol.lyrics.LyricsDocument;
import dev.fandroid.protocol.lyrics.LyricsMetadata;
import dev.fandroid.protocol.lyrics.LyricsSource;
import dev.fandroid.protocol.lyrics.LyricsSyncMode;
import dev.fandroid.protocol.lyrics.SecureTtmlParser;
import dev.fandroid.protocol.lyrics.SpicyLyricsParser;
import dev.fandroid.protocol.music.MusicAction;
import dev.fandroid.protocol.music.MusicCommand;
import dev.fandroid.protocol.music.MusicConnector;
import dev.fandroid.protocol.music.PlaybackSnapshot;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.DrawContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class MusicService implements AutoCloseable {
    private final MusicConnector connector;
    private final AmllLyricsClient lyricsClient = new AmllLyricsClient();
    private final LrclibLyricsClient lineLyricsClient = new LrclibLyricsClient();
    private final SecureTtmlParser ttmlParser = new SecureTtmlParser();
    private final SpicyLyricsParser spicyParser = new SpicyLyricsParser();
    private final Path importsFolder = FabricLoader.getInstance().getConfigDir().resolve("fandroid/lyrics/imports");
    private final ImportedLyricsClient importedLyrics = new ImportedLyricsClient(importsFolder);
    private final Path spicyCache = FabricLoader.getInstance().getConfigDir().resolve("fandroid/lyrics/spicy");
    private final CoverArtTexture artwork = new CoverArtTexture();
    private final MusicPlaybackClock clock = new MusicPlaybackClock();
    private final AtomicReference<PlaybackSnapshot> snapshot = new AtomicReference<>(PlaybackSnapshot.unavailable());
    private final LyricsOverlay overlay;
    private final ClientPreferences preferences;
    private final SpotifyAccountClient spotifyAccount;
    private volatile String lookupKey;
    private volatile String importsPendingKey;
    private volatile String importedLyricsKey;
    private volatile String spicyLyricsKey;
    private volatile List<AmllLyricsClient.LyricsCandidate> candidates = List.of();
    private volatile List<ImportedLyricsClient.Candidate> importCandidates = List.of();
    private volatile String lyricsStatus = "Start playback to find lyrics";
    private volatile String artworkTrackKey;
    private volatile String artworkUrl;

    public MusicService(LyricsOverlay overlay, ClientPreferences preferences) {
        this.overlay = overlay;
        this.preferences = preferences;
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
        Path hotUpdate = directory.resolve("fandroid-media-bridge-next.exe");
        Path newestUpdate = directory.resolve("fandroid-media-bridge-update.exe");
        Path musicTwoUpdate = directory.resolve("fandroid-media-bridge-v2.exe");
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
        connector.subscribe(value -> {
            if (preferences.getMusicSource() != MusicSource.SPOTIFY_ACCOUNT) update(value);
        });
        if (connector instanceof MediaBridgeConnector bridge) bridge.subscribeLyrics(this::acceptSpicyLyrics);
        spotifyAccount = new SpotifyAccountClient(preferences, this::update);
        syncMusicSource();
        overlay.setArtworkTexture(artwork);
    }

    public MusicService(LyricsOverlay overlay) {
        this(overlay, ClientPreferences.load());
    }

    public void syncMusicSource() {
        if (connector instanceof MediaBridgeConnector bridge) {
            bridge.setSource(preferences.getMusicSource().name());
        }
    }

    public CompletableFuture<java.net.URI> connectSpotifyAccount(String clientId) {
        return spotifyAccount.connect(clientId);
    }
    public void disconnectSpotifyAccount() { spotifyAccount.disconnect(); }
    public boolean spotifyAccountConnected() { return spotifyAccount.connected(); }
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
        artworkUrl = nextArtworkUrl;
        if (nextArtworkUrl != null) {
            artwork.update(nextArtworkUrl);
        } else if (changedTrack && (value.title() == null || value.title().equals("Nothing playing"))) {
            artwork.update(null);
        }
        overlay.setPlayback(value);
        if (value.title() == null || value.title().equals("Nothing playing")) {
            lookupKey = null;
            importsPendingKey = null;
            importedLyricsKey = null;
            spicyLyricsKey = null;
            candidates = List.of();
            importCandidates = List.of();
            overlay.clearLyrics();
            lyricsStatus = "Start playback to find lyrics";
            return;
        }
        if (isSpotifyDjSpeaking(value)) {
            String djKey = "spotify-dj\n" + value.title() + '\n' + value.artist();
            if (!djKey.equals(lookupKey)) {
                lookupKey = djKey;
                importsPendingKey = null;
                importedLyricsKey = null;
                spicyLyricsKey = null;
                candidates = List.of();
                importCandidates = List.of();
                overlay.clearLyrics();
                overlay.markUnavailable("Spotify DJ is talking. Lyrics return with the next song.");
                lyricsStatus = "Spotify DJ voice segment - no lyrics";
            }
            return;
        }
        String key = (value.spotifyUri() == null ? "" : value.spotifyUri()) + '\n'
                + value.title() + '\n' + value.artist() + '\n' + value.album();
        if (key.equals(lookupKey)) return;
        lookupKey = key;
        importsPendingKey = key;
        importedLyricsKey = null;
        spicyLyricsKey = null;
        candidates = List.of();
        importCandidates = List.of();
        overlay.beginLookup();
        lyricsStatus = "Finding lyrics...";

        String spotifyId = AmllLyricsClient.spotifyTrackId(value.spotifyUri());
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
        if (spotifyId != null) loadCachedSpicy(value.spotifyUri(), key, spotifyId);
        CompletableFuture.runAsync(() -> beginNetworkLookup(value, key, spotifyId),
                CompletableFuture.delayedExecutor(1_200, TimeUnit.MILLISECONDS));
    }

    private void beginNetworkLookup(PlaybackSnapshot value, String key, String spotifyId) {
        if (!stillNeedsFallback(key)) return;
        lyricsStatus = "Finding lyrics...";
        if (spotifyId == null) {
            searchByMetadata(value, key, null);
            return;
        }
        lyricsClient.bySpotifyUri(value.spotifyUri()).thenAccept(document -> {
            if (!stillNeedsFallback(key)) return;
            if (document.isPresent()) applyAmll(document.get(), key);
            else searchByMetadata(value, key, spotifyId);
        });
    }

    private void searchByMetadata(PlaybackSnapshot value, String key, String spotifyId) {
        lyricsClient.search(value.title(), value.artist(), value.album(), spotifyId).thenAccept(matches -> {
            if (!stillNeedsFallback(key)) return;
            candidates = matches;
            if (isConfident(matches)) loadCandidate(matches.getFirst(), value, key, true);
            else fallbackToLineLyrics(value, key, matches);
        });
    }

    private static boolean isConfident(List<AmllLyricsClient.LyricsCandidate> matches) {
        if (matches.isEmpty() || matches.getFirst().score() < .72) return false;
        return matches.size() == 1 || matches.getFirst().score() - matches.get(1).score() >= .025
                || matches.getFirst().score() >= .90;
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
    public void retryLyrics() {
        PlaybackSnapshot current = snapshot();
        lookupKey = null;
        update(current);
    }
    public List<AmllLyricsClient.LyricsCandidate> candidates() { return candidates; }
    public List<ImportedLyricsClient.Candidate> importCandidates() { return importCandidates; }
    public Path importsFolder() { return importsFolder; }
    public void reloadImports() { retryLyrics(); }
    public void selectImportCandidate(int index) {
        List<ImportedLyricsClient.Candidate> values = importCandidates;
        if (index < 0 || index >= values.size()) return;
        importedLyrics.load(values.get(index)).ifPresent(match -> applyImported(match, lookupKey));
    }
    public void selectCandidate(int index) {
        List<AmllLyricsClient.LyricsCandidate> values = candidates;
        if (index < 0 || index >= values.size()) return;
        PlaybackSnapshot value = snapshot();
        loadCandidate(values.get(index), value, lookupKey, true);
    }

    private void loadCandidate(AmllLyricsClient.LyricsCandidate selected, PlaybackSnapshot value,
                               String key, boolean fallback) {
        lyricsStatus = "Loading lyrics...";
        candidates = List.of();
        lyricsClient.load(selected).thenAccept(document -> {
            if (key != null && stillNeedsFallback(key)) {
                if (document.isPresent()) applyAmll(document.get(), key);
                else if (fallback) fallbackToLineLyrics(value, key, List.of());
                else markUnavailable("AMLL match could not be read");
            }
        });
    }

    private void fallbackToLineLyrics(PlaybackSnapshot value, String key,
                                      List<AmllLyricsClient.LyricsCandidate> deferredAmll) {
        if (!stillNeedsFallback(key)) return;
        lyricsStatus = "Finding lyrics...";
        lineLyricsClient.find(value.title(), value.artist(), value.album(), value.durationMillis())
                .thenAccept(result -> {
                    if (!stillNeedsFallback(key)) return;
                    if (result.isPresent()) {
                        LrclibLyricsClient.LyricsResult found = result.get();
                        String precision = found.wordSynced() ? "WORD SYNC" : "LINE SYNC";
                        lyricsStatus = "Lyrics ready";
                        candidates = List.of();
                        LyricsMetadata previous = found.document().metadata();
                        LyricsDocument labelled = found.document().withMetadata(new LyricsMetadata(
                                LyricsSource.LRCLIB,
                                found.wordSynced() ? LyricsSyncMode.WORD : LyricsSyncMode.LINE,
                                previous.trackKey(), previous.title(), previous.artist(), previous.album(),
                                previous.songwriters()));
                        overlay.setLyrics(labelled, "LYRICS");
                        FanDroidMod.LOGGER.info("Loaded {} lyrics from LRCLIB for '{}'", precision, value.title());
                    } else if (!deferredAmll.isEmpty()) {
                        // AMLL occasionally has several equivalent community revisions. If LRCLIB
                        // has no result, use the highest-scoring one instead of blocking the player.
                        loadCandidate(deferredAmll.getFirst(), value, key, false);
                    } else {
                        markUnavailable("No synced lyrics in AMLL or LRCLIB");
                    }
                }).exceptionally(error -> {
                    if (stillNeedsFallback(key)) {
                        if (!deferredAmll.isEmpty()) loadCandidate(deferredAmll.getFirst(), value, key, false);
                        else markUnavailable("Lyrics lookup failed - check connection");
                    }
                    return null;
                });
    }

    private void applyAmll(LyricsDocument document, String key) {
        if (!stillNeedsFallback(key)) return;
        boolean wordSynced = hasWordTiming(document);
        String precision = wordSynced ? "WORD SYNC" : "LINE SYNC";
        lyricsStatus = "Lyrics ready";
        candidates = List.of();
        LyricsMetadata metadata = new LyricsMetadata(LyricsSource.AMLL,
                wordSynced ? LyricsSyncMode.WORD : LyricsSyncMode.LINE,
                document.metadata().trackKey(), document.metadata().title(), document.metadata().artist(),
                document.metadata().album(), document.metadata().songwriters());
        overlay.setLyrics(document.withMetadata(metadata), "LYRICS");
        FanDroidMod.LOGGER.info("Loaded {} lyrics from AMLL ({} lines)", precision, document.lines().size());
    }

    private void markUnavailable(String message) {
        lyricsStatus = message;
        candidates = List.of();
        overlay.markUnavailable(message);
        FanDroidMod.LOGGER.info(message);
    }

    private boolean stillNeedsFallback(String key) {
        return key != null && key.equals(lookupKey) && !key.equals(importedLyricsKey)
                && !key.equals(spicyLyricsKey);
    }

    private void acceptSpicyLyrics(SpicetifyLyricsPayload payload) {
        PlaybackSnapshot current = snapshot();
        String key = lookupKey;
        if (key == null || current.spotifyUri() == null || !current.spotifyUri().equals(payload.uri())) return;
        if (key.equals(importsPendingKey) || key.equals(importedLyricsKey)) return;
        CompletableFuture.runAsync(() -> parseAndApplySpicy(payload, key, true));
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
                    AmllLyricsClient.spotifyTrackId(payload.uri()), previous.title(), previous.artist(), previous.album(),
                    songwriters, provider, payload.communityProvided() || previous.communityProvided(),
                    makerUsername, makerAvatar, uploaderUsername, uploaderAvatar));
            lyricsStatus = "Lyrics ready";
            candidates = List.of();
            overlay.setLyrics(document, "LYRICS");
            FanDroidMod.LOGGER.info("Loaded {} lyrics from {} ({} lines)", precision,
                    payload.source(), document.lines().size());
            if (persist) persistSpicy(payload, songwriters);
        } catch (Exception error) {
            FanDroidMod.LOGGER.warn("Could not parse lyrics supplied by Spicy Lyrics", error);
        }
    }

    private void persistSpicy(SpicetifyLyricsPayload payload, String songwriters) {
        String trackId = AmllLyricsClient.spotifyTrackId(payload.uri());
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
            case "spt" -> "Spotify";
            case "aml" -> "Apple Music";
            case "spl" -> "Spicy Lyrics";
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
        candidates = List.of();
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
        if (preferences.getMusicSource() == MusicSource.SPOTIFY_ACCOUNT) {
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
