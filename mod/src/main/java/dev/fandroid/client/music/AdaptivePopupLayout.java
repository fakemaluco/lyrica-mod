package dev.fandroid.client.music;

/** Content budgets use the chosen outer frame, so changing media never resizes that frame. */
public final class AdaptivePopupLayout {
    public enum Mode {
        VIDEO_LYRICS, VIDEO_ONLY, PHOTO_LYRICS, PHOTO_PLAYER, TEXT_PLAYER;
        public boolean video() { return this == VIDEO_LYRICS || this == VIDEO_ONLY; }
        public boolean lyrics() { return this == VIDEO_LYRICS || this == PHOTO_LYRICS; }
        public boolean compact() { return this == PHOTO_PLAYER || this == TEXT_PLAYER; }
    }
    public record Player(ResponsiveLyricsLayout.Bounds artwork, ResponsiveLyricsLayout.Bounds metadata,
                         ResponsiveLyricsLayout.Bounds previous, ResponsiveLyricsLayout.Bounds play,
                         ResponsiveLyricsLayout.Bounds next, ResponsiveLyricsLayout.Bounds settings,
                         ResponsiveLyricsLayout.Bounds progress, int timeY, boolean times) { }
    public static Mode choose(double width, double height, double aspect, boolean hasLyrics, double textScale) {
        width = Double.isFinite(width) ? Math.max(1,width) : 1;
        height = Double.isFinite(height) ? Math.max(1,height) : 1;
        double font = Double.isFinite(textScale) ? Math.clamp(textScale,.8,1.8) : 1;
        boolean video = Double.isFinite(aspect) && aspect > 0;
        if (video) {
            double minimumLyrics = 136 * font;
            double minimumVideo = 72;
            if (hasLyrics && height >= 128 * font && width >= minimumVideo + minimumLyrics + 24)
                return Mode.VIDEO_LYRICS;
            return Mode.VIDEO_ONLY;
        }
        if (hasLyrics && width >= 240*font && height >= 170*font) return Mode.PHOTO_LYRICS;
        return width >= 160 && height >= 100 ? Mode.PHOTO_PLAYER : Mode.TEXT_PLAYER;
    }
    public static CanvasPopupLayout.Size resize(int width, int height) {
        return new CanvasPopupLayout.Size(Math.clamp(width,96,900),Math.clamp(height,64,600));
    }
    public static Player player(int width, int height, boolean artwork) {
        width = Math.max(96,width); height = Math.max(64,height);
        int edge = Math.clamp(Math.min(width,height)/16,6,10);
        int button = Math.clamp(height/8,12,20), gap = Math.clamp(width/24,5,10);
        boolean times = height >= 100;
        int bar = height >= 100 ? 4 : 3;
        int progressY = height-edge-bar-(times?9:0);
        int controlsY = progressY-5-button;
        int playX = (width-button)/2;
        int settings = Math.clamp(button,12,18);
        int cover = artwork ? Math.max(24,Math.min(72,Math.min(width/3,controlsY-edge*2))) : 0;
        int metadataX = edge + (artwork ? cover+8 : 0);
        int metadataY = artwork ? edge+Math.max(0,(cover-25)/2) : edge;
        return new Player(new ResponsiveLyricsLayout.Bounds(edge,edge,cover,cover),
                new ResponsiveLyricsLayout.Bounds(metadataX,metadataY,Math.max(1,width-edge-metadataX-settings-4),Math.max(1,controlsY-metadataY-4)),
                new ResponsiveLyricsLayout.Bounds(playX-button-gap,controlsY,button,button),
                new ResponsiveLyricsLayout.Bounds(playX,controlsY,button,button),
                new ResponsiveLyricsLayout.Bounds(playX+button+gap,controlsY,button,button),
                new ResponsiveLyricsLayout.Bounds(width-edge-settings,edge,settings,settings),
                new ResponsiveLyricsLayout.Bounds(edge,progressY,width-edge*2,bar),progressY+bar+2,times);
    }
    private AdaptivePopupLayout() { }
}
