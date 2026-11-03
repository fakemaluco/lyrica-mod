package dev.fandroid.client.music;

/** Pointer-anchored resizing from any edge. Dimensions are independent, including video frames. */
public final class PopupResizeGesture {
    public static final int LEFT = 1, RIGHT = 2, TOP = 4, BOTTOM = 8;
    public static int edgesAt(double px, double py, double x, double y, double width, double height, double grip) {
        if (px < x - 4 || px > x + width + 4 || py < y - 4 || py > y + height + 4) return 0;
        int edges = px <= x + grip ? LEFT : px >= x + width - grip ? RIGHT : 0;
        edges |= py <= y + grip ? TOP : py >= y + height - grip ? BOTTOM : 0;
        if (px >= x + width - grip * 3 && py >= y + height - grip * 3) edges = RIGHT | BOTTOM;
        return edges;
    }
    public record Start(double x, double y, double width, double height, double pointerX, double pointerY, int edges) {
        public ResponsiveLyricsLayout.Bounds resize(double px, double py, double left, double top, double right, double bottom) {
            double minWidth = Math.min(96, Math.max(1, right - left));
            double minHeight = Math.min(64, Math.max(1, bottom - top));
            double dx = px - pointerX, dy = py - pointerY;
            double l = x, t = y, r = x + width, b = y + height;
            if ((edges & LEFT) != 0) l = Math.clamp(x + dx, Math.max(left, r - 900), Math.max(left, r - minWidth));
            if ((edges & RIGHT) != 0) r = Math.clamp(x + width + dx, Math.min(right, l + minWidth), Math.min(right, l + 900));
            if ((edges & TOP) != 0) t = Math.clamp(y + dy, Math.max(top, b - 600), Math.max(top, b - minHeight));
            if ((edges & BOTTOM) != 0) b = Math.clamp(y + height + dy, Math.min(bottom, t + minHeight), Math.min(bottom, t + 600));
            return new ResponsiveLyricsLayout.Bounds((int) Math.round(l), (int) Math.round(t),
                    Math.max(1, (int) Math.round(r - l)), Math.max(1, (int) Math.round(b - t)));
        }
    }
    private PopupResizeGesture() { }
}
