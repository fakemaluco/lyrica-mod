package dev.fandroid.client;

import dev.fandroid.protocol.ProtocolCodec;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

public final class ClientPreferences {
    private double phoneHeight = 0.55;
    private boolean lyricsVisible = true;
    private boolean lyricsPinned = true;
    private boolean lyricsAllowGuiOverlap;
    private LyricsEffectsMode lyricsEffects = LyricsEffectsMode.AUTO;
    private boolean lyricsTranslation = true;
    private boolean lyricsRomanization = true;
    private double lyricsTextScale = 1.0;
    private LyricsFont lyricsFont = LyricsFont.MINECRAFT;
    private String googleFontFamily = "";
    private boolean lyricsTextShadow = true;
    private boolean lyricsCoverShadow = true;
    private boolean lyricsBold;
    private boolean simpleLyricsMode;
    private LyricsTextAlignment lyricsAlignment = LyricsTextAlignment.LEFT;
    private LyricsFontWeight lyricsFontWeight;
    private LyricsGlowIntensity lyricsGlowIntensity = LyricsGlowIntensity.NORMAL;
    private LyricsTransitionSpeed lyricsTransitionSpeed = LyricsTransitionSpeed.SMOOTH;
    private int lyricsShadowOpacity = 72;
    private int lyricsShadowDistance = 1;
    private int lyricsShadowSoftness = 1;
    private boolean lyricsClickSeek = true;
    private boolean readReceipts = true;
    private int lyricsX = 24;
    private int lyricsY = 24;
    private int lyricsWidth = 420;
    private int lyricsHeight = 260;
    private boolean lyricsDynamicBackground = true;
    private int lyricsBackgroundSpeed = 1;
    private int lyricsBackgroundOpacity = 100;
    private int lyricsBlurStrength = 2;
    private boolean lyricsReactiveBackground;
    private ReactiveSource lyricsReactiveSource = ReactiveSource.WORDS;
    private double lyricsReactiveIntensity = 1.0;
    private boolean lyricsReactiveHue = true;
    private double lyricsReactiveHueAmount = 80;
    private boolean lyricsReactiveHueAuto;
    private boolean lyricsReactiveBloom = true;
    private double lyricsReactiveBloomAmount = 100;
    private boolean lyricsReactiveSpectral;
    private double lyricsReactiveSpectralAmount = 100;
    private boolean lyricsAnimations = true;
    private boolean lyricsBorder = true;
    private boolean lyricsResizeGrip;
    private boolean phoneDarkMode;
    private boolean modernMode;
    private MusicSource musicSource = MusicSource.AUTOMATIC;
    private LyricsRenderType lyricsRenderType = LyricsRenderType.NON_INTRUSIVE;
    private String spotifyClientId = "";

    public static ClientPreferences load() {
        Path file = file();
        try {
            if (Files.exists(file)) {
                return ProtocolCodec.mapper().readValue(Files.readAllBytes(file), ClientPreferences.class);
            }
        } catch (Exception ignored) {
        }
        return new ClientPreferences();
    }

