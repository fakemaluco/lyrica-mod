package dev.fandroid.client;

public enum LyricsGlowIntensity {
    SOFT(.55),
    NORMAL(1.0),
    STRONG(1.45);

    private final double multiplier;

    LyricsGlowIntensity(double multiplier) { this.multiplier = multiplier; }

    public double multiplier() { return multiplier; }

    public LyricsGlowIntensity next() {
        LyricsGlowIntensity[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    public LyricsGlowIntensity previous() {
        LyricsGlowIntensity[] values = values();
        return values[Math.floorMod(ordinal() - 1, values.length)];
    }
}
