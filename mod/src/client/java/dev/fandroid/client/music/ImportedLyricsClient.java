package dev.fandroid.client.music;

import dev.fandroid.FanDroidMod;
import dev.fandroid.protocol.lyrics.LyricsDocument;
import dev.fandroid.protocol.lyrics.LyricsMatchScorer;
import dev.fandroid.protocol.lyrics.LyricsMetadata;
import dev.fandroid.protocol.lyrics.LyricsSource;
import dev.fandroid.protocol.lyrics.SecureTtmlParser;
import dev.fandroid.protocol.music.PlaybackSnapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/** Bounded, local-only TTML override catalogue. */
public final class ImportedLyricsClient {
    public static final int MAX_FILES = 500;
    public static final long MAX_FILE_BYTES = 4L * 1024 * 1024;
    private static final double MATCH_THRESHOLD = .88;
    private static final double AMBIGUITY_MARGIN = .04;

    private final Path imports;
    private final Path manualCache;
    private final SecureTtmlParser parser = new SecureTtmlParser();

    public ImportedLyricsClient(Path imports) {
        this.imports = imports;
        // A subdirectory of the lyrics cache, outside its automatic provider-entry pruning.
        manualCache = imports.resolveSibling("sources").resolve("manual-ttml");
    }

    public Path folder() {
        return imports;
    }

    public SearchResult find(PlaybackSnapshot track) {
        Path override = manualCache.resolve(trackKey(track) + ".ttml");
        if (Files.isRegularFile(override)) {
            var match = parsed(override, 1);
            if (match.isPresent()) return new SearchResult(match, List.of());
        }
        try {
            Files.createDirectories(imports);
            String spotifyId = SpicyLyricsApiClient.spotifyTrackId(track.spotifyUri());
            List<Path> files;
            try (Stream<Path> stream = Files.list(imports)) {
                files = stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ttml"))
                        .sorted().limit(MAX_FILES).toList();
            }
            if (spotifyId != null) {
                for (Path path : files) {
                    String name = path.getFileName().toString();
                    if (name.equalsIgnoreCase(spotifyId + ".ttml")) {
                        return parsed(path, 1).map(match -> new SearchResult(Optional.of(match), List.of()))
                                .orElseGet(SearchResult::empty);
                    }
                }
            }

            List<Candidate> candidates = new ArrayList<>();
            for (Path path : files) {
                Optional<Match> parsed = parsed(path, 0);
                if (parsed.isEmpty()) continue;
                LyricsMetadata metadata = parsed.get().document().metadata();
                if (spotifyId != null && spotifyId.equalsIgnoreCase(metadata.trackKey())) {
                    return new SearchResult(Optional.of(new Match(path, parsed.get().document(), 1)), List.of());
                }
                if (metadata.title().isBlank()) continue;
                double score = LyricsMatchScorer.score(track.title(), track.artist(), track.album(),
                        metadata.title(), metadata.artist(), metadata.album());
                if (score >= MATCH_THRESHOLD) {
                    candidates.add(new Candidate(path, metadata.title(), metadata.artist(), metadata.album(), score));
                }
            }
            candidates.sort(Comparator.comparingDouble(Candidate::score).reversed()
                    .thenComparing(candidate -> candidate.path().getFileName().toString()));
            if (candidates.isEmpty()) return SearchResult.empty();
            if (candidates.size() > 1 && candidates.getFirst().score() - candidates.get(1).score() < AMBIGUITY_MARGIN) {
                return new SearchResult(Optional.empty(), List.copyOf(candidates.subList(0, Math.min(8, candidates.size()))));
            }
            return load(candidates.getFirst()).map(match -> new SearchResult(Optional.of(match), List.of()))
                    .orElseGet(SearchResult::empty);
        } catch (Exception error) {
            FanDroidMod.LOGGER.warn("Could not scan imported TTML lyrics", error);
            return SearchResult.empty();
        }
    }

    Match saveForTrack(PlaybackSnapshot track, Path source) throws IOException {
        if (!source.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ttml") || !Files.isRegularFile(source)) {
            throw new IllegalArgumentException("Expected a TTML file");
        }
        byte[] contents = readTtml(source);
        LyricsDocument document = parse(contents);
        Files.createDirectories(manualCache);
        Path destination = manualCache.resolve(trackKey(track) + ".ttml");
        Path temporary = Files.createTempFile(manualCache, "import-", ".tmp");
        try {
            Files.write(temporary, contents);
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return new Match(destination, document, 1);
    }

    static String trackKey(PlaybackSnapshot track) {
        String spotifyId = SpicyLyricsApiClient.spotifyTrackId(track.spotifyUri());
        String identity = spotifyId != null ? "spotify:" + spotifyId
                : "metadata:" + metadataPart(track.title()) + metadataPart(track.artist()) + metadataPart(track.album());
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static String metadataPart(String value) {
        String normalized = Objects.toString(value, "").strip().toLowerCase(Locale.ROOT);
        return normalized.length() + ":" + normalized;
    }

    public Optional<Match> load(Candidate candidate) {
        if (!candidate.path().normalize().startsWith(imports.normalize())) return Optional.empty();
        return parsed(candidate.path(), candidate.score());
    }

    private Optional<Match> parsed(Path path, double score) {
        try {
            return Optional.of(new Match(path, parse(readTtml(path)), score));
        } catch (Exception error) {
            FanDroidMod.LOGGER.debug("Ignored invalid imported TTML {}: {}", path.getFileName(), error.getMessage());
            return Optional.empty();
        }
    }

    private byte[] readTtml(Path path) throws IOException {
        try (var stream = Files.newInputStream(path)) {
            byte[] bytes = stream.readNBytes((int) MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) throw new IllegalArgumentException("TTML exceeds 4 MiB");
            return bytes;
        }
    }

    private LyricsDocument parse(byte[] bytes) {
        LyricsDocument document = parser.parse(bytes);
        if (document.lines().isEmpty()) throw new IllegalArgumentException("TTML has no lyrics");
        return document.withMetadata(document.metadata().withSource(LyricsSource.IMPORTED));
    }

    public record Candidate(Path path, String title, String artist, String album, double score) { }
    public record Match(Path path, LyricsDocument document, double score) { }
    public record SearchResult(Optional<Match> match, List<Candidate> candidates) {
        public SearchResult {
            match = match == null ? Optional.empty() : match;
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }

        public static SearchResult empty() {
            return new SearchResult(Optional.empty(), List.of());
        }
    }
}
