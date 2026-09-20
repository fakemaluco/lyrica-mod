package dev.fandroid.client;

public enum LyricsTextAlignment {
    LEFT,
    CENTER,
    SOURCE;

    public LyricsTextAlignment next() {
        LyricsTextAlignment[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    public LyricsTextAlignment previous() {
        LyricsTextAlignment[] values = values();
        return values[Math.floorMod(ordinal() - 1, values.length)];
    }
}
