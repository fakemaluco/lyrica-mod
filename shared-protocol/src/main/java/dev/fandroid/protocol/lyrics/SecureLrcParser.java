package dev.fandroid.protocol.lyrics;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bounded parser for classic and enhanced LRC. Classic timestamps produce a
 * line-synchronised document; enhanced {@code <mm:ss.xx>} timestamps retain
 * word timing when a provider supplies it.
 */
public final class SecureLrcParser {
    private static final int MAX_DOCUMENT_BYTES = 2 * 1024 * 1024;
    private static final Pattern LINE_TIME = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[\\.:](\\d{1,3}))?]");
    private static final Pattern WORD_TIME = Pattern.compile("<(\\d{1,3}):(\\d{2})(?:[\\.:](\\d{1,3}))?>");
    private static final Pattern OFFSET = Pattern.compile("(?i)^\\[offset:([+-]?\\d+)]$");
    private static final Pattern INVISIBLE_FORMATTING = Pattern.compile(
            "[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F"
                    + "\\u200B\\u200C\\u200E\\u200F\\u202A-\\u202E\\u2060\\u2066-\\u2069\\uFEFF]");

    public LyricsDocument parse(byte[] source) {
        if (source.length > MAX_DOCUMENT_BYTES) {
            throw new IllegalArgumentException("LRC document exceeds 2 MiB");
        }
        String value = new String(source, StandardCharsets.UTF_8).replace("\uFEFF", "");
        long offset = readOffset(value);
        List<Draft> drafts = new ArrayList<>();
        for (String rawLine : value.split("\\R", -1)) {
            Matcher timestamps = LINE_TIME.matcher(rawLine);
            List<Long> starts = new ArrayList<>();
            int contentStart = 0;
            while (timestamps.find()) {
                starts.add(Math.max(0, millis(timestamps.group(1), timestamps.group(2), timestamps.group(3)) + offset));
                contentStart = timestamps.end();
            }
            if (starts.isEmpty()) continue;
            String content = cleanVisibleText(rawLine.substring(contentStart)).strip();
            if (content.isEmpty()) continue;
            for (long start : starts) drafts.add(new Draft(start, content));
        }
        drafts.sort(Comparator.comparingLong(Draft::startMillis));
        List<LyricLine> lines = new ArrayList<>(drafts.size());
        for (int i = 0; i < drafts.size(); i++) {
            Draft draft = drafts.get(i);
            long next = i + 1 < drafts.size() ? drafts.get(i + 1).startMillis() : draft.startMillis() + 4_000;
            long end = Math.max(draft.startMillis() + 250, next);
            Enhanced enhanced = enhancedWords(draft.content(), draft.startMillis(), end, offset);
            lines.add(new LyricLine(draft.startMillis(), end, enhanced.text(), enhanced.words(),
                    "v1", false, null, null));
        }
        return new LyricsDocument(lines);
    }

    public boolean hasWordTiming(LyricsDocument document) {
        return document.lines().stream().anyMatch(line -> line.words().size() > 1
                && line.words().stream().map(TimedWord::startMillis).distinct().count() > 1);
    }

    private static Enhanced enhancedWords(String content, long lineStart, long lineEnd, long offset) {
        Matcher matcher = WORD_TIME.matcher(content);
        List<WordStart> starts = new ArrayList<>();
        StringBuilder visible = new StringBuilder();
        int cursor = 0;
        long pendingStart = lineStart;
        while (matcher.find()) {
            String before = content.substring(cursor, matcher.start());
            if (!before.isEmpty()) {
                visible.append(before);
                starts.add(new WordStart(pendingStart, before));
            }
            pendingStart = Math.max(lineStart,
                    millis(matcher.group(1), matcher.group(2), matcher.group(3)) + offset);
            cursor = matcher.end();
        }
        String tail = content.substring(cursor);
        if (!tail.isEmpty()) {
            visible.append(tail);
            starts.add(new WordStart(pendingStart, tail));
        }
        String text = cleanVisibleText(visible.toString()).strip();
        if (starts.isEmpty()) {
            return new Enhanced(text, List.of(new TimedWord(lineStart, lineEnd, text)));
        }
        List<TimedWord> words = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            WordStart word = starts.get(i);
            long end = i + 1 < starts.size() ? starts.get(i + 1).startMillis() : lineEnd;
            if (!word.text().isBlank()) words.add(new TimedWord(word.startMillis(), Math.max(word.startMillis(), end), word.text()));
        }
        return new Enhanced(text, words.isEmpty() ? List.of(new TimedWord(lineStart, lineEnd, text)) : words);
    }

    private static long readOffset(String value) {
        for (String line : value.split("\\R")) {
            Matcher matcher = OFFSET.matcher(line.strip());
            if (matcher.matches()) return Long.parseLong(matcher.group(1));
        }
        return 0;
    }

    private static String cleanVisibleText(String value) {
        String normalized = value.replace('\u2800', ' ').replace('\u3164', ' ')
                .replace('\uFFA0', ' ').replace('\u115F', ' ').replace('\u1160', ' ');
        return INVISIBLE_FORMATTING.matcher(normalized).replaceAll("")
                .replaceAll("\\p{Z}+", " ")
                .replaceAll("[\\t ]+", " ");
    }

    private static long millis(String minutes, String seconds, String fraction) {
        long result = Long.parseLong(minutes) * 60_000L + Long.parseLong(seconds) * 1_000L;
        if (fraction == null) return result;
        String normalized = (fraction + "000").substring(0, 3);
        if (fraction.length() == 1) normalized = fraction + "00";
        else if (fraction.length() == 2) normalized = fraction + "0";
        return result + Long.parseLong(normalized);
    }

    private record Draft(long startMillis, String content) { }
    private record WordStart(long startMillis, String text) { }
    private record Enhanced(String text, List<TimedWord> words) { }
}
