package dev.fandroid.client;

public enum LyricsFontWeight {
    REGULAR(400),
    MEDIUM(500),
    SEMIBOLD(600),
    BOLD(700);

    private final int cssWeight;

    LyricsFontWeight(int cssWeight) {
        this.cssWeight = cssWeight;
    }

    public int cssWeight() { return cssWeight; }

    public LyricsFontWeight next() {
        LyricsFontWeight[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    public LyricsFontWeight previous() {
        LyricsFontWeight[] values = values();
        return values[Math.floorMod(ordinal() - 1, values.length)];
    }

    public boolean syntheticBold(LyricsFont font) {
        return font != LyricsFont.GOOGLE && cssWeight >= 600;
    }
}
