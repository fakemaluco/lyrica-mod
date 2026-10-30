package dev.fandroid.client.music;

/** Cached glass material, adapted from Spicy Mobile's GlassButton / SpicyGlass.
 * Geometry is sampled at framebuffer density; interaction only changes GPU opacity. */
public final class GlassButtonRaster {
    public record Surface(int width, int height, int padding, double density, int[] base, int[] light) { }

    public static Surface render(int width, int height, int radius, double density) {
        if (width < 1 || height < 1 || width > 1024 || height > 256 || !Double.isFinite(density))
            throw new IllegalArgumentException("Invalid glass surface");
        density = Math.clamp(density, .5, 4);
        double unit = Math.min(Math.min(width, height), 42) / 42.0;
        int pad = Math.max(2, (int) Math.ceil(18 * unit));
        int rw = (int) Math.ceil((width + 2 * pad) * density);
        int rh = (int) Math.ceil((height + 2 * pad) * density);
        int[] base = new int[rw * rh], light = new int[base.length];
        double r = Math.clamp(radius, 0, Math.min(width, height) / 2.0);
        double sigma = Math.max(1, 6 * unit), shift = 10 * unit, spread = 8 * unit;
        for (int py = 0; py < rh; py++) for (int px = 0; px < rw; px++) {
            double x = (px + .5) / density - pad, y = (py + .5) / density - pad;
            double shape = coverage(x, y, width, height, r, density);
            double inner = coverage(x - 1, y - 1, width - 2, height - 2, Math.max(0, r - 1), density);
            double top = Math.max(0, shape - coverage(x, y - 1, width, height, r, density));
            double bottom = Math.max(0, shape - coverage(x, y + 1, width, height, r, density));
            double shadowDistance = Math.max(0, distance(x - spread, y - shift - spread,
                    Math.max(1, width - 2 * spread), Math.max(1, height - 2 * spread), Math.max(0, r - spread)));
            double shadow = .42 * Math.exp(-shadowDistance * shadowDistance / (2 * sigma * sigma));
            // The backdrop copy occludes the cast inside the glass, as Haze does on Mobile.
            int color = alpha(0x080A12, shadow * (1 - shape));
            color = over(alpha(0, .10 * shape), color);
            color = over(alpha(0xFFFFFF, .16 * Math.max(0, shape - inner)), color);
            color = over(alpha(0xFFFFFF, .36 * top), color);
            color = over(alpha(0xFFFFFF, .18 * bottom), color);
            base[py * rw + px] = color;
            light[py * rw + px] = alpha(0xFFFFFF, .12 * shape);
        }
        return new Surface(rw, rh, pad, density, base, light);
    }

    public static double coverage(double x, double y, double width, double height, double radius, double density) {
        return Math.clamp(.5 - distance(x, y, width, height, radius) * density, 0, 1);
    }

    private static double distance(double x, double y, double width, double height, double radius) {
        double qx = Math.abs(x - width / 2) - (width / 2 - radius);
        double qy = Math.abs(y - height / 2) - (height / 2 - radius);
        return Math.hypot(Math.max(0, qx), Math.max(0, qy)) + Math.min(Math.max(qx, qy), 0) - radius;
    }

    /** A tiny, separable blur, used once for each static artwork, never for each button/frame. */
    public static int[] frost(int[] source, int width, int height) {
        if (width < 1 || height < 1 || source.length != width * height) throw new IllegalArgumentException("Invalid backdrop");
        int[] a = source.clone(), b = new int[a.length];
        for (int pass = 0; pass < 4; pass++) {
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int red = 0, green = 0, blue = 0;
                for (int delta = -4; delta <= 4; delta++) {
                    int xx = (pass & 1) == 0 ? Math.clamp(x + delta, 0, width - 1) : x;
                    int yy = (pass & 1) == 0 ? y : Math.clamp(y + delta, 0, height - 1);
                    int c = a[yy * width + xx]; red += c >>> 16 & 255; green += c >>> 8 & 255; blue += c & 255;
                }
                b[y * width + x] = 0xFF000000 | red / 9 << 16 | green / 9 << 8 | blue / 9;
            }
            int[] swap = a; a = b; b = swap;
        }
        return a;
    }

    private static int alpha(int color, double opacity) { return (int) Math.round(255 * Math.clamp(opacity, 0, 1)) << 24 | color; }
    private static int over(int foreground, int background) {
        double f = (foreground >>> 24) / 255.0, b = (background >>> 24) / 255.0 * (1 - f), total = f + b;
        if (total == 0) return 0;
        int result = (int) Math.round(total * 255) << 24;
        for (int shift = 0; shift < 24; shift += 8)
            result |= (int) Math.round(((foreground >>> shift & 255) * f + (background >>> shift & 255) * b) / total) << shift;
        return result;
    }
    private GlassButtonRaster() { }
}
