package dev.fandroid.client;

import dev.fandroid.protocol.ProtocolCodec;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import dev.fandroid.client.music.MultiSourceLyricsClient;

public final class ClientPreferences {
    private boolean romanizationUnderLine = true;
    private boolean automaticTranslation;
    private String translationLanguage = java.util.Locale.getDefault().getLanguage();
    private String translationProvider = "Google";
    private String translationAddress = "https://api.openai.com/v1";
    private String translationModel = "", translationGeminiModel = "";
    private boolean motionVideos = true;
    private boolean updateChecks = true, updatePrereleases;
    public boolean isRomanizationUnderLine() { return romanizationUnderLine; }
    public void setRomanizationUnderLine(boolean value) { romanizationUnderLine = value; }
    public boolean isAutomaticTranslation() { return automaticTranslation; }
    public void setAutomaticTranslation(boolean value) { automaticTranslation = value; if (value) lyricsTranslation = true; }
    public String getTranslationLanguage() {
        String code = com.tx24.spicyplayer.translation.TranslationModelsKt.languageCode(translationLanguage);
        return code == null ? "en" : code;
    }
    public void setTranslationLanguage(String value) { translationLanguage = value; }
    public String getTranslationProvider() { return java.util.List.of("Google", "DeepL", "Gemini", "OpenAi").contains(translationProvider) ? translationProvider : "Google"; }
    public void setTranslationProvider(String value) { translationProvider = value; }
    public String getTranslationAddress() { return translationAddress == null ? "https://api.openai.com/v1" : translationAddress.strip(); }
    public void setTranslationAddress(String value) { translationAddress = value; }
    public String getTranslationModel() { return translationModel == null ? "" : translationModel.strip(); }
    public void setTranslationModel(String value) { translationModel = value; }
    public String getTranslationGeminiModel() { return translationGeminiModel == null ? "" : translationGeminiModel.strip(); }
    public void setTranslationGeminiModel(String value) { translationGeminiModel = value; }
    public String translationModel() { return getTranslationProvider().equals("Gemini") ? getTranslationGeminiModel() : getTranslationModel(); }
    public void setActiveTranslationModel(String value) { if (getTranslationProvider().equals("Gemini")) setTranslationGeminiModel(value); else setTranslationModel(value); }
    public boolean isMotionVideos() { return motionVideos; }
    public void setMotionVideos(boolean value) { motionVideos = value; }
    public boolean isUpdateChecks() { return updateChecks; }
    public void setUpdateChecks(boolean value) { updateChecks = value; }
    public boolean isUpdatePrereleases() { return updatePrereleases; }
    public void setUpdatePrereleases(boolean value) { updatePrereleases = value; }

    private dev.fandroid.client.music.WorldLyricsExperience.Style worldLyricsStyle;
    private dev.fandroid.client.music.WorldLyricsExperience.RenderMode worldLyricsRenderMode;
    private boolean worldLyricsSmoothFont = true;
    public boolean isWorldLyricsSmoothFont() { return worldLyricsSmoothFont; }
    public void setWorldLyricsSmoothFont(boolean value) { worldLyricsSmoothFont = value; }
    private dev.fandroid.client.music.WorldLyricsSpace.Position worldLyricsPosition;
    private boolean worldLyricsTerrainLight = true;
    private int worldLyricsLuminance = 14;
    public dev.fandroid.client.music.WorldLyricsSpace.Position getWorldLyricsPosition() { return worldLyricsPosition == null ? dev.fandroid.client.music.WorldLyricsSpace.Position.ADAPTIVE : worldLyricsPosition; }
    public void setWorldLyricsPosition(dev.fandroid.client.music.WorldLyricsSpace.Position value) { worldLyricsPosition=value; }
    public boolean isWorldLyricsTerrainLight() { return worldLyricsTerrainLight; }
    public void setWorldLyricsTerrainLight(boolean value) { worldLyricsTerrainLight=value; }
    public int getWorldLyricsLuminance() { return Math.clamp(worldLyricsLuminance,0,15); }
    public void setWorldLyricsLuminance(int value) { worldLyricsLuminance=Math.clamp(value,0,15); }
    private boolean worldLyricsGlow;
    private int worldLyricsAccent = 0xC7B7FF;
    public dev.fandroid.client.music.WorldLyricsExperience.Style getWorldLyricsStyle() {
        return worldLyricsStyle == null ? dev.fandroid.client.music.WorldLyricsExperience.Style.SPICY : worldLyricsStyle;
    }
    public void setWorldLyricsStyle(dev.fandroid.client.music.WorldLyricsExperience.Style value) { worldLyricsStyle = value; }
    public dev.fandroid.client.music.WorldLyricsExperience.RenderMode getWorldLyricsRenderMode() {
        return worldLyricsRenderMode == null ? dev.fandroid.client.music.WorldLyricsExperience.RenderMode.AUTO : worldLyricsRenderMode;
    }
    public void setWorldLyricsRenderMode(dev.fandroid.client.music.WorldLyricsExperience.RenderMode value) { worldLyricsRenderMode = value; }
    public boolean isWorldLyricsGlow() { return worldLyricsGlow; }
    public void setWorldLyricsGlow(boolean value) { worldLyricsGlow = value; }
    public int getWorldLyricsAccent() { return worldLyricsAccent & 0xFFFFFF; }
    public void setWorldLyricsAccent(int value) { worldLyricsAccent = value & 0xFFFFFF; }

