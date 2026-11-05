package dev.relaylyrics.client;

import dev.relaylyrics.FanDroidMod;
import dev.relaylyrics.protocol.ProtocolCodec;
import dev.relaylyrics.client.music.WorldLyricsDirector;
import dev.relaylyrics.client.music.WorldLyricsPresentation;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import dev.relaylyrics.client.music.MultiSourceLyricsClient;

/** Preferences owned by the standalone popup. It never reads or rewrites fandroid.json. */
public final class ClientPreferences {
    @com.fasterxml.jackson.annotation.JsonProperty
    private java.util.Map<dev.relaylyrics.client.music.PopupSizeProfile, dev.relaylyrics.client.music.PopupSizeProfile.Size> popupSizes =
            new java.util.EnumMap<>(dev.relaylyrics.client.music.PopupSizeProfile.class);
    private Double popupPreciseX, popupPreciseY;
    public double getPopupPreciseX() { return popupPreciseX != null && Double.isFinite(popupPreciseX) ? popupPreciseX : getLyricsX(); }
    public double getPopupPreciseY() { return popupPreciseY != null && Double.isFinite(popupPreciseY) ? popupPreciseY : getLyricsY(); }
    public void setPopupPosition(double x, double y) {
        if (!Double.isFinite(x) || !Double.isFinite(y)) return;
        lyricsX = (int) Math.round(x); lyricsY = (int) Math.round(y);
        popupPreciseX = x; popupPreciseY = y;
    }
    public int getPopupWidth(dev.relaylyrics.client.music.PopupSizeProfile profile) {
        if (profile == null) profile = dev.relaylyrics.client.music.PopupSizeProfile.COVER_WITH_LYRICS;
        var size = popupSizes == null ? null : popupSizes.get(profile);
        return size == null ? getPopupWidth(profile.video(), profile == dev.relaylyrics.client.music.PopupSizeProfile.CANVAS_ONLY && lyricsSquareVideoSize > 0)
                : Math.clamp(size.width(), 96, 900);
    }
    public int getPopupHeight(dev.relaylyrics.client.music.PopupSizeProfile profile) {
        if (profile == null) profile = dev.relaylyrics.client.music.PopupSizeProfile.COVER_WITH_LYRICS;
        var size = popupSizes == null ? null : popupSizes.get(profile);
        return size == null ? getPopupHeight(profile.video(), profile == dev.relaylyrics.client.music.PopupSizeProfile.CANVAS_ONLY && lyricsSquareVideoSize > 0)
                : Math.clamp(size.height(), 64, 600);
    }
    public void setPopupWidth(dev.relaylyrics.client.music.PopupSizeProfile profile, int value) {
        if (profile == null) profile = dev.relaylyrics.client.music.PopupSizeProfile.COVER_WITH_LYRICS;
        int height = getPopupHeight(profile);
        if (popupSizes == null) popupSizes = new java.util.EnumMap<>(dev.relaylyrics.client.music.PopupSizeProfile.class);
        popupSizes.put(profile, new dev.relaylyrics.client.music.PopupSizeProfile.Size(Math.clamp(value, 96, 900), height));
    }
    public void setPopupHeight(dev.relaylyrics.client.music.PopupSizeProfile profile, int value) {
        if (profile == null) profile = dev.relaylyrics.client.music.PopupSizeProfile.COVER_WITH_LYRICS;
        int width = getPopupWidth(profile);
        if (popupSizes == null) popupSizes = new java.util.EnumMap<>(dev.relaylyrics.client.music.PopupSizeProfile.class);
        popupSizes.put(profile, new dev.relaylyrics.client.music.PopupSizeProfile.Size(width, Math.clamp(value, 64, 600)));
    }

    public enum LineLyricsAnimation { TOP_TO_BOTTOM, TIMED }
    private LineLyricsAnimation lineLyricsAnimation = LineLyricsAnimation.TOP_TO_BOTTOM;
    public LineLyricsAnimation getLineLyricsAnimation() {
        return lineLyricsAnimation == null ? LineLyricsAnimation.TOP_TO_BOTTOM : lineLyricsAnimation;
    }
    public void setLineLyricsAnimation(LineLyricsAnimation value) { lineLyricsAnimation = value; }
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

