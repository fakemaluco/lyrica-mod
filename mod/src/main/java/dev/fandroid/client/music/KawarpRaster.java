package dev.fandroid.client.music;

import com.tx24.spicyplayer.lyrics.spicy.canvas.kawarp.KawarpBlurCore;

/** Native CPU adaptation of Spicy Mobile's fused Kawarp shader, backed by its original
 * eight-pass Kawase preparation. The image is warped, rather than a derived palette.
 * Reference: mobile 630587f / KawarpBackground.kt; @kawarp/core (MIT). */
public final class KawarpRaster {
    public static final int SIZE = 128;
    private KawarpRaster() { }

    public static float[] prepare(int[] argb, int width, int height, int blurPasses) {
        if (width <= 0 || height <= 0 || argb.length != width * height) throw new IllegalArgumentException("Invalid cover dimensions");
        float[] rgba = new float[argb.length * 4];
        for (int i = 0; i < argb.length; i++) {
            int c = argb[i];
            rgba[i * 4] = (c >>> 16 & 255) / 255f;
            rgba[i * 4 + 1] = (c >>> 8 & 255) / 255f;
            rgba[i * 4 + 2] = (c & 255) / 255f;
            rgba[i * 4 + 3] = (c >>> 24) / 255f;
        }
        return KawarpBlurCore.INSTANCE.processFast(rgba, width, height, Math.clamp(blurPasses, 1, 16));
    }