    private boolean spotifyCanvas = true;
    private boolean animatedCovers = true;
    private boolean canvasBackground = true;
    private boolean pauseMotionWithMusic = true;

    private boolean preferSpicyLyrics = true;
    public boolean isPreferSpicyLyrics() { return preferSpicyLyrics; }
    public void setPreferSpicyLyrics(boolean value) { preferSpicyLyrics = value; }
    private java.util.List<String> lyricsSourceOrder;
    public java.util.List<String> getLyricsSourceOrder() {
        var all = dev.fandroid.client.music.MultiSourceLyricsClient.defaultOrder();
        var ordered = new java.util.LinkedHashSet<String>();
        if (lyricsSourceOrder != null) for (String id : lyricsSourceOrder) if (id != null && all.contains(id)) ordered.add(id);
        ordered.addAll(all); return java.util.List.copyOf(ordered);
    }
    public void setLyricsSourceOrder(java.util.List<String> value) { lyricsSourceOrder = value == null ? null : value.stream().filter(java.util.Objects::nonNull).toList(); }
    public void moveLyricsSource(String id, int index) {
        var order = new java.util.ArrayList<>(getLyricsSourceOrder());
        if (!order.remove(id)) return;
        order.add(Math.clamp(index, 0, order.size()), id); lyricsSourceOrder = java.util.List.copyOf(order);
    }

    public boolean isSpotifyCanvas() { return spotifyCanvas; }
    public void setSpotifyCanvas(boolean value) { spotifyCanvas = value; }
    public boolean isAnimatedCovers() { return animatedCovers; }
    public void setAnimatedCovers(boolean value) { animatedCovers = value; }
    public boolean isCanvasBackground() { return canvasBackground; }
    public void setCanvasBackground(boolean value) { canvasBackground = value; }
    public boolean isPauseMotionWithMusic() { return pauseMotionWithMusic; }
    public void setPauseMotionWithMusic(boolean value) { pauseMotionWithMusic = value; }

    private List<String> lyricsSources;

    public List<String> getLyricsSources() {
        return lyricsSources == null ? MultiSourceLyricsClient.defaultSources() : List.copyOf(lyricsSources);
    }
    public void setLyricsSources(List<String> values) {
        lyricsSources = values == null ? null : values.stream().filter(java.util.Objects::nonNull).distinct().toList();
    }
    public boolean isLyricsSourceEnabled(String id) { return getLyricsSources().contains(id); }
    public void setLyricsSourceEnabled(String id, boolean enabled) {
        var values = new ArrayList<>(getLyricsSources());
        if (enabled && !values.contains(id)) values.add(id);
        if (!enabled) values.remove(id);
        setLyricsSources(values);
    }
    private double phoneHeight = 0.55;
    private boolean islandKawarpBackground, islandVideoCover;
    private boolean jadeIsland = true;
    public boolean isJadeIsland() { return jadeIsland; }
    public void setJadeIsland(boolean value) { jadeIsland=value; }
    public boolean isIslandKawarpBackground() { return islandKawarpBackground; }
    public void setIslandKawarpBackground(boolean value) { islandKawarpBackground=value; }
    public boolean isIslandVideoCover() { return islandVideoCover; }
    public void setIslandVideoCover(boolean value) { islandVideoCover=value; }

    private boolean popupEdgeDocking = true, popupDocked;
    private dev.fandroid.client.music.PopupDockLayout.Edge popupDockEdge;
    private double popupDockAnchor = .5;
    public boolean isPopupEdgeDocking() { return popupEdgeDocking; }
    public void setPopupEdgeDocking(boolean enabled) { popupEdgeDocking=enabled; if(!enabled) popupDocked=false; }
    public boolean isPopupDocked() { return popupEdgeDocking && popupDocked; }
    public void setPopupDocked(boolean value) { popupDocked=popupEdgeDocking && value; }
    public dev.fandroid.client.music.PopupDockLayout.Edge getPopupDockEdge() { return dev.fandroid.client.music.PopupDockLayout.Edge.TOP; }
    public void setPopupDockEdge(dev.fandroid.client.music.PopupDockLayout.Edge value) { popupDockEdge=dev.fandroid.client.music.PopupDockLayout.Edge.TOP; }
    public double getPopupDockAnchor() { return .5; }
    public void setPopupDockAnchor(double value) { popupDockAnchor=.5; }



