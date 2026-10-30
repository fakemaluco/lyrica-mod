package dev.fandroid.protocol.lyrics;

public enum LyricsSource {
    UNKNOWN("UNKNOWN"),
    IMPORTED("IMPORTED"),
    REMOTE("ONLINE"),
    SPICY_TTML("SPICY TTML"),
    SPICY_CACHE("SPICY CACHE");

    private final String label;

    LyricsSource(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
