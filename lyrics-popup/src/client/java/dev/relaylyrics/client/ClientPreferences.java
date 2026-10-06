package dev.relaylyrics.client;

import dev.relaylyrics.FanDroidMod;
import dev.relaylyrics.protocol.ProtocolCodec;
import dev.relaylyrics.client.music.WorldLyricsDirector;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/** Preferences owned by the standalone popup. It never reads or rewrites fandroid.json. */
public final class ClientPreferences {
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
    private boolean worldLyrics;
    private WorldIntensity worldLyricsIntensity = WorldIntensity.NORMAL;
    private boolean worldLyricsPlayerReactions = true;
    private double worldLyricsSize = 1.2;
    private boolean worldLyricsAutoWalls = true;
    private boolean worldLyricsCinematic = true;
    private Boolean worldLyricsAutomatic;
    private WorldLyricsDirector.Preset worldLyricsPreset;
    private WorldLyricsDirector.Variation worldLyricsVariation = WorldLyricsDirector.Variation.MEDIUM;
    private WorldLyricsDirector.Quality worldLyricsQuality = WorldLyricsDirector.Quality.MEDIUM;
    private int worldLyricsPresetMask = (1 << WorldLyricsDirector.Preset.values().length) - 1;
    private double worldLyricsMinimumDistance = 6;
    private double worldLyricsMaximumDistance = 192;
    private int worldLyricsPositionVersion = 5;
    private WorldLyricsDirector.SurfacePreference worldLyricsSurfacePreference = WorldLyricsDirector.SurfacePreference.CONTEXT;
    private WorldLyricsDirector.Material worldLyricsMaterial = WorldLyricsDirector.Material.AUTO;
    private WorldLyricsDirector.Entrance worldLyricsEntranceOverride;
    private boolean worldLyricsBevel = true;
    private double worldLyricsBevelWidth = .45;
    private boolean worldLyricsContactShadow = true;
    private boolean worldLyricsWorldLight = true;
    private double worldLyricsDistanceSensitivity = 1;
    private double worldLyricsArcRadius = .65;
    private double worldLyricsCenterGap = .32;
    private double worldLyricsDepth = .10;
    private double worldLyricsAnimationIntensity = 1;
    private double worldLyricsAnimationSpeed = 1;
    private boolean worldLyricsTrails;
    private int worldLyricsSideColor = 0x56647A;
    private int worldLyricsFaceColor = 0xFFFFFF;
    private boolean lyricsAnimations = true;
    private int lyricsAnimationFrameRate;
    private boolean lyricsLoadingSkeleton;
    private boolean lyricsBorder = true;
    private boolean inventoryPopup = true;
    private boolean inventoryNowPlaying = true;
    private boolean modernMode;
    private MusicSource musicSource = MusicSource.AUTOMATIC;
    private LyricsRenderType lyricsRenderType = LyricsRenderType.NON_INTRUSIVE;
    private String spotifyClientId = "";

    public static ClientPreferences load() {
        Path file = file();
        try {
            if (Files.isRegularFile(file)) {
                return decode(Files.readAllBytes(file));
            }
        } catch (Exception exception) {
            FanDroidMod.LOGGER.warn("Could not read Relay Lyrics preferences; using defaults", exception);
        }
        return new ClientPreferences();
    }

