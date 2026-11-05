package dev.fandroid.client.music;

/** Saved geometry uses a stable design canvas; small GUI viewports shrink the presentation
 * without overwriting the user's chosen position or dimensions. */
public final class ResponsiveLyricsLayout {
    public static final int MIN_WIDTH = 96, MIN_HEIGHT = 64;
    public record Bounds(int x, int y, int width, int height) { }
    public static double scale(int width, int height) {
        return Math.max(.05, Math.min(1, Math.min(Math.max(1, width) / 960.0, Math.max(1, height) / 540.0)));
    }
    public static int design(double displayed, double scale) { return (int) Math.round(displayed / scale); }
    /** Below this size, shrink the contents as well as the frame so the header cannot
     * consume the entire lyrics area at the newly supported minimum dimensions. */
    public static double contentScale(double width, double height) {
        return Math.max(.05, Math.min(1, Math.min(width / 240.0, height / 160.0)));
    }
    public static Bounds popup(int viewportWidth, int viewportHeight, double x, double y, int width, int height) {
        double s = scale(viewportWidth, viewportHeight);
        int margin = Math.max(2, (int) Math.round(12 * s));
        int w = Math.clamp((int) Math.round(width * s), 1, Math.max(1, viewportWidth - margin * 2));
        int h = Math.clamp((int) Math.round(height * s), 1, Math.max(1, viewportHeight - margin * 2));
        return new Bounds(Math.clamp((int) Math.round(x * s), 0, Math.max(0, viewportWidth - w)),
                Math.clamp((int) Math.round(y * s), 0, Math.max(0, viewportHeight - h)), w, h);
    }
    private ResponsiveLyricsLayout() { }
}
