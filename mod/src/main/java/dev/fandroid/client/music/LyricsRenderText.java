package dev.fandroid.client.music;

import dev.fandroid.protocol.lyrics.LyricLine;
import dev.fandroid.protocol.lyrics.TimedWord;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts protocol words into stable visual words and grapheme animation spans.
 * This class deliberately has no Minecraft rendering dependency so layout rules
 * can be tested without starting a client.
 */
public final class LyricsRenderText {
    private static final Pattern TEXT_CHUNK = Pattern.compile("\\S+\\s*|\\s+");
    private static final Pattern INVISIBLE_FORMATTING = Pattern.compile(
            "[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F"
                    + "\\u200B\\u200C\\u200E\\u200F\\u202A-\\u202E\\u2060\\u2066-\\u2069\\uFEFF\\uFFFD]");
    private static final String CLOSING_PUNCTUATION = ",.;:!?%)]}\u00BB\u201D\u2019";
    private static final String OPENING_PUNCTUATION = "([{\u00AB\u201C\u2018/";

    private LyricsRenderText() { }

    public static List<VisualWord> visualWords(LyricLine line) {
        return visualWords(line, false);
    }

    /**
     * Builds the same stable geometry for the original or romanized lyric.
     * Per-fragment transliterations keep their exact timings; line-only
     * transliterations are aligned to the original visual words when possible.
     */
    public static List<VisualWord> visualWords(LyricLine line, boolean romanized) {
        List<TimedWord> source = romanized ? romanizedSourceWords(line) : sourceWords(line);
        return groupVisualWords(source);
    }

    /** True when either the line or its synced fragments provide a transliteration. */
    public static boolean hasRomanization(LyricLine line) {
        if (line.romanization() != null && !line.romanization().isBlank()) return true;
        if (line.words().stream().anyMatch(word -> word.romanization() != null
                && !word.romanization().isBlank())) return true;
        return LyricsRomanizer.romanize(line.text()) != null;
    }

    private static List<VisualWord> groupVisualWords(List<TimedWord> source) {
        List<VisualWord> result = new ArrayList<>();
        List<Fragment> current = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        long start = Long.MAX_VALUE;
        long end = Long.MIN_VALUE;
        for (TimedWord word : source) {
            String fragmentText = normalizeFragment(word.text());
            if (fragmentText.isEmpty()) continue;
            if (!current.isEmpty() && !word.joinPrevious()) {
                result.add(new VisualWord(List.copyOf(current), text.toString(), start, Math.max(start, end)));
                current.clear();
                text.setLength(0);
                start = Long.MAX_VALUE;
                end = Long.MIN_VALUE;
            }
            current.add(new Fragment(word, fragmentText));
            text.append(fragmentText);
            start = Math.min(start, word.startMillis());
            end = Math.max(end, word.endMillis());
        }
        if (!current.isEmpty()) {
            result.add(new VisualWord(List.copyOf(current), text.toString(), start, Math.max(start, end)));
        }
        return List.copyOf(result);
    }

    private static List<TimedWord> romanizedSourceWords(LyricLine line) {
        List<TimedWord> source = sourceWords(line);
        String suppliedLineRomanization = line.romanization() != null && !line.romanization().isBlank()
                ? line.romanization() : null;
        String lineRomanization = suppliedOrGenerated(line.romanization(), line.text());
        boolean hasFragmentRomanization = source.stream().anyMatch(word ->
                suppliedOrGenerated(word.romanization(), word.text()) != null);
        if (hasFragmentRomanization) {
            List<TimedWord> romanized = new ArrayList<>(source.size());
            for (TimedWord word : source) {
                String generated = suppliedOrGenerated(word.romanization(), word.text());
                String text = generated == null ? word.text() : generated;
                romanized.add(new TimedWord(word.startMillis(), word.endMillis(), text,
                        word.joinPrevious(), generated));
            }
            List<TimedWord> timedRomanized = List.copyOf(romanized);
            // A locally generated whole-line value must never override a provider's per-fragment
            // spelling. It is only used below when the source has no timed fragments to convert.
            if (suppliedLineRomanization == null) {
                return timedRomanized;
            }
            List<String> displayedWords = textChunks(suppliedLineRomanization);
            List<TimedWord> boundaryAligned = alignRomanizedFragments(timedRomanized, displayedWords);
            if (!boundaryAligned.isEmpty()) return boundaryAligned;
            List<VisualWord> fragmentWords = groupVisualWords(timedRomanized);
            if (fragmentWords.size() == displayedWords.size()) {
                boolean sameGeometry = true;
                for (int index = 0; index < displayedWords.size(); index++) {
                    if (!fragmentWords.get(index).text().equals(displayedWords.get(index))) {
                        sameGeometry = false;
                        break;
                    }
                }
                if (sameGeometry) return timedRomanized;
            }
        }

        if (lineRomanization == null) return source;

        List<VisualWord> originalWords = groupVisualWords(source);
        List<String> romanizedWords = textChunks(lineRomanization);
        if (!originalWords.isEmpty() && romanizedWords.size() == originalWords.size()) {
            List<TimedWord> aligned = new ArrayList<>(romanizedWords.size());
            for (int index = 0; index < romanizedWords.size(); index++) {
                VisualWord original = originalWords.get(index);
                aligned.add(new TimedWord(original.startMillis(), original.endMillis(),
                        romanizedWords.get(index)));
            }
            return List.copyOf(aligned);
        }
        return splitTextByTiming(lineRomanization, line.startMillis(), line.endMillis());
    }