    public void save() {
        Path file = file();
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, ProtocolCodec.mapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(this));
        } catch (Exception exception) {
            FanDroidMod.LOGGER.warn("Could not save Relay Lyrics preferences", exception);
        }
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("relaylyrics.json");
    }

    public void resetLayout() {
        lyricsX = 24;
        lyricsY = 24;
        lyricsWidth = 420;
        lyricsHeight = 260;
    }

    public boolean isLyricsVisible() { return lyricsVisible; }
    public void setLyricsVisible(boolean value) { lyricsVisible = value; }
    public boolean isLyricsPinned() { return lyricsPinned; }
    public void setLyricsPinned(boolean value) { lyricsPinned = value; }
    public boolean isLyricsAllowGuiOverlap() { return lyricsAllowGuiOverlap; }
    public void setLyricsAllowGuiOverlap(boolean value) { lyricsAllowGuiOverlap = value; }
    public LyricsEffectsMode getLyricsEffects() {
        return LyricsEffectsMode.ENHANCED;
    }
    public void setLyricsEffects(LyricsEffectsMode value) {
        lyricsEffects = LyricsEffectsMode.ENHANCED;
    }
    public boolean isLyricsTranslation() { return lyricsTranslation; }
    public void setLyricsTranslation(boolean value) { lyricsTranslation = value; }
    public boolean isLyricsRomanization() { return lyricsRomanization; }
    public void setLyricsRomanization(boolean value) { lyricsRomanization = value; }
    public double getLyricsTextScale() { return Math.clamp(lyricsTextScale, 0.6, 1.8); }
    public void setLyricsTextScale(double value) { lyricsTextScale = Math.clamp(value, 0.6, 1.8); }
    public LyricsFont getLyricsFont() {
        return getGoogleFontFamily().isEmpty() ? LyricsFont.MINECRAFT : LyricsFont.GOOGLE;
    }
    public void setLyricsFont(LyricsFont ignored) { lyricsFont = LyricsFont.MINECRAFT; }
    public String getGoogleFontFamily() {
        return googleFontFamily == null ? "" : googleFontFamily.strip();
    }
    public void setGoogleFontFamily(String value) {
        googleFontFamily = GoogleFontManager.normalizeFamily(value);
        lyricsFont = LyricsFont.MINECRAFT;
    }
    public boolean isLyricsTextShadow() { return lyricsTextShadow; }
    public void setLyricsTextShadow(boolean value) { lyricsTextShadow = value; }
    public boolean isLyricsCoverShadow() { return lyricsCoverShadow; }
    public void setLyricsCoverShadow(boolean value) { lyricsCoverShadow = value; }
    public boolean isLyricsBold() { return getLyricsFontWeight() == LyricsFontWeight.BOLD; }
    public void setLyricsBold(boolean value) {
        lyricsBold = value;
        lyricsFontWeight = value ? LyricsFontWeight.BOLD : LyricsFontWeight.REGULAR;
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
    public void setLyricsX(int value) { lyricsX = value; }
    public int getLyricsY() { return lyricsY; }
    public void setLyricsY(int value) { lyricsY = value; }
    public int getLyricsWidth() { return Math.clamp(lyricsWidth, 160, 900); }
    public void setLyricsWidth(int value) { lyricsWidth = Math.clamp(value, 160, 900); }
    public int getLyricsHeight() { return Math.clamp(lyricsHeight, 100, 600); }
    public void setLyricsHeight(int value) { lyricsHeight = Math.clamp(value, 100, 600); }
    public boolean isLyricsDynamicBackground() { return lyricsDynamicBackground; }
    public void setLyricsDynamicBackground(boolean value) { lyricsDynamicBackground = value; }
    public int getLyricsBackgroundSpeed() { return Math.clamp(lyricsBackgroundSpeed, 0, 3); }
    public void setLyricsBackgroundSpeed(int value) { lyricsBackgroundSpeed = Math.clamp(value, 0, 3); }
    public double getLyricsBackgroundSpeedMultiplier() {
        return switch (getLyricsBackgroundSpeed()) {
            case 0 -> .45;
            case 2 -> 2.0;
            case 3 -> 3.5;
            default -> 1.0;
        };
    }
    public boolean isLyricsReactiveBackground() { return lyricsReactiveBackground; }
    public void setLyricsReactiveBackground(boolean value) { lyricsReactiveBackground = value; }
    public ReactiveSource getLyricsReactiveSource() {
        return lyricsReactiveSource == null ? ReactiveSource.WORDS : lyricsReactiveSource;
    }
    public void setLyricsReactiveSource(ReactiveSource value) {
        lyricsReactiveSource = value == null ? ReactiveSource.WORDS : value;
    }
    public double getLyricsReactiveIntensity() {
        return Math.clamp(lyricsReactiveIntensity, 0.25, 3.0);
    }
    public void setLyricsReactiveIntensity(double value) {
        lyricsReactiveIntensity = Math.clamp(value, 0.25, 3.0);
    }
    public boolean isLyricsReactiveHue() { return lyricsReactiveHue; }
    public void setLyricsReactiveHue(boolean value) { lyricsReactiveHue = value; }
    public double getLyricsReactiveHueAmount() {
        return Math.clamp(lyricsReactiveHueAmount, 0, 180);
    }
    public void setLyricsReactiveHueAmount(double value) {
        lyricsReactiveHueAmount = Math.clamp(value, 0, 180);
    }
    public boolean isLyricsReactiveHueAuto() { return lyricsReactiveHueAuto; }
    public void setLyricsReactiveHueAuto(boolean value) { lyricsReactiveHueAuto = value; }
    public boolean isLyricsReactiveBloom() { return lyricsReactiveBloom; }
    public void setLyricsReactiveBloom(boolean value) { lyricsReactiveBloom = value; }
    public double getLyricsReactiveBloomAmount() {
        return Math.clamp(lyricsReactiveBloomAmount, 0, 300);
    }
    public void setLyricsReactiveBloomAmount(double value) {
        lyricsReactiveBloomAmount = Math.clamp(value, 0, 300);
    }
    public boolean isLyricsReactiveSpectral() { return lyricsReactiveSpectral; }
    public void setLyricsReactiveSpectral(boolean value) { lyricsReactiveSpectral = value; }
    public double getLyricsReactiveSpectralAmount() {
        return Math.clamp(lyricsReactiveSpectralAmount, 0, 300);
    }
    public void setLyricsReactiveSpectralAmount(double value) {
        lyricsReactiveSpectralAmount = Math.clamp(value, 0, 300);
    }
    public boolean isWorldLyrics() { return worldLyrics; }
    public void setWorldLyrics(boolean value) { worldLyrics = value; }
    public void enableWorldLyricsWithModernDefaults() {
        if (worldLyricsPreset == null) {
            worldLyricsPreset = WorldLyricsDirector.Preset.SIGN;
            worldLyricsAutomatic = true;
        }
        worldLyrics = true;
    }

    static ClientPreferences decode(byte[] bytes) throws java.io.IOException {
        var json = ProtocolCodec.mapper().readTree(bytes);
        boolean oldWorldStyle = json.path("worldLyricsPositionVersion").asInt(0) < 5;
        if (oldWorldStyle) {
            // Old enum names must be removed before Jackson reads the new six-style enum.
            ((com.fasterxml.jackson.databind.node.ObjectNode) json).remove("worldLyricsPreset");
            ((com.fasterxml.jackson.databind.node.ObjectNode) json).remove("worldLyricsPresetMask");
            ((com.fasterxml.jackson.databind.node.ObjectNode) json).remove("enabledWorldLyricsPresets");
        }
        ClientPreferences result = ProtocolCodec.mapper().treeToValue(json, ClientPreferences.class);
        if (oldWorldStyle) {
            result.worldLyricsPreset = WorldLyricsDirector.Preset.SIGN;
            result.worldLyricsAutomatic = true;
            result.worldLyricsPresetMask = (1 << WorldLyricsDirector.Preset.values().length) - 1;
            result.worldLyricsSurfacePreference = WorldLyricsDirector.SurfacePreference.CONTEXT;
            if (result.worldLyricsMaximumDistance <= 16) {
                result.worldLyricsMinimumDistance = 6;
                result.worldLyricsMaximumDistance = 192;
            }
        }
        result.worldLyricsPositionVersion = 5;
        return result;
    }
    public int getWorldLyricsPositionVersion() { return worldLyricsPositionVersion; }
    public void setWorldLyricsPositionVersion(int value) { worldLyricsPositionVersion = value; }

    public WorldIntensity getWorldLyricsIntensity() {
        return worldLyricsIntensity == null ? WorldIntensity.NORMAL : worldLyricsIntensity;
    }
    public void setWorldLyricsIntensity(WorldIntensity value) { worldLyricsIntensity = value; }
    public boolean isWorldLyricsPlayerReactions() { return worldLyricsPlayerReactions; }
    public void setWorldLyricsPlayerReactions(boolean value) { worldLyricsPlayerReactions = value; }
    public double getWorldLyricsSize() { return Double.isFinite(worldLyricsSize) ? Math.clamp(worldLyricsSize, .75, 2) : 1.2; }
    public void setWorldLyricsSize(double value) { worldLyricsSize = Double.isFinite(value) ? Math.clamp(value, .75, 2) : 1.2; }
    public boolean isWorldLyricsAutoWalls() { return worldLyricsAutoWalls; }
    public void setWorldLyricsAutoWalls(boolean value) { worldLyricsAutoWalls = value; }
    public WorldLyricsDirector.SurfacePreference getWorldLyricsSurfacePreference() {
        return worldLyricsSurfacePreference == null ? WorldLyricsDirector.SurfacePreference.CONTEXT : worldLyricsSurfacePreference;
    }
    public void setWorldLyricsSurfacePreference(WorldLyricsDirector.SurfacePreference value) {
        worldLyricsSurfacePreference = value == null ? WorldLyricsDirector.SurfacePreference.CONTEXT : value;
    }
    public WorldLyricsDirector.Material getWorldLyricsMaterial() {
        return worldLyricsMaterial == null ? WorldLyricsDirector.Material.AUTO : worldLyricsMaterial;
    }
    public void setWorldLyricsMaterial(WorldLyricsDirector.Material value) {
        worldLyricsMaterial = value == null ? WorldLyricsDirector.Material.AUTO : value;
    }
    public WorldLyricsDirector.Entrance getWorldLyricsEntranceOverride() { return worldLyricsEntranceOverride; }
    public void setWorldLyricsEntranceOverride(WorldLyricsDirector.Entrance value) { worldLyricsEntranceOverride = value; }
    public boolean isWorldLyricsBevel() { return worldLyricsBevel; }
    public void setWorldLyricsBevel(boolean value) { worldLyricsBevel = value; }
    public double getWorldLyricsBevelWidth() { return finite(worldLyricsBevelWidth, .45, .15, 1.2); }
    public void setWorldLyricsBevelWidth(double value) { worldLyricsBevelWidth = finite(value, .45, .15, 1.2); }
    public boolean isWorldLyricsContactShadow() { return worldLyricsContactShadow; }
    public void setWorldLyricsContactShadow(boolean value) { worldLyricsContactShadow = value; }
    public boolean isWorldLyricsWorldLight() { return worldLyricsWorldLight; }
    public void setWorldLyricsWorldLight(boolean value) { worldLyricsWorldLight = value; }
    public boolean isWorldLyricsCinematic() { return worldLyricsCinematic; }
    public void setWorldLyricsCinematic(boolean value) { worldLyricsCinematic = value; }
    public boolean isWorldLyricsAutomatic() {
        return worldLyricsAutomatic == null || worldLyricsAutomatic;
    }
    public void setWorldLyricsAutomatic(boolean value) { worldLyricsAutomatic = value; }
    public WorldLyricsDirector.Preset getWorldLyricsPreset() {
        return worldLyricsPreset == null
                ? WorldLyricsDirector.Preset.SIGN
                : worldLyricsPreset;
    }
    public void setWorldLyricsPreset(WorldLyricsDirector.Preset value) {
        worldLyricsPreset = value == null ? WorldLyricsDirector.Preset.SIGN : value;
    }
    public WorldLyricsDirector.Variation getWorldLyricsVariation() {
        return worldLyricsVariation == null ? WorldLyricsDirector.Variation.MEDIUM : worldLyricsVariation;
    }
    public void setWorldLyricsVariation(WorldLyricsDirector.Variation value) {
        worldLyricsVariation = value == null ? WorldLyricsDirector.Variation.MEDIUM : value;
    }
    public WorldLyricsDirector.Quality getWorldLyricsQuality() {
        return worldLyricsQuality == null ? WorldLyricsDirector.Quality.MEDIUM : worldLyricsQuality;
    }
    public void setWorldLyricsQuality(WorldLyricsDirector.Quality value) {
        worldLyricsQuality = value == null ? WorldLyricsDirector.Quality.MEDIUM : value;
    }
    public boolean isWorldLyricsPresetEnabled(WorldLyricsDirector.Preset preset) {
        int sanitized = worldLyricsPresetMask & ((1 << WorldLyricsDirector.Preset.values().length) - 1);
        return sanitized == 0 || (sanitized & (1 << preset.ordinal())) != 0;
    }
    public int getWorldLyricsPresetMask() { return worldLyricsPresetMask; }
    public void setWorldLyricsPresetMask(int value) {
        worldLyricsPresetMask = value & ((1 << WorldLyricsDirector.Preset.values().length) - 1);
    }
    public void setWorldLyricsPresetEnabled(WorldLyricsDirector.Preset preset, boolean enabled) {
        if (enabled) worldLyricsPresetMask |= 1 << preset.ordinal();
        else {
            int candidate = worldLyricsPresetMask & ~(1 << preset.ordinal());
            if (candidate != 0) worldLyricsPresetMask = candidate;
        }
    }
    public java.util.Set<WorldLyricsDirector.Preset> getEnabledWorldLyricsPresets() {
        java.util.EnumSet<WorldLyricsDirector.Preset> result = java.util.EnumSet.noneOf(WorldLyricsDirector.Preset.class);
        for (var preset : WorldLyricsDirector.Preset.values()) if (isWorldLyricsPresetEnabled(preset)) result.add(preset);
        return result;
    }
    public double getWorldLyricsMinimumDistance() { return finite(worldLyricsMinimumDistance, 6, 3, 64); }
    public void setWorldLyricsMinimumDistance(double value) {
        worldLyricsMinimumDistance = finite(value, 6, 3, Math.min(64, getWorldLyricsMaximumDistance()));
    }
    public double getWorldLyricsMaximumDistance() { return finite(worldLyricsMaximumDistance, 192, getWorldLyricsMinimumDistance(), 256); }
    public void setWorldLyricsMaximumDistance(double value) {
        worldLyricsMaximumDistance = finite(value, 192, getWorldLyricsMinimumDistance(), 256);
    }
    public double getWorldLyricsDistanceSensitivity() { return finite(worldLyricsDistanceSensitivity, 1, 0, 2); }
    public void setWorldLyricsDistanceSensitivity(double value) { worldLyricsDistanceSensitivity = finite(value, 1, 0, 2); }
    public double getWorldLyricsArcRadius() { return finite(worldLyricsArcRadius, .65, 0, 1); }
    public void setWorldLyricsArcRadius(double value) { worldLyricsArcRadius = finite(value, .65, 0, 1); }
    public double getWorldLyricsCenterGap() { return finite(worldLyricsCenterGap, .32, 0, .8); }
    public void setWorldLyricsCenterGap(double value) { worldLyricsCenterGap = finite(value, .32, 0, .8); }
    public double getWorldLyricsDepth() { return finite(worldLyricsDepth, .10, 0, .30); }
    public void setWorldLyricsDepth(double value) { worldLyricsDepth = finite(value, .10, 0, .30); }
    public double getWorldLyricsAnimationIntensity() { return finite(worldLyricsAnimationIntensity, 1, 0, 2); }
    public void setWorldLyricsAnimationIntensity(double value) { worldLyricsAnimationIntensity = finite(value, 1, 0, 2); }
    public double getWorldLyricsAnimationSpeed() { return finite(worldLyricsAnimationSpeed, 1, .25, 2); }
    public void setWorldLyricsAnimationSpeed(double value) { worldLyricsAnimationSpeed = finite(value, 1, .25, 2); }
    public boolean isWorldLyricsTrails() { return worldLyricsTrails; }
    public void setWorldLyricsTrails(boolean value) { worldLyricsTrails = value; }
    public int getWorldLyricsSideColor() { return worldLyricsSideColor & 0xFFFFFF; }
    public void setWorldLyricsSideColor(int value) { worldLyricsSideColor = value & 0xFFFFFF; }
    public int getWorldLyricsFaceColor() { return worldLyricsFaceColor & 0xFFFFFF; }
    public void setWorldLyricsFaceColor(int value) { worldLyricsFaceColor = value & 0xFFFFFF; }

    private static double finite(double value, double fallback, double minimum, double maximum) {
        return Double.isFinite(value) ? Math.clamp(value, minimum, maximum) : fallback;
    }

    public enum WorldIntensity {
        SOFT(.6), NORMAL(1), INTENSE(1.4);
        public final double multiplier;
        WorldIntensity(double multiplier) { this.multiplier = multiplier; }
        public WorldIntensity next() { return values()[(ordinal() + 1) % values().length]; }
        public WorldIntensity previous() { return values()[(ordinal() + values().length - 1) % values().length]; }
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
    public void setLyricsBackgroundOpacity(int value) { lyricsBackgroundOpacity = Math.clamp(value, 0, 100); }
    public double getLyricsBackgroundOpacityMultiplier() { return getLyricsBackgroundOpacity() / 100.0; }
    public int getLyricsBlurStrength() { return Math.clamp(lyricsBlurStrength, 0, 3); }
    public void setLyricsBlurStrength(int value) { lyricsBlurStrength = Math.clamp(value, 0, 3); }
    public boolean isLyricsAnimations() { return lyricsAnimations; }
    public void setLyricsAnimations(boolean value) { lyricsAnimations = value; }
    public int getLyricsAnimationFrameRate() { return lyricsAnimationFrameRate == 30 || lyricsAnimationFrameRate == 60 ? lyricsAnimationFrameRate : 0; }
    public void setLyricsAnimationFrameRate(int value) { lyricsAnimationFrameRate = value == 30 || value == 60 ? value : 0; }
    public boolean isLyricsLoadingSkeleton() { return lyricsLoadingSkeleton; }
    public void setLyricsLoadingSkeleton(boolean value) { lyricsLoadingSkeleton = value; }
    public boolean isLyricsBorder() { return lyricsBorder; }
    public void setLyricsBorder(boolean value) { lyricsBorder = value; }
    public boolean isInventoryPopup() { return inventoryPopup; }
    public void setInventoryPopup(boolean value) { inventoryPopup = value; }
    public boolean isInventoryNowPlaying() { return inventoryNowPlaying; }
    public void setInventoryNowPlaying(boolean value) { inventoryNowPlaying = value; }
    public boolean isModernMode() { return modernMode; }
    public void setModernMode(boolean value) { modernMode = value; }
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