    /** All arithmetic is bounded; the caller owns and reuses the output buffer. */
    public static void render(float[] previous, float[] current, double blend, double time, int[] out) {
        if (current.length != SIZE * SIZE * 4 || out.length != SIZE * SIZE
                || previous != null && previous.length != current.length) throw new IllegalArgumentException("Invalid Kawarp buffer");
        double t = time * .05, amount = Math.clamp(blend, 0, 1);
        for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
            double u = (x + .5) / SIZE, v = 1 - (y + .5) / SIZE;
            double cx = u - .5, cy = v - .5, radius = Math.hypot(cx, cy);
            double k = Math.clamp(radius / .7, 0, 1);
            double weight = 1 - k * k * (3 - 2 * k);
            double wx = (.65 * noise(u * .35 + t, v * .35 + t * .7)
                    + .35 * noise(u * .9 + t * 1.2 + 100, v * .9 - t)) * weight;
            double wy = (.65 * noise(u * .35 - t * .8 + 50, v * .35 + t * .5 + 50)
                    + .35 * noise(u * .9 - t, v * .9 + t * 1.1 + 100)) * weight;
            double sx = Math.clamp(u + wx, 0, 1) * SIZE - .5;
            double sy = Math.clamp(v + wy, 0, 1) * SIZE - .5;
            double r = sample(current, sx, sy, 0), g = sample(current, sx, sy, 1), b = sample(current, sx, sy, 2);
            if (previous != null && amount < 1) {
                r = mix(sample(previous, sx, sy, 0), r, amount);
                g = mix(sample(previous, sx, sy, 1), g, amount);
                b = mix(sample(previous, sx, sy, 2), b, amount);
            }
            double vignette = 1 - (cx * cx + cy * cy) * .3;
            r *= vignette; g *= vignette; b *= vignette;
            double gray = .299 * r + .587 * g + .114 * b;
            double dither = (hash(x, y, Math.floor(time * 60)) - .5) * .008;
            r = Math.clamp(gray + (r - gray) * 1.5 + dither, 0, 1);
            g = Math.clamp(gray + (g - gray) * 1.5 + dither, 0, 1);
            b = Math.clamp(gray + (b - gray) * 1.5 + dither, 0, 1);
            double cssGray = .213 * r + .715 * g + .072 * b;
            int red = (int) Math.round(Math.clamp(cssGray + (r - cssGray) * 2.5, 0, 1) * .65 * 255);
            int green = (int) Math.round(Math.clamp(cssGray + (g - cssGray) * 2.5, 0, 1) * .65 * 255);
            int blue = (int) Math.round(Math.clamp(cssGray + (b - cssGray) * 2.5, 0, 1) * .65 * 255);
            out[y * SIZE + x] = 0xFF000000 | red << 16 | green << 8 | blue;
        }
    }

    public static double speed(boolean playing, double tempo, double intensity, double beatPhase) {
        if (!playing) return .1;
        double bpm = Double.isFinite(tempo) && tempo >= 40 && tempo <= 240 ? tempo : 120;
        double signal = Double.isFinite(intensity) ? Math.clamp(intensity, 0, 1) : 0;
        // A beat pulse is used only when the bridge actually supplies tempo analysis.
        boolean hasBeat = Double.isFinite(tempo) && tempo >= 40 && tempo <= 240 && Double.isFinite(beatPhase);
        double beat = hasBeat ? Math.exp(-5 * Math.clamp(beatPhase, 0, 1)) : 0;
        return Math.clamp(bpm / 120 * (.5 + .7 * signal) + 1.5 * signal * beat, .1, 3);
    }

    private static double sample(float[] data, double x, double y, int channel) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y);
        double fx = x - ix, fy = y - iy;
        int a = (Math.clamp(iy, 0, SIZE - 1) * SIZE + Math.clamp(ix, 0, SIZE - 1)) * 4 + channel;
        int b = (Math.clamp(iy, 0, SIZE - 1) * SIZE + Math.clamp(ix + 1, 0, SIZE - 1)) * 4 + channel;
        int c = (Math.clamp(iy + 1, 0, SIZE - 1) * SIZE + Math.clamp(ix, 0, SIZE - 1)) * 4 + channel;
        int d = (Math.clamp(iy + 1, 0, SIZE - 1) * SIZE + Math.clamp(ix + 1, 0, SIZE - 1)) * 4 + channel;
        return mix(mix(data[a], data[b], fx), mix(data[c], data[d], fx), fy);
    }
    private static double mix(double a, double b, double t) { return a + (b - a) * t; }
    private static double fract(double x) { return x - Math.floor(x); }
    private static double mod289(double x) { return x - Math.floor(x / 289) * 289; }
    private static double permute(double x) { return mod289(((x * 34) + 1) * x); }
    private static double hash(double x, double y, double z) {
        x = fract(x * .1031); y = fract(y * .1031); z = fract(z * .1031);
        double dot = x * (z + 31.32) + y * (y + 31.32) + z * (x + 31.32);
        return fract((x + dot + y + dot) * (z + dot));
    }
    /** Ashima 2D simplex kernel, scalar translation of the reference shader. */
    static double noise(double vx, double vy) {
        double skew = (vx + vy) * .366025403784439;
        double ix = Math.floor(vx + skew), iy = Math.floor(vy + skew);
        double unskew = (ix + iy) * .211324865405187;
        double x0 = vx - ix + unskew, y0 = vy - iy + unskew;
        double i1x = x0 > y0 ? 1 : 0, i1y = 1 - i1x;
        double x1 = x0 + .211324865405187 - i1x, y1 = y0 + .211324865405187 - i1y;
        double x2 = x0 - .577350269189626, y2 = y0 - .577350269189626;
        ix = mod289(ix); iy = mod289(iy);
        return 130 * (contribution(permute(permute(iy) + ix), x0, y0)
                + contribution(permute(permute(iy + i1y) + ix + i1x), x1, y1)
                + contribution(permute(permute(iy + 1) + ix + 1), x2, y2));
    }
    private static double contribution(double p, double x, double y) {
        double m = Math.max(.5 - x * x - y * y, 0); m *= m; m *= m;
        double gradient = 2 * fract(p * .024390243902439) - 1;
        double h = Math.abs(gradient) - .5, a = gradient - Math.floor(gradient + .5);
        return m * (1.79284291400159 - .85373472095314 * (a * a + h * h)) * (a * x + h * y);
    }
}