    private dev.relaylyrics.client.music.WorldLyricsExperience.Style worldLyricsStyle;
    private dev.relaylyrics.client.music.WorldLyricsExperience.RenderMode worldLyricsRenderMode;
    private boolean worldLyricsSmoothFont = true;
    public boolean isWorldLyricsSmoothFont() { return worldLyricsSmoothFont; }
    public void setWorldLyricsSmoothFont(boolean value) { worldLyricsSmoothFont = value; }
    private dev.relaylyrics.client.music.WorldLyricsSpace.Position worldLyricsPosition;
    private boolean worldLyricsTerrainLight = true;
    private int worldLyricsLuminance = 14;
    public dev.relaylyrics.client.music.WorldLyricsSpace.Position getWorldLyricsPosition() { return worldLyricsPosition == null ? dev.relaylyrics.client.music.WorldLyricsSpace.Position.ADAPTIVE : worldLyricsPosition; }
    public void setWorldLyricsPosition(dev.relaylyrics.client.music.WorldLyricsSpace.Position value) { worldLyricsPosition=value; }
    public boolean isWorldLyricsTerrainLight() { return worldLyricsTerrainLight; }
    public void setWorldLyricsTerrainLight(boolean value) { worldLyricsTerrainLight=value; }
    public int getWorldLyricsLuminance() { return Math.clamp(worldLyricsLuminance,0,15); }
    public void setWorldLyricsLuminance(int value) { worldLyricsLuminance=Math.clamp(value,0,15); }
    private boolean worldLyricsGlow;
    private int worldLyricsAccent = 0xC7B7FF;
    public dev.relaylyrics.client.music.WorldLyricsExperience.Style getWorldLyricsStyle() {
        return worldLyricsStyle == null ? dev.relaylyrics.client.music.WorldLyricsExperience.Style.SPICY : worldLyricsStyle;
    }
    public void setWorldLyricsStyle(dev.relaylyrics.client.music.WorldLyricsExperience.Style value) { worldLyricsStyle = value; }
    public dev.relaylyrics.client.music.WorldLyricsExperience.RenderMode getWorldLyricsRenderMode() {
        return worldLyricsRenderMode == null ? dev.relaylyrics.client.music.WorldLyricsExperience.RenderMode.AUTO : worldLyricsRenderMode;
    }
    public void setWorldLyricsRenderMode(dev.relaylyrics.client.music.WorldLyricsExperience.RenderMode value) { worldLyricsRenderMode = value; }
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
        var all = dev.relaylyrics.client.music.MultiSourceLyricsClient.defaultOrder();
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

    private boolean islandKawarpBackground = true, islandVideoCover = true;
    private boolean jadeIsland = true;
    private boolean islandLyrics;
    public boolean isIslandLyrics() { return islandLyrics; }
    public void setIslandLyrics(boolean value) { islandLyrics=value; }
    public boolean isJadeIsland() { return jadeIsland; }
    public void setJadeIsland(boolean value) { jadeIsland=value; }
    public boolean isIslandKawarpBackground() { return islandKawarpBackground; }
    public void setIslandKawarpBackground(boolean value) { islandKawarpBackground=value; }
    public boolean isIslandVideoCover() { return islandVideoCover; }
    public void setIslandVideoCover(boolean value) { islandVideoCover=value; }

