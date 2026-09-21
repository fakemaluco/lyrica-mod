package dev.fandroid.protocol.lyrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Reads the processed lyric object that Spicy Lyrics stores in Spotify's
 * Cache Storage. Timing in that format is expressed in seconds.
 */
public final class SpicyLyricsParser {
    private static final int MAX_DOCUMENT_BYTES = 2 * 1024 * 1024;
    private static final int MAX_LINES = 10_000;
    private static final int MAX_SYLLABLES = 1_000;
    private static final int MAX_TEXT = 8_192;
    private static final ObjectMapper JSON = new ObjectMapper();

    public ParsedLyrics parse(byte[] source) {
        if (source.length > MAX_DOCUMENT_BYTES) {
            throw new IllegalArgumentException("Spicy Lyrics document exceeds 2 MiB");
        }
        try {
            JsonNode root = JSON.readTree(source);
            if (root.path("Body").isObject()) root = root.path("Body");
            String type = text(root, "Type").toLowerCase(Locale.ROOT);
            JsonNode content = root.path("Content");
            if (!content.isArray() || content.isEmpty()) {
                throw new IllegalArgumentException("Spicy Lyrics payload has no lyric lines");
            }
            if (content.size() > MAX_LINES) {
                throw new IllegalArgumentException("Spicy Lyrics payload has too many lines");
            }

            List<LyricVocalGroup> groups = switch (type) {
                case "syllable" -> parseSyllableGroups(content);
                case "line" -> parseLineGroups(content);
                default -> throw new IllegalArgumentException("unsupported Spicy Lyrics type: " + type);
            };
            if (groups.isEmpty()) throw new IllegalArgumentException("Spicy Lyrics payload has no usable lines");
            groups = repairGroupEnds(groups);
            String providerCode = text(root, "source").toLowerCase(Locale.ROOT);
            JsonNode upload = root.path("UploadAttribution").isObject()
                    ? root.path("UploadAttribution") : root.path("TTMLUploadMetadata");
            JsonNode maker = upload.path("Maker");
            JsonNode uploader = upload.path("Uploader");
            LyricsMetadata metadata = new LyricsMetadata(LyricsSource.SPICY_CACHE,
                    type.equals("syllable") ? LyricsSyncMode.WORD : LyricsSyncMode.LINE,
                    "", "", "", "", songwriters(root), providerLabel(providerCode),
                    providerCode.equals("spl") || providerCode.equals("spicy_lyrics"),
                    profileText(maker, 160, "username", "Username"),
                    profileText(maker, 2_048, "avatar", "Avatar"),
                    profileText(uploader, 160, "username", "Username"),
                    profileText(uploader, 2_048, "avatar", "Avatar"));
            return new ParsedLyrics(new LyricsDocument(groups, metadata), type.equals("syllable"));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid Spicy Lyrics JSON", e);
        }
    }

    private static List<LyricVocalGroup> parseSyllableGroups(JsonNode content) {
        List<LyricVocalGroup> groups = new ArrayList<>();
        for (JsonNode group : content) {
            boolean opposite = oppositeAligned(group);
            LyricLine lead = syllableLine(group.path("Lead"), opposite, false);
            if (lead == null) continue;
            List<LyricLine> backingLines = new ArrayList<>();
            JsonNode backgrounds = group.path("Background");
            if (backgrounds.isArray()) {
                for (JsonNode background : backgrounds) {
                    LyricLine line = syllableLine(background, opposite, true);
                    if (line != null) backingLines.add(line);
                }
            }
            long end = Math.max(lead.endMillis(), backingLines.stream()
                    .mapToLong(LyricLine::endMillis).max().orElse(lead.endMillis()));
            groups.add(new LyricVocalGroup(lead.startMillis(), end, lead, backingLines,
                    opposite ? LyricAlignment.OPPOSITE : LyricAlignment.NORMAL));
        }
        return groups;
    }

