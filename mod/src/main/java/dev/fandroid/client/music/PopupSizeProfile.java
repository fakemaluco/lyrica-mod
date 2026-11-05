package dev.fandroid.client.music;

/** Content, rather than the current frame size, selects the saved popup dimensions. */
public enum PopupSizeProfile {
    LYRICS_ONLY(false), COVER_WITH_LYRICS(false), COVER_ONLY(false),
    CANVAS_WITH_LYRICS(true), CANVAS_ONLY(true), EMPTY(false);

    private final boolean video;
    PopupSizeProfile(boolean video) { this.video = video; }
    public boolean video() { return video; }

    public static PopupSizeProfile choose(boolean lyrics, boolean video, boolean artwork) {
        if (video) return lyrics ? CANVAS_WITH_LYRICS : CANVAS_ONLY;
        if (artwork) return lyrics ? COVER_WITH_LYRICS : COVER_ONLY;
        return lyrics ? LYRICS_ONLY : EMPTY;
    }

    public record Size(int width, int height) { }
}
