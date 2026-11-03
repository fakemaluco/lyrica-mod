package dev.fandroid.client.music;

/** Geometry for a persistent video column beside the lyrics. Saved width belongs to
 * the lyrics surface; a temporary video column never overwrites that preference. */
public final class CanvasPopupLayout {
    public record Size(int width, int height) { }
    public record UtilityRow(int x, int y, int size, int gap) {
        public int left(int index) { return x + index * (size + gap); }
    }
    public record PlayerSliders(ResponsiveLyricsLayout.Bounds progress, ResponsiveLyricsLayout.Bounds volume,
                                int controlsY, int timeY, boolean volumeVisible, boolean volumeLeft) { }

    /** A full-width timeline below transport; the vertical volume clears both rows and utility buttons. */
    public static PlayerSliders playerSliders(int width, int height, int utilityCount, boolean video, int timeHeight) {
        int minimum = Math.min(width, height);
        int edge = Math.clamp(minimum / 24, 5, 10);
        int control = Math.clamp(minimum / 11, 13, 24);
        int barHeight = Math.clamp((int) Math.round(minimum * (video ? .015 : .022)), 3, 7);
        barHeight += barHeight % 2;
        int barY = height - edge - Math.max(5, timeHeight) - 3 - barHeight;
        int controlsY = Math.max(edge, barY - Math.clamp(minimum / 22, 7, 12) - control);
        var progress = new ResponsiveLyricsLayout.Bounds(edge, barY, Math.max(1, width - 2 * edge), barHeight);
        int volumeHeight = Math.clamp((int) Math.round(height * .32), 36, 110);
        int volumeWidth = Math.clamp((int) Math.round(minimum * .04), 8, 12);
        var utilities = utilityRow(width, height, utilityCount, video);
        int low = utilities.y() + utilities.size() + 8, high = controlsY - 12 - volumeHeight;
        boolean visible = minimum >= 84 && high >= low;
        int volumeY = visible ? Math.clamp((height - volumeHeight) / 2, low, high) : low;
        var volume = new ResponsiveLyricsLayout.Bounds(video ? edge : width - edge - volumeWidth,
                volumeY, volumeWidth, volumeHeight);
        return new PlayerSliders(progress, volume, controlsY, barY + barHeight + 3, visible, video);
    }
    /** Utility controls stay over the video, clear of the playback row and volume slider. */
    public static UtilityRow utilityRow(int width, int height, int count, boolean leftAligned) {
        count = Math.clamp(count, 1, 5);
        int minimum = Math.min(width, height);
        int edge = Math.clamp(minimum / 24, 5, 10);
        int gap = Math.clamp(minimum / 24, 4, 6);
        int size = Math.min(Math.clamp(minimum / 8, 16, 24),
                Math.max(1, (width - edge * 2 - gap * (count - 1)) / count));
        int rowWidth = count * size + gap * (count - 1);
        return new UtilityRow(leftAligned ? edge : (width - rowWidth) / 2, edge, size, gap);
    }
    /** Convert the visible dragged frame back to saved lyric dimensions. The video
     * contribution is recomputed from the NEW height, so the grip stays under the mouse. */
    public static Size resize(int outerWidth, int outerHeight, double aspect, boolean square, int storedWidth) {
        if (square) return new Size(storedWidth, Math.clamp(Math.min(outerWidth, outerHeight), 64, 600));
        int height = Math.clamp(outerHeight, 64, 600);
        int width = Math.max(96, outerWidth);
        if (Double.isFinite(aspect) && aspect > 0) {
            double ratio = columnAspect(aspect);
            height = Math.min(height, Math.max(64, (int) Math.floor((width - 96) / ratio)));
            width -= expandedWidth(0, height, aspect);
        }
        return new Size(Math.clamp(width, 96, 900), height);
    }
    public record Layout(ResponsiveLyricsLayout.Bounds artwork, ResponsiveLyricsLayout.Bounds lyrics,
                         ResponsiveLyricsLayout.Bounds videoSurface) { }
    public static int expandedWidth(int savedWidth, int savedHeight, double aspect) {
        if (!Double.isFinite(aspect) || aspect <= 0) return savedWidth;
        return savedWidth + Math.max(1, (int) Math.round(savedHeight * columnAspect(aspect)));
    }
    public static Layout columns(int width, int height, double aspect) {
        int padding = Math.max(0, Math.min(12, Math.min(width, height) / 8));
        int minimumLyrics = Math.max(1, Math.min(136, width / 2));
        int artWidth = Math.clamp((int) Math.round(height * columnAspect(aspect)), 1, Math.max(1, width - padding * 2 - minimumLyrics));
        int lyricsX = artWidth + padding;
        int feather = Math.max(1, Math.min((int) Math.round(artWidth * .32), width - artWidth - padding));
        return new Layout(new ResponsiveLyricsLayout.Bounds(0, 0, artWidth, Math.max(1, height)),
                new ResponsiveLyricsLayout.Bounds(lyricsX, padding, Math.max(1, width - padding - lyricsX), Math.max(1, height - padding * 2)),
                new ResponsiveLyricsLayout.Bounds(0, 0, artWidth + feather, Math.max(1, height)));
    }
    private static double columnAspect(double aspect) { return Double.isFinite(aspect) && aspect > 0 ? Math.clamp(aspect, .55, 1) : 1; }
    private CanvasPopupLayout() { }
}
