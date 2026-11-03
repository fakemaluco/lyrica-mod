package dev.fandroid.client.music;

import dev.fandroid.protocol.lyrics.LyricLine;
import dev.fandroid.protocol.lyrics.LyricsDocument;
import dev.fandroid.protocol.lyrics.LyricsMetadata;
import dev.fandroid.protocol.lyrics.LyricsSource;
import dev.fandroid.protocol.lyrics.LyricsSyncMode;
import dev.fandroid.protocol.lyrics.SecureLrcParser;
import dev.fandroid.protocol.lyrics.SecureTtmlParser;
import dev.fandroid.protocol.music.PlaybackSnapshot;
import dev.lyrica.engine.DesktopLyricsEngine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Maps Spicy Mobile's complete JVM provider backend into Lyrica's secure rendering model. */
public final class MultiSourceLyricsClient implements AutoCloseable {
    private final DesktopLyricsEngine engine;
    private final SecureTtmlParser ttml = new SecureTtmlParser();
    private final SecureLrcParser lrc = new SecureLrcParser();

    public MultiSourceLyricsClient(Path cacheDirectory) {
        this(cacheDirectory, SpicyLyricsApiClient.configuredKey());
    }
    public MultiSourceLyricsClient(Path cacheDirectory, String key) {
        engine = new DesktopLyricsEngine(cacheDirectory.toFile(), key);
    }

    public record Source(String id, String name, boolean defaultEnabled) { }
    public record Result(Optional<LyricsDocument> document, String provider, String diagnostics, boolean cached, String sourceId) { }

    private static final List<Source> SOURCES = List.of(
            new Source("spicy_lyrics", "Spicy Lyrics", true),
            new Source("amll_ttml_db", "AMLL TTML DB", true),
            new Source("rmm_revival", "RMM Revival", true),
            new Source("lrc_red", "lrc.red", true),
            new Source("unison", "Unison", true),
            new Source("spicy_unison", "Spicy Lyrics / Unison", true),
            new Source("kugou", "KuGou", true),
            new Source("netease", "NetEase", true),
            new Source("qq_music", "QQ Music", true),
            new Source("kuwo", "Kuwo", true),
            new Source("lrclib", "LRCLIB", true),
            new Source("lrcmux", "LRCMux", true),
            new Source("genius", "Genius", true),
            new Source("youtube_transcript", "YouTube transcripts", true));

    private static final List<DesktopLyricsEngine.Companion.Origin> ORIGINS = List.copyOf(DesktopLyricsEngine.origins());
    private static final List<String> DEFAULT_ORDER = ORIGINS.stream().map(origin -> origin.getId()).toList();
    public static List<DesktopLyricsEngine.Companion.Origin> origins() { return ORIGINS; }
    public static List<String> defaultOrder() { return DEFAULT_ORDER; }
    public static List<Source> sources() { return SOURCES; }
    public static List<String> defaultSources() {
        return SOURCES.stream().filter(Source::defaultEnabled).map(Source::id).toList();
    }

    public CompletableFuture<Result> find(PlaybackSnapshot track, List<String> spotifyIds,
                                          List<String> enabledIds, Consumer<Result> onUpdate) {
        return find(track, spotifyIds, enabledIds, defaultOrder(), onUpdate);
    }
    public CompletableFuture<Result> find(PlaybackSnapshot track, List<String> spotifyIds,
                                          List<String> enabledIds, List<String> order, Consumer<Result> onUpdate) {
        return find(track, spotifyIds, enabledIds, order, true, onUpdate);
    }
    public CompletableFuture<Result> find(PlaybackSnapshot track, List<String> spotifyIds,
                                          List<String> enabledIds, List<String> order, boolean preferSpicy, Consumer<Result> onUpdate) {
        CompletableFuture<DesktopLyricsEngine.Result> lookup = engine.find(safe(track.title()), safe(track.artist()),
                safe(track.album()), track.durationMillis(), spotifyIds, enabledIds, order, preferSpicy,
                raw -> onUpdate.accept(convert(raw, track)));
        CompletableFuture<Result> converted = lookup.thenApply(raw -> convert(raw, track));
        converted.whenComplete((ignored, error) -> { if (converted.isCancelled()) lookup.cancel(true); });
        return converted;
    }

    Result convert(DesktopLyricsEngine.Result raw, PlaybackSnapshot track) {
        if (!raw.found()) return new Result(Optional.empty(), raw.getProvider(), raw.getDiagnostics(), raw.getCached(), raw.getSourceId());
        LyricsDocument document = null;
        String invalid = "";
        if (raw.getTtml() != null && !raw.getTtml().isBlank()) {
            try { document = ttml.parse(raw.getTtml().getBytes(StandardCharsets.UTF_8)); }
            catch (IllegalArgumentException error) { invalid = "\nTTML rejected: " + error.getMessage(); }
        }
        if ((document == null || document.lines().isEmpty()) && raw.getLrc() != null && !raw.getLrc().isBlank()) {
            try { document = lrc.parse(raw.getLrc().getBytes(StandardCharsets.UTF_8)); }
            catch (IllegalArgumentException error) { invalid += "\nLRC rejected: " + error.getMessage(); }
        }
        boolean plain = false;
        if ((document == null || document.lines().isEmpty()) && raw.getPlain() != null && !raw.getPlain().isBlank()) {
            // Unsynchronised text stays static: never fabricate karaoke timestamps.
            List<LyricLine> lines = Arrays.stream(raw.getPlain().split("\\R"))
                    .map(String::strip).filter(line -> !line.isEmpty()).limit(10_000)
                    .map(line -> new LyricLine(0, Math.max(1, track.durationMillis()), line,
                            List.of(), "v1", false, null, null)).toList();
            document = new LyricsDocument(lines);
            plain = true;
        }
        if (document == null || document.lines().isEmpty()) {
            return new Result(Optional.empty(), raw.getProvider(), raw.getDiagnostics() + invalid, raw.getCached(), raw.getSourceId());
        }
        LyricsMetadata previous = document.metadata();
        LyricsSyncMode mode = plain || raw.getQuality().equals("PLAIN") ? LyricsSyncMode.STATIC
                : raw.getQuality().equals("WORD_SYNCED") ? LyricsSyncMode.WORD : LyricsSyncMode.LINE;
        LyricsMetadata metadata = new LyricsMetadata(LyricsSource.REMOTE, mode,
                safe(track.spotifyUri()), safe(track.title()), safe(track.artist()), safe(track.album()),
                raw.getWriters().isEmpty() ? previous.songwriters() : String.join(", ", raw.getWriters()),
                raw.getProvider(), !raw.getMaker().isBlank() || !raw.getUploader().isBlank(),
                raw.getMaker(), raw.getMakerAvatar(), raw.getUploader(), raw.getUploaderAvatar());
        return new Result(Optional.of(document.withMetadata(metadata)), raw.getProvider(), raw.getDiagnostics() + invalid, raw.getCached(), raw.getSourceId());
    }

    private static String safe(String text) { return text == null ? "" : text; }
    @Override public void close() { engine.close(); }
}
