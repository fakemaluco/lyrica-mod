package dev.relaylyrics.client.ui;

/** Desktop Spicy Lyrics motion tokens (settings-panel.css and tokens.css).
 * Values are sampled by monotonic time, never by frame count. */
final class SettingsMotion {
    static final int FAST = 150, PRESS = 120, PANE = 140, TOGGLE = 220, MODAL = 220, EXPAND = 180, STAGGER = 30;
    enum Curve {
        GLASS(.34, 1.56, .64, 1), OUT(.23, 1, .32, 1), STANDARD(.4, 0, .2, 1), EASE(.25, .1, .25, 1);
        final double x1, y1, x2, y2;
        Curve(double x1, double y1, double x2, double y2) { this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2; }
        double at(double progress) {
            if (progress <= 0) return 0;
            if (progress >= 1) return 1;
            double low = 0, high = 1;
            for (int i = 0; i < 24; i++) {
                double t = (low + high) / 2, inv = 1 - t;
                double x = 3 * inv * inv * t * x1 + 3 * inv * t * t * x2 + t * t * t;
                if (x < progress) low = t; else high = t;
            }
            double t = (low + high) / 2, inv = 1 - t;
            return 3 * inv * inv * t * y1 + 3 * inv * t * t * y2 + t * t * t;
        }
    }
    static final class Tween {
        private double from, target;
        private long started, duration;
        private Curve curve = Curve.OUT;
        Tween(double initial) { from = target = initial; }
        double value(long now) {
            if (duration == 0 || now - started >= duration) return target;
            return from + (target - from) * curve.at(Math.max(0, (now - started) / (double) duration));
        }
        double to(double next, int millis, Curve easing, long now, boolean enabled) {
            if (!enabled) { snap(next); return next; }
            if (Double.compare(next, target) != 0) {
                from = value(now); target = next; started = now; duration = millis * 1_000_000L; curve = easing;
            }
            return value(now);
        }
        void snap(double next) { from = target = next; duration = 0; }
    }
    static int mix(int a, int b, double amount) {
        double t = Math.clamp(amount, 0, 1);
        int color = 0;
        for (int shift = 0; shift <= 24; shift += 8)
            color |= (int) Math.round(((a >>> shift) & 255) * (1 - t) + ((b >>> shift) & 255) * t) << shift;
        return color;
    }
    static int opacity(int color, double amount) {
        return color & 0xFFFFFF | (int) Math.round((color >>> 24) * Math.clamp(amount, 0, 1)) << 24;
    }
    private SettingsMotion() { }
}
