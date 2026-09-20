package dev.fandroid.protocol.lyrics;

public enum LyricsSource {
    UNKNOWN("UNKNOWN"),
    IMPORTED("IMPORTED"),
    SPICY_TTML("SPICY TTML"),
    SPICY_CACHE("SPICY CACHE"),
    AMLL("AMLL"),
    BETTER_LYRICS("BETTER LYRICS"),
    LRCLIB("LRCLIB");

    private final String label;

    LyricsSource(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