    private int geminiCreativeFreedom=85;
    private boolean geminiSongContext=true;
    public int getGeminiCreativeFreedom(){return Math.clamp(geminiCreativeFreedom,0,100);}
    public void setGeminiCreativeFreedom(int v){geminiCreativeFreedom=Math.clamp(v,0,100);}
    public boolean isGeminiSongContext(){return geminiSongContext;}
    public void setGeminiSongContext(boolean v){geminiSongContext=v;}
    private String geminiArtStyle="auto", geminiArtSecondary="none";
    private int geminiArtScale=100, geminiArtStroke=100, geminiArtGrain=30, geminiArtSeed=1, geminiArtBlend=35;
    private boolean geminiArtAnimated, geminiArtAudio=true;
    public String getGeminiArtStyle(){return geminiArtStyle==null?"auto":geminiArtStyle;}
    public void setGeminiArtStyle(String v){geminiArtStyle=v;}
    public String getGeminiArtSecondary(){return geminiArtSecondary==null?"none":geminiArtSecondary;}
    public void setGeminiArtSecondary(String v){geminiArtSecondary=v;}
    public int getGeminiArtScale(){return Math.clamp(geminiArtScale,25,300);}
    public void setGeminiArtScale(int v){geminiArtScale=Math.clamp(v,25,300);}
    public int getGeminiArtStroke(){return Math.clamp(geminiArtStroke,25,300);}
    public void setGeminiArtStroke(int v){geminiArtStroke=Math.clamp(v,25,300);}
    public int getGeminiArtGrain(){return Math.clamp(geminiArtGrain,0,100);}
    public void setGeminiArtGrain(int v){geminiArtGrain=Math.clamp(v,0,100);}
    public int getGeminiArtSeed(){return Math.clamp(geminiArtSeed,0,999);}
    public void setGeminiArtSeed(int v){geminiArtSeed=Math.clamp(v,0,999);}
    public int getGeminiArtBlend(){return Math.clamp(geminiArtBlend,0,100);}
    public void setGeminiArtBlend(int v){geminiArtBlend=Math.clamp(v,0,100);}
    public boolean isGeminiArtAnimated(){return geminiArtAnimated;}
    public void setGeminiArtAnimated(boolean v){geminiArtAnimated=v;}
    public boolean isGeminiArtAudio(){return geminiArtAudio;}
    public void setGeminiArtAudio(boolean v){geminiArtAudio=v;}