    private static String suppliedOrGenerated(String supplied, String original) {
        return supplied != null && !supplied.isBlank() ? supplied : LyricsRomanizer.romanize(original);
    }

    /**
     * Recovers romanized word boundaries without changing fragment timestamps.
     * This matters for scripts that do not use spaces in the original text: the
     * repaired original geometry may join every syllable even though the supplied
     * romanization contains distinct words.
     */
    private static List<TimedWord> alignRomanizedFragments(List<TimedWord> fragments,
                                                            List<String> displayedWords) {
        if (fragments.isEmpty() || displayedWords.isEmpty()) return List.of();
        List<TimedWord> aligned = new ArrayList<>(fragments.size());
        int fragmentIndex = 0;
        for (String displayedWord : displayedWords) {
            String target = normalizeFragment(displayedWord);
            if (target.isEmpty()) continue;
            List<TimedWord> wordFragments = new ArrayList<>();
            StringBuilder combined = new StringBuilder();
            while (fragmentIndex < fragments.size() && combined.length() < target.length()) {
                TimedWord fragment = fragments.get(fragmentIndex++);
                String fragmentText = normalizeFragment(fragment.text());
                if (fragmentText.isEmpty()) continue;
                combined.append(fragmentText);
                wordFragments.add(fragment);
                if (!target.startsWith(combined.toString())) return List.of();
            }
            if (!combined.toString().equals(target) || wordFragments.isEmpty()) return List.of();
            for (int index = 0; index < wordFragments.size(); index++) {
                TimedWord fragment = wordFragments.get(index);
                aligned.add(new TimedWord(fragment.startMillis(), fragment.endMillis(),
                        normalizeFragment(fragment.text()), index > 0, fragment.romanization()));
            }
        }
        return fragmentIndex == fragments.size() ? List.copyOf(aligned) : List.of();
    }

    public static List<GraphemeSpan> timedGraphemes(String text, long startMillis, long endMillis) {
        if (text == null || text.isEmpty()) return List.of();
        BreakIterator iterator = BreakIterator.getCharacterInstance(Locale.ROOT);
        iterator.setText(text);
        List<int[]> boundaries = new ArrayList<>();
        for (int start = iterator.first(), end = iterator.next(); end != BreakIterator.DONE;
             start = end, end = iterator.next()) {
            boundaries.add(new int[]{start, end});
        }
        int animatedCount = 0;
        for (int[] boundary : boundaries) {
            if (!text.substring(boundary[0], boundary[1]).isBlank()) animatedCount++;
        }
        List<GraphemeSpan> result = new ArrayList<>(boundaries.size());
        long duration = Math.max(0, endMillis - startMillis);
        int animatedIndex = 0;
        for (int[] boundary : boundaries) {
            String value = text.substring(boundary[0], boundary[1]);
            boolean animated = !value.isBlank();
            long glyphStart = Long.MIN_VALUE;
            long glyphEnd = Long.MIN_VALUE;
            if (animated) {
                if (animatedCount <= 1 || duration == 0) {
                    glyphStart = startMillis;
                    glyphEnd = endMillis;
                } else {
                    double startFraction = animatedIndex / (double) (animatedCount - 1) * .58;
                    double tail = Math.max(.42, 1.0 / animatedCount * 1.7);
                    glyphStart = startMillis + Math.round(duration * startFraction);
                    glyphEnd = startMillis + Math.round(duration * Math.min(1, startFraction + tail));
                }
                animatedIndex++;
            }
            result.add(new GraphemeSpan(value, boundary[0], boundary[1], glyphStart,
                    animated ? Math.max(glyphStart, glyphEnd) : Long.MIN_VALUE));
        }
        return List.copyOf(result);
    }