    private static LyricLine syllableLine(JsonNode node, boolean opposite, boolean background) {
        JsonNode syllables = node.path("Syllables");
        if (!syllables.isArray() || syllables.isEmpty()) return null;
        if (syllables.size() > MAX_SYLLABLES) {
            throw new IllegalArgumentException("Spicy Lyrics line has too many syllables");
        }
        long start = millis(node.path("StartTime"));
        long end = millis(node.path("EndTime"));
        StringBuilder visible = new StringBuilder();
        StringBuilder romanized = new StringBuilder();
        boolean hasRomanization = false;
        List<TimedWord> words = new ArrayList<>(syllables.size());
        int index = 0;
        boolean previousContinuesWord = false;
        for (JsonNode syllable : syllables) {
            String value = text(syllable, "Text");
            if (value.isBlank()) continue;
            boolean joined = index > 0 && previousContinuesWord;
            boolean continuesWord = syllable.path("IsPartOfWord").asBoolean(false);
            String fragment = (index > 0 && !joined ? " " : "") + value;
            visible.append(fragment);
            String transliteration = text(syllable, "TransliteratedText");
            if (!transliteration.isBlank()) hasRomanization = true;
            String romanizedFragment = (index > 0 && !joined ? " " : "")
                    + (transliteration.isBlank() ? value : transliteration);
            romanized.append(romanizedFragment);
            long wordStart = millis(syllable.path("StartTime"));
            long wordEnd = millis(syllable.path("EndTime"));
            words.add(new TimedWord(wordStart, Math.max(wordStart, wordEnd), fragment, joined,
                    transliteration.isBlank() ? null : romanizedFragment));
            previousContinuesWord = continuesWord;
            index++;
        }
        String suppliedLine = text(node, "Text");
        String line = clean(suppliedLine.isBlank() ? visible.toString() : suppliedLine);
        if (line.isBlank()) return null;
        List<TimedWord> repairedWords = repairWordBoundaries(line, words);
        String translation = firstText(node, "TranslatedText", "Translation");
        return new LyricLine(start, Math.max(start, end), line, repairedWords, opposite ? "v2" : "v1",
                background, blankToNull(translation), hasRomanization ? clean(romanized.toString()) : null);
    }

    private static List<TimedWord> repairWordBoundaries(String line, List<TimedWord> words) {
        String lowerLine = line.toLowerCase(Locale.ROOT);
        List<TimedWord> result = new ArrayList<>(words.size());
        int cursor = 0;
        for (int index = 0; index < words.size(); index++) {
            TimedWord word = words.get(index);
            String fragment = clean(word.text());
            int found = fragment.isEmpty() ? -1
                    : lowerLine.indexOf(fragment.toLowerCase(Locale.ROOT), cursor);
            boolean joined = index > 0 && (word.joinPrevious() || found >= 0 && found == cursor);
            String value = joined ? word.text().stripLeading() : word.text();
            String romanized = joined && word.romanization() != null
                    ? word.romanization().stripLeading() : word.romanization();
            result.add(new TimedWord(word.startMillis(), word.endMillis(), value, joined, romanized));
            if (found >= 0) cursor = found + fragment.length();
        }
        return List.copyOf(result);
    }

    private static List<LyricVocalGroup> parseLineGroups(JsonNode content) {
        List<LyricVocalGroup> groups = new ArrayList<>(content.size());
        for (JsonNode node : content) {
            String line = clean(text(node, "Text"));
            if (line.isBlank()) continue;
            long start = millis(node.path("StartTime"));
            long end = Math.max(start, millis(node.path("EndTime")));
            String translation = firstText(node, "TranslatedText", "Translation");
            String romanization = text(node, "TransliteratedText");
            boolean opposite = oppositeAligned(node);
            LyricLine lead = new LyricLine(start, end, line,
                    List.of(new TimedWord(start, end, line, false, blankToNull(romanization))),
                    opposite ? "v2" : "v1", false, blankToNull(translation), blankToNull(romanization));
            groups.add(new LyricVocalGroup(start, end, lead, List.of(),
                    opposite ? LyricAlignment.OPPOSITE : LyricAlignment.NORMAL));
        }
        return groups;
    }