    private boolean popupEdgeDocking = true, popupDocked = true;
    private dev.relaylyrics.client.music.PopupDockLayout.Edge popupDockEdge;
    private double popupDockAnchor = .5;
    public boolean isPopupEdgeDocking() { return popupEdgeDocking; }
    public void setPopupEdgeDocking(boolean enabled) { popupEdgeDocking=enabled; if(!enabled) popupDocked=false; }
    public boolean isPopupDocked() { return popupEdgeDocking && popupDocked; }
    public void setPopupDocked(boolean value) { popupDocked=popupEdgeDocking && value; }
    public dev.relaylyrics.client.music.PopupDockLayout.Edge getPopupDockEdge() { return dev.relaylyrics.client.music.PopupDockLayout.Edge.TOP; }
    public void setPopupDockEdge(dev.relaylyrics.client.music.PopupDockLayout.Edge value) { popupDockEdge=dev.relaylyrics.client.music.PopupDockLayout.Edge.TOP; }
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
    private double lyricsTextScale = 0.6;
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
    /** Positive values delay lyrics without changing the playback timeline. */
    public int getLyricsDelayMillis() { return Math.clamp(lyricsDelayMillis, -5_000, 5_000); }
    public void setLyricsDelayMillis(int value) { lyricsDelayMillis = Math.clamp(value, -5_000, 5_000); }
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
    private boolean worldLyrics;
    private WorldIntensity worldLyricsIntensity = WorldIntensity.NORMAL;
    private boolean worldLyricsPlayerReactions = true;
    private double worldLyricsSize = 1.2;
    private boolean worldLyricsAutoWalls = true;
    private boolean worldLyricsReposition;
    private boolean jiAfkResumeMusic = true;
    private boolean audioDucking;
    private DuckingTrigger duckingTrigger = DuckingTrigger.VOICE;
    private DuckingTarget duckingTarget = DuckingTarget.SPOTIFY;
    private String duckingApplication = "";
    private double duckingVolume = .30;
    private boolean worldLyricsCinematic;
    private boolean worldLyricsVolumetric;
    private WorldLyricsPresentation.Direction worldLyricsDirection = WorldLyricsPresentation.Direction.AUTOMATIC;
    private WorldLyricsPresentation.Intensity worldLyricsPresentationIntensity = WorldLyricsPresentation.Intensity.DISCREET;
    private boolean worldLyricsGeminiFont;
    private String worldLyricsGeminiModel = "gemini-flash-latest";
    private String worldLyricsGeminiDirection = "";
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
    private boolean lyricsBorder;
    private boolean lyricsResizeGrip;
    private boolean inventoryPopup = true;
    private boolean inventoryNowPlaying = true;
    private boolean modernMode = true;
    private MusicSource musicSource = MusicSource.AUTOMATIC;
    private LyricsRenderType lyricsRenderType = LyricsRenderType.NON_INTRUSIVE;
    private String spotifyClientId = "";
    private String spicyLyricsApiKey = "";
    private double settingsUiScale = 1;
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
        if (Boolean.getBoolean("lyrica.worldExperienceSelfTest") || Boolean.getBoolean("lyrica.geminiWorldSelfTest")) return;
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
        if (popupSizes != null) popupSizes.clear();
        popupPreciseX = popupPreciseY = null;
        popupDocked = true;
        lyricsX = 24;
        lyricsY = 24;
        lyricsWidth = 210;
        lyricsHeight = 130;
        lyricsVideoWidth = lyricsVideoHeight = lyricsSquareVideoSize = 0;
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
    public void setLyricsX(int value) { popupPreciseX = null; lyricsX = value; }
    public int getLyricsY() { return lyricsY; }
    public void setLyricsY(int value) { popupPreciseY = null; lyricsY = value; }
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
    public void setLyricsWidth(int value) { lyricsWidth = Math.clamp(value, 96, 900); }
    public int getLyricsHeight() { return Math.clamp(lyricsHeight, 64, 600); }
    public void setLyricsHeight(int value) { lyricsHeight = Math.clamp(value, 64, 600); }
    public boolean isLyricsArtistBackground() { return lyricsArtistBackground; }
    public void setLyricsArtistBackground(boolean value) { lyricsArtistBackground = value; }
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
        if (!json.has("romanizationUnderLine")) ((com.fasterxml.jackson.databind.node.ObjectNode) json).put("romanizationUnderLine", false);
        boolean oldWorldStyle = json.path("worldLyricsPositionVersion").asInt(0) < 5;
        if (oldWorldStyle) {
            // Old enum names must be removed before Jackson reads the new six-style enum.
            ((com.fasterxml.jackson.databind.node.ObjectNode) json).remove("worldLyricsPreset");
            ((com.fasterxml.jackson.databind.node.ObjectNode) json).remove("worldLyricsPresetMask");
            ((com.fasterxml.jackson.databind.node.ObjectNode) json).remove("enabledWorldLyricsPresets");
        }
        ClientPreferences result = ProtocolCodec.mapper().treeToValue(json, ClientPreferences.class);
        // Legacy X/Y setters must not discard precise coordinates because of JSON property order.
        if (json.path("popupPreciseX").isNumber()) result.popupPreciseX = json.path("popupPreciseX").doubleValue();
        if (json.path("popupPreciseY").isNumber()) result.popupPreciseY = json.path("popupPreciseY").doubleValue();
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