    public static boolean needsWordSeparator(String previous, String next) {
        if (previous == null || previous.isEmpty() || next == null || next.isEmpty()) return false;
        int first = next.codePointAt(0);
        int last = previous.codePointBefore(previous.length());
        if (CLOSING_PUNCTUATION.indexOf(first) >= 0) return false;
        return OPENING_PUNCTUATION.indexOf(last) < 0;
    }

    private static List<TimedWord> sourceWords(LyricLine line) {
        String firstWord = line.words().isEmpty() ? "" : normalizeVisible(line.words().getFirst().text());
        boolean singleLineToken = line.words().size() == 1
                && firstWord.codePoints().anyMatch(Character::isWhitespace);
        if (!line.words().isEmpty() && !singleLineToken) {
            return repairWordBoundaries(line.text(), line.words());
        }
        TimedWord timing = line.words().isEmpty()
                ? new TimedWord(line.startMillis(), line.endMillis(), line.text())
                : line.words().getFirst();
        String source = normalizeVisible(timing.text());
        return splitTextByTiming(source, timing.startMillis(), timing.endMillis());
    }

    private static List<TimedWord> splitTextByTiming(String source, long startMillis, long endMillis) {
        List<String> chunks = textChunks(source);
        int totalCharacters = 0;
        for (String chunk : chunks) totalCharacters += chunk.codePointCount(0, chunk.length());
        List<TimedWord> split = new ArrayList<>(chunks.size());
        long duration = Math.max(0, endMillis - startMillis);
        int consumedCharacters = 0;
        for (String chunk : chunks) {
            int characters = chunk.codePointCount(0, chunk.length());
            long start = startMillis + duration * consumedCharacters / Math.max(1, totalCharacters);
            consumedCharacters += characters;
            long end = startMillis + duration * consumedCharacters / Math.max(1, totalCharacters);
            split.add(new TimedWord(start, Math.max(start, end), chunk));
        }
        if (split.isEmpty() && source != null && !source.isEmpty()) {
            split.add(new TimedWord(startMillis, Math.max(startMillis, endMillis), source));
        }
        return List.copyOf(split);
    }

    private static List<String> textChunks(String source) {
        source = normalizeVisible(source);
        if (source.isBlank()) return List.of();
        List<String> chunks = new ArrayList<>();
        Matcher matcher = TEXT_CHUNK.matcher(source);
        while (matcher.find()) {
            String chunk = matcher.group().strip();
            if (!chunk.isEmpty()) chunks.add(chunk);
        }
        return List.copyOf(chunks);
    }

    private static List<TimedWord> repairWordBoundaries(String lineText, List<TimedWord> source) {
        String visible = normalizeVisible(lineText);
        String lowerVisible = visible.toLowerCase(Locale.ROOT);
        List<TimedWord> repaired = new ArrayList<>(source.size());
        int cursor = 0;
        for (int index = 0; index < source.size(); index++) {
            TimedWord word = source.get(index);
            String fragment = normalizeFragment(word.text());
            int found = fragment.isEmpty() ? -1
                    : lowerVisible.indexOf(fragment.toLowerCase(Locale.ROOT), cursor);
            boolean touchesPrevious = index > 0 && found >= 0 && found == cursor;
            boolean joined = index > 0 && (word.joinPrevious() || touchesPrevious);
            repaired.add(new TimedWord(word.startMillis(), word.endMillis(), word.text(), joined,
                    word.romanization()));
            if (found >= 0) cursor = found + fragment.length();
        }
        return repaired;
    }

    private static String normalizeFragment(String value) {
        return normalizeVisible(value).strip();
    }

    private static String normalizeVisible(String value) {
        if (value == null || value.isEmpty()) return "";
        String normalized = value.replace('\u2800', ' ').replace('\u3164', ' ')
                .replace('\uFFA0', ' ').replace('\u115F', ' ').replace('\u1160', ' ');
        normalized = INVISIBLE_FORMATTING.matcher(normalized).replaceAll("");
        return normalized.replaceAll("\\p{Z}+", " ").replaceAll("[\\t ]+", " ");
    }

    public record VisualWord(List<Fragment> fragments, String text, long startMillis, long endMillis) {
        public VisualWord {
            fragments = List.copyOf(fragments);
        }

        public long durationMillis() {
            return Math.max(0, endMillis - startMillis);
        }
    }

    public record Fragment(TimedWord timing, String text) { }

    public record GraphemeSpan(String text, int startIndex, int endIndex,
                               long startMillis, long endMillis) {
        public boolean animated() {
            return startMillis != Long.MIN_VALUE;
        }
    }
}
