package dev.fandroid.client.music;

/** Pure timing curves shared by compatible and enhanced lyric render passes. */
public final class LyricsAnimation {
    private LyricsAnimation() { }

    public static double durationStrength(long durationMillis) {
        double normalized = Math.clamp((durationMillis - 300.0) / 2_700.0, 0, 1);
        return smooth(normalized);
    }

    public static double wordEmphasis(double progress) {
        if (progress <= 0 || progress >= 1) return 0;
        double enter = smooth(progress / .16);
        double leave = smooth((1 - progress) / .22);
        return Math.min(enter, leave);
    }

    public static double sustainedGlowStrength(long durationMillis) {
        double normalized = Math.clamp((durationMillis - 900.0) / 4_100.0, 0, 1);
        return smooth(normalized);
    }

    public static double sustainedGlowEnvelope(double progress) {
        if (progress <= 0 || progress >= 1) return 0;
        double enter = smooth(progress / .08);
        double leave = smooth((1 - progress) / .16);
        return Math.min(enter, leave);
    }

    public static double timedProgress(long now, long startMillis, long endMillis) {
        if (endMillis <= startMillis) return now >= startMillis ? 1 : 0;
        return Math.clamp((now - startMillis) / (double) (endMillis - startMillis), 0, 1);
    }

    /** A staggered, looping pulse used by the three interlude dots. */
    public static double interludePulse(long elapsedMillis, int dotIndex) {
        double cycle = Math.floorMod(elapsedMillis, 1_500L) / 1_500.0;
        double local = cycle - Math.clamp(dotIndex, 0, 2) / 3.0;
        if (local < 0) local += 1;
        if (local >= .34) return 0;
        double wave = Math.sin(local / .34 * Math.PI);
        return smooth(wave * wave);
    }

    /** Permanent left-to-right fill, with each dot owning exactly one third of the silent interval. */
    public static double interludeDotFill(double intervalProgress, int dotIndex) {
        double local = Math.clamp(intervalProgress, 0, 1) * 3 - Math.clamp(dotIndex, 0, 2);
        return smooth(Math.clamp(local, 0, 1));
    }

    /** Uses the same enter/hold/leave glow curve as timed lyric glyphs. */
    public static double interludeDotLift(double intervalProgress, int dotIndex) {
        double local = Math.clamp(intervalProgress, 0, 1) * 3 - Math.clamp(dotIndex, 0, 2);
        return glowKeyframe(Math.clamp(local, 0, 1));
    }

    public static double keyframe(double progress, double start, double peakAt, double peak, double end) {
        if (progress <= peakAt) return mix(start, peak, smooth(progress / peakAt));
        return mix(peak, end, smooth((progress - peakAt) / (1 - peakAt)));
    }

    public static double glowKeyframe(double progress) {
        if (progress < .15) return smooth(progress / .15);
        if (progress <= .60) return 1;
        return 1 - smooth((progress - .60) / .40);
    }

    public static double smooth(double value) {
        double t = Math.clamp(value, 0, 1);
        return t * t * (3 - 2 * t);
    }

    public static double mix(double first, double second, double amount) {
        return first + (second - first) * amount;
    }
}
