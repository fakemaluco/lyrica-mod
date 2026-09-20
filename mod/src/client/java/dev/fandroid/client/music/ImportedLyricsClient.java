package dev.fandroid.client.music;

import dev.fandroid.FanDroidMod;
import dev.fandroid.protocol.lyrics.LyricsDocument;
import dev.fandroid.protocol.lyrics.LyricsMatchScorer;
import dev.fandroid.protocol.lyrics.LyricsMetadata;
import dev.fandroid.protocol.lyrics.LyricsSource;
import dev.fandroid.protocol.lyrics.SecureTtmlParser;
import dev.fandroid.protocol.music.PlaybackSnapshot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/** Bounded, local-only TTML override catalogue. */
public final class ImportedLyricsClient {
    public static final int MAX_FILES = 500;
    public static final long MAX_FILE_BYTES = 4L * 1024 * 1024;
    private static final double MATCH_THRESHOLD = .88;
    private static final double AMBIGUITY_MARGIN = .04;

    private final Path imports;
    private final SecureTtmlParser parser = new SecureTtmlParser();

    public ImportedLyricsClient(Path imports) {
        this.imports = imports;
    }

    public Path folder() {
        return imports;
    }

    public SearchResult find(PlaybackSnapshot track) {
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

    public Optional<Match> load(Candidate candidate) {
        return parsed(candidate.path(), candidate.score());
    }

    private Optional<Match> parsed(Path path, double score) {
        try {
            if (!path.normalize().startsWith(imports.normalize()) || Files.size(path) > MAX_FILE_BYTES) {
                return Optional.empty();
            }
            LyricsDocument parsed = parser.parse(Files.readAllBytes(path));
            if (parsed.lines().isEmpty()) return Optional.empty();
            LyricsMetadata metadata = parsed.metadata().withSource(LyricsSource.IMPORTED);
            return Optional.of(new Match(path, parsed.withMetadata(metadata), score));
        } catch (Exception error) {
            FanDroidMod.LOGGER.debug("Ignored invalid imported TTML {}: {}", path.getFileName(), error.getMessage());
            return Optional.empty();
        }
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
