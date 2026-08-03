package dev.fandroid.protocol.lyrics;

public record TimedWord(long startMillis, long endMillis, String text,
                        boolean joinPrevious, String romanization) {
    public TimedWord(long startMillis, long endMillis, String text) {
        this(startMillis, endMillis, text, false, null);
    }

    public TimedWord(long startMillis, long endMillis, String text, boolean joinPrevious) {
        this(startMillis, endMillis, text, joinPrevious, null);
    }
}
