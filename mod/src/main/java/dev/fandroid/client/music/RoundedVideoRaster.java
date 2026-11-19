package dev.fandroid.client.music;

/** Reusable cover-crop sampling and physical-pixel alpha mask for live Canvas frames. */
public final class RoundedVideoRaster {
    private final int sourceWidth, sourceHeight, width, height;
    private final int[] x0, x1, y0, y1, alpha;
    private final int[] tx, ty;
    private final boolean canvasSurface;
    private final int blurRadius;
    private final int blurMix;
    private final int[] blurA, blurB;
    private final int[] shade;
    public int width() { return width; }
    public int height() { return height; }
    public RoundedVideoRaster(int sourceWidth, int sourceHeight, int logicalWidth, int logicalHeight, int radius, double density) {
        this(sourceWidth, sourceHeight, logicalWidth, logicalHeight, radius, density, false);
    }
    public RoundedVideoRaster(int sourceWidth, int sourceHeight, int logicalWidth, int logicalHeight, int radius, double density, boolean canvasSurface) {
        this(sourceWidth,sourceHeight,logicalWidth,logicalHeight,radius,density,canvasSurface,true);
    }
    public RoundedVideoRaster(int sourceWidth, int sourceHeight, int logicalWidth, int logicalHeight, int radius, double density, boolean canvasSurface, boolean soften) {
        if (sourceWidth <= 0 || sourceHeight <= 0 || logicalWidth <= 0 || logicalHeight <= 0) throw new IllegalArgumentException("Invalid video bounds");
        this.sourceWidth = sourceWidth; this.sourceHeight = sourceHeight;
        this.canvasSurface = canvasSurface;
        int maxEdge = Math.min(1024, Math.max(256, Math.max(sourceWidth, sourceHeight)));
        double ppu = Math.min(Math.max(.05, density), maxEdge / (double) Math.max(logicalWidth, logicalHeight));
        width = Math.max(1, (int) Math.ceil(logicalWidth * ppu)); height = Math.max(1, (int) Math.ceil(logicalHeight * ppu));
        x0 = new int[width]; x1 = new int[width]; tx = new int[width];
        y0 = new int[height]; y1 = new int[height]; ty = new int[height]; alpha = new int[width * height];
        double fit = Math.max(logicalWidth / (double) sourceWidth, logicalHeight / (double) sourceHeight);
        blurRadius = Math.clamp((int) Math.ceil(1.4 / fit), 1, 4);
        blurMix = (int) Math.round(256 * Math.min(1, 1.4 / (fit * blurRadius)));
        blurA = canvasSurface && soften ? new int[sourceWidth * sourceHeight] : null;
        blurB = canvasSurface && soften ? new int[sourceWidth * sourceHeight] : null;
        shade = new int[height];
        for (int y = 0; y < height; y++)
            shade[y] = canvasSurface ? (int) Math.round(256 * (1 - .44 * smooth((y / (double) height - .55) / .45))) : 256;
        double cropW = logicalWidth / fit, cropH = logicalHeight / fit;
        for (int x = 0; x < width; x++) {
            double u = (sourceWidth - cropW) / 2 + (x + .5) / width * cropW - .5;
            int base = (int) Math.floor(u); tx[x] = (int) Math.round((u - base) * 256);
            x0[x] = Math.clamp(base, 0, sourceWidth - 1); x1[x] = Math.clamp(base + 1, 0, sourceWidth - 1);
        }
        for (int y = 0; y < height; y++) {
            double v = (sourceHeight - cropH) / 2 + (y + .5) / height * cropH - .5;
            int base = (int) Math.floor(v); ty[y] = (int) Math.round((v - base) * 256);
            y0[y] = sourceWidth * Math.clamp(base, 0, sourceHeight - 1);
            y1[y] = sourceWidth * Math.clamp(base + 1, 0, sourceHeight - 1);
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
        if (canvasSurface && blurA!=null) {
            blur(source, blurA, sourceWidth, sourceHeight, blurRadius, true);
            blur(blurA, blurB, sourceWidth, sourceHeight, blurRadius, false);
            for (int i = 0; i < blurB.length; i++) blurB[i] = mix(source[i], blurB[i], blurMix);
            source = blurB;
        }
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int index = y * width + x;
            int mask = alpha[index];
            if (mask == 0) {
                output[index] = 0;
                continue;
            }
            int top = mix(source[y0[y] + x0[x]], source[y0[y] + x1[x]], tx[x]);
            int bottom = mix(source[y1[y] + x0[x]], source[y1[y] + x1[x]], tx[x]);
            int color = mix(top, bottom, ty[y]);
            int opacity = (color >>> 24) * mask / 255;
            int rgb = shade[y] == 256 ? color : mix(0, color, shade[y]);
            output[index] = opacity << 24 | rgb & 0xFFFFFF;
        }
    }
    private static double smooth(double value) { double t = Math.clamp(value, 0, 1); return t * t * (3 - 2 * t); }
    /** Sliding windows keep the source softening linear in the decoded frame size. */
    private static int mix(int first, int second, int weight) {
        // Two channels fit in each product because their weights are 16 bits apart.
        int inverse = 256 - weight;
        int lanes = 0x00FF00FF;
        int rb = ((first & lanes) * inverse + (second & lanes) * weight + 0x00800080) >>> 8;
        int ag = ((first >>> 8 & lanes) * inverse + (second >>> 8 & lanes) * weight + 0x00800080) >>> 8;
        return (rb & lanes) | (ag & lanes) << 8;
    }

    public static void renderAmbient(int[] source, int width, int height, int size, int[] output, int[] scratch) {
        for (int y = 0; y < size; y++) {
            int row = (y * height / size) * width;
            for (int x = 0; x < size; x++) scratch[y * size + x] = source[row + x * width / size] | 0xFF000000;
        }
        blur(scratch, output, size, size, 4, true);
        blur(output, scratch, size, size, 4, false);
        blur(scratch, output, size, size, 4, true);
    }

    private static void blur(int[] input, int[] output, int sourceWidth, int sourceHeight, int blurRadius, boolean horizontal) {
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
