package dev.fandroid.protocol.lyrics;

import java.util.ArrayList;
import java.util.List;

/** Builds a display document when the provider did not label backing vocals. */
public final class ParentheticalBackingVocals {
    private ParentheticalBackingVocals() { }

    public static LyricsDocument infer(LyricsDocument document) {
        if (document.lines().stream().anyMatch(LyricLine::background)
                || document.groups().stream().anyMatch(group -> !group.backingVocals().isEmpty())) return document;

        List<LyricVocalGroup> groups = new ArrayList<>(document.groups().size());
        boolean changed = false;
        for (LyricVocalGroup group : document.groups()) {
            LyricVocalGroup inferred = infer(group);
            groups.add(inferred);
            changed |= inferred != group;
        }
        return changed ? new LyricsDocument(groups, document.metadata()) : document;
    }

    private static LyricVocalGroup infer(LyricVocalGroup group) {
        LyricLine source = group.lead();
        Parts original = split(source.text());
        if (original.backing().isEmpty()) return group;

        List<TimedRange> timed = align(source, null);
        if (timed.isEmpty() && !source.words().isEmpty()) {
            // Some sources carry the markers in the written line but omit them from their timed words.
            boolean[] markers = new boolean[source.text().length()];
            for (Range range : original.backing()) {
                markers[range.start() - 1] = true;
                markers[range.end()] = true;
            }
            timed = align(source, markers);
        }
        Parts translation = alternative(source.translation(), original);
        Parts romanization = alternative(source.romanization(), original);
        List<LyricLine> backing = new ArrayList<>();
        for (int index = 0; index < original.backing().size(); index++) {
            backing.add(voice(source, List.of(original.backing().get(index)), timed, true,
                    translation == null ? null : text(source.translation(), List.of(translation.backing().get(index))),
                    romanization == null ? null : text(source.romanization(), List.of(romanization.backing().get(index)))));
        }
        LyricLine lead = original.lead().isEmpty() ? backing.removeFirst()
                : voice(source, original.lead(), timed, false,
                        translation == null ? source.translation() : text(source.translation(), translation.lead()),
                        romanization == null ? null : text(source.romanization(), romanization.lead()));
        return new LyricVocalGroup(group.startMillis(), group.endMillis(), lead, backing, group.alignment());
    }

    private record Range(int start, int end) { }
    private record Parts(List<Range> lead, List<Range> backing) { }
    private record TimedRange(int start, int end, TimedWord word) { }

    private static Parts split(String text) {
        List<Range> lead = new ArrayList<>();
        List<Range> backing = new ArrayList<>();
        int depth = 0;
        int opening = 0;
        int cursor = 0;
        for (int index = 0; index < text.length(); index++) {
            char letter = text.charAt(index);
            if (letter == '(') {
                if (depth++ == 0) opening = index;
            } else if (letter == ')' && depth > 0 && --depth == 0
                    && !text.substring(opening + 1, index).isBlank()) {
                addRange(lead, text, cursor, opening);
                backing.add(new Range(opening + 1, index));
                cursor = index + 1;
            }
        }
        addRange(lead, text, cursor, text.length());
        return new Parts(lead, backing);
    }

    private static void addRange(List<Range> ranges, String text, int start, int end) {
        if (!text.substring(start, end).isBlank()) ranges.add(new Range(start, end));
    }

    private static Parts alternative(String value, Parts original) {
        if (value == null || value.isBlank()) return null;
        Parts alternative = split(value);
        if (alternative.backing().size() == original.backing().size()
                && alternative.lead().isEmpty() == original.lead().isEmpty()) return alternative;
        if (original.lead().isEmpty() && original.backing().size() == 1 && alternative.backing().isEmpty()) {
            return new Parts(List.of(), List.of(new Range(0, value.length())));
        }
        return null;
    }

    private static String text(String source, List<Range> ranges) {
        StringBuilder result = new StringBuilder();
        for (Range range : ranges) {
            String part = source.substring(range.start(), range.end()).strip();
            if (!result.isEmpty() && ",.;:!?，。！？、；".indexOf(part.charAt(0)) < 0) result.append(' ');
            result.append(part);
        }
        return result.toString();
    }

    private static LyricLine voice(LyricLine source, List<Range> ranges, List<TimedRange> timed, boolean backing,
                                   String translation, String romanization) {
        List<TimedWord> words = new ArrayList<>();
        for (Range range : ranges) {
            boolean first = true;
            for (TimedRange token : timed) {
                int start = Math.max(range.start(), token.start());
                int end = Math.min(range.end(), token.end());
                if (start >= end) continue;
                String piece = source.text().substring(start, end).strip();
                if (piece.isEmpty()) continue;
                String roman = romanizationForSlice(source.text(), token, start, end);
                TimedWord word = token.word();
                words.add(new TimedWord(word.startMillis(), word.endMillis(), piece, !first && word.joinPrevious(), roman));
                first = false;
            }
        }
        long start = words.stream().mapToLong(TimedWord::startMillis).min().orElse(source.startMillis());
        long end = words.stream().mapToLong(TimedWord::endMillis).max().orElse(source.endMillis());
        return new LyricLine(start, end, text(source.text(), ranges), words, source.agent(), backing, translation, romanization);
    }

    private static String romanizationForSlice(String text, TimedRange token, int start, int end) {
        String roman = token.word().romanization();
        if (roman == null) return null;
        if (start > token.start()) {
            if (!text.substring(token.start(), start).strip().equals("(")) return null;
            roman = roman.stripLeading();
            if (roman.startsWith("(")) roman = roman.substring(1).stripLeading();
        }
        if (end < token.end()) {
            if (!text.substring(end, token.end()).strip().equals(")")) return null;
            roman = roman.stripTrailing();
            if (roman.endsWith(")")) roman = roman.substring(0, roman.length() - 1).stripTrailing();
        }
        return roman;
    }

    /** Match timed fragments to the written line even when the provider supplies spacing separately. */
    private static List<TimedRange> align(LyricLine line, boolean[] markers) {
        List<TimedRange> result = new ArrayList<>();
        String text = line.text();
        int cursor = 0;
        for (TimedWord word : line.words()) {
            int start = -1;
            for (int index = 0; index < word.text().length(); index++) {
                char letter = word.text().charAt(index);
                if (Character.isWhitespace(letter) || markers != null && (letter == '(' || letter == ')')) continue;
                cursor = nextWrittenCharacter(text, cursor, markers);
                if (cursor == text.length() || text.charAt(cursor) != letter) return List.of();
                if (start < 0) start = cursor;
                cursor++;
            }
            if (start >= 0) result.add(new TimedRange(start, cursor, word));
        }
        return nextWrittenCharacter(text, cursor, markers) == text.length() ? result : List.of();
    }

    private static int nextWrittenCharacter(String text, int cursor, boolean[] markers) {
        while (cursor < text.length() && (Character.isWhitespace(text.charAt(cursor))
                || markers != null && markers[cursor])) cursor++;
        return cursor;
    }
}