    private static List<LyricVocalGroup> repairGroupEnds(List<LyricVocalGroup> source) {
        List<LyricVocalGroup> sorted = source.stream()
                .sorted(Comparator.comparingLong(LyricVocalGroup::startMillis)).toList();
        List<LyricVocalGroup> repaired = new ArrayList<>(sorted.size());
        for (int index = 0; index < sorted.size(); index++) {
            LyricVocalGroup group = sorted.get(index);
            long next = index + 1 < sorted.size() ? sorted.get(index + 1).startMillis()
                    : group.startMillis() + 3_000;
            LyricLine lead = repairLineEnd(group.lead(), next);
            List<LyricLine> backing = group.backingVocals().stream()
                    .map(line -> repairLineEnd(line, lead.endMillis())).toList();
            long end = Math.max(lead.endMillis(), backing.stream()
                    .mapToLong(LyricLine::endMillis).max().orElse(lead.endMillis()));
            repaired.add(new LyricVocalGroup(group.startMillis(), end, lead, backing, group.alignment()));
        }
        return repaired;
    }

    private static LyricLine repairLineEnd(LyricLine line, long next) {
        long end = Math.max(line.endMillis(), line.words().stream()
                .mapToLong(TimedWord::endMillis).max().orElse(line.startMillis()));
        if (end <= line.startMillis()) end = Math.max(line.startMillis() + 1, next);
        return new LyricLine(line.startMillis(), end, line.text(), line.words(), line.agent(),
                line.background(), line.translation(), line.romanization());
    }

    private static long millis(JsonNode value) {
        if (!value.isNumber()) return 0;
        double seconds = value.asDouble();
        if (!Double.isFinite(seconds) || seconds < 0 || seconds > 86_400) return 0;
        return Math.round(seconds * 1_000);
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private static boolean oppositeAligned(JsonNode node) {
        for (String field : List.of("OppositeAligned", "oppositeAligned", "opposite_aligned")) {
            JsonNode value = node.path(field);
            if (value.isBoolean()) return value.asBoolean();
            if (value.isTextual()) return Boolean.parseBoolean(value.asText().strip());
        }
        String alignment = firstText(node, "Alignment", "alignment").strip().toLowerCase(Locale.ROOT);
        return alignment.equals("opposite") || alignment.equals("right");
    }

    private static String songwriters(JsonNode root) {
        List<String> names = new ArrayList<>();
        for (JsonNode container : List.of(root, root.path("Metadata"), root.path("metadata"),
                root.path("Credits"), root.path("credits"))) {
            for (String field : List.of("SongWriters", "songWriters", "songwriters", "Writers", "writers")) {
                collectWriterNames(container.path(field), names, 0);
            }
        }
        return String.join(", ", names.stream().distinct().limit(32).toList());
    }

    private static void collectWriterNames(JsonNode value, List<String> names, int depth) {
        if (value == null || value.isMissingNode() || value.isNull() || depth > 3) return;
        if (value.isTextual()) {
            String name = clean(value.asText());
            if (!name.isBlank()) names.add(name.length() <= 160 ? name : name.substring(0, 160));
            return;
        }
        if (value.isArray()) {
            value.forEach(entry -> collectWriterNames(entry, names, depth + 1));
            return;
        }
        if (value.isObject()) {
            for (String field : List.of("name", "Name", "fullName", "FullName", "displayName", "DisplayName")) {
                collectWriterNames(value.path(field), names, depth + 1);
            }
        }
    }

    private static String providerLabel(String code) {
        return switch (code) {
            case "spt", "spotify" -> "Spotify";
            case "aml", "apple_music" -> "Apple Music";
            case "spl", "spicy_lyrics" -> "Spicy Lyrics";
            case "ldb" -> "Local DB";
            default -> "Spicy Lyrics";
        };
    }

    private static String profileText(JsonNode node, int maximumLength, String... fields) {
        String value = firstText(node, fields).strip();
        return value.length() <= maximumLength ? value : value.substring(0, maximumLength);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual()) return "";
        String text = value.asText();
        return text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text;
    }

    private static String clean(String value) {
        return value.replace('\u00A0', ' ').replaceAll("[\\t\\r\\n ]+", " ").strip();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : clean(value);
    }

    public record ParsedLyrics(LyricsDocument document, boolean wordSynced) { }
}