    public void save() {
        Path file = file();
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, ProtocolCodec.mapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(this));
        } catch (Exception ignored) {
        }
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("fandroid.json");
    }

    public double getPhoneHeight() {
        return phoneHeight;
    }

    public void setPhoneHeight(double phoneHeight) {
        this.phoneHeight = Math.clamp(phoneHeight, 0.35, 0.70);
    }

    public boolean isLyricsVisible() {
        return lyricsVisible;
    }

    public void setLyricsVisible(boolean lyricsVisible) {
        this.lyricsVisible = lyricsVisible;
    }

    public boolean isLyricsPinned() {
        return lyricsPinned;
    }

    public void setLyricsPinned(boolean lyricsPinned) {
        this.lyricsPinned = lyricsPinned;
    }

    public boolean isLyricsAllowGuiOverlap() { return lyricsAllowGuiOverlap; }
    public void setLyricsAllowGuiOverlap(boolean value) { lyricsAllowGuiOverlap = value; }

    public LyricsEffectsMode getLyricsEffects() {
        return LyricsEffectsMode.ENHANCED;
    }
    public void setLyricsEffects(LyricsEffectsMode lyricsEffects) {
        this.lyricsEffects = LyricsEffectsMode.ENHANCED;
    }
    public boolean isLyricsTranslation() { return lyricsTranslation; }
    public void setLyricsTranslation(boolean lyricsTranslation) { this.lyricsTranslation = lyricsTranslation; }
    public boolean isLyricsRomanization() { return lyricsRomanization; }
    public void setLyricsRomanization(boolean lyricsRomanization) { this.lyricsRomanization = lyricsRomanization; }
    public double getLyricsTextScale() { return Math.clamp(lyricsTextScale, 0.6, 1.8); }
    public void setLyricsTextScale(double lyricsTextScale) {
        this.lyricsTextScale = Math.clamp(lyricsTextScale, 0.6, 1.8);
    }
    public LyricsFont getLyricsFont() {
        return getGoogleFontFamily().isEmpty() ? LyricsFont.MINECRAFT : LyricsFont.GOOGLE;
    }
    public void setLyricsFont(LyricsFont lyricsFont) {
        this.lyricsFont = LyricsFont.MINECRAFT;
    }
    public String getGoogleFontFamily() {
        return googleFontFamily == null ? "" : googleFontFamily.strip();
    }
    public void setGoogleFontFamily(String googleFontFamily) {
        this.googleFontFamily = GoogleFontManager.normalizeFamily(googleFontFamily);
        this.lyricsFont = LyricsFont.MINECRAFT;
    }
    public boolean isLyricsTextShadow() { return lyricsTextShadow; }
    public void setLyricsTextShadow(boolean lyricsTextShadow) { this.lyricsTextShadow = lyricsTextShadow; }
    public boolean isLyricsCoverShadow() { return lyricsCoverShadow; }
    public void setLyricsCoverShadow(boolean lyricsCoverShadow) { this.lyricsCoverShadow = lyricsCoverShadow; }
    public boolean isLyricsBold() { return getLyricsFontWeight() == LyricsFontWeight.BOLD; }
    public void setLyricsBold(boolean lyricsBold) {
        this.lyricsBold = lyricsBold;
        this.lyricsFontWeight = lyricsBold ? LyricsFontWeight.BOLD : LyricsFontWeight.REGULAR;
    }
    public boolean isSimpleLyricsMode() { return simpleLyricsMode; }
    public void setSimpleLyricsMode(boolean value) { simpleLyricsMode = value; }
    public LyricsTextAlignment getLyricsAlignment() {
        return lyricsAlignment == null ? LyricsTextAlignment.LEFT : lyricsAlignment;
    }
    public void setLyricsAlignment(LyricsTextAlignment value) {
        lyricsAlignment = value == null ? LyricsTextAlignment.LEFT : value;
    }
    public LyricsFontWeight getLyricsFontWeight() {
        return lyricsFontWeight == null
            ? (lyricsBold ? LyricsFontWeight.BOLD : LyricsFontWeight.REGULAR)
            : lyricsFontWeight;
    }
    public void setLyricsFontWeight(LyricsFontWeight value) {
        lyricsFontWeight = value == null ? LyricsFontWeight.REGULAR : value;
        lyricsBold = lyricsFontWeight == LyricsFontWeight.BOLD;
    }
    public LyricsGlowIntensity getLyricsGlowIntensity() {
        return lyricsGlowIntensity == null ? LyricsGlowIntensity.NORMAL : lyricsGlowIntensity;
    }
    public void setLyricsGlowIntensity(LyricsGlowIntensity value) {
        lyricsGlowIntensity = value == null ? LyricsGlowIntensity.NORMAL : value;
    }
    public LyricsTransitionSpeed getLyricsTransitionSpeed() {
        return lyricsTransitionSpeed == null ? LyricsTransitionSpeed.SMOOTH : lyricsTransitionSpeed;
    }
    public void setLyricsTransitionSpeed(LyricsTransitionSpeed value) {
        lyricsTransitionSpeed = value == null ? LyricsTransitionSpeed.SMOOTH : value;
    }
    public int getLyricsShadowOpacity() { return Math.clamp(lyricsShadowOpacity, 0, 100); }
    public void setLyricsShadowOpacity(int value) { lyricsShadowOpacity = Math.clamp(value, 0, 100); }
    public int getLyricsShadowDistance() { return Math.clamp(lyricsShadowDistance, 1, 4); }
    public void setLyricsShadowDistance(int value) { lyricsShadowDistance = Math.clamp(value, 1, 4); }
    public int getLyricsShadowSoftness() { return Math.clamp(lyricsShadowSoftness, 0, 2); }
    public void setLyricsShadowSoftness(int value) { lyricsShadowSoftness = Math.clamp(value, 0, 2); }
    public boolean isLyricsClickSeek() { return lyricsClickSeek; }
    public void setLyricsClickSeek(boolean value) { lyricsClickSeek = value; }
    public int getLyricsX() { return lyricsX; }
    public void setLyricsX(int lyricsX) { this.lyricsX = lyricsX; }
    public int getLyricsY() { return lyricsY; }
    public void setLyricsY(int lyricsY) { this.lyricsY = lyricsY; }
    public int getLyricsWidth() { return Math.clamp(lyricsWidth, 160, 900); }
    public void setLyricsWidth(int lyricsWidth) { this.lyricsWidth = Math.clamp(lyricsWidth, 160, 900); }
    public int getLyricsHeight() { return Math.clamp(lyricsHeight, 100, 600); }
    public void setLyricsHeight(int lyricsHeight) { this.lyricsHeight = Math.clamp(lyricsHeight, 100, 600); }
    public boolean isLyricsDynamicBackground() { return lyricsDynamicBackground; }
    public void setLyricsDynamicBackground(boolean lyricsDynamicBackground) {
        this.lyricsDynamicBackground = lyricsDynamicBackground;
    }
    public int getLyricsBackgroundSpeed() { return Math.clamp(lyricsBackgroundSpeed, 0, 3); }
    public void setLyricsBackgroundSpeed(int lyricsBackgroundSpeed) {
        this.lyricsBackgroundSpeed = Math.clamp(lyricsBackgroundSpeed, 0, 3);
    }
    public double getLyricsBackgroundSpeedMultiplier() {
        return switch (getLyricsBackgroundSpeed()) {
            case 0 -> .45;
            case 2 -> 2.0;
            case 3 -> 3.5;
            default -> 1.0;
        };
    }
    public boolean isLyricsReactiveBackground() { return lyricsReactiveBackground; }
    public void setLyricsReactiveBackground(boolean lyricsReactiveBackground) {
        this.lyricsReactiveBackground = lyricsReactiveBackground;
    }
    public ReactiveSource getLyricsReactiveSource() {
        return lyricsReactiveSource == null ? ReactiveSource.WORDS : lyricsReactiveSource;
    }
    public void setLyricsReactiveSource(ReactiveSource lyricsReactiveSource) {
        this.lyricsReactiveSource = lyricsReactiveSource == null ? ReactiveSource.WORDS : lyricsReactiveSource;
    }
    public double getLyricsReactiveIntensity() {
        return Math.clamp(lyricsReactiveIntensity, 0.25, 3.0);
    }
    public void setLyricsReactiveIntensity(double lyricsReactiveIntensity) {
        this.lyricsReactiveIntensity = Math.clamp(lyricsReactiveIntensity, 0.25, 3.0);
    }
    public boolean isLyricsReactiveHue() { return lyricsReactiveHue; }
    public void setLyricsReactiveHue(boolean lyricsReactiveHue) {
        this.lyricsReactiveHue = lyricsReactiveHue;
    }
    public double getLyricsReactiveHueAmount() {
        return Math.clamp(lyricsReactiveHueAmount, 0, 180);
    }
    public void setLyricsReactiveHueAmount(double lyricsReactiveHueAmount) {
        this.lyricsReactiveHueAmount = Math.clamp(lyricsReactiveHueAmount, 0, 180);
    }
    public boolean isLyricsReactiveHueAuto() { return lyricsReactiveHueAuto; }
    public void setLyricsReactiveHueAuto(boolean lyricsReactiveHueAuto) {
        this.lyricsReactiveHueAuto = lyricsReactiveHueAuto;
    }
    public boolean isLyricsReactiveBloom() { return lyricsReactiveBloom; }
    public void setLyricsReactiveBloom(boolean lyricsReactiveBloom) {
        this.lyricsReactiveBloom = lyricsReactiveBloom;
    }
    public double getLyricsReactiveBloomAmount() {
        return Math.clamp(lyricsReactiveBloomAmount, 0, 300);
    }

    public void setLyricsReactiveBloomAmount(double lyricsReactiveBloomAmount) {
        this.lyricsReactiveBloomAmount = Math.clamp(lyricsReactiveBloomAmount, 0, 300);
    }

    public boolean isLyricsReactiveSpectral() { return lyricsReactiveSpectral; }

    public void setLyricsReactiveSpectral(boolean lyricsReactiveSpectral) {
        this.lyricsReactiveSpectral = lyricsReactiveSpectral;
    }

    public double getLyricsReactiveSpectralAmount() {
        return Math.clamp(lyricsReactiveSpectralAmount, 0, 300);
    }

    public void setLyricsReactiveSpectralAmount(double lyricsReactiveSpectralAmount) {
        this.lyricsReactiveSpectralAmount = Math.clamp(lyricsReactiveSpectralAmount, 0, 300);
    }
    public enum ReactiveSource {
        VOLUME, WORDS, WAVEFORM;

        public ReactiveSource next() {
            ReactiveSource[] values = values();
            return values[(ordinal() + 1) % values.length];
        }

        public ReactiveSource previous() {
            ReactiveSource[] values = values();
            return values[(ordinal() + values.length - 1) % values.length];
        }
    }
    public int getLyricsBackgroundOpacity() { return Math.clamp(lyricsBackgroundOpacity, 0, 100); }
    public void setLyricsBackgroundOpacity(int lyricsBackgroundOpacity) {
        this.lyricsBackgroundOpacity = Math.clamp(lyricsBackgroundOpacity, 0, 100);
    }
    public double getLyricsBackgroundOpacityMultiplier() { return getLyricsBackgroundOpacity() / 100.0; }
    public int getLyricsBlurStrength() { return Math.clamp(lyricsBlurStrength, 0, 3); }
    public void setLyricsBlurStrength(int lyricsBlurStrength) {
        this.lyricsBlurStrength = Math.clamp(lyricsBlurStrength, 0, 3);
    }
    public boolean isLyricsAnimations() { return lyricsAnimations; }
    public void setLyricsAnimations(boolean lyricsAnimations) { this.lyricsAnimations = lyricsAnimations; }
    public boolean isLyricsBorder() { return lyricsBorder; }
    public void setLyricsBorder(boolean lyricsBorder) { this.lyricsBorder = lyricsBorder; }
    public boolean isLyricsResizeGrip() { return lyricsResizeGrip; }
    public void setLyricsResizeGrip(boolean lyricsResizeGrip) { this.lyricsResizeGrip = lyricsResizeGrip; }
    public boolean isReadReceipts() { return readReceipts; }
    public void setReadReceipts(boolean readReceipts) { this.readReceipts = readReceipts; }
    public boolean isPhoneDarkMode() { return phoneDarkMode; }
    public void setPhoneDarkMode(boolean phoneDarkMode) { this.phoneDarkMode = phoneDarkMode; }
    public boolean isModernMode() { return modernMode; }
    public void setModernMode(boolean modernMode) { this.modernMode = modernMode; }
    public MusicSource getMusicSource() {
        return musicSource == null ? MusicSource.AUTOMATIC : musicSource;
    }
    public void setMusicSource(MusicSource value) {
        musicSource = value == null ? MusicSource.AUTOMATIC : value;
    }
    public LyricsRenderType getLyricsRenderType() {
        return lyricsRenderType == null ? LyricsRenderType.NON_INTRUSIVE : lyricsRenderType;
    }
    public void setLyricsRenderType(LyricsRenderType value) {
        lyricsRenderType = value == null ? LyricsRenderType.NON_INTRUSIVE : value;
    }
    public String getSpotifyClientId() { return spotifyClientId == null ? "" : spotifyClientId.strip(); }
    public void setSpotifyClientId(String value) { spotifyClientId = value == null ? "" : value.strip(); }
}
