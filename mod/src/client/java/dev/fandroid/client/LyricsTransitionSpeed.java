package dev.fandroid.client;

public enum LyricsTransitionSpeed {
    SMOOTH(1.0),
    FAST(2.0),
    INSTANT(12.0);

    private final double deltaMultiplier;

    LyricsTransitionSpeed(double deltaMultiplier) { this.deltaMultiplier = deltaMultiplier; }

    public double deltaMultiplier() { return deltaMultiplier; }

    public LyricsTransitionSpeed next() {
        LyricsTransitionSpeed[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    public LyricsTransitionSpeed previous() {
        LyricsTransitionSpeed[] values = values();
        return values[Math.floorMod(ordinal() - 1, values.length)];
    }
}