    public WorldLyricsPresentation.Direction getWorldLyricsDirection() {
        return worldLyricsDirection == null ? WorldLyricsPresentation.Direction.AUTOMATIC : worldLyricsDirection;
    }
    public void setWorldLyricsDirection(WorldLyricsPresentation.Direction value) { worldLyricsDirection = value; }
    public WorldLyricsPresentation.Intensity getWorldLyricsPresentationIntensity() {
        return worldLyricsPresentationIntensity == null ? WorldLyricsPresentation.Intensity.DISCREET : worldLyricsPresentationIntensity;
    }
    public void setWorldLyricsPresentationIntensity(WorldLyricsPresentation.Intensity value) { worldLyricsPresentationIntensity = value; }
    public boolean isWorldLyricsGeminiFont() { return worldLyricsGeminiFont; }
    public void setWorldLyricsGeminiFont(boolean value) { worldLyricsGeminiFont = value; }
    public String getWorldLyricsGeminiModel() {
        return worldLyricsGeminiModel == null || worldLyricsGeminiModel.isBlank() ? "gemini-flash-latest" : worldLyricsGeminiModel.strip();
    }
    public void setWorldLyricsGeminiModel(String value) { worldLyricsGeminiModel = limited(value, 100); }
    public String getWorldLyricsGeminiDirection() { return worldLyricsGeminiDirection == null ? "" : worldLyricsGeminiDirection; }
    public void setWorldLyricsGeminiDirection(String value) { worldLyricsGeminiDirection = limited(value, 500); }
    private static String limited(String value, int maximum) {
        String text = value == null ? "" : value.strip();
        return text.substring(0, Math.min(text.length(), maximum));
    }

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
    public boolean isWorldLyricsVolumetric() { return worldLyricsVolumetric; }
    public void setWorldLyricsVolumetric(boolean value) { worldLyricsVolumetric = value; }
    public boolean isWorldLyricsCinematic() { return worldLyricsCinematic; }
    public enum DuckingTrigger { VOICE, MUSIC, BOTH }
    public enum DuckingTarget { SPOTIFY, APPLICATION, ALL }

    public boolean isAudioDucking() { return audioDucking; }
    public void setAudioDucking(boolean value) { audioDucking = value; }
    public DuckingTrigger getDuckingTrigger() { return duckingTrigger == null ? DuckingTrigger.VOICE : duckingTrigger; }
    public void setDuckingTrigger(DuckingTrigger value) { duckingTrigger = value; }
    public DuckingTarget getDuckingTarget() { return duckingTarget == null ? DuckingTarget.SPOTIFY : duckingTarget; }
    public void setDuckingTarget(DuckingTarget value) { duckingTarget = value; }
    public String getDuckingApplication() { return duckingApplication == null ? "" : duckingApplication; }
    public void setDuckingApplication(String value) { duckingApplication = value; }
    public double getDuckingVolume() { return Double.isFinite(duckingVolume) ? Math.clamp(duckingVolume, 0, 1) : .30; }
    public void setDuckingVolume(double value) { duckingVolume = Math.clamp(value, 0, 1); }

    public boolean isJiAfkResumeMusic() { return jiAfkResumeMusic; }
    public void setJiAfkResumeMusic(boolean value) { jiAfkResumeMusic = value; }

    public boolean isWorldLyricsReposition() { return worldLyricsReposition; }
    public void setWorldLyricsReposition(boolean value) { worldLyricsReposition = value; }
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
    public boolean isLyricsResizeGrip() { return lyricsResizeGrip; }
    public void setLyricsResizeGrip(boolean value) { lyricsResizeGrip = value; }
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
    public String getSpicyLyricsApiKey() { return spicyLyricsApiKey == null ? "" : spicyLyricsApiKey.strip(); }
    public void setSpicyLyricsApiKey(String value) { spicyLyricsApiKey = value == null ? "" : value.strip(); }
    public double getSettingsUiScale() { return Double.isFinite(settingsUiScale) ? Math.clamp(settingsUiScale, .55, 1.5) : 1; }
    public void setSettingsUiScale(double value) { settingsUiScale = Double.isFinite(value) ? Math.clamp(value, .55, 1.5) : 1; }

    public String getSpotifyClientId() { return spotifyClientId == null ? "" : spotifyClientId.strip(); }
    public void setSpotifyClientId(String value) { spotifyClientId = value == null ? "" : value.strip(); }
}
