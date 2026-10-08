package dev.relaylyrics.client;

/** Recognizes existing values without introducing a second persisted preset state. */
public final class LyricsSettingsPresets {
    private LyricsSettingsPresets() { }

    public enum Effects { NONE, SOFT, SPICY, CUSTOM }
    public enum Background { SIMPLE, COVER, REACTIVE }

    public static Effects effects(ClientPreferences p) {
        for (Effects preset : Effects.values()) {
            if (preset == Effects.CUSTOM) continue;
            ClientPreferences sample = new ClientPreferences();
            applyEffects(sample, preset);
            if (p.isSimpleLyricsMode() == sample.isSimpleLyricsMode()
                    && p.isLyricsTextShadow() == sample.isLyricsTextShadow()
                    && p.getLyricsGlowIntensity() == sample.getLyricsGlowIntensity()
                    && p.getLyricsTransitionSpeed() == sample.getLyricsTransitionSpeed()
                    && p.getLyricsShadowOpacity() == sample.getLyricsShadowOpacity()
                    && p.getLyricsShadowDistance() == sample.getLyricsShadowDistance()
                    && p.getLyricsShadowSoftness() == sample.getLyricsShadowSoftness()) return preset;
        }
        return Effects.CUSTOM;
    }

    public static void applyEffects(ClientPreferences p, Effects preset) {
        if (preset == Effects.CUSTOM) return;
        p.setSimpleLyricsMode(preset == Effects.NONE);
        p.setLyricsTextShadow(preset != Effects.NONE);
        p.setLyricsGlowIntensity(preset == Effects.SOFT ? LyricsGlowIntensity.SOFT : LyricsGlowIntensity.NORMAL);
        p.setLyricsTransitionSpeed(preset == Effects.NONE ? LyricsTransitionSpeed.INSTANT : LyricsTransitionSpeed.SMOOTH);
        p.setLyricsShadowOpacity(preset == Effects.SOFT ? 45 : 72);
        p.setLyricsShadowDistance(1);
        p.setLyricsShadowSoftness(1);
    }

    public static Background background(ClientPreferences p) {
        return p.isLyricsReactiveBackground() ? Background.REACTIVE
                : p.isLyricsDynamicBackground() ? Background.COVER : Background.SIMPLE;
    }

    public static void applyBackground(ClientPreferences p, Background value) {
        p.setLyricsDynamicBackground(value != Background.SIMPLE);
        p.setLyricsReactiveBackground(value == Background.REACTIVE);
    }

    public static int additionalText(ClientPreferences p) {
        return (p.isLyricsTranslation() ? 1 : 0) | (p.isLyricsRomanization() ? 2 : 0);
    }

    public static void setAdditionalText(ClientPreferences p, int value) {
        p.setLyricsTranslation((value & 1) != 0);
        p.setLyricsRomanization((value & 2) != 0);
    }

    public static int inventoryContent(ClientPreferences p) {
        return (p.isInventoryPopup() ? 1 : 0) | (p.isInventoryNowPlaying() ? 2 : 0);
    }

    public static void setInventoryContent(ClientPreferences p, int value) {
        p.setInventoryPopup((value & 1) != 0);
        p.setInventoryNowPlaying((value & 2) != 0);
    }
}