    private boolean geminiShaderEnabled;
    private int geminiWorldStrength = 100;
    public int getGeminiWorldStrength() { return Math.clamp(geminiWorldStrength, 0, 100); }
    public void setGeminiWorldStrength(int value) { geminiWorldStrength = Math.clamp(value, 0, 100); }
    private boolean geminiShaderAutomatic = true;
    private String geminiShaderModel = "gemini-flash-latest";
    private String geminiShaderDirection = "";
    private int geminiShaderResolution = 192, geminiShaderFps = 24, geminiShaderBrightness = 65;
    public boolean isGeminiShaderEnabled() { return geminiShaderEnabled; }
    public void setGeminiShaderEnabled(boolean value) { geminiShaderEnabled = value; }
    public boolean isGeminiShaderAutomatic() { return geminiShaderAutomatic; }
    public void setGeminiShaderAutomatic(boolean value) { geminiShaderAutomatic = value; }
    public String getGeminiShaderModel() { return geminiShaderModel == null || geminiShaderModel.isBlank() ? "gemini-flash-latest" : geminiShaderModel.strip(); }
    public void setGeminiShaderModel(String value) { geminiShaderModel = value == null ? "" : value.strip().substring(0, Math.min(value.strip().length(), 100)); }
    public String getGeminiShaderDirection() { return geminiShaderDirection == null ? "" : geminiShaderDirection.substring(0, Math.min(geminiShaderDirection.length(), 500)); }
    public void setGeminiShaderDirection(String value) { geminiShaderDirection = value == null ? "" : value.substring(0, Math.min(value.length(), 500)); }
    public int getGeminiShaderResolution() { return geminiShaderResolution <= 128 ? 128 : geminiShaderResolution <= 192 ? 192 : 256; }
    public void setGeminiShaderResolution(int value) { geminiShaderResolution = value; }
    public int getGeminiShaderFps() { return Math.clamp(geminiShaderFps, 15, 30); }
    public void setGeminiShaderFps(int value) { geminiShaderFps = value; }
    public int getGeminiShaderBrightness() { return Math.clamp(geminiShaderBrightness, 15, 100); }
    public void setGeminiShaderBrightness(int value) { geminiShaderBrightness = Math.clamp(value, 15, 100); }

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
    private int lyricsDelayMillis;
    public int getLyricsDelayMillis() { return Math.clamp(lyricsDelayMillis, -5_000, 5_000); }
    public void setLyricsDelayMillis(int value) { lyricsDelayMillis = Math.clamp(value, -5_000, 5_000); }
    private boolean readReceipts = true;
    private int lyricsX = 24;
    private int lyricsY = 24;
    private boolean musicPresenceEnabled = true;
    private boolean musicPresenceSharing = true;
    public boolean isMusicPresenceEnabled() { return musicPresenceEnabled; }
    public void setMusicPresenceEnabled(boolean value) { musicPresenceEnabled = value; }
    public boolean isMusicPresenceSharing() { return musicPresenceSharing; }
    public void setMusicPresenceSharing(boolean value) { musicPresenceSharing = value; }
    private int lyricsWidth = 210;
    private int lyricsHeight = 130;
    // Zero migrates lazily from the existing dimensions, without overwriting them.
    @com.fasterxml.jackson.annotation.JsonProperty
    private int lyricsVideoWidth;
    @com.fasterxml.jackson.annotation.JsonProperty
    private int lyricsVideoHeight;
    @com.fasterxml.jackson.annotation.JsonProperty
    private int lyricsSquareVideoSize;
    private boolean lyricsDynamicBackground = true;
    private boolean lyricsArtistBackground;
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
    private boolean lyricsBorder;
    private boolean lyricsResizeGrip;
    private boolean phoneDarkMode;
    private boolean modernMode = true;
    private MusicSource musicSource = MusicSource.AUTOMATIC;
    private LyricsRenderType lyricsRenderType = LyricsRenderType.NON_INTRUSIVE;
    private String spotifyClientId = "";
    private String spicyLyricsApiKey = "";
    private double settingsUiScale = 1;

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
    public int getPopupWidth(boolean video, boolean square) {
        return square ? getPopupHeight(video, true) : video && lyricsVideoWidth > 0
                ? Math.clamp(lyricsVideoWidth, 96, 900) : getLyricsWidth();
    }
    public int getPopupHeight(boolean video, boolean square) {
        int saved = square ? lyricsSquareVideoSize : video ? lyricsVideoHeight : 0;
        return saved > 0 ? Math.clamp(saved, 64, 600) : getLyricsHeight();
    }
    public void setPopupWidth(boolean video, boolean square, int value) {
        if (square) lyricsSquareVideoSize = Math.clamp(value, 64, 600);
        else if (video) lyricsVideoWidth = Math.clamp(value, 96, 900);
        else setLyricsWidth(value);
    }
    public void setPopupHeight(boolean video, boolean square, int value) {
        if (square) lyricsSquareVideoSize = Math.clamp(value, 64, 600);
        else if (video) lyricsVideoHeight = Math.clamp(value, 64, 600);
        else setLyricsHeight(value);
    }
    public int getLyricsWidth() { return Math.clamp(lyricsWidth, 96, 900); }
    public void setLyricsWidth(int lyricsWidth) { this.lyricsWidth = Math.clamp(lyricsWidth, 96, 900); }
    public int getLyricsHeight() { return Math.clamp(lyricsHeight, 64, 600); }
    public void setLyricsHeight(int lyricsHeight) { this.lyricsHeight = Math.clamp(lyricsHeight, 64, 600); }
    public boolean isLyricsArtistBackground() { return lyricsArtistBackground; }
    public void setLyricsArtistBackground(boolean value) { lyricsArtistBackground = value; }
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
    public String getSpicyLyricsApiKey() { return spicyLyricsApiKey == null ? "" : spicyLyricsApiKey.strip(); }
    public void setSpicyLyricsApiKey(String value) { spicyLyricsApiKey = value == null ? "" : value.strip(); }
    public double getSettingsUiScale() { return Double.isFinite(settingsUiScale) ? Math.clamp(settingsUiScale, .55, 1.5) : 1; }
    public void setSettingsUiScale(double value) { settingsUiScale = Double.isFinite(value) ? Math.clamp(value, .55, 1.5) : 1; }

    public String getSpotifyClientId() { return spotifyClientId == null ? "" : spotifyClientId.strip(); }
    public void setSpotifyClientId(String value) { spotifyClientId = value == null ? "" : value.strip(); }
}
