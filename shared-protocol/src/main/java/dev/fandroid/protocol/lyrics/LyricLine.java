package dev.fandroid.protocol.lyrics;

import java.util.List;

public record LyricLine(
        long startMillis,
        long endMillis,
        String text,
        List<TimedWord> words,
        String agent,
        boolean background,
        String translation,
        String romanization
) {
    public LyricLine {
        words = List.copyOf(words);
    }
}

