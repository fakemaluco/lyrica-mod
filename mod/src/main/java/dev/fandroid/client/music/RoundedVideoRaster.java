package dev.fandroid.client.music;

/** Reusable cover-crop sampling and physical-pixel alpha mask for live Canvas frames. */
public final class RoundedVideoRaster {
    private final int sourceWidth, sourceHeight, width, height;
    private final int[] x0, x1, y0, y1, alpha;
    private final double[] tx, ty;
    private final boolean canvasSurface;
    private final int blurRadius;
    private final double blurMix;
    private final int[] blurA, blurB;
    private final double[] shade;
    public int width() { return width; }
    public int height() { return height; }
    public RoundedVideoRaster(int sourceWidth, int sourceHeight, int logicalWidth, int logicalHeight, int radius, double density) {
        this(sourceWidth, sourceHeight, logicalWidth, logicalHeight, radius, density, false);
    }
    public RoundedVideoRaster(int sourceWidth, int sourceHeight, int logicalWidth, int logicalHeight, int radius, double density, boolean canvasSurface) {
        if (sourceWidth <= 0 || sourceHeight <= 0 || logicalWidth <= 0 || logicalHeight <= 0) throw new IllegalArgumentException("Invalid video bounds");
        this.sourceWidth = sourceWidth; this.sourceHeight = sourceHeight;
        this.canvasSurface = canvasSurface;
        double ppu = Math.max(.05, Math.min(density, Math.min(1024.0 / logicalWidth, 1024.0 / logicalHeight)));
        width = Math.max(1, (int) Math.ceil(logicalWidth * ppu)); height = Math.max(1, (int) Math.ceil(logicalHeight * ppu));
        x0 = new int[width]; x1 = new int[width]; tx = new double[width];
        y0 = new int[height]; y1 = new int[height]; ty = new double[height]; alpha = new int[width * height];
        double fit = Math.max(logicalWidth / (double) sourceWidth, logicalHeight / (double) sourceHeight);
        blurRadius = Math.clamp((int) Math.ceil(1.4 / fit), 1, 4);
        blurMix = Math.min(1, 1.4 / (fit * blurRadius));
        blurA = canvasSurface ? new int[sourceWidth * sourceHeight] : null;
        blurB = canvasSurface ? new int[sourceWidth * sourceHeight] : null;
        shade = new double[height];
        for (int y = 0; y < height; y++) shade[y] = canvasSurface ? 1 - .44 * smooth((y / (double) height - .55) / .45) : 1;
        double cropW = logicalWidth / fit, cropH = logicalHeight / fit;
        for (int x = 0; x < width; x++) {
            double u = (sourceWidth - cropW) / 2 + (x + .5) / width * cropW - .5;
            int base = (int) Math.floor(u); tx[x] = u - base;
            x0[x] = Math.clamp(base, 0, sourceWidth - 1); x1[x] = Math.clamp(base + 1, 0, sourceWidth - 1);
        }
        for (int y = 0; y < height; y++) {
            double v = (sourceHeight - cropH) / 2 + (y + .5) / height * cropH - .5;
            int base = (int) Math.floor(v); ty[y] = v - base;
            y0[y] = Math.clamp(base, 0, sourceHeight - 1); y1[y] = Math.clamp(base + 1, 0, sourceHeight - 1);
        }
        double r = Math.min(radius * ppu, Math.min(width, height) / 2.0);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            double qx = canvasSurface ? r - x - .5 : Math.abs(x + .5 - width / 2.0) - (width / 2.0 - r);
            double qy = Math.abs(y + .5 - height / 2.0) - (height / 2.0 - r);
            double d = Math.hypot(Math.max(qx, 0), Math.max(qy, 0)) + Math.min(Math.max(qx, qy), 0) - r;
            double feather = canvasSurface ? 1 - smooth((x / (double) width - .55) / .45) : 1;
            alpha[y * width + x] = (int) Math.round(Math.clamp(.5 - d, 0, 1) * feather * 255);
        }
    }
    public void render(int[] source, int[] output) {
        if (source.length != sourceWidth * sourceHeight || output.length != width * height) throw new IllegalArgumentException("Invalid frame dimensions");
        if (canvasSurface) {
            blur(source, blurA, true); blur(blurA, blurB, false);
            for (int i = 0; i < blurB.length; i++) {
                int mixed = 0;
                for (int shift = 0; shift <= 24; shift += 8) {
                    int original = source[i] >>> shift & 255;
                    mixed |= (int) Math.round(original + ((blurB[i] >>> shift & 255) - original) * blurMix) << shift;
                }
                blurB[i] = mixed;
            }
            source = blurB;
        }
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int a = source[y0[y] * sourceWidth + x0[x]], b = source[y0[y] * sourceWidth + x1[x]];
            int c = source[y1[y] * sourceWidth + x0[x]], d = source[y1[y] * sourceWidth + x1[x]];
            int color = 0;
            for (int shift = 0; shift <= 24; shift += 8) {
                double top = (a >>> shift & 255) + ((b >>> shift & 255) - (a >>> shift & 255)) * tx[x];
                double bottom = (c >>> shift & 255) + ((d >>> shift & 255) - (c >>> shift & 255)) * tx[x];
                int channel = (int) Math.round(top + (bottom - top) * ty[y]);
                if (shift == 24) channel = channel * alpha[y * width + x] / 255;
                else channel = (int) Math.round(channel * shade[y]);
                color |= channel << shift;
            }
            output[y * width + x] = color;
        }
    }
    private static double smooth(double value) { double t = Math.clamp(value, 0, 1); return t * t * (3 - 2 * t); }
    /** Sliding windows keep the source softening linear in the decoded frame size. */
    private void blur(int[] input, int[] output, boolean horizontal) {
        int lines = horizontal ? sourceHeight : sourceWidth, length = horizontal ? sourceWidth : sourceHeight;
        int step = horizontal ? 1 : sourceWidth, count = blurRadius * 2 + 1;
        for (int line = 0; line < lines; line++) {
            int base = horizontal ? line * sourceWidth : line;
            int r = 0, g = 0, b = 0, a = 0;
            for (int i = -blurRadius; i <= blurRadius; i++) {
                int color = input[base + Math.clamp(i, 0, length - 1) * step];
                a += color >>> 24; r += color >>> 16 & 255; g += color >>> 8 & 255; b += color & 255;
            }
            for (int pos = 0; pos < length; pos++) {
                output[base + pos * step] = a / count << 24 | r / count << 16 | g / count << 8 | b / count;
                int old = input[base + Math.clamp(pos - blurRadius, 0, length - 1) * step];
                int next = input[base + Math.clamp(pos + blurRadius + 1, 0, length - 1) * step];
                a += (next >>> 24) - (old >>> 24); r += (next >>> 16 & 255) - (old >>> 16 & 255);
                g += (next >>> 8 & 255) - (old >>> 8 & 255); b += (next & 255) - (old & 255);
            }
        }
    }
}
