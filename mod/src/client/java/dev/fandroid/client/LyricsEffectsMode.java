package dev.fandroid.client;

public enum LyricsEffectsMode {
    AUTO,
    ENHANCED,
    COMPATIBLE;

    public LyricsEffectsMode next() {
        return values()[(ordinal() + 1) % values().length];
    }
}
