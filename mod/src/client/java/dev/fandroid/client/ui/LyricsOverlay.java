package dev.fandroid.client.ui;

import dev.fandroid.client.ClientPreferences;
import dev.fandroid.client.LyricsFont;
import dev.fandroid.client.LyricsFontWeight;
import dev.fandroid.client.LyricsTextAlignment;
import dev.fandroid.client.music.CoverArtTexture;
import dev.fandroid.client.music.ResponsiveLyricsLayout;
import dev.fandroid.client.music.CanvasPopupLayout;
import dev.fandroid.client.music.AdaptivePopupLayout;
import dev.fandroid.client.music.PopupDockLayout;
import dev.fandroid.client.music.PopupDockMotion;
import dev.fandroid.client.music.IslandAudioMotion;
import dev.fandroid.client.music.IslandLyrics;
import dev.fandroid.client.music.IslandWaitingMotion;
import dev.fandroid.protocol.music.MusicAudioLevels;
import dev.fandroid.client.music.PlatformTextures;
import dev.fandroid.client.music.LyricsAnimation;
import dev.fandroid.client.music.LyricsEffectRaster;
import dev.fandroid.client.music.LyricsRenderText;
import dev.fandroid.client.music.LyricsRomanizer;
import dev.fandroid.client.music.LyricsViewport;
import dev.fandroid.client.music.ManualLyricsScroll;
import dev.fandroid.client.music.MusicPlaybackClock;
import dev.fandroid.client.music.PlaybackProgressTransition;
import dev.fandroid.client.music.SpringValue;
import dev.fandroid.client.music.UnavailableLyricsTransition;
import dev.fandroid.protocol.lyrics.LyricAlignment;
import dev.fandroid.protocol.lyrics.LyricLine;
import dev.fandroid.protocol.lyrics.LyricVocalGroup;
import dev.fandroid.protocol.lyrics.LyricsDocument;
import dev.fandroid.protocol.lyrics.LyricsMetadata;
import dev.fandroid.protocol.lyrics.TimedWord;
import dev.fandroid.protocol.music.PlaybackSnapshot;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.OptionalDouble;

import static dev.fandroid.client.music.LyricsAnimation.durationStrength;
import static dev.fandroid.client.music.LyricsAnimation.ACTIVE_TEXT;
import static dev.fandroid.client.music.LyricsAnimation.UPCOMING_TEXT;
import static dev.fandroid.client.music.LyricsAnimation.UNSYNCED_TEXT;
import static dev.fandroid.client.music.LyricsAnimation.activeGlowProfile;
import static dev.fandroid.client.music.LyricsAnimation.activeGlowEligible;
import static dev.fandroid.client.music.LyricsAnimation.fillInterval;
import static dev.fandroid.client.music.LyricsAnimation.focusDistance;
import static dev.fandroid.client.music.LyricsAnimation.focusProfile;
import static dev.fandroid.client.music.LyricsAnimation.glowKeyframe;
import static dev.fandroid.client.music.LyricsAnimation.interludeDotFill;
import static dev.fandroid.client.music.LyricsAnimation.interludeDotLift;
import static dev.fandroid.client.music.LyricsAnimation.interludeProgress;
import static dev.fandroid.client.music.LyricsAnimation.individualMotionStrength;
import static dev.fandroid.client.music.LyricsAnimation.isRightToLeft;
import static dev.fandroid.client.music.LyricsAnimation.mix;
import static dev.fandroid.client.music.LyricsAnimation.sungShadowOpacity;
import dev.fandroid.client.music.LyricsShadowKernel;
import static dev.fandroid.client.music.LyricsAnimation.smooth;
import static dev.fandroid.client.music.LyricsAnimation.timedProgress;

/**
 * Focused lyric renderer adapted from Spicy Lyrics' motion language. It keeps
 * Minecraft's default font and treats lead/backing vocals as structured groups.
 */
public final class LyricsOverlay implements AutoCloseable {
    private static final int FUTURE_TEXT = UPCOMING_TEXT;
    private static final int AUXILIARY_TEXT = 0xFFD0D3D7;
    private static final double FILL_LEAD_FRACTION = .25;
    private static final long MAX_FILL_LEAD_MILLIS = 350;
    private static final int MAX_LAYOUTS = 384;
    private static final int MAX_SCENES = 24;
    private static final int MAX_STYLED_TEXT = 2_048;

    public static void initializeRenderingBackend() {
        LyricsBloomRenderer.register();
    }
    private static final long INTERLUDE_MIN_GAP_MILLIS = LyricsAnimation.INTERLUDE_MIN_GAP_MILLIS;
    private static final int INTERLUDE_LAYOUT_HEIGHT = 30;
    private static final double PHONE_LYRICS_SCALE = 1.72;

    private final ClientPreferences preferences;
    private final MusicPlaybackClock clock = new MusicPlaybackClock();
    private final PlaybackProgressTransition playbackProgress = new PlaybackProgressTransition();
    private final SpringScroll phoneScroll = new SpringScroll();
    private final SpringScroll hudScroll = new SpringScroll();
    private final ManualLyricsScroll phoneManualScroll = new ManualLyricsScroll();
    private final ManualLyricsScroll hudManualScroll = new ManualLyricsScroll();
    private final ManualReadMotion phoneManualReadMotion = new ManualReadMotion();
    private final ManualReadMotion hudManualReadMotion = new ManualReadMotion();
    private final Map<LayoutKey, LineLayout> layoutCache = new LinkedHashMap<>(128, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<LayoutKey, LineLayout> eldest) {
            return size() > MAX_LAYOUTS;
        }
    };
    private final Map<SceneKey, LyricsScene> sceneCache = new LinkedHashMap<>(16, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<SceneKey, LyricsScene> eldest) {
            return size() > MAX_SCENES;
        }
    };
    private final Map<StyledTextKey, Text> styledTextCache = new LinkedHashMap<>(256, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<StyledTextKey, Text> eldest) {
            return size() > MAX_STYLED_TEXT;
        }
    };
    private final List<HitRegion> phoneHitRegions = new ArrayList<>();
    private final List<HitRegion> hudHitRegions = new ArrayList<>();
    private final List<ActiveGlowCommand> activeGlowCommands = new ArrayList<>();
    private final DotMotion[] interludeDots = {new DotMotion(), new DotMotion(), new DotMotion()};
    private final FrameClock frameClock = new FrameClock();
    private final PopupMotion popupMotion = new PopupMotion();
    private final EmptyStateMotion emptyStateMotion = new EmptyStateMotion();
    private final UnavailableLyricsTransition unavailableTransition = new UnavailableLyricsTransition();
    private final CoverFocusMotion popupCoverMotion = new CoverFocusMotion();
    private final LyricsBloomRenderer bloomRenderer = new LyricsBloomRenderer();
    private final CoverArtTexture makerAvatarTexture = new CoverArtTexture();
    private final CoverArtTexture uploaderAvatarTexture = new CoverArtTexture();
    private volatile LyricsDocument document = new LyricsDocument(List.of());
    private volatile PlaybackSnapshot playback = PlaybackSnapshot.unavailable();
    private volatile String emptyMessage = ui("lyrics.fandroid.no_synced");
    private volatile String syncLabel = ui("lyrics.fandroid.synced");
    private volatile boolean lyricsLoading;
    private volatile int artworkAccent;
    private volatile CoverArtTexture artworkTexture;
    private LyricsOverlay currentSongPreview;
    private LyricsOverlay currentSongSource;
    private boolean hudEnhancedFailed;
    private boolean phoneEnhancedFailed;
    private boolean renderingInPhone;
    private boolean renderingOverScreen;
    private boolean renderingWorldPopup;
    private boolean worldPopupShaderActive;
    private double worldPopupLyricsZoom = 1;
    private boolean bandedTransitionDrawing;
    private LyricsFramePlan bandedLyricsFramePlan;
    private boolean drawingLyricsShadow;
    private double cornerMorph;
    private boolean stylingLyrics;
    private double lyricsSurfaceOpacity = 1;
    private double smoothX;
    private double smoothY;
    private double smoothWidth;
    private double smoothHeight;
    private long smoothBoundsNanos;
    private boolean smoothBoundsInitialized;
    private PhoneViewport phoneViewport;
    private dev.fandroid.client.music.PopupSizeProfile gestureSizeProfile;
    private Boolean profileHasLyrics;
    private final dev.fandroid.client.music.PopupAlignment.State popupAlignment = new dev.fandroid.client.music.PopupAlignment.State();
    private dev.fandroid.client.music.PopupAlignment.Result popupGuides;
    private boolean alignmentHeld;
    private boolean dragging;
    private boolean resizing;
    private double dragOffsetX;
    private double dragOffsetY;
    private double resizeVideoAspect;
    private LyricsDocument sceneDocument;
    private boolean sceneTranslation;
    private boolean sceneRomanization;
    private LyricsFont sceneFont;
    private LyricsFontWeight sceneFontWeight;
    private double pointerX = Double.NaN;
    private double pointerY = Double.NaN;
    private double phoneMinimumCamera;
    private double phoneMaximumCamera;
    private double hudMinimumCamera;
    private double hudMaximumCamera;
    private boolean phoneCameraReady;
    private boolean hudCameraReady;
    private ControlBounds popupPreviousButtonBounds;
    private ControlBounds popupShuffleButtonBounds;
    private ControlBounds popupPlayPauseButtonBounds;
    private ControlBounds popupHeartButtonBounds;
    private ControlBounds popupNextButtonBounds;
    private ControlBounds popupRepeatButtonBounds;
    private ControlBounds popupSettingsButtonBounds;
    private ControlBounds popupWorldButtonBounds;
    private double popupWorldHover;
    private final SpringValue popupWorldScale = new SpringValue(1, 4.5, .72);
    private ControlBounds popupRomanizationButtonBounds, popupTranslationButtonBounds;
    private LyricsDocument popupUtilityDocument;
    private boolean popupHasRomanization, popupHasTranslation;
    private ControlBounds popupVolumeBounds;
    private ControlBounds popupVolumeTrackBounds, popupProgressTrackBounds;
    private final SpringValue popupVolumeEngagement = new SpringValue(0, 4.5, .72);
    private final SpringValue popupProgressEngagement = new SpringValue(0, 4.5, .72);
    private ControlBounds popupMuteButtonBounds;
    private ControlBounds popupProgressBounds;
    private boolean popupProgressScrubbing;
    private boolean popupVolumeScrubbing;
    private double popupVolumePreview = Double.NaN;
    private long popupVolumePreviewNanos;
    private boolean popupCoverModeActive;
    private boolean popupCoverHoverLatched;
    private CoverBounds popupCoverInteractionBounds;
    private long popupScrubPosition;
    private long popupScrubDuration;
    private double popupPreviousHover;
    private double popupPlayPauseHover;
    private double popupHeartHover;
    private final SpringValue popupHeartScale = new SpringValue(1, 4.5, .72);
    private final SpringValue popupSettingsScale = new SpringValue(1, 4.5, .72);
    private PopupControl popupUtilityPressed = PopupControl.NONE;
    private final SpringValue popupRomanizationScale = new SpringValue(1, 4.5, .72);
    private final SpringValue popupTranslationScale = new SpringValue(1, 4.5, .72);
    private double popupRomanizationHover, popupTranslationHover;
    private final long[] popupClickNanos = new long[PopupControl.values().length];
    private double popupHeartFill;
    private long popupHeartFillPreviewUntil;
    private boolean popupHeartPreviewLiked;
    private double popupDisplayedVolume = Double.NaN;
    private double popupNextHover;
    private double popupShuffleHover;
    private double popupRepeatHover;
    private double popupSettingsHover;
    private double popupVolumeHover;
    private double popupMuteHover;
    private double popupProgressHover;
    private double sceneTextScale = Double.NaN;
    private int sceneBuilds;
    private volatile RenderStats renderStats = RenderStats.EMPTY;
    private long cachedPositionSecond = Long.MIN_VALUE;
    private long cachedDurationSecond = Long.MIN_VALUE;
    private String cachedPositionLabel = "0:00";
    private String cachedDurationLabel = "0:00";

    private final Runnable saveLayout;
    public LyricsOverlay(ClientPreferences preferences) { this(preferences, preferences::save); }
    LyricsOverlay(ClientPreferences preferences, Runnable saveLayout) { this(preferences,saveLayout,System::nanoTime); }
    LyricsOverlay(ClientPreferences preferences, Runnable saveLayout, java.util.function.LongSupplier waitingClock) {
        this.waitingClock = waitingClock;
        this.preferences = preferences;
        this.saveLayout = saveLayout;
        this.cornerMorph = preferences.isModernMode() ? 1 : 0;
    }

    public void beginLookup() {
        unavailableTransition.reset();
        lyricsLoading = true;
        document = new LyricsDocument(List.of());
        emptyMessage = ui("lyrics.fandroid.finding");
        syncLabel = ui("lyrics.fandroid.searching");
        resetAnimation();
    }

    public void clearLyrics() {
        unavailableTransition.reset();
        lyricsLoading = false;
        document = new LyricsDocument(List.of());
        emptyMessage = ui("lyrics.fandroid.no_synced");
        syncLabel = ui("lyrics.fandroid.synced");
        resetAnimation();
    }

    public void markUnavailable() {
        markUnavailable(ui("lyrics.fandroid.no_synced"));
    }

    public void markUnavailable(String message) {
        lyricsLoading = false;
        if (document.lines().isEmpty()) {
            emptyMessage = localizeEmptyMessage(message);
            unavailableTransition.unavailable();
        }
        syncLabel = ui("lyrics.fandroid.not_found");
    }

    public void setLyrics(LyricsDocument lyrics) {
        setLyrics(lyrics, ui("lyrics.fandroid.synced"));
    }

    public void setLyrics(LyricsDocument lyrics, String label) {
        lyricsLoading = false;
        document = lyrics == null ? new LyricsDocument(List.of()) : lyrics;
        LyricsRomanizer.setDocumentContext(document.lines().stream().flatMap(line -> java.util.stream.Stream.of(
                line.text(), line.words().stream().map(TimedWord::text).collect(java.util.stream.Collectors.joining()))).toList());
        if (document.groups().isEmpty()) unavailableTransition.unavailable();
        else unavailableTransition.reset();
        updateCreditAvatars(document.metadata());
        emptyMessage = document.lines().isEmpty() ? ui("lyrics.fandroid.no_synced") : "";
        syncLabel = label == null || label.isBlank() ? ui("lyrics.fandroid.synced") : label;
        resetAnimation();
    }

    private Runnable translationRequest = () -> { };
    public void setTranslationRequest(Runnable request) { translationRequest = request; }
    public LyricsDocument document() { return document; }
    private static boolean settling(LyricVocalGroup group, long now) {
        return now >= group.endMillis() && now - group.endMillis() < 2_000;
    }
    private boolean staticLyrics() { return document.metadata().syncMode().name().equals("STATIC"); }
    private boolean reducedLyricsMotion() {
        return preferences.isSimpleLyricsMode() || !preferences.isLyricsAnimations() || staticLyrics();
    }
    public String syncLabel() { return syncLabel; }
    public RenderStats renderStats() { return renderStats; }
    public long currentPositionMillis() { return positionMillis(); }
    public long currentLyricsPositionMillis() {
        return dev.fandroid.client.music.LyricsTiming.position(positionMillis(), preferences.getLyricsDelayMillis());
    }
    private long lyricSeekPosition(long position) {
        return dev.fandroid.client.music.LyricsTiming.seekPosition(position, preferences.getLyricsDelayMillis(), playback.durationMillis());
    }
    private long worldPlaybackRevision;
    public record WorldPlayback(String track, long positionMillis, boolean playing, long revision) { }
    public WorldPlayback worldPlayback() {
        return new WorldPlayback(playbackTrackKey(playback), currentLyricsPositionMillis(), playback.playing(), worldPlaybackRevision);
    }

    public void setPlayback(PlaybackSnapshot playback) {
        if(dockSeeking && (playback.durationMillis()<=0 || !playbackTrackKey(this.playback).equals(playbackTrackKey(playback)))) cancelPopupProgressScrub();
        unavailableTransition.track(playbackTrackKey(playback));
        if(meaningfulIslandTrack(playback)) {
            if(lastIslandTrack==null || !playbackTrackKey(lastIslandTrack).equals(playbackTrackKey(playback))) dockMotion.revealTitle();
            lastIslandTrack=playback;
        }
        this.playback = playback;
        clock.update(playback);
    }

    public void seekTo(long positionMillis) {
        worldPlaybackRevision++;
        clock.seekTo(positionMillis);
        playbackProgress.snap(playbackTrackKey(playback), positionMillis, playback.durationMillis());
        phoneScroll.reset();
        hudScroll.reset();
        phoneManualScroll.reset();
        hudManualScroll.reset();
        phoneCameraReady = false;
        hudCameraReady = false;
        synchronized (layoutCache) { layoutCache.clear(); }
        synchronized (sceneCache) { sceneCache.clear(); }
        phoneHitRegions.clear();
        hudHitRegions.clear();
    }

    public void setArtworkAccent(int argb) {
        artworkAccent = argb;
    }

    public void setArtworkTexture(CoverArtTexture artworkTexture) {
        this.artworkTexture = artworkTexture;
    }

    /** Visual progress shared by the popup, inventory card and phone player. */
    public double visualPlaybackProgress() {
        return playbackProgress.update(playbackTrackKey(playback), positionMillis(),
                playback.durationMillis(), preferences.isLyricsAnimations());
    }

    /** Extra background speed multiplier from the experimental reactive source. Returns 1.0 when disabled. */
    public double getLyricsBackgroundReactiveMultiplier() {
        if (!preferences.isLyricsReactiveBackground() || !playback.playing()) return 1.0;
        double signal;
        double speed;
        switch (preferences.getLyricsReactiveSource()) {
            case VOLUME -> {
                signal = playback.volume();
                speed = signal <= .005 ? 0 : .2 + signal * 3.8;
            }
            case WAVEFORM -> {
                signal = playback.intensity();
                speed = .15 + signal * 3.85;
            }
            default -> {
                signal = reactiveWordsPulse(currentLyricsPositionMillis());
                speed = .15 + signal * 3.85;
            }
        }
        return Math.clamp(speed * preferences.getLyricsReactiveIntensity(), 0, 8);
    }

    private double reactiveWordsPulse(long positionMillis) {
        List<LyricLine> lines = document.lines();
        if (lines.isEmpty() || positionMillis < 0) return 0;
        int index = document.lineAt(positionMillis);
        if (index < 0 || index >= lines.size()) return 0;
        LyricLine line = lines.get(index);
        long start = Math.max(0, line.startMillis());
        long end = Math.max(start + 1, line.endMillis());
        if (!line.words().isEmpty()) {
            for (TimedWord word : line.words()) {
                if (positionMillis >= word.startMillis() && positionMillis < word.endMillis()) {
                    double t = (positionMillis - word.startMillis())
                            / (double) Math.max(1, word.endMillis() - word.startMillis());
                    return Math.clamp(1 - Math.abs(2 * t - 1), 0, 1);
                }
            }
        }
        double t = (positionMillis - start) / (double) (end - start);
        return Math.clamp(1 - Math.abs(2 * t - 1), 0, 1);
    }

    private long fontLayoutRevision;
    public long fontLayoutRevision() { return fontLayoutRevision; }

    /** Clears width and scene caches after the runtime font resource changes. */
    public void fontChanged() {
        if (currentSongPreview != null) currentSongPreview.fontChanged();
        bloomRenderer.clear();
        fontLayoutRevision++;
        synchronized (layoutCache) { layoutCache.clear(); }
        synchronized (sceneCache) { sceneCache.clear(); }
        synchronized (styledTextCache) { styledTextCache.clear(); }
        sceneDocument = null;
        sceneFont = null;
        phoneScroll.reset();
        hudScroll.reset();
        phoneManualScroll.reset();
        hudManualScroll.reset();
        phoneCameraReady = false;
        hudCameraReady = false;
    }

    public void tick() {
        if(islandAudioDemand!=null) islandAudioDemand.accept(wantsIslandAudio(System.nanoTime()));
        bloomRenderer.tick();
        if (currentSongPreview != null) currentSongPreview.tick();
    }

    private int cinematicCaptionProbeFrames, cinematicWordFillFrames;
    private long cinematicFrameNanos, cinematicPosition = Long.MIN_VALUE;
    public int cinematicWordFillFrames() { return cinematicWordFillFrames; }
    public int cinematicCaptionProbeFrames() { return cinematicCaptionProbeFrames; }

    /** Drawn after Ji's letterbox, even when its HUD injection cancels Fabric's HUD callback. */
    public void renderCinematicCaption(DrawContext context) {
        var client = MinecraftClient.getInstance();
        if (client.currentScreen != null || !preferences.isLyricsVisible() || !playback.playing()
                || !dev.fandroid.client.CompatibilityManager.jiAfkCinematicActive() || renderer() == null) return;
        long now = currentLyricsPositionMillis();
        var active = document.lines().stream().filter(line -> !line.background()
                && line.startMillis() <= now && now < line.endMillis()).limit(2).toList();
        if (active.isEmpty()) return;
        if (Boolean.getBoolean("lyrica.worldCinematicSelfTest")) cinematicCaptionProbeFrames++;
        int width = client.getWindow().getScaledWidth(), height = client.getWindow().getScaledHeight();
        double scale = Math.clamp(Math.min(width / 600.0, height / 340.0), .75, 1.6);
        int available = Math.max(32, width - 32);
        var layouts = new java.util.ArrayList<LineLayout>();
        for (var line : active) {
            boolean romanized = preferences.isLyricsRomanization() && LyricsRenderText.hasRomanization(line);
            var prepared = layout(line, available, scale, romanized);
            if (prepared.rows.size() > 2) {
                double fullWidth = prepared.rows.stream().mapToDouble(row -> row.width).sum();
                scale *= Math.min(1, available * 1.8 / Math.max(1, fullWidth));
            }
        }
        long frame = System.nanoTime();
        boolean reset = cinematicFrameNanos == 0 || frame - cinematicFrameNanos > 250_000_000L
                || cinematicPosition != Long.MIN_VALUE && Math.abs(now - cinematicPosition) > 600;
        double delta = reset ? 0 : Math.clamp((frame - cinematicFrameNanos) / 1_000_000_000.0, 0, 1.0 / 15);
        cinematicFrameNanos = frame; cinematicPosition = now;
        for (var line : active) {
            var prepared = layout(line, available, scale,
                    preferences.isLyricsRomanization() && LyricsRenderText.hasRomanization(line));
            var motion = new PreparedLine(line, prepared, scale, prepared.height);
            if (reset) resetLineMotion(motion, now, !reducedLyricsMotion());
            updateLineTokenMotion(motion, now, delta, reducedLyricsMotion());
            layouts.add(prepared);
        }
        int totalRows = layouts.stream().mapToInt(l -> l.rows.size()).sum();
        double y = height - Math.round(height * .16) - 10 - totalRows * rowHeight(scale);
        double previousOpacity = lyricsSurfaceOpacity;
        lyricsSurfaceOpacity = 1;
        boolean previousShadow = drawingLyricsShadow; drawingLyricsShadow = false;
        try {
            if (enhancedEffects() && !reducedLyricsMotion()) {
                activeGlowCommands.clear();
                double glowY = y;
                for (int i = 0; i < layouts.size(); i++) {
                    var layout = layouts.get(i);
                    for (var row : layout.rows) {
                        if (verticalLineHighlight(active.get(i))) for (var token : row.tokens)
                            collectLineGlowCommand(layout, token, (width - row.width) / 2 + token.x, glowY, scale, 1);
                        glowY += rowHeight(scale);
                    }
                }
                renderActiveGlowPass(context, activeGlowCommands, 0, 0, width, height,
                        0xFFFFFFFF, RenderPass.ACTIVE_GLOW_CORE, activeGlowCoreRadius());
            }
            for (int lineIndex = 0; lineIndex < layouts.size(); lineIndex++) {
                var prepared = layouts.get(lineIndex);
                var line = active.get(lineIndex);
                double top = y;
                for (var row : prepared.rows) {
                    double left = (width - row.width) / 2;
                    for (var token : row.tokens) {
                        if (Boolean.getBoolean("lyrica.worldCinematicSelfTest") && token.fillOffset(now) > .01
                                && token.fillOffset(now) < token.rawWidth - .01) cinematicWordFillFrames++;
                        if (verticalLineHighlight(line)) drawVerticalLineToken(context, token, left + token.x, y,
                                scale, line, now, 1, top, prepared.rows.size() * rowHeight(scale));
                        else drawTimedToken(context, token, left + token.x, y, scale, now,
                                ACTIVE_TEXT, lyricsTextColor(0xFFAAAAAA), 1, RenderPass.BASE);
                    }
                    y += rowHeight(scale);
                }
            }
        } finally { lyricsSurfaceOpacity = previousOpacity; drawingLyricsShadow = previousShadow; }
    }

    public void renderHud(DrawContext context) {
        dockSurfaceVisibility=1;
        if (!isHudVisible()) return;
        pointerX = Double.NaN;
        pointerY = Double.NaN;
        hudHitRegions.clear();
        PopupBounds bounds = smoothPopupBounds(context.getScaledWindowWidth(), context.getScaledWindowHeight());
        if (!renderDockSurface(context,bounds,context.getScaledWindowWidth(),context.getScaledWindowHeight())) {
            renderCompact(context, bounds.x(), bounds.y(), bounds.width(), bounds.height());
        }
    }

    /** Draws a non-interactive reveal/collapse of the complete HUD surface. */
    public void renderHudTransition(DrawContext context, double visibility, boolean overScreen) {
        if (!isHudVisible()) return;
        double rawAmount = visibility;
        double amount = Math.clamp(rawAmount, 0, 1);
        PopupBounds bounds = smoothPopupBounds(context.getScaledWindowWidth(), context.getScaledWindowHeight());
        if (preferences.isPopupDocked() || dockPresentationActive()) {
            dockSurfaceVisibility=amount;
            clearPopupControlBounds(); pointerX=pointerY=Double.NaN; renderingOverScreen=false;
            if(amount<=.001) return;
            int surfaceWidth=context.getScaledWindowWidth(),surfaceHeight=context.getScaledWindowHeight();
            GuiTransform.push(context);
            try {
                // Integer screen translation keeps adjacent compatibility clip bands from overlapping.
                GuiTransform.translate(context,0,(float)-Math.round(18*ResponsiveLyricsLayout.scale(surfaceWidth,surfaceHeight)*(1-amount)));
                if(renderDockSurface(context,bounds,surfaceWidth,surfaceHeight)) return;
            } finally { GuiTransform.pop(context); clearPopupControlBounds(); }
        }
        if (amount <= .001) return;
        pointerX = Double.NaN;
        pointerY = Double.NaN;
        hudHitRegions.clear();
        clearPopupControlBounds();
        renderingOverScreen = overScreen;
        double centerX = bounds.x() + bounds.width() / 2.0;
        double centerY = bounds.y() + bounds.height() / 2.0;
        // Scale the complete surface with its clip. Cropping a full-sized popup
        // into a shrinking window leaves square corners and detached content.
        float scale = (float) Math.clamp(rawAmount, .001, 1.03);
        int clipLeft = (int) Math.floor(centerX - bounds.width() * scale / 2);
        int clipTop = (int) Math.floor(centerY - bounds.height() * scale / 2);
        int clipRight = (int) Math.ceil(centerX + bounds.width() * scale / 2);
        int clipBottom = (int) Math.ceil(centerY + bounds.height() * scale / 2);
        int clipWidth = Math.max(1, clipRight - clipLeft);
        int clipHeight = Math.max(1, clipBottom - clipTop);
        GuiTransform.push(context);
        try {
            GuiTransform.translate(context, (float) centerX, (float) centerY);
            GuiTransform.scale(context, scale, scale);
            GuiTransform.translate(context, (float) -centerX, (float) -centerY);
            if (preferences.isModernMode()) {
                int radius = Math.max(1, (int) Math.round(scale * morphRadius(
                        Math.clamp(Math.min(bounds.width(), bounds.height()) / 22, 9, 16))));
                bandedTransitionDrawing = true;
                bandedLyricsFramePlan = null;
                try {
                    RoundedGui.clipped(context, clipLeft, clipTop, clipWidth, clipHeight, radius,
                            () -> renderCompact(context, bounds.x(), bounds.y(),
                                    bounds.width(), bounds.height()));
                } finally {
                    bandedTransitionDrawing = false;
                    bandedLyricsFramePlan = null;
                }
            } else {
                context.enableScissor(clipLeft, clipTop, clipRight, clipBottom);
                try {
                    renderCompact(context, bounds.x(), bounds.y(), bounds.width(), bounds.height());
                } finally {
                    context.disableScissor();
                }
            }
        } finally {
            GuiTransform.pop(context);
            renderingOverScreen = false;
            pointerX = Double.NaN;
            pointerY = Double.NaN;
            clearPopupControlBounds();
        }
    }

    public boolean isHudVisible() {
        return preferences.isLyricsVisible() && preferences.isLyricsPinned()
                && (meaningfulIslandTrack(playback) || isPopupDocked() && lastIslandTrack!=null);
    }

    /** Renders above inventory screens using a stable, non-blurred editing surface. */
    public void renderHudOverScreen(DrawContext context, double mouseX, double mouseY) {
        dockSurfaceVisibility=1;
        if (!isHudVisible()) return;
        PopupBounds bounds = smoothPopupBounds(context.getScaledWindowWidth(), context.getScaledWindowHeight());
        pointerX = mouseX;
        pointerY = mouseY;
        hudHitRegions.clear();
        renderingOverScreen = true;
        try {
            if (!renderDockSurface(context,bounds,context.getScaledWindowWidth(),context.getScaledWindowHeight())) {
            renderCompact(context, bounds.x(), bounds.y(), bounds.width(), bounds.height());
        }
            if (dragging && alignmentHeld && popupGuides != null
                    && bounds.x() == popupGuides.bounds().x() && bounds.y() == popupGuides.bounds().y())
                PopupAlignmentGuides.draw(context, renderer(), popupGuides, context.getScaledWindowWidth(), context.getScaledWindowHeight());
        } finally {
            renderingOverScreen = false;
            pointerX = Double.NaN;
            pointerY = Double.NaN;
        }
    }

    public void renderHudOverScreen(DrawContext context) {
        renderHudOverScreen(context, Double.NaN, Double.NaN);
    }

    /** Full renderer at configuration-screen coordinates without drawing the positioned HUD copy. */
    public void renderPreview(DrawContext context, int x, int y, int width, int height) {
        if (!isHudVisible() || width < 80 || height < 60) return;
        renderCompact(context, x, y, width, height);
    }

    /** A private demo overlay can preview disabled/hidden lyrics without changing saved preferences. */
    public void renderDemoPreview(DrawContext context, int x, int y, int width, int height) {
        if (width >= 80 && height >= 60) renderCompact(context, x, y, width, height);
    }

    public boolean hasCurrentSong() {
        String title = playback.title();
        return title != null && !title.isBlank() && !title.equals("Nothing playing");
    }

    public void renderCurrentSongPreview(DrawContext context, int x, int y, int width, int height) {
        renderCurrentSongPreview(context, x, y, width, height, preferences);
    }

    /** Independent presentation state; the HUD keeps its own bounds, input and animation clocks. */
    public void renderCurrentSongPreview(DrawContext context, int x, int y, int width, int height,
                                         ClientPreferences previewPreferences) {
        if (!hasCurrentSong() || width < 80 || height < 60) return;
        if (currentSongPreview == null || currentSongPreview.preferences != previewPreferences) {
            releaseCurrentSongPreview();
            currentSongPreview = new LyricsOverlay(previewPreferences);
            currentSongPreview.currentSongSource = this;
        }
        LyricsOverlay preview = currentSongPreview;
        preview.playback = playback;
        preview.lyricsLoading = lyricsLoading;
        preview.emptyMessage = emptyMessage;
        preview.syncLabel = syncLabel;
        preview.artworkAccent = artworkAccent;
        preview.artworkTexture = artworkTexture;
        if (preview.document != document) {
            preview.document = document;
            preview.updateCreditAvatars(document.metadata());
            preview.resetAnimation();
        }
        preview.renderCompact(context, x, y, width, height);
    }

    /** Copies local media state into an independent world view, including live album video. */
    public void usePopupMediaFrom(LyricsOverlay source) {
        currentSongSource = source;
        playback = source.playback;
        artworkTexture = source.artworkTexture;
        artworkAccent = source.artworkAccent;
        lyricsLoading = source.lyricsLoading;
        emptyMessage = source.emptyMessage;
        syncLabel = source.syncLabel;
        if (document != source.document) setLyrics(source.document, source.syncLabel);
    }

    public void setWorldPopupLyricsZoom(double zoom) { worldPopupLyricsZoom = zoom; }

    private double lyricsTextScale() {
        return preferences.getLyricsTextScale() * (renderingWorldPopup ? worldPopupLyricsZoom : 1);
    }

    /** Paints the HUD popup at fixed logical dimensions with its own pointer and hit regions. */
    public void renderWorldPopup(DrawContext context, int width, int height, double mouseX, double mouseY) {
        clearPopupControlBounds();
        hudHitRegions.clear();
        renderingWorldPopup = true;
        worldPopupShaderActive = dev.fandroid.client.CompatibilityManager.worldShaderActive();
        renderingOverScreen = true;
        pointerX = mouseX;
        pointerY = mouseY;
        try {
            adaptivePopupMode = AdaptivePopupLayout.choose(width, height, videoArtworkAspect(),
                    !document.groups().isEmpty(), preferences.getLyricsTextScale());
            renderCompactDesign(context, 0, 0, width, height);
            int size = 18, x = width - size - 7, y = 7;
            var close = new ControlBounds(x, y, size, size);
            boolean hovered = close.contains(mouseX, mouseY);
            GuiTransform.nextLayer(context);
            RoundedGui.smoothFill(context, x, y, size, size, size / 2,
                    hovered ? 0xFF824659 : 0xDD211D2B);
            PopupIcons.drawCentered(context, PopupIcons.CLOSE, x + size / 2, y + size / 2, 11, 0xFFFFFFFF);
            popupCloseButtonBounds = close;
        } finally {
            renderingOverScreen = false;
            renderingWorldPopup = false;
            worldPopupShaderActive = false;
            pointerX = pointerY = Double.NaN;
        }
    }

    /** Album artwork belongs to MusicService and is never closed by the preview. */
    public void releaseCurrentSongPreview() {
        LyricsOverlay retired = currentSongPreview;
        currentSongPreview = null;
        if (retired != null) retired.close();
    }

    /** Bounds currently seen by the player, including drag/resize smoothing. */
    private int lastViewportWidth, lastViewportHeight;
    public PopupBounds popupBoundsSnapshot(int windowWidth, int windowHeight) {
        if (dockPresentationActive() && dockFrame != null && dockWindowWidth==windowWidth && dockWindowHeight==windowHeight) return popupBox(dockFrame.bounds());
        return fullPopupBoundsSnapshot(windowWidth,windowHeight);
    }
    private PopupBounds fullPopupBoundsSnapshot(int windowWidth,int windowHeight) {
        if (dragging || resizing || !preferences.isLyricsAnimations() || !smoothBoundsInitialized || lastViewportWidth != windowWidth || lastViewportHeight != windowHeight) return targetPopupBounds(windowWidth, windowHeight);
        int width = Math.clamp((int) Math.round(smoothWidth), 1, Math.max(1, windowWidth));
        int height = Math.clamp((int) Math.round(smoothHeight), 1, Math.max(1, windowHeight));
        int x = Math.clamp((int) Math.round(smoothX), 0, Math.max(0, windowWidth - width));
        int y = Math.clamp((int) Math.round(smoothY), 0, Math.max(0, windowHeight - height));
        return new PopupBounds(x, y, width, height);
    }

    public void renderInPhone(DrawContext context, int x, int y, int width, int height,
                              double screenX, double screenY, double scaleX, double scaleY) {
        renderInPhone(context, x, y, width, height, screenX, screenY, scaleX, scaleY,
                Double.NaN, Double.NaN);
    }

    public void renderInPhone(DrawContext context, int x, int y, int width, int height,
                              double screenX, double screenY, double scaleX, double scaleY,
                              double mouseX, double mouseY) {
        renderingInPhone = true;
        phoneViewport = new PhoneViewport(screenX, screenY, scaleX, scaleY);
        pointerX = mouseX;
        pointerY = mouseY;
        try {
            renderExpanded(context, x, y, width, height);
        } finally {
            renderingInPhone = false;
            phoneViewport = null;
            pointerX = Double.NaN;
            pointerY = Double.NaN;
        }
    }

    public OptionalLong seekTargetAt(double x, double y) {
        if (!preferences.isLyricsClickSeek() || staticLyrics()) return OptionalLong.empty();
        for (HitRegion region : List.copyOf(phoneHitRegions)) {
            if (x >= region.x && x <= region.x + region.width && y >= region.y && y <= region.y + region.height) {
                return OptionalLong.of(lyricSeekPosition(region.positionMillis));
            }
        }
        return OptionalLong.empty();
    }

    public OptionalLong seekTargetAtHud(double x, double y) {
        if (!preferences.isLyricsClickSeek() || staticLyrics() || isPopupCoverInteractionActiveAt(x, y)) {
            return OptionalLong.empty();
        }
        for (HitRegion region : List.copyOf(hudHitRegions)) {
            if (region.contains(x, y)) return OptionalLong.of(lyricSeekPosition(region.positionMillis));
        }
        return OptionalLong.empty();
    }

    public PopupControl popupControlAt(double x, double y) {
        if (contains(popupRestoreButtonBounds,x,y)) return PopupControl.RESTORE;
        if (contains(popupCloseButtonBounds,x,y)) return PopupControl.CLOSE;
        if (contains(popupSettingsButtonBounds, x, y)) return PopupControl.SETTINGS;
        if (contains(popupIslandLyricsButtonBounds, x, y)) return PopupControl.TOGGLE_ISLAND_LYRICS;
        if (contains(popupWorldButtonBounds, x, y)) return PopupControl.TOGGLE_WORLD_LYRICS;
        if (contains(popupRomanizationButtonBounds, x, y)) return PopupControl.TOGGLE_ROMANIZATION;
        if (contains(popupTranslationButtonBounds, x, y)) return PopupControl.TOGGLE_TRANSLATION;
        if (contains(popupShuffleButtonBounds, x, y)) return PopupControl.TOGGLE_SHUFFLE;
        if (contains(popupPreviousButtonBounds, x, y)) return PopupControl.PREVIOUS;
        if (contains(popupPlayPauseButtonBounds, x, y)) return PopupControl.PLAY_PAUSE;
        if (contains(popupHeartButtonBounds, x, y)) return PopupControl.TOGGLE_HEART;
        if (contains(popupNextButtonBounds, x, y)) return PopupControl.NEXT;
        if (contains(popupRepeatButtonBounds, x, y)) return PopupControl.CYCLE_REPEAT;
        if (contains(popupMuteButtonBounds, x, y)) return PopupControl.TOGGLE_MUTE;
        return PopupControl.NONE;
    }

    public OptionalDouble popupVolumeTargetAt(double x, double y) {
        if (!contains(popupVolumeBounds, x, y) || contains(popupMuteButtonBounds, x, y)
                || popupVolumeTrackBounds == null) return OptionalDouble.empty();
        double fraction = Math.clamp((popupVolumeTrackBounds.y() + popupVolumeTrackBounds.height() - y)
                / popupVolumeTrackBounds.height(), 0, 1);
        return OptionalDouble.of(fraction);
    }

    public OptionalDouble popupVolumeTargetAtY(double y) {
        if (popupVolumeTrackBounds == null) return OptionalDouble.empty();
        double fraction = Math.clamp((popupVolumeTrackBounds.y() + popupVolumeTrackBounds.height() - y)
                / popupVolumeTrackBounds.height(), 0, 1);
        return OptionalDouble.of(fraction);
    }

    public void setPopupVolumeScrubbing(boolean value) { popupVolumeScrubbing = value; }

    public double popupVolumeLevel() {
        long previewAge = System.nanoTime() - popupVolumePreviewNanos;
        double volume = popupVolumePreviewNanos != 0 && previewAge >= 0
                && previewAge < 1_000_000_000L && Double.isFinite(popupVolumePreview)
                ? popupVolumePreview : playback.volume();
        return Math.clamp(Double.isFinite(volume) ? volume : 0, 0, 1);
    }

    public void animatePopupHeartLike() {
        popupHeartPreviewLiked = !playback.liked();
        popupHeartFillPreviewUntil = System.nanoTime() + 1_000_000_000L;
        animatePopupControl(PopupControl.TOGGLE_HEART);
    }

    public void setPopupSettingsPressed(boolean pressed) {
        setPopupUtilityPressed(pressed ? PopupControl.SETTINGS : PopupControl.NONE);
    }

    public static boolean isPopupUtilityControl(PopupControl control) {
        return control == PopupControl.CLOSE || control == PopupControl.RESTORE || control == PopupControl.SETTINGS || control == PopupControl.TOGGLE_ROMANIZATION
                || control == PopupControl.TOGGLE_TRANSLATION || control == PopupControl.TOGGLE_WORLD_LYRICS
                || control == PopupControl.TOGGLE_ISLAND_LYRICS;
    }

    public void setPopupUtilityPressed(PopupControl control) {
        popupUtilityPressed = isPopupUtilityControl(control) ? control : PopupControl.NONE;
    }

    private void refreshPopupUtilityAvailability() {
        LyricsDocument current = document;
        if (popupUtilityDocument == current) return;
        popupHasRomanization = current.lines().stream().anyMatch(LyricsRenderText::hasRomanization);
        popupHasTranslation = current.lines().stream().anyMatch(line -> line.translation() != null && !line.translation().isBlank());
        popupUtilityDocument = current;
    }

    /** Local preferences only; no playback command or provider request is needed. */
    public boolean togglePopupUtility(PopupControl control) {
        if (control == PopupControl.RESTORE) { restoreMinimizedPopup(); return true; }
        if (control == PopupControl.CLOSE) {
            if(isPopupDocked()) { cancelPopupProgressScrub(); dockMotion.collapseDetails(); }
            else minimizePopup();
            return true;
        }
        if (control == PopupControl.TOGGLE_ISLAND_LYRICS) {
            preferences.setIslandLyrics(!preferences.isIslandLyrics());
            islandLyricLayout = null; dockMotion.revealTitle(); saveLayout.run(); return true;
        }
        if (control == PopupControl.TOGGLE_WORLD_LYRICS) {
            preferences.setWorldLyrics(!preferences.isWorldLyrics()); saveLayout.run(); return true;
        }
        refreshPopupUtilityAvailability();
        if (control == PopupControl.TOGGLE_ROMANIZATION && popupHasRomanization)
            preferences.setLyricsRomanization(!preferences.isLyricsRomanization());
        else if (control == PopupControl.TOGGLE_TRANSLATION && popupHasTranslation)
            preferences.setLyricsTranslation(!preferences.isLyricsTranslation());
            if (preferences.isLyricsTranslation()) translationRequest.run();
        else return false;
        saveLayout.run();
        return true;
    }

    public void animatePopupControl(PopupControl control) {
        if (preferences.isLyricsAnimations() && control != PopupControl.NONE) {
            popupClickNanos[control.ordinal()] = System.nanoTime();
        }
    }

    private double popupClickScale(PopupControl control) {
        if (!preferences.isLyricsAnimations()) return 1;
        long elapsed = System.nanoTime() - popupClickNanos[control.ordinal()];
        if (elapsed < 0 || elapsed >= 420_000_000L) return 1;
        double phase = elapsed / 420_000_000.0;
        double strength = control == PopupControl.TOGGLE_HEART ? .27 : .14;
        return 1 + strength * Math.sin(phase * Math.PI * 2) * Math.exp(-1.2 * phase);
    }

    public void previewPopupVolume(double value) {
        popupVolumePreview = Math.clamp(value, 0, 1);
        popupVolumePreviewNanos = System.nanoTime();
    }

    public boolean beginPopupProgressScrub(double x, double y) {
        if (isPopupDocked()) {
            if (dockFrame==null || dockFrame.folded()<.98 || dockFrame.hover()<.98 || dockFrame.grip().contains(x,y) || playback.durationMillis()<=0
                    || !dockFrame.track().near(x,y,10*dockScale)) return false;
            dockGestureFrame=dockFrame; dockSeeking=true; popupProgressScrubbing=true;
            popupScrubDuration=playback.durationMillis(); updatePopupProgressScrub(x,y); return true;
        }
        if (!contains(popupProgressBounds, x, y) || playback.durationMillis() <= 0) return false;
        popupProgressScrubbing = true;
        popupScrubDuration = playback.durationMillis();
        updatePopupProgressScrub(x);
        return true;
    }

    public boolean updatePopupProgressScrub(double mouseX,double mouseY) {
        if(dockSeeking && popupProgressScrubbing && dockGestureFrame!=null && popupScrubDuration>0) {
            popupScrubPosition=PopupDockLayout.seek(dockGestureFrame.track(),dockGestureFrame.horizontal()?mouseX:mouseY,popupScrubDuration); return true;
        }
        return updatePopupProgressScrub(mouseX);
    }
    public boolean updatePopupProgressScrub(double mouseX) {
        if (!popupProgressScrubbing || popupProgressBounds == null || popupScrubDuration <= 0) return false;
        double fraction = Math.clamp(
                (mouseX - popupProgressBounds.x()) / popupProgressBounds.width(), 0, 1);
        popupScrubPosition = Math.round(popupScrubDuration * fraction);
        return true;
    }

    public OptionalLong finishPopupProgressScrub() {
        if (!popupProgressScrubbing) return OptionalLong.empty();
        popupProgressScrubbing = false;
        dockSeeking=false; dockGestureFrame=null;
        long target = Math.clamp(popupScrubPosition, 0, Math.max(0, popupScrubDuration));
        popupScrubDuration = 0;
        return OptionalLong.of(target);
    }

    public boolean isPopupProgressScrubbing() {
        return popupProgressScrubbing;
    }

    public boolean isPopupCoverInteractionActiveAt(double x, double y) {
        return popupCoverModeActive && popupCoverInteractionBounds != null
                && popupCoverInteractionBounds.contains(x, y);
    }

    public void cancelPopupProgressScrub() {
        dockSeeking=false; dockGestureFrame=null;
        popupProgressScrubbing = false;
        popupScrubDuration = 0;
    }

    private static boolean contains(ControlBounds bounds, double x, double y) {
        return bounds != null && bounds.contains(x, y);
    }

    /** Moves the lyric camera manually; synchronization resumes three seconds after the last wheel event. */
    public boolean scrollLyrics(boolean inPhone, double verticalAmount) {
        if (verticalAmount == 0 || document.groups().isEmpty()) return false;
        boolean ready = inPhone ? phoneCameraReady : hudCameraReady;
        if (!ready) return false;
        SpringScroll motion = inPhone ? phoneScroll : hudScroll;
        ManualLyricsScroll manual = inPhone ? phoneManualScroll : hudManualScroll;
        double minimum = inPhone ? phoneMinimumCamera : hudMinimumCamera;
        double maximum = inPhone ? phoneMaximumCamera : hudMaximumCamera;
        double surfaceScale = lyricsTextScale() * (inPhone ? PHONE_LYRICS_SCALE : 1.0);
        double wheel = Math.clamp(verticalAmount, -3, 3);
        manual.nudge(motion.position(), -wheel * Math.max(30, 42 * surfaceScale),
                minimum, maximum, System.nanoTime());
        return true;
    }

    private final IslandAudioMotion islandAudio = new IslandAudioMotion();
    private IslandLyrics.Frame islandLyricFrame = IslandLyrics.METADATA;
    private final java.util.function.LongSupplier waitingClock;
    private final IslandWaitingMotion islandWaiting = new IslandWaitingMotion();
    private IslandWaitingMotion.Frame islandWaitingFrame = new IslandWaitingMotion.Frame(0,0,0,0,0,false);
    private IslandLyrics.Frame islandBeforeWaiting = IslandLyrics.METADATA, islandLastWaiting = IslandLyrics.METADATA;
    private String islandWaitingTrack;
    private long islandWaitingPosition;
    public IslandWaitingMotion.Frame islandWaitingFrameSnapshot() { return islandWaitingFrame; }
    private LineLayout islandLyricLayout;
    private List<IslandLyrics.Cell> islandLyricCells = List.of();
    private LyricsMetadata islandCreditsMetadata;
    private List<IslandLyrics.Credit> islandCredits = List.of();
    private boolean islandWordTimed;
    private IslandLyrics.Viewport islandLyricViewport;
    public IslandLyrics.Viewport islandLyricsViewportSnapshot() { return islandLyricViewport; }
    private long islandLyricNanos, islandLyricPosition = Long.MIN_VALUE;
    private ControlBounds popupIslandLyricsButtonBounds;
    private double popupIslandLyricsHover;
    public IslandLyrics.Frame islandLyricsFrameSnapshot() { return islandLyricFrame; }
    private volatile PlaybackSnapshot lastIslandTrack;
    private java.util.function.Consumer<Boolean> islandAudioDemand;
    public void setIslandAudioLevels(MusicAudioLevels levels) { islandAudio.accept(levels); }
    public void setIslandAudioDemand(java.util.function.Consumer<Boolean> demand) { islandAudioDemand=demand; }
    public boolean wantsIslandAudio(long now) {
        return preferences.isLyricsVisible() && dockSurfaceVisibility>.01 && isPopupDocked() && playback.playing() && dockFrame!=null
                && dockFrame.hover()<.95 && dockFrame.titlePresence()>.01 && dockFrameNanos!=0 && now-dockFrameNanos<500_000_000L
                && (!preferences.isIslandLyrics() || islandLyricFrame.kind()==IslandLyrics.Kind.METADATA);
    }
    private static boolean meaningfulIslandTrack(PlaybackSnapshot value) {
        return value!=null && value.title()!=null && !value.title().isBlank() && !value.title().equals("Nothing playing");
    }
    private PlaybackSnapshot islandMetadata() { return meaningfulIslandTrack(playback) || lastIslandTrack==null?playback:lastIslandTrack; }

    private final PopupDockMotion dockMotion = new PopupDockMotion();
    private PopupDockMotion.Frame dockFrame,dockGestureFrame;
    private ControlBounds popupCloseButtonBounds, popupRestoreButtonBounds;
    private boolean dockInitialized,dockPreference,dockGripDragging,dockSeeking;
    private double dockDownX,dockDownY,dockScale=1,popupCloseHover,popupRestoreHover;
    private double dragStartX,dragStartY;
    private boolean popupDragMoved;
    public boolean isPopupDragActive(){return dragging;}
    private int dockWindowWidth,dockWindowHeight;
    private long dockFrameNanos;
    private double dockSurfaceVisibility=1;
    public boolean isPopupDocked() { return preferences.isPopupDocked(); }
    private boolean dockPresentationActive() { return preferences.isPopupDocked() || dockInitialized && dockMotion.active(); }
    public PopupDockMotion.Frame dockFrameSnapshot() { return dockFrame; }
    public boolean dockContains(double x,double y) { return isPopupDocked() && dockFrame!=null && dockFrame.bounds().near(x,y,6*dockScale); }
    private static PopupBounds popupBox(PopupDockLayout.Rect box) { return new PopupBounds((int)Math.round(box.x()),(int)Math.round(box.y()),Math.max(1,(int)Math.round(box.width())),Math.max(1,(int)Math.round(box.height()))); }
    private static PopupDockLayout.Rect dockBox(PopupBounds box) { return new PopupDockLayout.Rect(box.x(),box.y(),box.width(),box.height()); }
    public void minimizePopup() {
        if(!preferences.isPopupEdgeDocking()) return;
        var window=MinecraftClient.getInstance().getWindow(); int w=window.getScaledWidth(),h=window.getScaledHeight();
        if(!dockInitialized) { dockMotion.initialize(false,preferences.getPopupDockEdge(),preferences.getPopupDockAnchor(),playback.playing()); dockInitialized=true; }
        var anchor=dockMotion.minimize(dockBox(fullPopupBoundsSnapshot(w,h)),w,h,ResponsiveLyricsLayout.scale(w,h)*PopupDockLayout.PRESENTATION_SCALE);
        preferences.setPopupDockEdge(anchor.edge()); preferences.setPopupDockAnchor(anchor.position()); preferences.setPopupDocked(true);
        dockPreference=true; dockGripDragging=false; dragging=false; resizing=false; cancelPopupProgressScrub(); clearPopupControlBounds(); saveLayout.run();
    }
    public void restoreMinimizedPopup() {
        var window=MinecraftClient.getInstance().getWindow();int w=window.getScaledWidth(),h=window.getScaledHeight();
        cancelPopupProgressScrub(); dockGripDragging=false; dragging=false;
        preferences.setPopupDocked(false); dockPreference=false; dockMotion.restore(dockBox(configuredPopupBounds(w,h)),w,h,ResponsiveLyricsLayout.scale(w,h)*PopupDockLayout.PRESENTATION_SCALE);
        clearPopupControlBounds(); saveLayout.run();
    }
    private void setDockedDuringDrag(boolean attached,int width,int height) {
        if(!dockInitialized){dockMotion.initialize(false,preferences.getPopupDockEdge(),preferences.getPopupDockAnchor(),playback.playing());dockInitialized=true;}
        if(attached){
            var anchor=dockMotion.minimize(dockBox(configuredPopupBounds(width,height)),width,height,ResponsiveLyricsLayout.scale(width,height)*PopupDockLayout.PRESENTATION_SCALE);
            preferences.setPopupDockEdge(anchor.edge());preferences.setPopupDockAnchor(anchor.position());
        }else dockMotion.restore(dockBox(configuredPopupBounds(width,height)),width,height,ResponsiveLyricsLayout.scale(width,height)*PopupDockLayout.PRESENTATION_SCALE);
        preferences.setPopupDocked(attached);dockPreference=attached;
        dockGripDragging=false;dockGestureFrame=null;cancelPopupProgressScrub();clearPopupControlBounds();
    }
    private void restorePopupFromGrip(double x,double y) {
        var window=MinecraftClient.getInstance().getWindow(); int w=window.getScaledWidth(),h=window.getScaledHeight();
        double scale=ResponsiveLyricsLayout.scale(w,h);
        var full=configuredPopupBounds(w,h);
        int left=(int)Math.clamp(Math.round(x-full.width()/2.0),0,Math.max(0,w-full.width()));
        int top=(int)Math.clamp(Math.round(y-full.height()/2.0),0,Math.max(0,h-full.height()));
        preferences.setLyricsX(ResponsiveLyricsLayout.design(left,scale)); preferences.setLyricsY(ResponsiveLyricsLayout.design(top,scale));
        preferences.setPopupDocked(false); dockPreference=false; dockMotion.restore(dockBox(configuredPopupBounds(w,h)),w,h,ResponsiveLyricsLayout.scale(w,h)*PopupDockLayout.PRESENTATION_SCALE);
        gestureSizeProfile = popupSizeProfile();
        dockGripDragging=false; dockGestureFrame=null; dragging=true; resizing=false;
        dragOffsetX=x-left; dragOffsetY=y-top; dragStartX=dockDownX; dragStartY=dockDownY; popupDragMoved=true;
    }
    /** Only the native render path changes; playback, lyric documents and 3D lyrics keep running. */
    private boolean renderDockSurface(DrawContext context,PopupBounds full,int width,int height) {
        boolean minimized=preferences.isPopupDocked();
        if(!dockInitialized) { dockMotion.initialize(minimized,preferences.getPopupDockEdge(),preferences.getPopupDockAnchor(),playback.playing()); dockPreference=minimized; dockInitialized=true; }
        if(dockPreference!=minimized) {
            if(minimized) dockMotion.initialize(true,preferences.getPopupDockEdge(),preferences.getPopupDockAnchor(),playback.playing()); else dockMotion.restore(dockBox(full),width,height,ResponsiveLyricsLayout.scale(width,height)*PopupDockLayout.PRESENTATION_SCALE);
            dockPreference=minimized;
        }
        if(!preferences.isPopupEdgeDocking() && (dockSeeking || dockGripDragging)) { cancelPopupProgressScrub(); dockGripDragging=false; }
        long now=System.nanoTime(); double dt=dockFrameNanos==0?0:Math.clamp((now-dockFrameNanos)/1_000_000_000.0,0,.05); dockFrameNanos=now;
        if(dockWindowWidth>0 && (dockWindowWidth!=width || dockWindowHeight!=height) && dockGestureFrame!=null) { cancelPopupProgressScrub(); dockGripDragging=false; }
        dockScale=ResponsiveLyricsLayout.scale(width,height)*PopupDockLayout.PRESENTATION_SCALE; dockWindowWidth=width; dockWindowHeight=height;
        long lyricsNow=currentLyricsPositionMillis();
        islandLyricFrame=preferences.isIslandLyrics()?IslandLyrics.sample(document,lyricsNow,lyricsLoading):IslandLyrics.METADATA;
        String waitingTrack=playbackTrackKey(playback);
        if(!java.util.Objects.equals(waitingTrack,islandWaitingTrack)) islandBeforeWaiting=IslandLyrics.METADATA;
        islandWaitingTrack=waitingTrack;
        if(islandLyricFrame.kind()==IslandLyrics.Kind.WAITING) {
            islandLastWaiting=islandLyricFrame; islandWaitingPosition=lyricsNow;
        } else islandBeforeWaiting=islandLyricFrame;
        islandWaitingFrame=islandWaiting.update(islandLyricFrame,waitingTrack,waitingClock.getAsLong(),
                preferences.isLyricsAnimations(),reducedLyricsMotion());
        if(islandLyricFrame.kind()!=IslandLyrics.Kind.LYRICS) islandLyricViewport=null;
        if(islandLyricFrame.kind()!=IslandLyrics.Kind.METADATA) dockMotion.revealTitle();
        var sampled=dockMotion.step(dockBox(full),width,height,dockScale,pointerX,pointerY,renderingOverScreen && !dragging,dockGripDragging || dockSeeking,dt,preferences.isLyricsAnimations(),playback.playing());
        dockFrame=dockGestureFrame!=null && minimized ? dockGestureFrame : sampled;
        if(!minimized && !dockMotion.active()) {
            dockFrame=null; popupRestoreButtonBounds=null; popupCloseButtonBounds=null;
            return false;
        }
        clearPopupControlBounds(); hudHitRegions.clear(); hudCameraReady=false; popupCoverModeActive=false; popupCoverInteractionBounds=null;
        var frame=dockFrame; var box=frame.bounds();
        if(dockSurfaceVisibility<1) {
            double compactWeight=1-smooth(Math.clamp(frame.hover()/.22,0,1));
            double shrink=1-.08*(1-dockSurfaceVisibility)*compactWeight;
            box=new PopupDockLayout.Rect(box.centerX()-box.width()*shrink/2,box.y(),box.width()*shrink,box.height());
        }
        var painted=popupBox(box);
        double fold=frame.folded(),hover=Math.clamp(frame.hover(),0,1),visibility=Math.clamp(fold/.45,0,1)*dockSurfaceVisibility;
        if(fold<.45 && box.width()>=96*dockScale && box.height()>=64*dockScale)
            renderCompact(context,painted.x(),painted.y(),painted.width(),painted.height());
        clearPopupControlBounds(); hudHitRegions.clear();
        int radius=(int)Math.max(1,Math.min(box.height()/2,(16+12*hover)*dockScale));
        drawDockShape(context,painted.x(),painted.y(),painted.width(),painted.height(),radius,multiplyAlpha(0xFF101010,visibility));
        double warpOpacity=visibility*smooth(Math.clamp((hover-.12)/.7,0,1));
        if(preferences.isIslandKawarpBackground() && artworkTexture!=null && warpOpacity>.005) {
            double speed=preferences.isLyricsAnimations() && (!preferences.isPauseMotionWithMusic() || playback.playing())?dev.fandroid.client.music.KawarpRaster.speed(playback.playing(),playback.bpm(),playback.intensity(),playback.beatPhase()):0;
            if(artworkTexture.drawIslandBackdrop(context,painted.x(),painted.y(),painted.width(),painted.height(),radius,speed,warpOpacity))
                drawDockShape(context,painted.x(),painted.y(),painted.width(),painted.height(),radius,multiplyAlpha(0x55000000,warpOpacity));
        }
        double titlePresence=Math.clamp(frame.titlePresence(),0,1);
        double artVisibility=visibility*Math.max(1-titlePresence,preferences.isIslandVideoCover()?1:smooth(Math.clamp((hover-.22)/.68,0,1)));
        artVisibility*=1-islandWaitingFrame.visibility()*(1-islandWaitingFrame.title())*(1-hover);
        if(artVisibility>.005) {
            double idleArtWidth=32*titlePresence+PopupDockLayout.MEDIA_WIDTH*(1-titlePresence);
            int artWidth=Math.min(painted.width(),Math.max(1,(int)Math.round((idleArtWidth+(108-idleArtWidth)*hover)*dockScale)));
            int feather=Math.max(0,(int)Math.round((12*titlePresence+8*hover)*dockScale));
            drawIslandArtwork(context,painted.x(),painted.y(),artWidth,painted.height(),feather,radius,artVisibility,minimized && frame.folded()>.98 && titlePresence<.05 && hover<.05 && visibility>.01);
        }
        double compact=visibility*(1-smooth(Math.clamp(hover/.45,0,1)));
        islandAudio.step(playback.playing(),now,dt,preferences.isLyricsAnimations());
        if(compact*titlePresence>.01) {
            GuiTransform.enableScissor(context,painted.x(),painted.y(),painted.x()+painted.width(),painted.y()+painted.height());
            try {
                double waiting=islandWaitingFrame.visibility(),opacity=compact*titlePresence;
                if(waiting<.995) {
                    var lyric=islandBeforeWaiting.line();
                    if(islandBeforeWaiting.kind()==IslandLyrics.Kind.CREDITS) renderIslandCredits(context,box,opacity*(1-waiting));
                    else if(lyric!=null) renderIslandLyrics(context,box,opacity*(1-waiting),lyric);
                    else renderIslandTitle(context,box,opacity*(1-waiting),frame.audioPresence());
                }
                if(waiting>.005) renderIslandWaiting(context,box,opacity);
            }
            finally { context.disableScissor(); }
        }
        double reveal=visibility*smooth(Math.clamp((hover-.22)/.68,0,1));
        if(reveal>.005) {
            GuiTransform.enableScissor(context,painted.x(),painted.y(),painted.x()+painted.width(),painted.y()+painted.height());
            try { renderIslandDetails(context,frame,reveal,dt); }
            finally { context.disableScissor(); }
        }
        return true;
    }


    /** A single clipped row, driven by song position so pause/seek never desynchronizes it. */
    private void renderIslandLyrics(DrawContext context,PopupDockLayout.Rect box,double opacity,LyricLine line) {
        if(line==null || renderer()==null) return;
        double scale=1.2*dockScale;
        boolean romanized=preferences.isLyricsRomanization() && LyricsRenderText.hasRomanization(line);
        var prepared=layout(line,Integer.MAX_VALUE,scale,romanized);
        if(prepared.rows.isEmpty()) return;
        Row row=prepared.rows.getFirst();
        boolean changed=islandLyricLayout!=prepared;
        if(changed) {
            islandLyricLayout=prepared;
            islandWordTimed=document.metadata().syncMode()!=dev.fandroid.protocol.lyrics.LyricsSyncMode.LINE
                    && dev.fandroid.client.music.WorldLyricsPlan.hasWordTiming(line,romanized);
            islandLyricCells=row.tokens.stream().map(token -> new IslandLyrics.Cell(
                    token.timing.startMillis(),token.timing.endMillis(),token.x,
                    token.rawWidth*scale,isRightToLeft(token.text))).toList();
        }
        long now=currentLyricsPositionMillis(),frame=System.nanoTime();
        boolean reset=changed || islandLyricNanos==0 || frame-islandLyricNanos>250_000_000L
                || islandLyricPosition!=Long.MIN_VALUE && Math.abs(now-islandLyricPosition)>600;
        double delta=reset?0:Math.clamp((frame-islandLyricNanos)/1_000_000_000.0,0,.05);
        islandLyricNanos=frame; islandLyricPosition=now;
        var motion=new PreparedLine(line,prepared,scale,prepared.height);
        if(reset) resetLineMotion(motion,now,!reducedLyricsMotion());
        updateLineTokenMotion(motion,now,delta,reducedLyricsMotion());
        double left=box.x()+(preferences.isIslandVideoCover()?44:12)*dockScale;
        double right=box.right()-12*dockScale,available=Math.max(1,right-left);
        var viewport=IslandLyrics.viewport(islandLyricCells,available,now,line.startMillis(),line.endMillis(),islandWordTimed);
        islandLyricViewport=viewport;
        double x=left+(available-viewport.width())/2-viewport.offset();
        double glyphHeight=renderer().fontHeight*scale,y=box.centerY()-glyphHeight/2;
        int clipLeft=(int)Math.ceil(left),clipRight=(int)Math.floor(right);
        int clipTop=(int)Math.ceil(box.y()+3*dockScale),clipBottom=(int)Math.floor(box.bottom()-3*dockScale);
        if(clipRight<=clipLeft || clipBottom<=clipTop) return;
        GuiTransform.enableScissor(context,clipLeft,clipTop,clipRight,clipBottom);
        boolean previousShadow=drawingLyricsShadow; drawingLyricsShadow=false;
        try {
            if(verticalLineHighlight(line) && enhancedEffects() && !reducedLyricsMotion()) {
                activeGlowCommands.clear();
                for(int i=viewport.first();i<viewport.end();i++) {
                    var token=row.tokens.get(i);
                    if(x+token.x+token.rawWidth*scale<left || x+token.x>right) continue;
                    collectLineGlowCommand(prepared,token,x+token.x,y,scale,opacity);
                }
                renderActiveGlowPass(context,activeGlowCommands,clipLeft,clipTop,
                        clipRight-clipLeft,clipBottom-clipTop,0xFFFFFFFF,RenderPass.ACTIVE_GLOW_CORE,activeGlowCoreRadius());
            }
            for(int i=viewport.first();i<viewport.end();i++) {
                var token=row.tokens.get(i);
                if(x+token.x+token.rawWidth*scale<left || x+token.x>right) continue;
                if(verticalLineHighlight(line)) drawVerticalLineToken(context,token,x+token.x,y,
                        scale,line,now,opacity,y,glyphHeight);
                else drawTimedToken(context,token,x+token.x,y,scale,now,ACTIVE_TEXT,lyricsTextColor(UNSYNCED_TEXT),opacity,RenderPass.BASE);
            }
        } finally { drawingLyricsShadow=previousShadow; context.disableScissor(); }
    }


    private void renderIslandCredits(DrawContext context,PopupDockLayout.Rect box,double opacity) {
        var metadata=document.metadata();
        if(islandCreditsMetadata!=metadata) {
            islandCreditsMetadata=metadata; islandCredits=IslandLyrics.credits(metadata);
        }
        var page=IslandLyrics.creditPage(islandCredits,islandBeforeWaiting,currentLyricsPositionMillis());
        if(page==null) return;
        var credit=page.credit();
        String label=ui("lyrics.fandroid."+credit.role().translation);
        String text=credit.name().isBlank()?label:label+": "+credit.name();
        double scale=.95*dockScale,left=box.x()+(preferences.isIslandVideoCover()?44:12)*dockScale;
        double right=box.right()-12*dockScale,available=Math.max(1,right-left);
        double width=islandTextWidth(text)*scale;
        double scroll=Math.clamp((page.progress()-.08)/.84,0,1)*Math.max(0,width-available);
        double x=left+Math.max(0,(available-width)/2)-scroll;
        double duration=IslandLyrics.CREDITS_MILLIS/(double)islandCredits.size();
        double reveal=reducedLyricsMotion()?1:smooth(Math.clamp(page.progress()*duration/150,0,1))
                *smooth(Math.clamp((1-page.progress())*duration/150,0,1));
        int clipLeft=(int)Math.ceil(left),clipRight=(int)Math.floor(right);
        int clipTop=(int)Math.ceil(box.y()+3*dockScale),clipBottom=(int)Math.floor(box.bottom()-3*dockScale);
        if(clipRight<=clipLeft || clipBottom<=clipTop) return;
        GuiTransform.enableScissor(context,clipLeft,clipTop,clipRight,clipBottom);
        try {
            drawIslandText(context,text,x,box.centerY()-3.5*scale,multiplyAlpha(0xFFF2F2F2,opacity*reveal),scale);
        } finally {context.disableScissor();}
    }
    private void renderIslandWaiting(DrawContext context,PopupDockLayout.Rect box,double opacity) {
        var motion=islandWaitingFrame;
        double s=dockScale,textScale=.95*s,reveal=motion.title(),placement=motion.placement();
        boolean thumbnail=preferences.isIslandVideoCover();
        double diameter=Math.max(2.5,4.5*s),step=Math.max(9*s,diameter+2*s);
        double span=2*step+diameter,leading=span+9.5*s;
        double titleWidth=Math.min((thumbnail?126:152)*s,box.width()-(thumbnail?44:0)*s-12*s-leading);
        String title=fitIsland(islandMetadata().title(),Math.max(1,(int)Math.floor(titleWidth)),textScale);
        double groupWidth=leading+islandTextWidth(title)*textScale;
        double left=box.centerX()+(thumbnail?22*s:0)-groupWidth/2;
        double dotsCenter=box.centerX()+(left+span/2-box.centerX())*placement;
        double progress=islandLastWaiting.waitProgress(islandWaitingPosition);
        for(int i=0;i<3;i++) {
            double enter=motion.dot(i),fill=interludeDotFill(progress,i);
            double lift=motion.simple()?0:interludeDotLift(progress,i);
            double size=diameter*(motion.simple()?1:.94+.06*enter);
            double x=dotsCenter-span/2+i*step;
            double y=box.centerY()-size/2-lift*2*s+(motion.simple()?0:2*s*(1-enter));
            // Keep fractional positions and a fixed antialiased mask: no rounded pixel jumps.
            GuiTransform.push(context);
            try {
                GuiTransform.translate(context,(float)x,(float)y);
                GuiTransform.scale(context,(float)(size/32),(float)(size/32));
                RoundedGui.smoothFill(context,0,0,32,32,16,
                        multiplyAlpha(blend(0xFF777777,0xFFFFFFFF,fill),opacity*enter));
            } finally { GuiTransform.pop(context); }
        }
        double offset=motion.simple()?0:1-reveal;
        drawIslandText(context,title,dotsCenter-span/2+leading+6*s*offset,box.centerY()-3.5*textScale+3*s*offset,
                multiplyAlpha(0xFFF2F2F2,opacity*motion.visibility()*reveal),textScale);
    }
    private void renderIslandTitle(DrawContext context,PopupDockLayout.Rect box,double opacity,double audioPresence) {
        double textScale=.95*dockScale;
        boolean thumbnail=preferences.isIslandVideoCover();
        String title=fitIsland(islandMetadata().title(),(int)Math.round((thumbnail?126:152)*dockScale),textScale);
        double audioWidth=26*dockScale*audioPresence;
        double thumbnailWidth=thumbnail?44*dockScale:0;
        double groupWidth=audioWidth+islandTextWidth(title)*textScale;
        double left=box.centerX()+thumbnailWidth/2-groupWidth/2;
        if(audioPresence>.005) for(int i=0;i<5;i++) {
            double level=islandAudio.level(i);
            if(level<=.025) continue;
            double height=12*level*dockScale*audioPresence;
            int h=Math.max(1,(int)Math.round(height)),w=Math.max(1,(int)Math.round(2*dockScale));
            RoundedGui.smoothFill(context,(int)Math.round(left+i*4*dockScale),(int)Math.round(box.centerY()-h/2.0),w,h,Math.max(1,w/2),
                    multiplyAlpha(0xFF1DB954,opacity*audioPresence));
        }
        drawIslandText(context,title,left+audioWidth,box.centerY()-3.5*textScale,
                multiplyAlpha(0xFFF2F2F2,opacity),textScale);
    }

    private void renderIslandDetails(DrawContext context,PopupDockMotion.Frame frame,double opacity,double delta) {
        var content=frame.contents();
        double s=dockScale, childScale=.96+.04*opacity;
        GuiTransform.push(context);
        if(dockSurfaceVisibility<1) GuiTransform.translate(context,0,(float)Math.round(4*s*(1-opacity)));
        else {
            GuiTransform.translate(context,(float)content.centerX(),(float)content.y());
            GuiTransform.scale(context,(float)childScale,(float)childScale);
            GuiTransform.translate(context,(float)-content.centerX(),(float)(-content.y()+4*s*(1-opacity)));
        }
        try {
            var rail=frame.track();
            double tx=rail.x();
            int titleWidth=Math.max(1,(int)Math.round(PopupDockLayout.lyrics(content,s).x()-6*s-tx));
            drawIslandText(context,fitIsland(islandMetadata().title(),titleWidth,s),tx,content.y()+18*s,multiplyAlpha(0xFFFFFFFF,opacity),s);
            drawIslandText(context,fitIsland(islandMetadata().artist(),(int)Math.round(rail.width()),.86*s),tx,content.y()+34*s,multiplyAlpha(0xFFAAAAAA,opacity),.86*s);
            int rx=(int)Math.round(rail.x()),ry=(int)Math.round(rail.y()),rw=Math.max(1,(int)Math.round(rail.width())),rh=Math.max(1,(int)Math.round(rail.height()));
            RoundedGui.smoothFill(context,rx,ry,rw,rh,Math.max(1,rh/2),multiplyAlpha(0xFF353535,opacity));
            long duration=Math.max(0,playback.durationMillis()),position=dockSeeking?popupScrubPosition:Math.max(0,positionMillis());
            double progress=duration<=0?0:Math.clamp(position/(double)duration,0,1);
            if(progress>0) RoundedGui.smoothFill(context,rx,ry,Math.max(1,(int)Math.round(rw*progress)),rh,Math.max(1,rh/2),multiplyAlpha(0xFF1DB954,opacity));
            if(duration>0 && (dockSeeking || rail.near(pointerX,pointerY,6*s))) {
                int dot=Math.max(2,(int)Math.round(6*s));
                RoundedGui.smoothFill(context,(int)Math.round(rail.x()+progress*rail.width()-dot/2.0),(int)Math.round(rail.centerY()-dot/2.0),dot,dot,dot/2,multiplyAlpha(0xFFFFFFFF,opacity));
            }
            double textScale=.65*s;
            String end=duration>0?formatTime(duration):"--:--";
            drawIslandText(context,formatTime(position),rail.x(),content.y()+66*s,multiplyAlpha(0xFF929292,opacity),textScale);
            drawIslandText(context,end,rail.right()-islandTextWidth(end)*textScale,content.y()+66*s,multiplyAlpha(0xFF929292,opacity),textScale);
            ControlBounds previous=dockControl(PopupDockLayout.previous(content,s)),play=dockControl(PopupDockLayout.play(content,s)),next=dockControl(PopupDockLayout.next(content,s));
            boolean active=renderingOverScreen && frame.minimized() && frame.folded()>.98 && frame.hover()>.98 && !dockSeeking && !dockGripDragging;
            popupPreviousButtonBounds=active?previous:null; popupPlayPauseButtonBounds=active?play:null; popupNextButtonBounds=active?next:null;
            if(play.contains(pointerX,pointerY)) drawDockShape(context,play.x(),play.y(),play.width(),play.height(),play.width()/2,multiplyAlpha(0xFF353535,opacity));
            drawPopupTransport(context,previous,TransportIcon.PREVIOUS,multiplyAlpha(0xFFB8B8B8,opacity),popupClickScale(PopupControl.PREVIOUS));
            drawPopupTransport(context,play,playback.playing()?TransportIcon.PAUSE:TransportIcon.PLAY,multiplyAlpha(0xFFFFFFFF,opacity),popupClickScale(PopupControl.PLAY_PAUSE));
            drawPopupTransport(context,next,TransportIcon.NEXT,multiplyAlpha(0xFFB8B8B8,opacity),popupClickScale(PopupControl.NEXT));
            ControlBounds restore=dockControl(frame.grip()),close=dockControl(PopupDockLayout.close(content,s));
            popupRestoreButtonBounds=active?restore:null; popupCloseButtonBounds=active?close:null;
            ControlBounds settings=dockControl(PopupDockLayout.settings(content,s));
            popupSettingsButtonBounds=active?settings:null;
            ControlBounds lyrics=dockControl(PopupDockLayout.lyrics(content,s));
            popupIslandLyricsButtonBounds=active?lyrics:null;
            popupIslandLyricsHover=utilityHover(popupIslandLyricsHover,active && lyrics.contains(pointerX,pointerY),PopupControl.TOGGLE_ISLAND_LYRICS,delta);
            renderIslandHeaderIcon(context,lyrics,PopupIcons.LYRICS,PopupControl.TOGGLE_ISLAND_LYRICS,popupIslandLyricsHover,opacity);
            popupSettingsHover=utilityHover(popupSettingsHover,active && settings.contains(pointerX,pointerY),PopupControl.SETTINGS,delta);
            renderIslandHeaderIcon(context,settings,PopupIcons.SETTINGS,PopupControl.SETTINGS,popupSettingsHover,opacity);
            popupRestoreHover=utilityHover(popupRestoreHover,active && restore.contains(pointerX,pointerY),PopupControl.RESTORE,delta);
            popupCloseHover=utilityHover(popupCloseHover,active && close.contains(pointerX,pointerY),PopupControl.CLOSE,delta);
            renderIslandHeaderButton(context,restore,true,popupRestoreHover,opacity);
            renderIslandHeaderButton(context,close,false,popupCloseHover,opacity);
        } finally { GuiTransform.pop(context); }
    }

    private void drawIslandArtwork(DrawContext context,int x,int y,int width,int height,int feather,int radius,double opacity,boolean autoplay) {
        if(artworkTexture!=null) artworkTexture.drawIslandPanel(context,x,y,width,height,feather,radius,opacity,
                preferences.isIslandVideoCover() && preferences.isMotionVideos(),autoplay);
    }

    private Text islandText(String value) {
        if(!dev.fandroid.FanDroidMod.MOD_ID.equals("relaylyrics")) return styledText(value);
        return PlatformTextStyles.withFont(value,Identifier.of("relaylyrics","settings"));
    }
    private int islandTextWidth(String value) { return renderer().getWidth(islandText(value)); }
    private String fitIsland(String value,int width,double scale) {
        if(value==null) return "";
        if(islandTextWidth(value)*scale<=width) return value;
        int end=value.length();
        while(end>0) {
            end-=Character.charCount(value.codePointBefore(end));
            String candidate=value.substring(0,end).stripTrailing()+"…";
            if(islandTextWidth(candidate)*scale<=width) return candidate;
        }
        return "";
    }
    private void drawIslandText(DrawContext context,String value,double x,double y,int color,double scale) {
        color=multiplyAlpha(color,lyricsSurfaceOpacity);
        if(value==null || value.isEmpty() || color>>>24<4) return;
        GuiTransform.push(context);
        try {
            GuiTransform.translate(context,(float)x,(float)y); GuiTransform.scale(context,(float)scale,(float)scale);
            context.drawText(renderer(),islandText(value),0,0,color,false);
        } finally { GuiTransform.pop(context); }
    }

    private void renderIslandHeaderIcon(DrawContext context,ControlBounds bounds,Identifier icon,PopupControl control,double hover,double opacity) {
        if(hover>.005) drawDockShape(context,bounds.x(),bounds.y(),bounds.width(),bounds.height(),bounds.width()/2,multiplyAlpha(0xFF353535,opacity*hover));
        double press=popupUtilityPressed==control?.94:1;
        PopupIcons.drawCentered(context,icon,bounds.x()+bounds.width()/2,bounds.y()+bounds.height()/2,
                Math.max(3,(int)Math.round(13*dockScale*press)),multiplyAlpha(blend(
                        control==PopupControl.TOGGLE_ISLAND_LYRICS && preferences.isIslandLyrics()?0xFF1DB954:0xFF8A8A8A,0xFFFFFFFF,hover),opacity));
    }

    private void renderIslandHeaderButton(DrawContext context,ControlBounds bounds,boolean restore,double hover,double opacity) {
        PopupControl control=restore?PopupControl.RESTORE:PopupControl.CLOSE;
        double scale=popupUtilityPressed==control?.94:1;
        GuiTransform.push(context);
        GuiTransform.translate(context,bounds.x()+bounds.width()/2f,bounds.y()+bounds.height()/2f);
        GuiTransform.scale(context,(float)scale,(float)scale);
        GuiTransform.translate(context,-bounds.x()-bounds.width()/2f,-bounds.y()-bounds.height()/2f);
        try {
            if(hover>.005) drawDockShape(context,bounds.x(),bounds.y(),bounds.width(),bounds.height(),Math.max(1,bounds.width()/2),multiplyAlpha(0xFF353535,opacity*hover));
            int color=multiplyAlpha(blend(0xFF8A8A8A,0xFFFFFFFF,hover),opacity);
            if(!restore) PopupIcons.drawCentered(context,PopupIcons.CLOSE,bounds.x()+bounds.width()/2,bounds.y()+bounds.height()/2,Math.max(3,(int)Math.round(14*dockScale)),color);
            else {
                int w=Math.max(4,(int)Math.round(10*dockScale)),h=Math.max(3,(int)Math.round(8*dockScale)),line=Math.max(1,(int)Math.round(dockScale));
                int x=bounds.x()+(bounds.width()-w)/2,y=bounds.y()+(bounds.height()-h)/2;
                context.fill(x,y,x+w,y+line*2,color); context.fill(x,y+h-line,x+w,y+h,color);
                context.fill(x,y,x+line,y+h,color); context.fill(x+w-line,y,x+w,y+h,color);
            }
        } finally { GuiTransform.pop(context); }
    }
    private static ControlBounds dockControl(PopupDockLayout.Rect box) { return new ControlBounds((int)Math.round(box.x()),(int)Math.round(box.y()),Math.max(1,(int)Math.round(box.width())),Math.max(1,(int)Math.round(box.height()))); }
    /** Normalize large radii to the existing antialiased 16px mask instead of making square-ended pills. */
    private static void drawDockShape(DrawContext context,int x,int y,int width,int height,int radius,int color) {
        if(color>>>24==0 || width<=0 || height<=0) return;
        double scale=Math.max(1,radius/16.0);
        if(scale==1) { RoundedGui.smoothFill(context,x,y,width,height,radius,color); return; }
        GuiTransform.push(context); double density=PlatformTextures.beginUiScale(scale);
        try { GuiTransform.translate(context,x,y); GuiTransform.scale(context,(float)scale,(float)scale); RoundedGui.smoothFill(context,0,0,Math.max(1,(int)Math.round(width/scale)),Math.max(1,(int)Math.round(height/scale)),16,color); }
        finally { PlatformTextures.endUiScale(density); GuiTransform.pop(context); }
    }
    private void renderPopupClose(DrawContext context,ControlBounds close,CoverBounds cover,boolean integrated,double visibility,double delta) {
        popupCloseButtonBounds=null;
        if(renderingWorldPopup || !preferences.isPopupEdgeDocking() || preferences.isPopupDocked() || visibility<=.004) return;
        boolean over=renderingOverScreen && visibility>.32 && close.contains(pointerX,pointerY);
        popupCloseHover=utilityHover(popupCloseHover,over,PopupControl.CLOSE,delta);
        if(renderingOverScreen && visibility>.32) popupCloseButtonBounds=close;
        drawPopupUtility(context,close,PopupIcons.CLOSE,1,popupCloseHover,visibility,false,cover,integrated,3);
    }

    private AdaptivePopupLayout.Mode adaptivePopupMode = AdaptivePopupLayout.Mode.PHOTO_PLAYER;
    public AdaptivePopupLayout.Mode popupPresentation() { return adaptivePopupMode; }
    private void renderCompact(DrawContext context, int x, int y, int width, int height) {
        var window = MinecraftClient.getInstance().getWindow();
        double scale = ResponsiveLyricsLayout.scale(window.getScaledWidth(), window.getScaledHeight()) / PlatformTextures.renderScale();
        adaptivePopupMode = AdaptivePopupLayout.choose(width/scale,height/scale,videoArtworkAspect(),
                !document.groups().isEmpty(),lyricsTextScale());
        if (!adaptivePopupMode.compact()) scale *= ResponsiveLyricsLayout.contentScale(width / scale, height / scale);
        double px = pointerX, py = pointerY;
        GuiTransform.push(context); GuiTransform.scale(context, (float) scale, (float) scale);
        double oldDensity = PlatformTextures.beginUiScale(scale);
        scalePopupHits(1 / scale);
        pointerX /= scale; pointerY /= scale;
        try {
            renderCompactDesign(context, ResponsiveLyricsLayout.design(x, scale), ResponsiveLyricsLayout.design(y, scale),
                    Math.max(1, ResponsiveLyricsLayout.design(width, scale)), Math.max(1, ResponsiveLyricsLayout.design(height, scale)));
        } finally {
            scalePopupHits(scale); pointerX = px; pointerY = py;
            PlatformTextures.endUiScale(oldDensity); GuiTransform.pop(context);
        }
    }

    private void scalePopupHits(double scale) {
        popupCloseButtonBounds = scaled(popupCloseButtonBounds,scale);
        popupPreviousButtonBounds = scaled(popupPreviousButtonBounds, scale);
        popupPlayPauseButtonBounds = scaled(popupPlayPauseButtonBounds, scale);
        popupHeartButtonBounds = scaled(popupHeartButtonBounds, scale);
        popupNextButtonBounds = scaled(popupNextButtonBounds, scale);
        popupShuffleButtonBounds = scaled(popupShuffleButtonBounds, scale);
        popupRepeatButtonBounds = scaled(popupRepeatButtonBounds, scale);
        popupSettingsButtonBounds = scaled(popupSettingsButtonBounds, scale);
        popupWorldButtonBounds = scaled(popupWorldButtonBounds, scale);
        popupRomanizationButtonBounds = scaled(popupRomanizationButtonBounds, scale);
        popupTranslationButtonBounds = scaled(popupTranslationButtonBounds, scale);
        popupVolumeBounds = scaled(popupVolumeBounds, scale);
        popupVolumeTrackBounds = scaled(popupVolumeTrackBounds, scale);
        popupProgressTrackBounds = scaled(popupProgressTrackBounds, scale);
        popupMuteButtonBounds = scaled(popupMuteButtonBounds, scale);
        popupProgressBounds = scaled(popupProgressBounds, scale);
        if (popupCoverInteractionBounds != null) {
            var r = popupCoverInteractionBounds;
            popupCoverInteractionBounds = new CoverBounds((int) Math.round(r.x() * scale), (int) Math.round(r.y() * scale),
                    Math.max(1, (int) Math.round(r.width() * scale)), Math.max(1, (int) Math.round(r.height() * scale)));
        }
        for (int i = 0; i < hudHitRegions.size(); i++) {
            var r = hudHitRegions.get(i);
            hudHitRegions.set(i, new HitRegion((int) Math.round(r.x() * scale), (int) Math.round(r.y() * scale),
                    Math.max(1, (int) Math.round(r.width() * scale)), Math.max(1, (int) Math.round(r.height() * scale)), r.positionMillis()));
        }
    }
    private static ControlBounds scaled(ControlBounds r, double scale) {
        return r == null ? null : new ControlBounds((int) Math.round(r.x() * scale), (int) Math.round(r.y() * scale),
                Math.max(1, (int) Math.round(r.width() * scale)), Math.max(1, (int) Math.round(r.height() * scale)));
    }

    private void renderCompactDesign(DrawContext context, int x, int y, int width, int height) {
        TextRenderer renderer = renderer();
        if (renderer == null) return;
        double frameDelta = bandedTransitionDrawing ? 0
                : frameClock.nextDelta(preferences.getLyricsAnimationFrameRate());
        double delta = frameDelta * preferences.getLyricsTransitionSpeed().deltaMultiplier();
        stepCornerMorph(delta);
        PopupMotion.State animation = popupMotion.step(delta,
                preferences.isLyricsAnimations() && !renderingOverScreen && !bandedTransitionDrawing);
        GuiTransform.push(context);
        GuiTransform.translate(context, (float) (x + width / 2.0),
                (float) (y + height / 2.0 + animation.offset()));
        GuiTransform.scale(context, (float) animation.scale(), (float) animation.scale());
        GuiTransform.translate(context, (float) -(x + width / 2.0), (float) -(y + height / 2.0));
        int accent = accentColor();
        int surfaceRadius = morphRadius(preferences.isModernMode()
                ? Math.clamp(Math.min(width, height) / 22, 9, 16) : 0);
        boolean dynamic = false;
        if ((preferences.isLyricsDynamicBackground() || preferences.isCanvasBackground() && preferences.isMotionVideos())
                && artworkTexture != null) {
            double reactive = getLyricsBackgroundReactiveMultiplier();
            double musicSpeed = dev.fandroid.client.music.KawarpRaster.speed(playback.playing(), playback.bpm(), playback.intensity(), playback.beatPhase());
            double backdropSpeed = preferences.isLyricsAnimations() ? preferences.getLyricsBackgroundSpeedMultiplier() * reactive * musicSpeed : 0;
            boolean hueAuto = preferences.isLyricsReactiveHueAuto();
            double hueSignal = Math.clamp(playback.intensity(), 0, 1);
            boolean playing = playback.playing();
            double huePulse = !preferences.isLyricsReactiveHue() || !playing
                    ? 0 : hueAuto ? hueSignal
                    : hueSignal * (preferences.getLyricsReactiveHueAmount() / 80.0);
            double bloomPulse = !preferences.isLyricsReactiveBloom() || !playing
                    ? 0 : hueSignal * (preferences.getLyricsReactiveBloomAmount() / 100.0);
            CoverArtTexture.BackdropFx fx = new CoverArtTexture.BackdropFx(
                    huePulse, hueAuto, bloomPulse,
                    preferences.isLyricsReactiveSpectral() && playing,
                    preferences.getLyricsReactiveSpectralAmount() / 100.0,
                    playback.spectralCentroid());
            if (preferences.isLyricsArtistBackground()) dynamic = artworkTexture.drawArtistBackdrop(context, x, y, width, height,
                    preferences.getLyricsBackgroundOpacityMultiplier(), surfaceRadius);
            else dynamic = artworkTexture.drawBackdrop(context, x, y, width, height,
                    preferences.getLyricsBlurStrength(), backdropSpeed,
                    preferences.getLyricsBackgroundOpacityMultiplier(), surfaceRadius, fx, adaptivePopupMode.video());
        }
        if (!dynamic) {
            int fallbackAccent = accentColor();
            double opacity = preferences.getLyricsBackgroundOpacityMultiplier();
            if (opacity > 0) {
                int top = multiplyAlpha(blend(0xFF171321, fallbackAccent, .58), opacity);
                int bottom = multiplyAlpha(blend(0xFF0F1520, fallbackAccent, .24), opacity);
                if (preferences.isModernMode()) {
                    RoundedGui.smoothFill(context, x, y, width, height, surfaceRadius,
                            blend(top, bottom, .46));
                } else {
                    RoundedGui.gradient(context, x, y, width, height, surfaceRadius, top, bottom);
                }
            }
        }
        if (adaptivePopupMode.compact()) {
            renderAdaptivePlayer(context,x,y,width,height,frameDelta);
            if (preferences.isLyricsBorder()) drawPopupBorder(context,x,y,width,height,accent,surfaceRadius);
            if (preferences.isLyricsResizeGrip()) drawResizeGrip(context,x+width-15,y+height-15,0xB8D7DADE);
            GuiTransform.pop(context); return;
        }
        double videoAspect = videoArtworkAspect();
        if (adaptivePopupMode.video()) {
            if (adaptivePopupMode == AdaptivePopupLayout.Mode.VIDEO_ONLY) renderSoloVideo(context, x, y, width, height, frameDelta);
            else renderCanvasColumns(context, x, y, width, height, videoAspect, frameDelta, delta);
            if (preferences.isLyricsBorder()) drawPopupBorder(context, x, y, width, height, accent, surfaceRadius);
            if (preferences.isLyricsResizeGrip()) drawResizeGrip(context, x + width - 15, y + height - 15, 0xB8D7DADE);
            GuiTransform.pop(context);
            return;
        }
        boolean empty = document.groups().isEmpty();
        UnavailableLyricsTransition.State unavailable = unavailablePresentation();
        double emptyProgress = emptyStateMotion.step(delta, empty, preferences.isLyricsAnimations());
        double transition = Math.clamp(emptyProgress, 0, 1);
        int headerCover = Math.min(46, height / 4);
        int centeredCover = (int) Math.clamp(Math.min(width * .42, height * .54),
                Math.max(headerCover, 72), 180);
        int restingCover = (int) Math.round(headerCover + (centeredCover - headerCover) * transition);
        double startCenterX = x + 12 + headerCover / 2.0;
        double targetCenterX = x + width / 2.0;
        double startCenterY = y + 12 + headerCover / 2.0;
        double targetCenterY = y + Math.max(centeredCover / 2.0 + 10,
                height * .40 - 18);
        int restingCoverX = (int) Math.round(startCenterX + (targetCenterX - startCenterX) * transition
                - restingCover / 2.0);
        int restingCoverY = (int) Math.round(startCenterY + (targetCenterY - startCenterY) * transition
                - restingCover / 2.0);
        CoverBounds restingBounds = new CoverBounds(
                restingCoverX, restingCoverY, restingCover, restingCover);
        int coverMargin = 12;
        CoverBounds expandedBounds = new CoverBounds(x + coverMargin, y + coverMargin,
                Math.max(1, width - coverMargin * 2), Math.max(1, height - coverMargin * 2));
        popupCoverInteractionBounds = expandedBounds;
        if (!renderingOverScreen) {
            popupCoverHoverLatched = false;
        } else if (restingBounds.contains(pointerX, pointerY)) {
            popupCoverHoverLatched = true;
        } else if (!expandedBounds.contains(pointerX, pointerY)) {
            popupCoverHoverLatched = false;
        }
        boolean coverHovered = renderingOverScreen && popupCoverHoverLatched;
        boolean coverFocusWanted = coverHovered || popupProgressScrubbing || popupVolumeScrubbing;
        double coverFocus = Math.clamp(popupCoverMotion.step(frameDelta,
                coverFocusWanted, preferences.isLyricsAnimations()), 0, 1);
        popupCoverModeActive = coverFocusWanted || coverFocus > .025;
        if (popupCoverModeActive) hudHitRegions.clear();
        double coverTransition = easeInOutQuint(coverFocus);
        CoverBounds drawnCover = CoverBounds.interpolate(restingBounds, expandedBounds, coverTransition);
        double contentOpacity = 1 - smooth(Math.clamp(coverTransition / .72, 0, 1));
        int metadataGap = 14;
        int textRightLimit = x + width - 14;
        if (empty) {
            double textOpacity = smooth((transition - .28) / .72) * contentOpacity;
            double coverCenterY = restingBounds.y() + restingBounds.height() / 2.0;
            double coverBottomY = restingBounds.y() + restingBounds.height();
            int rightX = Math.min(restingBounds.x() + restingBounds.width() + metadataGap,
                    textRightLimit - 40);
            int rightAvail = Math.max(40, textRightLimit - rightX);
            int fullAvail = Math.max(40, width - 36);
            int interpAvail = (int) Math.round(rightAvail + (fullAvail - rightAvail) * transition);
            String title = playback.title();
            String artist = fit(playback.artist(), interpAvail, .94);
            String message = emptyMessage.isBlank() ? "" : fit(emptyMessage, interpAvail, .82);
            double titleW = Math.min(fullAvail, textWidth(title) * 1.16);
            double artistW = textWidth(artist) * .94;
            double messageW = textWidth(message) * .82;
            double titleHeight = renderer().fontHeight * 1.16;
            double artistHeight = renderer().fontHeight * .94;
            double gap = 4;
            double messageHeight = message.isBlank() ? 0
                    : (renderer().fontHeight * .82 + gap) * unavailable.messageOpacity();
            double blockHeight = titleHeight + gap + artistHeight
                    + (messageHeight > 0 ? gap + messageHeight : 0);
            double besideY = coverCenterY - blockHeight / 2.0;
            double belowY = coverBottomY + 18;
            double textY = besideY + (belowY - besideY) * transition;
            double centerAnchorX = x + width / 2.0;
            double titleX = rightX + (centerAnchorX - titleW / 2.0 - rightX) * transition;
            double artistX = rightX + (centerAnchorX - artistW / 2.0 - rightX) * transition;
            double messageX = rightX + (centerAnchorX - messageW / 2.0 - rightX) * transition;
            int titleWidth = Math.max(1, Math.min((int) Math.round(titleW),
                    Math.max(1, textRightLimit - (int) Math.round(titleX))));
            drawMarqueeText(context, title, (int) Math.round(titleX), textY, titleWidth,
                    multiplyAlpha(0xFFF7F6F1, textOpacity), 1.16, false);
            drawScaled(context, renderer, artist, artistX, textY + titleHeight + gap,
                    multiplyAlpha(0xFFB9BEC6, textOpacity), .94);
            if (!message.isBlank() && unavailable.messageOpacity() > .004) {
                drawScaled(context, renderer, message, messageX,
                        textY + titleHeight + gap + artistHeight + gap,
                        multiplyAlpha(0xFF939AA4, textOpacity * .92 * unavailable.messageOpacity()), .82);
            }
            if (lyricsLoading && preferences.isLyricsLoadingSkeleton() && contentOpacity > .1) {
                renderLoadingSkeleton(context, x + 20, Math.min(y + height - 46,
                        (int) Math.round(textY + blockHeight + 12)), width - 40, contentOpacity);
            }
        } else {
            if (contentOpacity > .004) {
                int metadataX = Math.min(restingBounds.x() + restingBounds.width() + metadataGap,
                        textRightLimit - 40);
                int metadataWidth = Math.max(40, textRightLimit - metadataX);
                double metadataTitleHeight = renderer().fontHeight * 1.12;
                double metadataArtistHeight = renderer().fontHeight * .88;
                int metadataTop = (int) Math.round(
                        (restingBounds.y() + restingBounds.height() / 2.0)
                                - (metadataTitleHeight + 2 + metadataArtistHeight) / 2.0);
                drawMarqueeText(context, playback.title(), metadataX, metadataTop, metadataWidth,
                        multiplyAlpha(0xFFF7F6F1, contentOpacity), 1.12, false);
                drawScaled(context, renderer, fit(playback.artist(), metadataWidth, .88), metadataX,
                        metadataTop + (int) Math.round(metadataTitleHeight) + 2,
                        multiplyAlpha(0xFFB7BBC2, contentOpacity), .88);
                int lyricsTop = y + Math.max(64, headerCover + 18);
                renderLyricsBody(context, x + 5, lyricsTop, width - 10,
                        Math.max(1, height - (lyricsTop - y) - 8), false, delta, contentOpacity);
            }
        }
        int coverRadius = morphRadius(preferences.isModernMode()
                ? Math.clamp(Math.min(drawnCover.width(), drawnCover.height()) / 22, 9, 16) : 0);
        if (preferences.isLyricsCoverShadow() && drawnCover.width() < width - 4) {
            RoundedGui.smoothFill(context, drawnCover.x() + 2, drawnCover.y() + 6,
                    drawnCover.width(), drawnCover.height(), coverRadius, 0x14000000);
            RoundedGui.smoothFill(context, drawnCover.x() + 1, drawnCover.y() + 4,
                    drawnCover.width(), drawnCover.height(), coverRadius, 0x1B000000);
            RoundedGui.smoothFill(context, drawnCover.x(), drawnCover.y() + 2,
                    drawnCover.width(), drawnCover.height(), coverRadius, 0x24000000);
        }
        if (artworkTexture == null || !artworkTexture.drawStaticCover(context, drawnCover.x(), drawnCover.y(),
                drawnCover.width(), drawnCover.height(), coverRadius)) {
            int coverTop = blend(0xFF15121C, accent, .75);
            int coverBottom = blend(0xFF10151D, accent, .28);
            if (preferences.isModernMode()) {
                RoundedGui.smoothFill(context, drawnCover.x(), drawnCover.y(), drawnCover.width(),
                        drawnCover.height(), coverRadius, blend(coverTop, coverBottom, .48));
            } else {
                RoundedGui.gradient(context, drawnCover.x(), drawnCover.y(), drawnCover.width(),
                        drawnCover.height(), coverRadius, coverTop, coverBottom);
            }
        }
        renderPopupCoverControls(context, drawnCover, coverTransition, frameDelta);
        if (preferences.isLyricsBorder()) drawPopupBorder(context, x, y, width, height, accent, surfaceRadius);
        if (preferences.isLyricsResizeGrip()) {
            drawResizeGrip(context, x + width - 15, y + height - 15, 0xB8D7DADE);
        }
        GuiTransform.pop(context);
    }

    private ControlBounds playerBounds(ResponsiveLyricsLayout.Bounds box,int x,int y) {
        return new ControlBounds(x+box.x(),y+box.y(),box.width(),box.height());
    }
    /** Small players keep metadata and transport visible; their artwork is always static. */
    private void renderAdaptivePlayer(DrawContext context,int x,int y,int width,int height,double delta) {
        clearPopupControlBounds(); hudHitRegions.clear(); hudCameraReady = false;
        popupCoverModeActive = false; popupCoverHoverLatched = false; popupCoverInteractionBounds = null;
        boolean photo = adaptivePopupMode == AdaptivePopupLayout.Mode.PHOTO_PLAYER;
        var layout = AdaptivePopupLayout.player(width,height,photo);
        var art = layout.artwork();
        if (photo) {
            int radius = preferences.isModernMode() ? Math.clamp(art.width()/7,4,10) : 0;
            if (artworkTexture == null || !artworkTexture.drawStaticCover(context,x+art.x(),y+art.y(),art.width(),art.height(),radius))
                RoundedGui.smoothFill(context,x+art.x(),y+art.y(),art.width(),art.height(),radius,blend(0xFF171321,accentColor(),.5));
        }
        var metadata = layout.metadata();
        int closeReserve = renderingWorldPopup || preferences.isPopupEdgeDocking() ? layout.settings().width()+4 : 0;
        int worldReserve=layout.settings().width()+4;
        int metadataWidth = Math.max(1,metadata.width()-closeReserve-worldReserve);
        double titleScale = height < 90 ? .85 : 1.05, artistScale = height < 90 ? .72 : .84;
        drawMarqueeText(context,playback.title(),x+metadata.x(),y+metadata.y(),metadataWidth,0xFFF7F6F1,titleScale,false);
        drawScaled(context,renderer(),fit(playback.artist(),metadataWidth,artistScale),x+metadata.x(),y+metadata.y()+ (height < 90 ? 12 : 16),0xFFBCC2CE,artistScale);
        ControlBounds previous = playerBounds(layout.previous(),x,y), play = playerBounds(layout.play(),x,y);
        ControlBounds next = playerBounds(layout.next(),x,y), settings = playerBounds(layout.settings(),x-closeReserve,y);
        boolean interactive = renderingOverScreen;
        popupPreviousHover = fadeControl(popupPreviousHover,interactive && previous.contains(pointerX,pointerY),delta,13);
        popupPlayPauseHover = fadeControl(popupPlayPauseHover,interactive && play.contains(pointerX,pointerY),delta,13);
        popupNextHover = fadeControl(popupNextHover,interactive && next.contains(pointerX,pointerY),delta,13);
        boolean settingsHovered = interactive && settings.contains(pointerX,pointerY);
        popupSettingsHover = utilityHover(popupSettingsHover,settingsHovered,PopupControl.SETTINGS,delta);
        stepUtilityScale(popupSettingsScale,PopupControl.SETTINGS,settingsHovered,delta);
        popupPreviousButtonBounds = interactive ? previous : null; popupPlayPauseButtonBounds = interactive ? play : null;
        popupNextButtonBounds = interactive ? next : null; popupSettingsButtonBounds = interactive ? settings : null;
        drawPopupTransport(context,previous,TransportIcon.PREVIOUS,popupControlColor(popupPreviousHover,1,true),popupClickScale(PopupControl.PREVIOUS));
        drawPopupTransport(context,play,playback.playing()?TransportIcon.PAUSE:TransportIcon.PLAY,multiplyAlpha(0xFFFFFFFF, .92 + .08 * popupPlayPauseHover),popupClickScale(PopupControl.PLAY_PAUSE));
        drawPopupTransport(context,next,TransportIcon.NEXT,popupControlColor(popupNextHover,1,true),popupClickScale(PopupControl.NEXT));
        CoverBounds frame = new CoverBounds(x,y,width,height);
        drawPopupUtility(context,settings,PopupIcons.SETTINGS,popupSettingsScale.position(),popupSettingsHover,1,false,frame,true,0);
        ControlBounds world=playerBounds(layout.settings(),x-closeReserve-worldReserve,y);
        boolean worldHovered=interactive && world.contains(pointerX,pointerY);
        popupWorldHover=utilityHover(popupWorldHover,worldHovered,PopupControl.TOGGLE_WORLD_LYRICS,delta);
        stepUtilityScale(popupWorldScale,PopupControl.TOGGLE_WORLD_LYRICS,worldHovered,delta);
        popupWorldButtonBounds=interactive?world:null;
        drawPopupUtility(context,world,PopupIcons.WORLD_LYRICS,popupWorldScale.position(),popupWorldHover,1,preferences.isWorldLyrics(),frame,true,1);
        if(preferences.isPopupEdgeDocking()) renderPopupClose(context,playerBounds(layout.settings(),x,y),frame,true,1,delta);
        ControlBounds timeline = playerBounds(layout.progress(),x,y);
        long duration = Math.max(0,playback.durationMillis());
        long position = popupProgressScrubbing ? popupScrubPosition : Math.max(0,positionMillis());
        if (duration>0) position = Math.min(position,duration);
        popupProgressTrackBounds = interactive && duration>0 ? timeline : null;
        popupProgressBounds = interactive && duration>0 ? new ControlBounds(timeline.x(),timeline.y()-4,timeline.width(),timeline.height()+8) : null;
        boolean hover = contains(popupProgressBounds,pointerX,pointerY);
        stepSliderEngagement(popupProgressEngagement,hover || popupProgressScrubbing,delta);
        double progress = duration<=0 ? 0 : popupProgressScrubbing ? Math.clamp(position/(double)duration,0,1) : visualPlaybackProgress();
        drawPlayerCapsule(context,timeline,false,1+.36*popupProgressEngagement.position(),1,progress,false);
        if (layout.times()) {
            double timeScale = .65;
            String current = positionTimeLabel(position), end = durationTimeLabel(duration);
            drawScaled(context,renderer(),current,timeline.x(),y+layout.timeY(),0xFFAEB7C7,timeScale);
            drawScaled(context,renderer(),end,timeline.x()+timeline.width()-textWidth(end)*timeScale,y+layout.timeY(),0xFFAEB7C7,timeScale);
        }
    }

    /** A playing video occupies its own column; hover reveals controls in place. */
    private void renderCanvasColumns(DrawContext context, int x, int y, int width, int height,
                                     double aspect, double frameDelta, double lyricsDelta) {
        var columns = CanvasPopupLayout.columns(width, height, aspect);
        var media = columns.artwork();
        var lyrics = columns.lyrics();
        var surface = columns.videoSurface();
        CoverBounds cover = new CoverBounds(x + media.x(), y + media.y(), media.width(), media.height());
        popupCoverInteractionBounds = cover;
        boolean focused = renderingOverScreen && cover.contains(pointerX, pointerY)
                || popupProgressScrubbing || popupVolumeScrubbing;
        double focus = popupCoverMotion.step(frameDelta, focused, preferences.isLyricsAnimations(), .18);
        int radius = morphRadius(preferences.isModernMode() ? Math.clamp(Math.min(width, height) / 22, 9, 16) : 0);
        artworkTexture.drawCanvasSurface(context, x + surface.x(), y + surface.y(), surface.width(), surface.height(), radius);
        // Keep the title on the artwork, leaving the lyrics column free to show both voices.
        double metadataOpacity = 1 - smooth(focus);
        if (metadataOpacity > .004 && cover.width() >= 72 && cover.height() >= 72) {
            GuiTransform.nextLayer(context);
            int titleY = cover.bottom() - 35;
            drawMarqueeText(context, playback.title(), cover.x() + 10, titleY, Math.max(1, cover.width() - 20),
                    multiplyAlpha(0xFFF7F6F1, metadataOpacity), 1.0, false);
            drawScaled(context, renderer(), fit(playback.artist(), Math.max(1, cover.width() - 20), .82),
                    cover.x() + 10, titleY + 15, multiplyAlpha(0xFFCCD0D7, metadataOpacity), .82);
        }
        // A video hover must not hide the lyrics or block seeking in their separate column.
        popupCoverModeActive = false;
        if (!document.groups().isEmpty()) {
            renderLyricsBody(context, x + lyrics.x(), y + lyrics.y(), lyrics.width(), lyrics.height(),
                    false, lyricsDelta, 1);
        } else if (lyricsLoading && preferences.isLyricsLoadingSkeleton()) {
            renderLoadingSkeleton(context, x + lyrics.x() + 12, y + lyrics.y() + 28, Math.max(1, lyrics.width() - 24), 1);
        } else if (!emptyMessage.isBlank()) {
            drawMarqueeText(context, emptyMessage, x + lyrics.x() + 12, y + height / 2.0,
                    Math.max(1, lyrics.width() - 24), multiplyAlpha(0xFFCCD0D7, unavailablePresentation().messageOpacity()), .9, false);
        }
        popupCoverModeActive = focused || focus > .025;
        renderPopupCoverControls(context, cover, focus, frameDelta, true);
    }

    public dev.fandroid.client.music.PopupSizeProfile popupSizeProfile() {
        if ((dragging || resizing) && gestureSizeProfile != null) return gestureSizeProfile;
        if (!lyricsLoading || profileHasLyrics == null) profileHasLyrics = !document.groups().isEmpty();
        return dev.fandroid.client.music.PopupSizeProfile.choose(profileHasLyrics, videoArtworkAspect() > 0,
                artworkTexture != null && artworkTexture.presenceTexture() != null);
    }
    public int popupStoredWidth() { return preferences.getPopupWidth(popupSizeProfile()); }
    public int popupStoredHeight() { return preferences.getPopupHeight(popupSizeProfile()); }
    public void setPopupStoredWidth(int value) { preferences.setPopupWidth(popupSizeProfile(), value); }
    public void setPopupStoredHeight(int value) { preferences.setPopupHeight(popupSizeProfile(), value); }

    /** Adaptive layouts keep the user's outer frame and support free aspect-ratio resizing. */
    public boolean isSquareVideo() { return false; }
    public CanvasPopupLayout.Size resizePopup(int outerWidth, int outerHeight, int storedWidth) {
        return AdaptivePopupLayout.resize(outerWidth,outerHeight);
    }
    public int expandedPopupWidth(int savedWidth, int savedHeight) {
        return savedWidth;
    }

    private void renderSoloVideo(DrawContext context, int x, int y, int width, int height, double delta) {
        CoverBounds cover = new CoverBounds(x, y, width, height);
        popupCoverInteractionBounds = cover;
        boolean focused = renderingOverScreen && cover.contains(pointerX, pointerY) || popupProgressScrubbing || popupVolumeScrubbing;
        double focus = popupCoverMotion.step(delta, focused, preferences.isLyricsAnimations(), .18);
        int radius = morphRadius(preferences.isModernMode() ? Math.clamp(Math.min(width, height) / 22, 9, 16) : 0);
        artworkTexture.drawTransition(context, x, y, width, height, radius);
        double caption = 1 - smooth(focus);
        if (caption > .004 && width >= 80 && height >= 80) {
            GuiTransform.nextLayer(context);
            RoundedGui.smoothGradient(context, x, y, width, height, radius, 0, 0xB0000000, 0, 0, caption);
            drawMarqueeText(context, playback.title(), x + 12, y + height - 37, width - 24, multiplyAlpha(0xFFF7F6F1, caption), 1, false);
            drawScaled(context, renderer(), fit(playback.artist(), width - 24, .82), x + 12, y + height - 21,
                    multiplyAlpha(0xFFCCD0D7, caption), .82);
        }
        hudHitRegions.clear(); popupCoverModeActive = focused || focus > .025;
        renderPopupCoverControls(context, cover, focus, delta, true);
    }

    public double popupVideoAspect() { return videoArtworkAspect(); }

    private double videoArtworkAspect() { return artworkTexture == null ? 0 : artworkTexture.motionCoverAspectRatio(); }

    private void renderPopupCoverControls(DrawContext context, CoverBounds cover,
                                          double focus, double delta) {
        renderPopupCoverControls(context, cover, focus, delta, false);
    }
    private void renderPopupCoverControls(DrawContext context, CoverBounds cover,
                                          double focus, double delta, boolean integratedCanvas) {
        double visibility = renderingOverScreen ? smooth((focus - .06) / .82) : 0;
        int minimum = Math.min(cover.width(), cover.height());
        if (minimum < 84) visibility = 0;

        int controlSize = Math.clamp(minimum / 11, 13, 24);
        int controlGap = Math.clamp(minimum / 32, 3, 8);
        int controlsWidth = controlSize * 5 + controlGap * 4;
        int controlsX = cover.x() + (cover.width() - controlsWidth) / 2;
        refreshPopupUtilityAvailability();
        int utilityCount = 2 + (popupHasRomanization ? 1 : 0) + (popupHasTranslation ? 1 : 0) + (preferences.isPopupEdgeDocking()?1:0);
        boolean videoControls = integratedCanvas || isSquareVideo();
        double timeScale = minimum < 100 ? .62 : .72;
        int timeHeight = (int) Math.ceil(renderer().fontHeight * timeScale);
        var sliders = CanvasPopupLayout.playerSliders(cover.width(), cover.height(), utilityCount, videoControls, timeHeight);
        int controlsY = cover.y() + sliders.controlsY();
        ControlBounds shuffle = new ControlBounds(controlsX, controlsY, controlSize, controlSize);
        ControlBounds previous = new ControlBounds(controlsX + controlSize + controlGap,
                controlsY, controlSize, controlSize);
        ControlBounds playPause = new ControlBounds(controlsX + (controlSize + controlGap) * 2,
                controlsY, controlSize, controlSize);
        ControlBounds next = new ControlBounds(controlsX + (controlSize + controlGap) * 3,
                controlsY, controlSize, controlSize);
        ControlBounds repeat = new ControlBounds(controlsX + (controlSize + controlGap) * 4,
                controlsY, controlSize, controlSize);
        int edge = Math.clamp(minimum / 24, 5, 10);
        var utilities = CanvasPopupLayout.utilityRow(cover.width(), cover.height(), utilityCount,
                integratedCanvas || isSquareVideo());
        int utilitySize = utilities.size();
        int utilityTop = cover.y() + utilities.y();
        ControlBounds settings = new ControlBounds(cover.x() + utilities.left(0), utilityTop, utilitySize, utilitySize);
        ControlBounds romanization = popupHasRomanization ? new ControlBounds(cover.x() + utilities.left(2),
                utilityTop, utilitySize, utilitySize) : null;
        ControlBounds translation = popupHasTranslation ? new ControlBounds(cover.x() + utilities.left(popupHasRomanization ? 3 : 2),
                utilityTop, utilitySize, utilitySize) : null;
        ControlBounds world = new ControlBounds(cover.x()+utilities.left(1),utilityTop,utilitySize,utilitySize);
        ControlBounds close = preferences.isPopupEdgeDocking() ? new ControlBounds(cover.x()+utilities.left(utilityCount-1),utilityTop,utilitySize,utilitySize) : null;
        var volumeGeometry = sliders.volume();
        ControlBounds volume = new ControlBounds(cover.x() + volumeGeometry.x(), cover.y() + volumeGeometry.y(),
                volumeGeometry.width(), volumeGeometry.height());
        int volumeHeight = volume.height(), volumeWidth = volume.width();
        int heartSize = Math.clamp(minimum * 2 / 5, 34, 90);
        int heartCenterY = Math.min(cover.y() + cover.height() / 2,
                controlsY - heartSize / 2 - 4);
        ControlBounds heart = new ControlBounds(cover.x() + (cover.width() - heartSize) / 2,
                heartCenterY - heartSize / 2, heartSize, heartSize);
        int muteHeight = Math.clamp(volumeHeight / 5, 10, 14);
        ControlBounds mute = new ControlBounds(volume.x() - 4, volume.y() + volume.height() - muteHeight,
                volume.width() + 8, muteHeight);
        boolean volumeFits = sliders.volumeVisible();
        boolean interactive = visibility > .32;
        popupPreviousHover = fadeControl(popupPreviousHover,
                interactive && previous.contains(pointerX, pointerY), delta, 13);
        popupPlayPauseHover = fadeControl(popupPlayPauseHover,
                interactive && playPause.contains(pointerX, pointerY), delta, 13);
        boolean heartHovered = interactive && heart.contains(pointerX, pointerY);
        popupHeartHover = fadeControl(popupHeartHover, heartHovered, delta, 13);
        if (preferences.isLyricsAnimations()) {
            popupHeartScale.setGoal(heartHovered ? .88 : 1);
            popupHeartScale.step(delta);
        } else {
            popupHeartScale.snap(1);
        }
        boolean likedTarget = System.nanoTime() < popupHeartFillPreviewUntil
                ? popupHeartPreviewLiked : playback.liked();
        popupHeartFill = preferences.isLyricsAnimations()
                ? fadeControl(popupHeartFill, likedTarget, delta, 9) : (likedTarget ? 1 : 0);
        popupNextHover = fadeControl(popupNextHover,
                interactive && next.contains(pointerX, pointerY), delta, 13);
        popupShuffleHover = fadeControl(popupShuffleHover,
                interactive && shuffle.contains(pointerX, pointerY), delta, 13);
        popupRepeatHover = fadeControl(popupRepeatHover,
                interactive && repeat.contains(pointerX, pointerY), delta, 13);
        boolean settingsHovered = interactive && settings.contains(pointerX, pointerY);
        boolean worldHovered = interactive && world.contains(pointerX,pointerY);
        popupWorldHover=utilityHover(popupWorldHover,worldHovered,PopupControl.TOGGLE_WORLD_LYRICS,delta);
        stepUtilityScale(popupWorldScale,PopupControl.TOGGLE_WORLD_LYRICS,worldHovered,delta);
        popupWorldButtonBounds=interactive?world:null;
        boolean romanizationHovered = interactive && contains(romanization, pointerX, pointerY);
        boolean translationHovered = interactive && contains(translation, pointerX, pointerY);
        popupSettingsHover = utilityHover(popupSettingsHover, settingsHovered, PopupControl.SETTINGS, delta);
        popupRomanizationHover = utilityHover(popupRomanizationHover, romanizationHovered, PopupControl.TOGGLE_ROMANIZATION, delta);
        popupTranslationHover = utilityHover(popupTranslationHover, translationHovered, PopupControl.TOGGLE_TRANSLATION, delta);
        stepUtilityScale(popupSettingsScale, PopupControl.SETTINGS, settingsHovered, delta);
        stepUtilityScale(popupRomanizationScale, PopupControl.TOGGLE_ROMANIZATION, romanizationHovered, delta);
        stepUtilityScale(popupTranslationScale, PopupControl.TOGGLE_TRANSLATION, translationHovered, delta);
        popupVolumeHover = fadeControl(popupVolumeHover,
                interactive && volumeFits && (popupVolumeScrubbing || volume.contains(pointerX, pointerY)
                        || contains(popupVolumeBounds, pointerX, pointerY)), delta, 13);
        popupMuteHover = fadeControl(popupMuteHover,
                interactive && volumeFits && (mute.contains(pointerX, pointerY)
                        || contains(popupMuteButtonBounds, pointerX, pointerY)), delta, 13);

        popupPreviousButtonBounds = interactive ? previous : null;
        popupPlayPauseButtonBounds = interactive ? playPause : null;
        popupHeartButtonBounds = interactive ? heart : null;
        popupNextButtonBounds = interactive ? next : null;
        popupShuffleButtonBounds = interactive ? shuffle : null;
        popupRepeatButtonBounds = interactive ? repeat : null;
        popupSettingsButtonBounds = interactive ? settings : null;
        popupRomanizationButtonBounds = interactive ? romanization : null;
        popupTranslationButtonBounds = interactive ? translation : null;
        int volumeHitGrowth = (int) Math.ceil(volumeWidth * .4);
        popupVolumeTrackBounds = interactive && volumeFits ? volume : null;
        popupVolumeBounds = interactive && volumeFits ? new ControlBounds(
                volume.x() - 4 - (videoControls ? 0 : volumeHitGrowth), volume.y(),
                volume.width() + 8 + volumeHitGrowth, volume.height()) : null;
        popupMuteButtonBounds = interactive && volumeFits ? mute : null;
        if (visibility <= .004) {
            clearPopupControlBounds();
            return;
        }

        if (!integratedCanvas) {
            int shadeAlpha = (int) Math.round(150 * visibility);
            int coverRadius = morphRadius(preferences.isModernMode()
                    ? Math.clamp(Math.min(cover.width(), cover.height()) / 22, 9, 16) : 0);
            if (preferences.isModernMode()) {
                RoundedGui.smoothFill(context, cover.x(), cover.y(), cover.width(), cover.height(),
                        coverRadius, shadeAlpha << 24);
            } else {
                RoundedGui.fill(context, cover.x(), cover.y(), cover.width(), cover.height(),
                        coverRadius, shadeAlpha << 24);
            }
        }
        drawPopupTransport(context, previous, TransportIcon.PREVIOUS,
                popupControlColor(popupPreviousHover, visibility, true),
                popupClickScale(PopupControl.PREVIOUS));
        drawPopupTransport(context, playPause,
                playback.playing() ? TransportIcon.PAUSE : TransportIcon.PLAY,
                multiplyAlpha(0xFFFFFFFF, visibility * (.92 + .08 * popupPlayPauseHover)),
                popupClickScale(PopupControl.PLAY_PAUSE));
        drawPopupTransport(context, next, TransportIcon.NEXT,
                popupControlColor(popupNextHover, visibility, true),
                popupClickScale(PopupControl.NEXT));
        drawPopupShuffle(context, shuffle,
                popupControlColor(popupShuffleHover, visibility, playback.shuffled()),
                popupClickScale(PopupControl.TOGGLE_SHUFFLE));
        drawPopupRepeat(context, repeat,
                popupControlColor(popupRepeatHover, visibility, !"off".equals(playback.repeatMode())),
                popupClickScale(PopupControl.CYCLE_REPEAT));
        drawPopupHeart(context, heart, videoControls,
                popupControlColor(popupHeartHover, visibility, true), visibility);
        drawPopupUtility(context, settings, PopupIcons.SETTINGS, popupSettingsScale.position(),
                popupSettingsHover, visibility, false, cover, integratedCanvas, 0);
        drawPopupUtility(context,world,PopupIcons.WORLD_LYRICS,popupWorldScale.position(),popupWorldHover,visibility,preferences.isWorldLyrics(),cover,integratedCanvas,1);
        if (romanization != null) drawPopupUtility(context, romanization,
                preferences.isLyricsRomanization() ? PopupIcons.ROMANIZATION_ON : PopupIcons.ROMANIZATION_OFF,
                popupRomanizationScale.position(), popupRomanizationHover, visibility,
                preferences.isLyricsRomanization(), cover, integratedCanvas, 2);
        if (translation != null) drawPopupUtility(context, translation, PopupIcons.TRANSLATION,
                popupTranslationScale.position(), popupTranslationHover, visibility,
                preferences.isLyricsTranslation(), cover, integratedCanvas, 3);
        if(close!=null) renderPopupClose(context,close,cover,integratedCanvas,visibility,delta);
        if (settingsHovered || worldHovered || romanizationHovered || translationHovered) {
            String hint = ui(worldHovered ? "lyrics.fandroid.controls.world_lyrics" : romanizationHovered ? "lyrics.fandroid.controls.romanization"
                    : translationHovered ? "lyrics.fandroid.controls.translation" : "lyrics.fandroid.controls.settings");
            int hintWidth = Math.max(1, cover.width() - edge * 2);
            String label = fit(hint, Math.max(1, hintWidth - 10), .68);
            int paintedWidth = Math.min(hintWidth, (int) Math.ceil(textWidth(label) * .68) + 10);
            ControlBounds hovered = worldHovered ? world : romanizationHovered ? romanization
                    : translationHovered ? translation : settings;
            int hintX = Math.clamp(hovered.x() + (hovered.width() - paintedWidth) / 2,
                    cover.x() + edge, cover.right() - edge - paintedWidth);
            int hintY = hovered.y() + hovered.height() + 5;
            double hintAlpha = visibility * Math.max(popupWorldHover,Math.max(popupSettingsHover, Math.max(popupRomanizationHover, popupTranslationHover)));
            RoundedGui.smoothFill(context, hintX, hintY, paintedWidth, 15, 7,
                    multiplyAlpha(0xB0080A12, hintAlpha));
            drawScaled(context, renderer(), label, hintX + 5, hintY + 4,
                    multiplyAlpha(0xFFFFFFFF, hintAlpha), .68);
        }
        if (volumeFits) renderPopupVolume(context, volume, visibility, delta, videoControls);

        long duration = Math.max(0, playback.durationMillis());
        long position = popupProgressScrubbing ? popupScrubPosition : Math.max(0, positionMillis());
        if (duration > 0) position = Math.min(position, duration);
        var rail = sliders.progress();
        ControlBounds timeline = new ControlBounds(cover.x() + rail.x(), cover.y() + rail.y(), rail.width(), rail.height());
        boolean canSeek = duration > 0;
        popupProgressTrackBounds = interactive && canSeek ? timeline : null;
        int hitHeight = Math.max(18, timeline.height() + 12);
        popupProgressBounds = interactive && canSeek ? new ControlBounds(timeline.x(),
                timeline.y() + timeline.height() / 2 - hitHeight / 2, timeline.width(), hitHeight) : null;
        boolean progressHovered = contains(popupProgressBounds, pointerX, pointerY);
        stepSliderEngagement(popupProgressEngagement, progressHovered || popupProgressScrubbing, delta);
        double progress = duration <= 0 ? 0 : popupProgressScrubbing
                ? Math.clamp(position / (double) duration, 0, 1) : visualPlaybackProgress();
        drawPlayerCapsule(context, timeline, false, 1 + (videoControls ? .73 : .36) * popupProgressEngagement.position(),
                visibility, progress, false);
        String currentLabel = positionTimeLabel(position), durationLabel = durationTimeLabel(duration);
        double labelY = cover.y() + sliders.timeY();
        int textColor = multiplyAlpha(0xFFDCE2E4, visibility * .8);
        drawScaled(context, renderer(), currentLabel, timeline.x(), labelY, textColor, timeScale);
        drawScaled(context, renderer(), durationLabel, timeline.x() + timeline.width() - textWidth(durationLabel) * timeScale,
                labelY, textColor, timeScale);
        if (canSeek && (progressHovered || popupProgressScrubbing)) {
            long target = popupProgressScrubbing ? popupScrubPosition : Math.round(duration
                    * Math.clamp((pointerX - timeline.x()) / timeline.width(), 0, 1));
            String preview = formatTime(target);
            int bubbleWidth = (int) Math.ceil(textWidth(preview) * .8) + 12;
            int bubbleX = (int) Math.round(timeline.x() + timeline.width() * target / (double) duration - bubbleWidth / 2.0);
            bubbleX = Math.clamp(bubbleX, cover.x() + 2, Math.max(cover.x() + 2, cover.right() - bubbleWidth - 2));
            int bubbleY = timeline.y() - 22;
            RoundedGui.smoothFill(context, bubbleX, bubbleY, bubbleWidth, 17, 6, multiplyAlpha(0xA8121216, visibility));
            RoundedGui.smoothOutline(context, bubbleX, bubbleY, bubbleWidth, 17, 6, multiplyAlpha(0x30FFFFFF, visibility));
            drawScaled(context, renderer(), preview, bubbleX + 6, bubbleY + 5, multiplyAlpha(0xFFFFFFFF, visibility), .8);
        }
    }

    private static double fadeControl(double current, boolean active, double delta, double speed) {
        double response = 1 - Math.exp(-Math.clamp(delta, 0, .05) * speed);
        return current + ((active ? 1 : 0) - current) * response;
    }

    /** Advances the modern↔retro corner blend toward the currently selected mode. */
    private void stepCornerMorph(double delta) {
        double target = preferences.isModernMode() ? 1 : 0;
        if (!preferences.isLyricsAnimations() || delta <= 0) {
            cornerMorph = target;
            return;
        }
        double response = 1 - Math.exp(-Math.clamp(delta, 0, .05) * 14);
        cornerMorph += (target - cornerMorph) * response;
    }

    /** Current modern-mode corner blend, shared with the standalone controller. */
    public double cornerMorph() {
        return Math.clamp(cornerMorph, 0, 1);
    }

    /** Scales a modern corner radius by the current corner blend. */
    public int morphRadius(int modernRadius) {
        return (int) Math.round(modernRadius * cornerMorph());
    }

    private static int popupControlColor(double hover, double visibility, boolean enabled) {
        double resting = enabled ? .84 : .48;
        double highlight = enabled ? 1 : .8;
        return multiplyAlpha(0xFFFFFFFF, visibility * (resting + (highlight - resting) * Math.clamp(hover, 0, 1)));
    }

    private static void drawPopupTransport(DrawContext context, ControlBounds bounds,
                                           TransportIcon icon, int color, double clickScale) {
        var image = switch (icon) {
            case PREVIOUS -> PopupIcons.PREVIOUS;
            case PLAY -> PopupIcons.PLAY;
            case PAUSE -> PopupIcons.PAUSE;
            case NEXT -> PopupIcons.NEXT;
        };
        boolean primary = icon == TransportIcon.PLAY || icon == TransportIcon.PAUSE;
        int size = Math.clamp(bounds.height() - (primary ? 0 : 3), 9, primary ? 23 : 18);
        drawPopupTransportIcon(context, bounds, image, size, color, clickScale);
    }

    private static void drawPopupShuffle(DrawContext context, ControlBounds bounds, int color,
                                         double clickScale) {
        drawPopupTransportIcon(context, bounds, PopupIcons.SHUFFLE,
                Math.clamp(bounds.height() - 3, 9, 17), color, clickScale);
    }

    private static void drawPopupRepeat(DrawContext context, ControlBounds bounds, int color,
                                        double clickScale) {
        drawPopupTransportIcon(context, bounds, PopupIcons.REPEAT,
                Math.clamp(bounds.height() - 3, 9, 17), color, clickScale);
    }

    private static void drawPopupTransportIcon(DrawContext context, ControlBounds bounds,
                                               Identifier icon, int size, int color, double clickScale) {
        int centerX = bounds.x() + bounds.width() / 2, centerY = bounds.y() + bounds.height() / 2;
        int pixels = Math.max(1, (int) Math.round(size * clickScale));
        PopupIcons.drawCentered(context, icon, centerX, centerY + 1, pixels,
                multiplyAlpha(0x90000000, (color >>> 24) / 255.0));
        PopupIcons.drawCentered(context, icon, centerX, centerY, pixels, color);
    }

    private double utilityHover(double value, boolean hovered, PopupControl control, double delta) {
        boolean active = hovered || popupUtilityPressed == control;
        return preferences.isLyricsAnimations() ? fadeControl(value, active, delta, 13) : active ? 1 : 0;
    }

    private void stepUtilityScale(SpringValue motion, PopupControl control, boolean hovered, double delta) {
        if (!preferences.isLyricsAnimations()) { motion.snap(1); return; }
        motion.setGoal(popupUtilityPressed == control && hovered ? .94 : 1);
        motion.step(delta);
    }

    private void drawPopupUtility(DrawContext context, ControlBounds bounds, Identifier icon,
                                  double scale, double hover, double visibility, boolean selected,
                                  CoverBounds cover, boolean integratedCanvas, int slot) {
        scale = Math.clamp(scale, .92, 1.02);
        double centerX = bounds.x() + bounds.width() / 2.0;
        double centerY = bounds.y() + bounds.height() / 2.0;
        GuiTransform.push(context);
        GuiTransform.translate(context, (float) centerX, (float) centerY);
        GuiTransform.scale(context, (float) scale, (float) scale);
        GuiTransform.translate(context, (float) -centerX, (float) -centerY);
        try {
            if (preferences.isModernMode()) {
                int circle = bounds.width() / 2;
                if (artworkTexture != null) artworkTexture.drawGlassLens(context,
                        cover.x(), cover.y(), cover.width(), cover.height(), bounds.x(), bounds.y(),
                        bounds.width(), bounds.height(), circle, visibility * .86, integratedCanvas ? .77 : .42, slot);
                LiquidGlass.draw(context, bounds.x(), bounds.y(), bounds.width(), bounds.height(), circle,
                        visibility, Math.max(hover, selected ? .3 : 0));
            } else {
                RoundedGui.fill(context, bounds.x(), bounds.y(), bounds.width(), bounds.height(), 0,
                        multiplyAlpha(0xFFFFFFFF, hover * visibility * .15));
                RoundedGui.outline(context, bounds.x(), bounds.y(), bounds.width(), bounds.height(), 0,
                        multiplyAlpha(0xFFFFFFFF, visibility * .6));
            }
            int size = icon.equals(PopupIcons.CLOSE)
                    ? Math.clamp((int) Math.round(bounds.width() * .42), 6, 11)
                    : Math.clamp((int) Math.round(bounds.width() * .55), 10, 19);
            PopupIcons.drawPreciselyCentered(context, icon, centerX, centerY, size,
                    multiplyAlpha(0xFFFFFFFF, visibility * (.86 + .14 * hover)));
            if (selected) RoundedGui.smoothFill(context, bounds.x() + bounds.width() / 2 - 1,
                    bounds.y() + bounds.height() - 5, 2, 2, 1, multiplyAlpha(0xFFFFFFFF, visibility));
        } finally { GuiTransform.pop(context); }
    }

    private void stepSliderEngagement(SpringValue motion, boolean active, double delta) {
        if (!preferences.isLyricsAnimations()) { motion.snap(0); return; }
        motion.setGoal(active ? 1 : 0);
        motion.step(delta);
    }

    /** The hit region never grows with the glass; dragging stays tied to the actual capsule. */
    private void drawPlayerCapsule(DrawContext context, ControlBounds bounds,
                                   boolean vertical, double growth, double visibility, double level,
                                   boolean left) {
        growth = Math.clamp(growth, 1, 1.85);
        float anchorX = vertical ? bounds.x() + (left ? 0 : bounds.width()) : bounds.x() + bounds.width() / 2f;
        float anchorY = bounds.y() + bounds.height() / 2f;
        GuiTransform.push(context);
        GuiTransform.translate(context, anchorX, anchorY);
        GuiTransform.scale(context, vertical ? (float) growth : 1, vertical ? 1 : (float) growth);
        GuiTransform.translate(context, -anchorX, -anchorY);
        int radius = preferences.isModernMode() ? Math.min(bounds.width(), bounds.height()) / 2 : 0;
        try {
            RoundedGui.smoothFill(context, bounds.x(), bounds.y(), bounds.width(), bounds.height(), radius,
                    multiplyAlpha(0x87090C12, visibility));
            RoundedGui.smoothOutline(context, bounds.x(), bounds.y(), bounds.width(), bounds.height(), radius,
                    multiplyAlpha(0x45FFFFFF, visibility));
            int filled = (int) Math.round((vertical ? bounds.height() : bounds.width()) * Math.clamp(level, 0, 1));
            RoundedGui.smoothFillPortion(context, bounds.x(), bounds.y(), bounds.width(), bounds.height(), radius,
                    filled, vertical, multiplyAlpha(0xFFFFFFFF, visibility));
        } finally { GuiTransform.pop(context); }
    }

    private void renderPopupVolume(DrawContext context, ControlBounds bounds,
                                   double visibility, double delta, boolean left) {
        boolean hovered = contains(popupVolumeBounds, pointerX, pointerY) || popupVolumeScrubbing;
        stepSliderEngagement(popupVolumeEngagement, hovered, delta);
        double targetVolume = popupVolumeLevel();
        if (!Double.isFinite(popupDisplayedVolume) || popupVolumeScrubbing || !preferences.isLyricsAnimations())
            popupDisplayedVolume = targetVolume;
        else popupDisplayedVolume += (targetVolume - popupDisplayedVolume) * (1 - Math.exp(-Math.clamp(delta, 0, .05) * 18));
        double growth = 1 + .35 * popupVolumeEngagement.position();
        drawPlayerCapsule(context, bounds, true, growth, visibility, popupDisplayedVolume, left);
        int iconHeight = Math.clamp(bounds.height() / 5, 10, 14);
        double centerX = bounds.x() + (left ? bounds.width() * growth / 2 : bounds.width() - bounds.width() * growth / 2);
        boolean onFill = popupDisplayedVolume * bounds.height() >= iconHeight;
        PopupIcons.drawPreciselyCentered(context, PopupIcons.volume(popupDisplayedVolume), centerX,
                bounds.y() + bounds.height() - iconHeight / 2.0, Math.clamp(bounds.width() - 1, 7, 11),
                multiplyAlpha(onFill ? 0xAE000000 : 0xF0FFFFFF, visibility * (.85 + .15 * popupMuteHover)));
    }

    record SliderSnapshot(PopupBounds volume, PopupBounds volumeTarget, PopupBounds progress, PopupBounds progressTarget,
                          PopupBounds mute, double level) { }
    SliderSnapshot sliderSnapshotForProbe() {
        return new SliderSnapshot(sliderBounds(popupVolumeTrackBounds), sliderBounds(popupVolumeBounds),
                sliderBounds(popupProgressTrackBounds), sliderBounds(popupProgressBounds), sliderBounds(popupMuteButtonBounds), popupDisplayedVolume);
    }
    private static PopupBounds sliderBounds(ControlBounds bounds) {
        return bounds == null ? null : new PopupBounds(bounds.x(), bounds.y(), bounds.width(), bounds.height());
    }

    private void renderLoadingSkeleton(DrawContext context, int x, int y, int width, double opacity) {
        if (width < 30) return;
        double phase = preferences.isLyricsAnimations()
                ? (Math.sin(System.nanoTime() / 380_000_000.0) + 1) * .5 : .5;
        int color = multiplyAlpha(0xFFB5BEC5, opacity * (.16 + phase * .13));
        drawScaled(context, renderer(), ui("lyrics.fandroid.loading"), x, y - 13,
                multiplyAlpha(0xFFD6DDE1, opacity), .72);
        for (int index = 0; index < 3; index++) {
            int lineWidth = (int) Math.round(width * (index == 1 ? .72 : index == 2 ? .48 : .91));
            int lineY = y + index * 9;
            int radius = morphRadius(preferences.isModernMode() ? 2 : 0);
            if (preferences.isModernMode()) {
                RoundedGui.smoothFill(context, x, lineY, lineWidth, 4, radius, color);
            } else {
                RoundedGui.fill(context, x, lineY, lineWidth, 4, radius, color);
            }
        }
    }

    private void drawPopupHeart(DrawContext context, ControlBounds bounds,
                                boolean video, int color, double visibility) {
        int centerX = bounds.x() + bounds.width() / 2;
        int centerY = bounds.y() + bounds.height() / 2;
        int size = Math.max(1, (int) Math.round(bounds.width() * (video ? .8 : 1)
                * Math.clamp(popupHeartScale.position() * popupClickScale(PopupControl.TOGGLE_HEART), .68, 1.32)));
        if (popupHeartHover > .001) {
            PopupIcons.drawCentered(context, PopupIcons.HEART, centerX, centerY, size,
                    multiplyAlpha(0xFF9BA3A8, visibility * popupHeartHover * .42));
        }
        if (popupHeartFill > .001) {
            PopupIcons.drawCentered(context, PopupIcons.HEART, centerX, centerY, size,
                    multiplyAlpha(0xFFFFFFFF, visibility * popupHeartFill));
        }
        PopupIcons.drawCentered(context, PopupIcons.HEART_OUTLINE, centerX, centerY, size, color);
    }

    private static String formatTime(long millis) {
        long totalSeconds = Math.max(0, millis / 1_000);
        long hours = totalSeconds / 3_600;
        long minutes = totalSeconds / 60 % 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) return hours + ":" + twoDigits(minutes) + ":" + twoDigits(seconds);
        return minutes + ":" + twoDigits(seconds);
    }

    private static String twoDigits(long value) {
        return value < 10 ? "0" + value : Long.toString(value);
    }

    private String positionTimeLabel(long millis) {
        long second = Math.max(0, millis / 1_000);
        if (second != cachedPositionSecond) {
            cachedPositionSecond = second;
            cachedPositionLabel = formatTime(second * 1_000);
        }
        return cachedPositionLabel;
    }

    private String durationTimeLabel(long millis) {
        long second = Math.max(0, millis / 1_000);
        if (second != cachedDurationSecond) {
            cachedDurationSecond = second;
            cachedDurationLabel = formatTime(second * 1_000);
        }
        return cachedDurationLabel;
    }

    private static double easeInOutQuint(double value) {
        double clamped = Math.clamp(value, 0, 1);
        return clamped < .5
                ? 16 * Math.pow(clamped, 5)
                : 1 - Math.pow(-2 * clamped + 2, 5) / 2;
    }

    private void clearPopupControlBounds() {
        popupCloseButtonBounds = null;
        popupRestoreButtonBounds = null;
        popupIslandLyricsButtonBounds = null;
        popupShuffleButtonBounds = null;
        popupPreviousButtonBounds = null;
        popupPlayPauseButtonBounds = null;
        popupHeartButtonBounds = null;
        popupNextButtonBounds = null;
        popupRepeatButtonBounds = null;
        popupSettingsButtonBounds = null;
        popupWorldButtonBounds = null;
        popupRomanizationButtonBounds = null;
        popupTranslationButtonBounds = null;
        popupVolumeBounds = null;
        popupVolumeTrackBounds = null;
        popupProgressTrackBounds = null;
        popupMuteButtonBounds = null;
        popupProgressBounds = null;
    }

    private static void drawPopupBorder(DrawContext context, int x, int y, int width, int height,
                                        int accent, int radius) {
        if (width < 3 || height < 3) return;
        int outer = blend(0xFFDDE4E6, accent, .18);
        int inner = multiplyAlpha(0xFF07090B, .62);
        if (radius > 0) {
            RoundedGui.smoothOutline(context, x, y, width, height, radius, outer, inner);
            return;
        }
        context.fill(x, y, x + width, y + 1, outer);
        context.fill(x, y + height - 1, x + width, y + height, outer);
        context.fill(x, y + 1, x + 1, y + height - 1, outer);
        context.fill(x + width - 1, y + 1, x + width, y + height - 1, outer);
        context.fill(x + 1, y + 1, x + width - 1, y + 2, inner);
        context.fill(x + 1, y + height - 2, x + width - 1, y + height - 1, inner);
        context.fill(x + 1, y + 2, x + 2, y + height - 2, inner);
        context.fill(x + width - 2, y + 2, x + width - 1, y + height - 2, inner);
    }

    private static void drawResizeGrip(DrawContext context, int x, int y, int color) {
        for (int line = 0; line < 3; line++) {
            int length = 3 + line * 3;
            for (int pixel = 0; pixel < length; pixel++) {
                int drawX = x + 11 - pixel;
                int drawY = y + 11 - (length - 1 - pixel);
                context.fill(drawX, drawY, drawX + 1, drawY + 1, color);
            }
        }
    }

    private void drawLyricHover(DrawContext context, HitRegion region,
                                double opacity, double scale) {
        if (opacity <= .004) return;
        double safeScale = Math.clamp(scale, .94, 1);
        double insetX = region.width() * (1 - safeScale) / 2.0;
        double insetY = region.height() * (1 - safeScale) / 2.0;
        int left = (int) Math.round(region.x() + insetX);
        int top = (int) Math.round(region.y() + insetY);
        int right = (int) Math.round(region.x() + region.width() - insetX);
        int bottom = (int) Math.round(region.y() + region.height() - insetY);
        int radius = morphRadius(preferences.isModernMode() ? Math.clamp((bottom - top) / 4, 4, 8) : 0);
        int color = withAlpha(0x00FFFFFF,
                (int) Math.round(46 * Math.clamp(opacity, 0, 1)));
        if (preferences.isModernMode()) {
            RoundedGui.smoothFill(context, left, top, right - left, bottom - top, radius, color);
        } else {
            RoundedGui.fill(context, left, top, right - left, bottom - top, radius, color);
        }
    }

    private void renderExpanded(DrawContext context, int x, int y, int width, int height) {
        TextRenderer renderer = renderer();
        if (renderer == null) return;
        phoneHitRegions.clear();
        if (document.groups().isEmpty()) {
            if (lyricsLoading && preferences.isLyricsLoadingSkeleton()) {
                renderLoadingSkeleton(context, x + 28, y + height / 2 - 10,
                        Math.max(30, width - 56), 1);
            } else {
                drawScaled(context, renderer, emptyMessage, x + 28, y + height / 2.0 - 8,
                        0xFFD1D5DA, 1.35);
            }
            return;
        }
        renderLyricsBody(context, x + 6, y + 6, width - 12, height - 12, true,
                frameClock.nextDelta(preferences.getLyricsAnimationFrameRate())
                        * preferences.getLyricsTransitionSpeed().deltaMultiplier());
    }

    private void renderLyricsBody(DrawContext context, int x, int y, int width, int height,
                                  boolean interactive, double delta) {
        renderLyricsBody(context, x, y, width, height, interactive, delta, 1);
    }

    private void renderLyricsBody(DrawContext context, int x, int y, int width, int height,
                                  boolean interactive, double delta, double surfaceOpacity) {
        if (width < 8 || height < 8) return;
        boolean previousStyling = stylingLyrics;
        double previousSurfaceOpacity = lyricsSurfaceOpacity;
        stylingLyrics = true;
        lyricsSurfaceOpacity = Math.clamp(surfaceOpacity, 0, 1);
        try {
            renderLyricsBodyContent(context, x, y, width, height, interactive, delta);
        } finally {
            stylingLyrics = previousStyling;
            lyricsSurfaceOpacity = previousSurfaceOpacity;
        }
    }

    private void renderLyricsBodyContent(DrawContext context, int x, int y, int width, int height,
                                         boolean interactive, double delta) {
        int clipLeft = x + 2;
        int clipTop = y + 2;
        int clipRight = x + width - 2;
        int clipBottom = y + height - 2;
        if (clipRight <= clipLeft || clipBottom <= clipTop) return;
        int textWidth = Math.max(40, width - 28);
        double focusY = y + height * .43;
        LyricsFramePlan framePlan = bandedTransitionDrawing ? bandedLyricsFramePlan : null;
        boolean updateMotionThisPass = framePlan == null;
        if (framePlan == null) {
            long now = currentLyricsPositionMillis();
            int accent = accentColor();
            LyricsScene currentScene = ensureScene(textWidth);
            int active = currentScene.updateFocus(staticLyrics() ? Long.MIN_VALUE : now);
            InterludeLayout interlude = staticLyrics() ? InterludeLayout.NONE : currentScene.interludeLayout(now);
            double activeCenter = staticLyrics() ? height * .43 - 14
                    : currentScene.cameraTarget(now, height, interlude);
            double minimumCamera = staticLyrics() ? activeCenter : currentScene.minimumCamera(interlude);
            double maximumCamera = currentScene.maximumCamera(interlude);
            SpringScroll scrollMotion = interactive ? phoneScroll : hudScroll;
            ManualLyricsScroll manualScroll = interactive ? phoneManualScroll : hudManualScroll;
            if (interactive) {
                phoneMinimumCamera = minimumCamera;
                phoneMaximumCamera = maximumCamera;
                phoneCameraReady = true;
            } else {
                hudMinimumCamera = minimumCamera;
                hudMaximumCamera = maximumCamera;
                hudCameraReady = true;
            }
            long frameNanos = System.nanoTime();
            double cameraGoal = manualScroll.target(activeCenter, minimumCamera, maximumCamera, frameNanos, !staticLyrics());
            ManualReadMotion readMotion = interactive ? phoneManualReadMotion : hudManualReadMotion;
            boolean manualScrolling = manualScroll.active(frameNanos);
            double manualRead = readMotion.step(manualScrolling, delta);
            boolean manualReading = manualScrolling || manualRead > .01;
            double scroll = scrollMotion.step(cameraGoal, delta, preferences.isLyricsAnimations());
            double minimum = scroll + y + 25 - focusY;
            double maximum = scroll + y + height - 12 - focusY;
            LyricsViewport.Range window = currentScene.visibleRange(
                    minimum - interlude.expansion(), maximum + interlude.expansion());
            updateFrameMotion(currentScene, window, active, now, delta, manualReading, scroll, frameNanos);
            boolean distantBloomNeeded = enhancedEffects()
                    && currentScene.needsDistantBloom(window, preferences.isSimpleLyricsMode(), manualReading);
            List<ActiveGlowCommand> glowCommands = enhancedEffects()
                    ? buildActiveGlowCommands(currentScene, window, interlude, x, textWidth, focusY, scroll,
                    active, now, manualReading) : List.of();
            if (bandedTransitionDrawing && !glowCommands.isEmpty()) {
                glowCommands = List.copyOf(glowCommands);
            }
            framePlan = new LyricsFramePlan(currentScene, window, now, accent, active, scroll,
                    manualReading, distantBloomNeeded, glowCommands, interlude);
            if (bandedTransitionDrawing) bandedLyricsFramePlan = framePlan;
        }
        LyricsScene currentScene = framePlan.scene();
        LyricsViewport.Range window = framePlan.window();
        long now = framePlan.now();
        int accent = framePlan.accent();
        int active = framePlan.active();
        double scroll = framePlan.scroll();
        boolean manualReading = framePlan.manualReading();
        boolean distantBloomNeeded = framePlan.distantBloomNeeded();
        List<ActiveGlowCommand> glowCommands = framePlan.glowCommands();
        InterludeLayout interlude = framePlan.interlude();
        if (!glowCommands.isEmpty()) {
            if (GuiTransform.dualGlow()) {
                renderActiveGlowPass(context, glowCommands, x, y, width, height,
                        accent, RenderPass.ACTIVE_GLOW_OUTER, activeGlowOuterRadius());
            }
            renderActiveGlowPass(context, glowCommands, x, y, width, height,
                    accent, RenderPass.ACTIVE_GLOW_CORE, activeGlowCoreRadius());
        }
        if (distantBloomNeeded && enhancedEffects()) {
            renderEnhancedPass(context, currentScene, window, x, y, width, height,
                    textWidth, focusY, scroll, active, now, accent, manualReading,
                    RenderPass.DISTANT_BLOOM, distantBlurRadius(), interlude);
        }
        int visibleTokens;
        enableLyricsScissor(context, clipLeft, clipTop, clipRight, clipBottom);
        boolean previousShadow = drawingLyricsShadow;
        drawingLyricsShadow = renderingWorldPopup || preferences.isLyricsTextShadow();
        try {
            visibleTokens = renderScenePass(context, currentScene, window, x, y, width, height, textWidth,
                    focusY, scroll, active, now, accent, delta, interactive, manualReading,
                    RenderPass.BASE, updateMotionThisPass, interlude);
            drawInterlude(context, currentScene, x, y, width, height, focusY, scroll,
                    active, now, accent, delta, RenderPass.BASE, updateMotionThisPass, interlude);
            drawCredits(context, currentScene, x + 14, y, textWidth, height, focusY, scroll, now,
                    interlude);
        } finally {
            drawingLyricsShadow = previousShadow;
            context.disableScissor();
        }
        int cachedLayouts;
        synchronized (layoutCache) { cachedLayouts = layoutCache.size(); }
        RenderStats previousStats = renderStats;
        if (previousStats.totalGroups() != currentScene.groups.length
                || previousStats.visibleGroups() != window.size()
                || previousStats.visibleTokens() != visibleTokens
                || previousStats.sceneRebuilds() != sceneBuilds
                || previousStats.cachedLayouts() != cachedLayouts) {
            renderStats = new RenderStats(currentScene.groups.length, window.size(), visibleTokens,
                    sceneBuilds, cachedLayouts);
        }
    }

    /** Draws distant-line bloom through the renderer for this Minecraft version. */
    private void renderEnhancedPass(DrawContext context, LyricsScene currentScene, LyricsViewport.Range window,
                                    int x, int y, int width, int height, int textWidth, double focusY,
                                    double scroll, int active, long now, int accent,
                                    boolean manualReading, RenderPass pass, float radius,
                                    InterludeLayout interlude) {
        ClipBounds bounds = clipBounds(x + 2, y + 2, x + width - 2, y + height - 2);
        if (!bloomRenderer.begin(context, bounds.left(), bounds.top(), bounds.right(), bounds.bottom(),
                renderingInPhone)) {
            markEnhancedFailed();
            return;
        }
        renderScenePass(context, currentScene, window, x, y, width, height, textWidth,
                focusY, scroll, active, now, accent, 0, false, manualReading, pass, false,
                interlude);
        if (!bloomRenderer.finish(context, bounds.left(), bounds.top(), bounds.right(), bounds.bottom(),
                radius, true, pass.activeGlow())) {
            markEnhancedFailed();
        }
    }

    /** Draws the cached glyph coverage once, with Spicy's animated blur and opacity. */
    private void renderActiveGlowPass(DrawContext context, List<ActiveGlowCommand> commands,
                                      int x, int y, int width, int height, int accent,
                                      RenderPass pass, float radius) {
        if (commands.isEmpty()) return;
        ClipBounds bounds = clipBounds(x + 2, y + 2, x + width - 2, y + height - 2);
        if (!bloomRenderer.begin(context, bounds.left(), bounds.top(), bounds.right(), bounds.bottom(),
                renderingInPhone)) {
            markEnhancedFailed();
            return;
        }
        double intensity = preferences.getLyricsGlowIntensity().multiplier();
        for (ActiveGlowCommand command : commands) {
            var glow = command.wholeLine()
                    ? LyricsAnimation.lineGlowProfile(command.glowAmount(), intensity)
                    : LyricsAnimation.activeGlowProfile(command.glowAmount(), intensity, command.emphasized());
            bloomRenderer.drawGlyph(context, renderer(), styledText(command.text()),
                    command.x(), command.y(), command.scale(),
                    multiplyAlpha(lyricsTextColor(ACTIVE_TEXT), command.opacity() * glow.coreOpacity() * lyricsSurfaceOpacity),
                    bounds.left(), bounds.top(), bounds.right(), bounds.bottom(),
                    Integer.MIN_VALUE, Integer.MAX_VALUE, glow.coreRadius());
        }
        if (!bloomRenderer.finish(context, bounds.left(), bounds.top(), bounds.right(), bounds.bottom(),
                radius, true, true)) {
            markEnhancedFailed();
        }
    }

    /** Plans visible word/glyph halos, including the spring tail after a phrase ends. */
    private List<ActiveGlowCommand> buildActiveGlowCommands(LyricsScene scene, LyricsViewport.Range window, InterludeLayout interlude,
                                                            int x, int textWidth,
                                                            double focusY, double scroll, int active,
                                                            long now, boolean manualReading) {
        activeGlowCommands.clear();
        boolean simple = reducedLyricsMotion();
        if (active < 0 || active >= scene.groups.length || simple || manualReading) return activeGlowCommands;
        for (int index = window.firstInclusive(); index < window.lastExclusive(); index++) {
            PreparedGroup group = scene.groups[index];
            collectGroupGlowCommands(group, scene.groupTop(index, interlude), x, textWidth, focusY, scroll, now,
                    group.group.activeAt(now) || index == active || settling(group.group, now));
        }
        return activeGlowCommands;
    }

    private void collectGroupGlowCommands(PreparedGroup group, double top, int x, int textWidth,
                                          double focusY, double scroll, long now, boolean animated) {
        LyricsTextAlignment alignment = preferences.getLyricsAlignment();
        boolean centered = alignment == LyricsTextAlignment.CENTER;
        boolean opposite = oppositeAligned(alignment, group.group.alignment());
        double focusScale = group.motion.scale();
        double y = focusY + top - scroll + group.motion.elevation();
        collectPreparedGlowCommands(group.lead, group, x, textWidth, y, focusScale, centered, opposite, now, animated);
        double backingY = y + group.lead.totalHeight + group.leadToBackingGap;
        for (PreparedLine background : group.backgrounds) {
            collectPreparedGlowCommands(background, group, x, textWidth, backingY, focusScale, centered, opposite, now, animated);
            backingY += background.totalHeight + group.backingGap;
        }
    }

    private void collectPreparedGlowCommands(PreparedLine prepared, PreparedGroup group, int x,
                                              int textWidth, double y, double focusScale,
                                              boolean centered, boolean opposite, long now, boolean animated) {
        boolean vertical = verticalLineHighlight(prepared.line);
        double baseScale = prepared.scale;
        double stableRowHeight = rowHeight(baseScale);
        double stableTextHeight = renderer().fontHeight * baseScale;
        for (int rowIndex = 0; rowIndex < prepared.layout.rows.size(); rowIndex++) {
            Row row = prepared.layout.rows.get(rowIndex);
            double scaledWidth = row.width * focusScale;
            // Scale around the selected alignment anchor. Centering every row's scale
            // independently makes a shorter backing vocal drift away from its lead.
            double rowX = centered ? x + 14 + (textWidth - scaledWidth) / 2.0
                    : opposite ? x + 14 + textWidth - scaledWidth : x + 14;
            double rowY = y + rowIndex * stableRowHeight
                    + stableTextHeight * (1 - focusScale) / 2;
            for (Token token : row.tokens) {
                double opacity = group.motion.opacity() * (prepared.line.background() ? .78 : 1);
                if (vertical) collectLineGlowCommand(prepared.layout, token, rowX + token.x * focusScale,
                        rowY, baseScale * focusScale, opacity);
                else collectTokenGlowCommands(token, rowX + token.x * focusScale, rowY,
                        baseScale * focusScale, now, opacity, animated);
            }
        }
    }

    private void collectLineGlowCommand(LineLayout layout, Token token, double x, double y,
                                        double scale, double opacity) {
        double signal = layout.lineGlow.amount();
        if (signal > .001) activeGlowCommands.add(new ActiveGlowCommand(
                token.text, x, y, scale, signal, opacity, false, true));
    }

    private void collectTokenGlowCommands(Token token, double x, double y, double scale,
                                          long now, double opacity, boolean animated) {
        var motionPlan = token.motionPlan(false);
        boolean segmented = motionPlan.syllableMotion() || motionPlan.glyphMotion();
        if (!segmented && !token.segments.isEmpty()) {
            Segment segment = token.segments.getFirst();
            double wordScale = animated ? segment.motion.scale() : 1;
            double signal = Math.max(0, token.glow.position());
            if (signal <= .001) return;
            double glyphScale = scale * wordScale;
            double drawX = x + token.rawWidth * scale * (1 - wordScale) / 2;
            double drawY = y + renderer().fontHeight * (scale - glyphScale) / 2
                    - (animated ? segment.motion.elevation() * renderer().fontHeight * scale : 0);
            activeGlowCommands.add(new ActiveGlowCommand(token.text, drawX, drawY, glyphScale,
                    signal, opacity, false));
            return;
        }
        int currentGlyph = -1;
        for (int i = 0; i < token.glyphs.size(); i++) {
            long start = token.glyphs.get(i).startMillis;
            if (start != Long.MIN_VALUE && start <= now) currentGlyph = i;
        }
        for (int glyphIndex = 0; glyphIndex < token.glyphs.size(); glyphIndex++) {
            Glyph glyph = token.glyphs.get(glyphIndex);
            if (glyph.startMillis == Long.MIN_VALUE) continue;
            Segment segment = token.segments.get(glyph.segmentIndex);
            boolean emphasized = motionPlan.glyphMotionForFragment(segment.durationMillis());
            double signal = Math.max(0, emphasized ? glyph.motion.glow() : segment.motion.glow());
            if (emphasized && now >= glyph.endMillis)
                signal /= 1 + .9 * Math.max(0, currentGlyph - glyphIndex);
            if (signal <= .001) continue;
            double containerScale = animated ? segment.motion.scale() : 1;
            double letterScale = animated && emphasized ? glyph.motion.scale() : 1;
            double containerCenter = x + (segment.x + segment.rawWidth / 2) * scale;
            double baseX = containerCenter + (x + glyph.x * scale - containerCenter) * containerScale;
            double glyphScale = scale * containerScale;
            double animatedScale = glyphScale * letterScale;
            double drawX = baseX + glyph.rawWidth * glyphScale * (1 - letterScale) / 2;
            double drawY = y + renderer().fontHeight * (scale - animatedScale) / 2
                    - (animated ? segment.motion.elevation() + (emphasized ? glyph.motion.elevation() : 0) : 0)
                    * renderer().fontHeight * scale;
            activeGlowCommands.add(new ActiveGlowCommand(glyph.text, drawX, drawY, animatedScale,
                    signal, opacity, emphasized));
        }
    }

    /** Advances visible springs once before planning and drawing any effects or faces. */
    private void updateFrameMotion(LyricsScene scene, LyricsViewport.Range window, int active,
                                   long now, double delta, boolean manualReading, double scroll, long frameNanos) {
        boolean simple = reducedLyricsMotion() || manualReading;
        boolean discontinuity = scene.lastMotionMillis == Long.MIN_VALUE
                || now < scene.lastMotionMillis - 250 || now > scene.lastMotionMillis + 1_000;
        if (discontinuity) {
            resetMotionState(scene, now, !simple);
        }
        scene.lastMotionMillis = now;
        scene.scrollTrail.record(frameNanos, scroll, active, simple || discontinuity);
        prepareNearbyEffects(scene, now);
        for (int index = window.firstInclusive(); index < window.lastExclusive(); index++) {
            PreparedGroup group = scene.groups[index];
            group.motion.scrollOffset = simple ? 0 : scene.scrollTrail.offset(index);
            group.motion.update(focusProfile(scene.distanceToFocus(index), simple, manualReading), delta, simple);
            updateLineTokenMotion(group.lead, now, delta, simple);
            for (PreparedLine background : group.backgrounds) updateLineTokenMotion(background, now, delta, simple);
        }
    }

    private void prepareNearbyEffects(LyricsScene scene, long now) {
        boolean glow = enhancedEffects() && !reducedLyricsMotion();
        boolean shadow = preferences.isLyricsTextShadow();
        if (!glow && !shadow) return;
        int softness = preferences.getLyricsShadowSoftness();
        double shadowRadius = softness <= 0 ? 0 : softness == 1 ? .65 : 1.25;
        for (int i = Math.max(0, scene.firstFocused - 1); i <= scene.lastFocused + 1 && i < scene.groups.length; i++) {
            PreparedGroup group = scene.groups[i];
            prepareLineEffects(group.lead, now, glow, shadow, shadowRadius);
            for (PreparedLine backing : group.backgrounds) prepareLineEffects(backing, now, glow, shadow, shadowRadius);
        }
    }

    private void prepareLineEffects(PreparedLine line, long now, boolean glow, boolean shadow, double shadowRadius) {
        boolean vertical = verticalLineHighlight(line.line);
        if (vertical && (line.line.endMillis() < now - 1_000 || line.line.startMillis() > now + 1_800)) return;
        for (Row row : line.layout.rows) for (Token token : row.tokens) {
            if (!vertical && (token.timing.endMillis() < now - 1_000 || token.timing.startMillis() > now + 1_800)) continue;
            var plan = token.motionPlan(false);
            if (glow && (vertical || !plan.glyphMotion() && !plan.syllableMotion())) {
                bloomRenderer.prepare(styledText(token.text), 1.5);
            }
            for (Glyph glyph : token.glyphs) {
                if (glyph.text.isBlank()) continue;
                if (shadow) bloomRenderer.prepare(styledText(glyph.text), shadowRadius);
                if (glow && !vertical && (plan.glyphMotion() || plan.syllableMotion())) {
                    bloomRenderer.prepare(styledText(glyph.text), 1.5);
                }
            }
        }
    }

    private static void resetMotionState(LyricsScene scene, long now, boolean animated) {
        for (PreparedGroup group : scene.groups) {
            resetLineMotion(group.lead, now, animated);
            for (PreparedLine backing : group.backgrounds) resetLineMotion(backing, now, animated);
        }
    }

    private static void resetLineMotion(PreparedLine line, long now, boolean animated) {
        line.layout.lineGlow.reset(timedProgress(now, line.line.startMillis(), line.line.endMillis()), animated);
        for (Row row : line.layout.rows) for (Token token : row.tokens) {
            token.glow.snap(0);
            for (Segment segment : token.segments)
                segment.motion.pose.reset(timedProgress(now, segment.startMillis, segment.endMillis), animated);
            for (Glyph glyph : token.glyphs)
                glyph.motion.pose.reset(timedProgress(now, glyph.startMillis, glyph.endMillis), animated);
        }
    }

    private void updateLineTokenMotion(PreparedLine line, long now, double delta, boolean simple) {
        if (verticalLineHighlight(line.line)) {
            line.layout.lineGlow.update(timedProgress(now, line.line.startMillis(), line.line.endMillis()), !simple, delta);
            return;
        }
        for (Row row : line.layout.rows) {
            for (Token token : row.tokens) {
                token.glow.setGoal(simple ? 0 : dev.fandroid.client.music.LyricsAnimation.spicyGlowTarget(
                        timedProgress(now, token.timing.startMillis(), token.timing.endMillis())));
                token.glow.step(delta);
                var motionPlan = token.motionPlan(simple);
                boolean segmentMotion = !simple;
                for (Segment segment : token.segments) {
                    segment.motion.update(timedProgress(now, segment.startMillis, segment.endMillis),
                            durationStrength(segment.durationMillis()), segmentMotion, delta);
                }
                for (Glyph glyph : token.glyphs) {
                    if (glyph.startMillis == Long.MIN_VALUE) continue;
                    Segment segment = token.segments.get(glyph.segmentIndex);
                    boolean glyphMotion = motionPlan.glyphMotionForFragment(segment.durationMillis());
                    glyph.motion.update(timedProgress(now, glyph.startMillis, glyph.endMillis),
                            individualMotionStrength(segment.durationMillis()), glyphMotion, delta);
                }
            }
        }
    }

    private float distantBlurRadius() {
        return Math.clamp(2.4F + preferences.getLyricsBlurStrength() * .42F, 2.4F, 3.7F);
    }

    private float activeGlowOuterRadius() {
        return (float) activeGlowProfile(1, preferences.getLyricsGlowIntensity().multiplier())
                .outerRadius();
    }

    private float activeGlowCoreRadius() {
        return (float) activeGlowProfile(1, preferences.getLyricsGlowIntensity().multiplier())
                .coreRadius();
    }

    private int renderScenePass(DrawContext context, LyricsScene currentScene, LyricsViewport.Range window,
                                int x, int y, int width, int height, int textWidth, double focusY,
                                double scroll, int active, long now, int accent, double delta,
                                boolean interactive, boolean manualReading, RenderPass pass,
                                boolean updateInteractionMotion, InterludeLayout interlude) {
        int visibleTokens = 0;
        int left = x + 14;
        for (int index = window.firstInclusive(); index < window.lastExclusive(); index++) {
            PreparedGroup group = currentScene.groups[index];
            double baseY = focusY + currentScene.groupTop(index, interlude) - scroll;
            boolean simple = reducedLyricsMotion();
            boolean singing = group.group.activeAt(now);
            double lineScale = group.motion.scale();
            double opacity = group.motion.opacity();
            double textBrightness = group.motion.textBrightness();
            double blur = group.motion.blur();
            double bloomOpacity = group.motion.bloomOpacity();
            double groupY = baseY + group.motion.elevation();
            LyricsTextAlignment alignment = preferences.getLyricsAlignment();
            boolean centered = alignment == LyricsTextAlignment.CENTER;
            boolean opposite = oppositeAligned(alignment, group.group.alignment());

            int regionY = (int) Math.floor(groupY - 6);
            HitRegion region = new HitRegion(x + 7, regionY, width - 14,
                    Math.max(12, (int) Math.ceil(group.height)), group.group.lead().startMillis());
            if (pass == RenderPass.BASE) {
                boolean clickSeek = preferences.isLyricsClickSeek() && !staticLyrics();
                boolean hovered = false;
                HoverMotion hoverMotion = interactive ? group.phoneHoverMotion : group.hudHoverMotion;
                if (interactive && clickSeek) {
                    phoneHitRegions.add(region);
                    hovered = renderingInPhone && region.contains(pointerX, pointerY);
                } else if (renderingOverScreen && clickSeek && !popupCoverModeActive
                        && lyricsSurfaceOpacity > .35) {
                    hudHitRegions.add(region);
                    hovered = region.contains(pointerX, pointerY);
                }
                HoverMotion.State hover = updateInteractionMotion
                        ? hoverMotion.step(hovered, delta) : hoverMotion.state();
                drawLyricHover(context, region, hover.opacity(), hover.scale());
            }

            // Every genuinely overlapping vocal keeps consuming its own sync. The latest start
            // remains the camera anchor, but it no longer cancels the phrase already in progress.
            boolean activeLine = !staticLyrics() && (singing || index == active
                    || !simple && settling(group.group, now));
            boolean activeGlowLine = activeGlowEligible(index, index, singing, simple, manualReading);
            boolean drawAsActive = pass.activeGlow() ? activeGlowLine : activeLine;
            drawPreparedLine(context, group.lead, left, groupY, textWidth, lineScale, opacity, textBrightness,
                    blur, bloomOpacity, drawAsActive, now, accent, opposite, centered, true,
                    pass);
            visibleTokens += group.lead.layout.tokenCount;
            double backgroundY = groupY + group.lead.totalHeight + group.leadToBackingGap;
            for (PreparedLine background : group.backgrounds) {
                drawPreparedLine(context, background, left, backgroundY, textWidth,
                        lineScale, opacity * .78, textBrightness * .90, blur * .82, bloomOpacity * .16,
                        pass.activeGlow() ? false : drawAsActive, now, accent,
                        opposite, centered, true, pass);
                visibleTokens += background.layout.tokenCount;
                backgroundY += background.totalHeight + group.backingGap;
            }
        }
        return visibleTokens;
    }

    private boolean sceneRomanizationUnderLine;
    private LyricsScene ensureScene(int width) {
        LyricsDocument current = document;
        boolean translation = preferences.isLyricsTranslation();
        boolean romanization = preferences.isLyricsRomanization();
        LyricsFont font = preferences.getLyricsFont();
        LyricsFontWeight weight = preferences.getLyricsFontWeight();
        double preferenceScale = lyricsTextScale();
        if (sceneDocument != current || sceneTranslation != translation || sceneRomanization != romanization
                || sceneRomanizationUnderLine != preferences.isRomanizationUnderLine()
                || sceneFont != font || sceneFontWeight != weight
                || Double.compare(sceneTextScale, preferenceScale) != 0) {
            synchronized (layoutCache) { layoutCache.clear(); }
            synchronized (sceneCache) { sceneCache.clear(); }
            sceneDocument = current;
            sceneTranslation = translation;
            sceneRomanization = romanization;
            sceneRomanizationUnderLine = preferences.isRomanizationUnderLine();
            sceneFont = font;
            sceneFontWeight = weight;
            sceneTextScale = preferenceScale;
        }
        SceneKey sceneKey = new SceneKey(width, renderingInPhone);
        LyricsScene cached;
        synchronized (sceneCache) { cached = sceneCache.get(sceneKey); }
        if (cached != null) return cached;
        double textScale = lyricsRenderScale();
        LyricsSpacing spacing = LyricsSpacing.forTextScale(preferenceScale);
        PreparedGroup[] result = new PreparedGroup[current.groups().size()];
        double[] starts = new double[result.length];
        double[] ends = new double[result.length];
        long[] timeStarts = new long[result.length];
        long[] prefixMaximumEnds = new long[result.length];
        double cursor = 0;
        long maximumEnd = Long.MIN_VALUE;
        for (int index = 0; index < current.groups().size(); index++) {
            LyricVocalGroup group = current.groups().get(index);
            PreparedLine lead = prepareLine(group.lead(), width, 2.15 * textScale, true);
            PreparedLine[] backgrounds = new PreparedLine[group.backingVocals().size()];
            for (int backgroundIndex = 0; backgroundIndex < backgrounds.length; backgroundIndex++) {
                backgrounds[backgroundIndex] = prepareLine(
                        group.backingVocals().get(backgroundIndex), width, 1.34 * textScale, false);
            }
            double contentHeight = lead.totalHeight + spacing.phraseGap();
            for (PreparedLine background : backgrounds) {
                contentHeight += background.totalHeight + spacing.backingGap();
            }
            // Interlude space is inserted only while the silent interval is active.
            // Reserving it here caused a permanent blank hole before the dots appeared.
            double height = contentHeight;
            starts[index] = cursor;
            ends[index] = cursor + height;
            timeStarts[index] = group.startMillis();
            maximumEnd = Math.max(maximumEnd, group.endMillis());
            prefixMaximumEnds[index] = maximumEnd;
            result[index] = new PreparedGroup(group, lead, backgrounds, contentHeight, height, cursor,
                    spacing.leadToBackingGap(), spacing.backingGap(), spacing.interludeInset(),
                    spacing.initialInterludeOffset(), spacing.interludeHeight());
            cursor += height;
        }
        PreparedCredits credits = prepareCredits(current.metadata(), width, cursor,
                textScale, maximumEnd == Long.MIN_VALUE ? 0 : maximumEnd);
        LyricsScene built = new LyricsScene(result, starts, ends, timeStarts, prefixMaximumEnds, credits);
        synchronized (sceneCache) { sceneCache.put(sceneKey, built); }
        sceneBuilds++;
        return built;
    }

    private PreparedLine prepareLine(LyricLine line, int width, double scale, boolean auxiliary) {
        boolean romanized = preferences.isLyricsRomanization() && !preferences.isRomanizationUnderLine() && LyricsRenderText.hasRomanization(line);
        LineLayout layout = layout(line, width, scale, romanized);
        int auxiliaryHeight = auxiliaryHeight(line);
        return new PreparedLine(line, layout, scale, layout.height + auxiliaryHeight);
    }

    private PreparedCredits prepareCredits(LyricsMetadata metadata, int width, double top,
                                            double textScale, long revealStartMillis) {
        if (metadata == null) return null;
        double scale = Math.max(.72, 1.05 * textScale);
        double subtleScale = Math.max(.66, .82 * textScale);
        List<String> writerRows = metadata.songwriters().isBlank() ? List.of()
                : wrapText(ui("lyrics.fandroid.written_by") + ": " + metadata.songwriters().strip(), width, scale);
        String provider = metadata.provider().isBlank()
                ? LyricsMetadata.providerFor(metadata.source()) : metadata.provider();
        List<String> communityRows = metadata.hasCommunityCredits()
                ? wrapText(ui("lyrics.fandroid.community_provided"), width, subtleScale) : List.of();
        CreditProfile maker = profile(metadata.makerUsername(), metadata.makerAvatar());
        CreditProfile uploader = profile(metadata.uploaderUsername(), metadata.uploaderAvatar());
        if (writerRows.isEmpty() && provider.isBlank() && communityRows.isEmpty()
                && maker == null && uploader == null) return null;
        double creditsHeight = writerRows.size() * rowHeight(scale);
        if (!provider.isBlank()) creditsHeight += (writerRows.isEmpty() ? 0 : 4) + rowHeight(subtleScale);
        if (!communityRows.isEmpty()) {
            creditsHeight += 10 + communityRows.size() * rowHeight(subtleScale);
        }
        if (maker != null) creditsHeight += 4 + profileRowHeight(subtleScale);
        if (uploader != null) creditsHeight += 3 + profileRowHeight(subtleScale);
        return new PreparedCredits(List.copyOf(writerRows), provider, List.copyOf(communityRows),
                maker, uploader, top - 6, scale, subtleScale, creditsHeight + 18, revealStartMillis);
    }

    private List<String> wrapText(String text, int width, double scale) {
        List<String> rows = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split("\\s+")) {
            if (word.isBlank()) continue;
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (!current.isEmpty() && textWidth(candidate) * scale > width) {
                rows.add(current.toString());
                current.setLength(0);
            }
            if (!current.isEmpty()) current.append(' ');
            current.append(word);
        }
        if (!current.isEmpty()) rows.add(current.toString());
        return List.copyOf(rows);
    }

    private void drawCredits(DrawContext context, LyricsScene scene, int x, int y, int width, int height,
                             double focusY, double scroll, long now, InterludeLayout interlude) {
        PreparedCredits credits = scene.credits;
        if (credits == null) return;
        double drawY = focusY + scene.creditsTop(interlude) - scroll;
        if (drawY + credits.height < y || drawY > y + height) return;
        double reveal = staticLyrics() ? 1 : .30 + .70 * smooth((now - credits.revealStartMillis) / 900.0);
        double rowY = drawY;
        for (String row : credits.writerRows) {
            drawCreditText(context, row, x, rowY, width, 0xFFE3E4E1, credits.textScale, reveal);
            rowY += rowHeight(credits.textScale);
        }
        if (!credits.provider.isBlank()) {
            if (!credits.writerRows.isEmpty()) rowY += 4;
            drawCreditText(context, ui("lyrics.fandroid.provided_by") + ": " + credits.provider,
                    x, rowY, width, 0xFFB3B7BD, credits.subtleScale, reveal);
            rowY += rowHeight(credits.subtleScale);
        }
        if (!credits.communityRows.isEmpty()) {
            rowY += 10;
            for (String row : credits.communityRows) {
                drawCreditText(context, row, x, rowY, width, 0xFFB3B7BD, credits.subtleScale, reveal);
                rowY += rowHeight(credits.subtleScale);
            }
        }
        if (credits.maker != null) {
            rowY += 4;
            drawCreditProfile(context, ui("lyrics.fandroid.made_by"), credits.maker,
                    makerAvatarTexture, x, rowY, width, credits.subtleScale, reveal);
            rowY += profileRowHeight(credits.subtleScale);
        }
        if (credits.uploader != null) {
            rowY += 3;
            drawCreditProfile(context, ui("lyrics.fandroid.uploaded_by"), credits.uploader,
                    uploaderAvatarTexture, x, rowY, width, credits.subtleScale, reveal);
        }
    }

    private void drawCreditText(DrawContext context, String value, int x, double y, int width,
                                int color, double scale, double opacity) {
        double drawX = creditX(value, x, width, scale);
        drawScaledWithoutShadow(context, renderer(), value, drawX, y, multiplyAlpha(color, opacity), scale);
    }

    private void drawCreditProfile(DrawContext context, String label, CreditProfile profile,
                                   CoverArtTexture avatar, int x, double y, int width,
                                   double scale, double opacity) {
        int avatarSize = Math.max(9, (int) Math.round(12 * scale));
        String value = label + " @" + profile.username;
        int availableText = Math.max(20, width - avatarSize - 5);
        value = fit(value, availableText, scale);
        double groupWidth = textWidth(value) * scale + avatarSize + 4;
        double drawX = switch (preferences.getLyricsAlignment()) {
            case CENTER -> x + (width - groupWidth) / 2.0;
            case SOURCE, LEFT -> x;
        };
        drawScaledWithoutShadow(context, renderer(), value, drawX, y,
                multiplyAlpha(0xFFD7D9DD, opacity), scale);
        int avatarX = (int) Math.round(drawX + textWidth(value) * scale + 4);
        int avatarY = (int) Math.round(y + (renderer().fontHeight * scale - avatarSize) / 2.0);
        int frame = multiplyAlpha(0xFFE7EBED, opacity);
        context.fill(avatarX - 1, avatarY - 1, avatarX + avatarSize + 1, avatarY + avatarSize + 1, frame);
        if (!avatar.draw(context, avatarX, avatarY, avatarSize, avatarSize)) {
            context.fill(avatarX, avatarY, avatarX + avatarSize, avatarY + avatarSize,
                    multiplyAlpha(0xFF596269, opacity));
            String initial = profile.username.isBlank() ? "?"
                    : profile.username.substring(0, profile.username.offsetByCodePoints(0, 1)).toUpperCase();
            double initialScale = Math.max(.55, avatarSize / 14.0);
            drawScaledWithoutShadow(context, renderer(), initial,
                    avatarX + (avatarSize - textWidth(initial) * initialScale) / 2.0,
                    avatarY + (avatarSize - renderer().fontHeight * initialScale) / 2.0,
                    multiplyAlpha(0xFFFFFFFF, opacity), initialScale);
        }
    }

    private double creditX(String value, int x, int width, double scale) {
        return preferences.getLyricsAlignment() == LyricsTextAlignment.CENTER
                ? x + (width - textWidth(value) * scale) / 2.0 : x;
    }

    private static int profileRowHeight(double scale) {
        return Math.max(rowHeight(scale), (int) Math.ceil(12 * scale));
    }

    private static CreditProfile profile(String username, String avatar) {
        if (username == null || username.isBlank()) return null;
        return new CreditProfile(username.strip(), avatar == null ? "" : avatar.strip());
    }

    private void updateCreditAvatars(LyricsMetadata metadata) {
        makerAvatarTexture.update(metadata == null || metadata.makerAvatar().isBlank()
                ? null : compatibleCreditAvatar(metadata.makerAvatar()));
        uploaderAvatarTexture.update(metadata == null || metadata.uploaderAvatar().isBlank()
                ? null : compatibleCreditAvatar(metadata.uploaderAvatar()));
    }

    private static String compatibleCreditAvatar(String url) {
        // Spicy Lyrics currently supplies Discord WebP avatars. Minecraft's native
        // image reader supports the same CDN's PNG rendition more reliably.
        if (url.startsWith("https://cdn.discordapp.com/avatars/")
                || url.startsWith("https://media.discordapp.net/avatars/")) {
            return url.replaceFirst("(?i)\\.(?:webp|gif)(?=\\?|$)", ".png");
        }
        return url;
    }

    private void drawPreparedLine(DrawContext context, PreparedLine prepared, int x, double y, int width,
                                  double focusScale, double opacity, double textBrightness,
                                  double blur, double bloomOpacity,
                                  boolean active, long now, int accent,
                                  boolean opposite, boolean centered, boolean auxiliary, RenderPass pass) {
        drawRows(context, prepared.line, prepared.layout, x, y, width, prepared.scale,
                focusScale, opacity, textBrightness, blur, bloomOpacity, active, now, accent, opposite, centered,
                pass);
        if (pass == RenderPass.BASE && auxiliary) {
            drawAuxiliaryText(context, prepared, x, y, width, opposite, centered,
                    opacity, textBrightness, focusScale);
        }
    }

    private void drawRows(DrawContext context, LyricLine line, LineLayout layout, double x, double y, int width,
                           double baseScale, double focusScale, double opacity, double textBrightness,
                           double blur, double bloomOpacity,
                           boolean active, long now,
                           int accent, boolean opposite, boolean centered, RenderPass pass) {
        double stableRowHeight = rowHeight(baseScale);
        double stableTextHeight = renderer().fontHeight * baseScale;
        for (int rowIndex = 0; rowIndex < layout.rows.size(); rowIndex++) {
            Row row = layout.rows.get(rowIndex);
            double scaledWidth = row.width * focusScale;
            double rowX = centered ? x + (width - scaledWidth) / 2.0
                    : opposite ? x + width - scaledWidth : x;
            double rowY = y + rowIndex * stableRowHeight
                    + stableTextHeight * (1 - focusScale) / 2;
            for (Token token : row.tokens) {
                double tokenX = rowX + token.x * focusScale;
                if (!active) {
                    // The drop shadow turns dim distant text into black blocks; only the
                    // phrase being sung may carry the user's shadow.
                    boolean previousShadow = drawingLyricsShadow;
                    drawingLyricsShadow = false;
                    try {
                        drawDistant(context, token.text, tokenX, rowY, lyricsTextColor(FUTURE_TEXT), baseScale * focusScale,
                                opacity, textBrightness, blur, bloomOpacity, pass);
                    } finally {
                        drawingLyricsShadow = previousShadow;
                    }
                } else {
                    if (verticalLineHighlight(line)) {
                        if (pass == RenderPass.BASE) drawVerticalLineToken(context, token, tokenX, rowY,
                                baseScale * focusScale, line, now, opacity,
                                y, Math.max(stableTextHeight, (layout.rows.size() - 1) * stableRowHeight + stableTextHeight));
                    } else drawTimedToken(context, token, tokenX, rowY, baseScale * focusScale, now, accent,
                                lyricsTextColor(line.background() ? 0xFF746B80 : UNSYNCED_TEXT), opacity, pass);
                }
            }
        }
    }

    private void drawDistant(DrawContext context, String text, double x, double y, int color,
                             double scale, double opacity, double textBrightness, double blur, double bloomOpacity,
                             RenderPass pass) {
        // Inactive lines render one sharp, faded copy: no blurred halo and no glow sources.
        if (pass != RenderPass.BASE) return;
        int mutedColor = multiplyBrightness(color, textBrightness);
        drawScaled(context, renderer(), text, x, y, multiplyAlpha(mutedColor, opacity), scale);
    }

    private boolean verticalLineHighlight(LyricLine line) {
        return !staticLyrics() && preferences.getLineLyricsAnimation()
                == dev.fandroid.client.ClientPreferences.LineLyricsAnimation.TOP_TO_BOTTOM
                && (document.metadata().syncMode() == dev.fandroid.protocol.lyrics.LyricsSyncMode.LINE
                    || !dev.fandroid.client.music.WorldLyricsPlan.hasWordTiming(line));
    }

    private void drawVerticalLineToken(DrawContext context, Token token, double x, double y, double scale,
                                       LyricLine line, long now, double opacity, double top, double height) {
        double progress = LyricsAnimation.lineProgress(now, line.startMillis(), line.endMillis(), !reducedLyricsMotion());
        drawScaledShadowOnly(context, renderer(), token.text, x, y, opacity * progress, scale);

        double screenTop = GuiTransform.screenY(context, x, top);
        double screenHeight = GuiTransform.screenY(context, x, top + height) - screenTop;
        if (screenHeight <= 0) return;
        double physicalScale = scale * screenHeight / height * PlatformTextures.guiScale() / PlatformTextures.renderScale();
        if (!worldPopupShaderActive && physicalScale < .8 && bloomRenderer.drawMinifiedLineFill(context, styledText(token.text), x, y, scale,
                opacity * lyricsSurfaceOpacity, progress, top, height, lyricsTextColor(UNSYNCED_TEXT), lyricsTextColor(ACTIVE_TEXT))) return;

        // The native font retains its own sampling. Disjoint bands change only its tint.
        int firstRow = (int) Math.floor(GuiTransform.screenY(context, x, y - 2 * scale));
        int endRow = (int) Math.ceil(GuiTransform.screenY(context, x, y + (renderer().fontHeight + 2) * scale));
        var bands = LyricsEffectRaster.verticalTintBands(endRow - firstRow,
                (firstRow + .5 - screenTop) / screenHeight, 1 / screenHeight,
                progress, lyricsTextColor(UNSYNCED_TEXT), lyricsTextColor(ACTIVE_TEXT));
        if (bands.size() == 1) {
            drawScaledWithoutShadow(context, renderer(), token.text, x, y,
                    multiplyAlpha(bands.getFirst().color(), opacity), scale);
            return;
        }
        for (var band : bands) {
            GuiTransform.enableScreenBand(context, firstRow + band.firstRow(),
                    firstRow + band.endRow(), context.getScaledWindowWidth());
            try {
                drawScaledWithoutShadow(context, renderer(), token.text, x, y,
                        multiplyAlpha(band.color(), opacity), scale);
            } finally {
                context.disableScissor();
            }
        }
    }

    private void drawTimedToken(DrawContext context, Token token, double x, double y, double scale,
                                long now, int accent, int futureColor, double opacity, RenderPass pass) {
        TimedWord timing = token.timing;
        double wordProgress;
        if (timing.endMillis() <= timing.startMillis()) wordProgress = now >= timing.startMillis() ? 1 : -1;
        else wordProgress = (now - timing.startMillis())
                / (double) (timing.endMillis() - timing.startMillis());
        drawAnimatedGlyphs(context, token, x, y, scale, wordProgress, now,
                accent, futureColor, opacity, pass);
    }

    private void drawAnimatedGlyphs(DrawContext context, Token token, double x, double y, double scale,
                                     double wordProgress, long now, int accent, int futureColor,
                                    double opacity, RenderPass pass) {
        double centerY = y + renderer().fontHeight * scale / 2;
        boolean simple = reducedLyricsMotion();
        var motionPlan = token.motionPlan(simple);
        double fillOffset = token.fillOffset(now);
        var fill = fillInterval(token.rawWidth, fillOffset / token.rawWidth, isRightToLeft(token.text));
        int activeColor = lyricsTextColor(ACTIVE_TEXT);
        for (Glyph glyph : token.glyphs) {
            Segment segment = token.segments.get(glyph.segmentIndex);
            boolean glyphMotion = motionPlan.glyphMotionForFragment(segment.durationMillis());
            double containerScale = simple ? 1 : segment.motion.scale();
            double letterScale = !simple && glyphMotion ? glyph.motion.scale() : 1;
            // Scale each sung syllable around its own centre. A held letter's emphasis
            // stays inside that container, so unsung syllables never move with it.
            double containerCenter = x + (segment.x + segment.rawWidth / 2) * scale;
            double baseX = containerCenter + (x + glyph.x * scale - containerCenter) * containerScale;
            double glyphScale = scale * containerScale;
            double animatedScale = glyphScale * letterScale;
            double drawX = baseX + glyph.rawWidth * glyphScale * (1 - letterScale) / 2;
            double elevation = simple ? 0 : segment.motion.elevation()
                    + (glyphMotion ? glyph.motion.elevation() : 0);
            double drawY = centerY - renderer().fontHeight * animatedScale / 2
                    - elevation * renderer().fontHeight * scale;
            double segmentProgress = timedProgress(now, segment.startMillis, segment.endMillis);
            if (glyph.startMillis == Long.MIN_VALUE) {
                if (pass != RenderPass.BASE) continue;
                int color = segmentProgress >= 1 ? activeColor : futureColor;
                if (segmentProgress >= 1) {
                    drawScaled(context, renderer(), glyph.text, drawX, drawY,
                            multiplyAlpha(color, opacity), animatedScale);
                } else {
                    drawScaledWithoutShadow(context, renderer(), glyph.text, drawX, drawY,
                            multiplyAlpha(color, opacity), animatedScale);
                }
                continue;
            }
            double fillLeft = containerCenter + (x + fill.start() * scale - containerCenter) * containerScale;
            double fillRight = containerCenter + (x + fill.end() * scale - containerCenter) * containerScale;
            if (pass != RenderPass.BASE) continue;
            drawProgressiveGlyph(context, glyph, drawX, drawY, animatedScale,
                    futureColor, activeColor, opacity, fillLeft, fillRight);
        }
    }

    private void drawProgressiveGlyph(DrawContext context, Glyph glyph, double x, double y, double scale,
                                      int futureColor, int activeColor, double opacity,
                                      double fillLeft, double fillRight) {
        double glyphRight = x + glyph.rawWidth * scale;
        double visibleLeft = Math.max(x, fillLeft);
        double visibleRight = Math.min(glyphRight, fillRight);
        double coverage = Math.clamp((visibleRight - visibleLeft) / Math.max(glyphRight - x, .001), 0, 1);
        double activeAlpha = (activeColor >>> 24) / 255.0;
        // The shadow is one stable layer whose opacity follows fill coverage. Drawing
        // it once for the future text and again for the clipped active text caused
        // the black flash at the instant a word started.
        if (!GuiTransform.nativeTextShadow()) {
            drawScaledShadowOnly(context, renderer(), glyph.text, x, y,
                    opacity * sungShadowOpacity(activeAlpha, coverage), scale);
        }
        if (fillRight <= x + .01 || fillLeft >= glyphRight - .01) {
            drawScaledWithoutShadow(context, renderer(), glyph.text, x, y,
                    multiplyAlpha(futureColor, opacity), scale);
            return;
        }
        if (fillLeft <= x + .01 && fillRight >= glyphRight - .01) {
            if (GuiTransform.nativeTextShadow()) {
                drawScaled(context, renderer(), glyph.text, x, y,
                        multiplyAlpha(activeColor, opacity), scale);
            } else {
                drawScaledWithoutShadow(context, renderer(), glyph.text, x, y,
                        multiplyAlpha(activeColor, opacity), scale);
            }
            return;
        }
        drawScaledWithoutShadow(context, renderer(), glyph.text, x, y,
                multiplyAlpha(futureColor, opacity), scale);
        int clipLeft = (int) Math.floor(Math.max(x, fillLeft) - 1);
        int clipTop = (int) Math.floor(y - 2);
        int clipRight = (int) Math.ceil(Math.min(glyphRight, fillRight) + 1);
        int clipBottom = (int) Math.ceil(y + renderer().fontHeight * scale + 2);
        if (clipRight <= clipLeft || clipBottom <= clipTop) return;
        enableLyricsScissor(context, clipLeft, clipTop, clipRight, clipBottom);
        try {
            if (GuiTransform.nativeTextShadow()) {
                drawScaled(context, renderer(), glyph.text, x, y,
                        multiplyAlpha(activeColor, opacity), scale);
            } else {
                drawScaledWithoutShadow(context, renderer(), glyph.text, x, y,
                        multiplyAlpha(activeColor, opacity), scale);
            }
        } finally {
            context.disableScissor();
        }
    }

    private void drawAuxiliaryText(DrawContext context, PreparedLine prepared, int x, double y, int width,
                                   boolean opposite, boolean centered, double opacity,
                                   double textBrightness, double focusScale) {
        double currentY = y + prepared.layout.height + 5 * lyricsTextScale();
        double auxiliaryScale = .78 * lyricsRenderScale() * focusScale;
        double auxiliaryOpacity = opacity * .58;
        if (preferences.isLyricsRomanization() && preferences.isRomanizationUnderLine()) {
            String romanized = LyricsRenderText.romanizationText(prepared.line);
            if (!romanized.isBlank()) {
                String text = fit(romanized, width, auxiliaryScale);
                double drawX = centered ? x + (width - textWidth(text) * auxiliaryScale) / 2.0
                        : opposite ? x + width - textWidth(text) * auxiliaryScale : x;
                drawScaledWithoutShadow(context, renderer(), text, drawX, currentY,
                        multiplyAlpha(multiplyBrightness(lyricsTextColor(AUXILIARY_TEXT), textBrightness), auxiliaryOpacity), auxiliaryScale);
                currentY += rowHeight(auxiliaryScale) + 2;
            }
        }
        if (preferences.isLyricsTranslation() && prepared.line.translation() != null) {
            String text = fit(prepared.line.translation(), width, auxiliaryScale);
            double drawX = centered ? x + (width - textWidth(text) * auxiliaryScale) / 2.0
                    : opposite ? x + width - textWidth(text) * auxiliaryScale : x;
            drawScaledWithoutShadow(context, renderer(), text, drawX, currentY,
                    multiplyAlpha(multiplyBrightness(lyricsTextColor(AUXILIARY_TEXT), textBrightness), auxiliaryOpacity), auxiliaryScale);
        }
    }

    private void drawInterlude(DrawContext context, LyricsScene scene, int x, int y, int width, int height,
                               double focusY, double scroll, int active, long now, int accent, double delta,
                               RenderPass pass, boolean updateMotion, InterludeLayout interlude) {
        if (scene.groups.length == 0) {
            if (updateMotion) settleInterlude(delta);
            return;
        }
        PreparedGroup prepared;
        LyricAlignment alignment;
        long elapsed;
        long gap;
        double dotsPosition;
        PreparedGroup first = scene.groups[0];
        if (now < first.group.startMillis()) {
            prepared = first;
            alignment = first.group.alignment();
            elapsed = Math.max(0, now);
            gap = Math.max(1, first.group.startMillis());
            if (gap < INTERLUDE_MIN_GAP_MILLIS) {
                if (updateMotion) settleInterlude(delta);
                return;
            }
            dotsPosition = focusY + scene.groupTop(0, interlude) - scroll + first.motion.elevation()
                    - first.initialInterludeOffset;
        } else {
            if (active < 0 || active + 1 >= scene.groups.length) {
                if (updateMotion) settleInterlude(delta);
                return;
            }
            prepared = scene.groups[active];
            LyricVocalGroup current = prepared.group;
            LyricVocalGroup next = scene.groups[active + 1].group;
            if (now <= current.endMillis()) {
                if (updateMotion) settleInterlude(delta);
                return;
            }
            gap = next.startMillis() - current.endMillis();
            if (gap < INTERLUDE_MIN_GAP_MILLIS || now >= next.startMillis()) {
                if (updateMotion) settleInterlude(delta);
                return;
            }
            elapsed = now - current.endMillis();
            alignment = current.alignment();
            dotsPosition = focusY + scene.groupTop(active, interlude) - scroll + prepared.motion.elevation()
                    + prepared.contentHeight + prepared.interludeInset;
        }
        double progress = interludeProgress(elapsed, gap);
        double visibility = interlude.visibility();
        double dotScale = simpleLyricsDotScale();
        int dotStep = Math.max(11, (int) Math.ceil(textWidth("•") * dotScale) + 3);
        int dotsStartX = alignment == LyricAlignment.OPPOSITE
                ? x + width - dotStep * 3 - 12 : x + 12;
        int dotsY = (int) Math.clamp(dotsPosition, y + 14, y + height - 14);
        boolean simple = reducedLyricsMotion();
        for (int index = 0; index < 3; index++) {
            double fill = interludeDotFill(progress, index);
            double lift = simple ? 0 : interludeDotLift(progress, index);
            if (updateMotion) interludeDots[index].update(fill, lift, visibility, delta);
            DotMotion dot = interludeDots[index];
            int dotY = (int) Math.round(dotsY - lift * 5.0);
            int dotX = dotsStartX + index * dotStep;
            drawScaledWithoutShadow(context, renderer(), "•", dotX, dotY - renderer().fontHeight * dotScale / 2,
                    multiplyAlpha(lyricsTextColor(blend(0xFF747C87, ACTIVE_TEXT, .20 + fill * .55)),
                            (.62 + lift * .24) * dot.opacity()),
                    dotScale * (simple ? 1 : dot.scale()));
        }
    }

    private double simpleLyricsDotScale() {
        return preferences.isSimpleLyricsMode() ? 1.65 : 2.05;
    }

    /** A duet keeps its vocal side unless the user explicitly centers every lyric line. */
    static boolean oppositeAligned(LyricsTextAlignment selected, LyricAlignment source) {
        return selected != LyricsTextAlignment.CENTER && source == LyricAlignment.OPPOSITE;
    }

    private static void drawDotOutline(DrawContext context, int centerX, int centerY,
                                       int radius, int color) {
        double outer = (radius + .25) * (radius + .25);
        double inner = Math.max(0, (radius - 1.35) * (radius - 1.35));
        for (int row = -radius; row <= radius; row++) {
            for (int column = -radius; column <= radius; column++) {
                double distance = column * column + row * row;
                if (distance <= outer && distance >= inner) {
                    context.fill(centerX + column, centerY + row,
                            centerX + column + 1, centerY + row + 1, color);
                }
            }
        }
    }

    private void settleInterlude(double delta) {
        for (DotMotion dot : interludeDots) dot.update(0, 0, 0, delta);
    }

    static double interludeLayoutVisibility(long elapsedMillis, long gapMillis) {
        return LyricsAnimation.interludeVisibility(elapsedMillis, gapMillis);
    }

    private LineLayout layout(LyricLine line, int width, double scale, boolean romanized) {
        LayoutKey key = new LayoutKey(line, width, Double.doubleToLongBits(scale), romanized);
        synchronized (layoutCache) {
            LineLayout cached = layoutCache.get(key);
            if (cached != null) return cached;
        }
        LineLayout built = buildLayout(line, width, scale, romanized);
        synchronized (layoutCache) {
            LineLayout raced = layoutCache.get(key);
            if (raced != null) return raced;
            layoutCache.put(key, built);
            return built;
        }
    }

    private LineLayout buildLayout(LyricLine line, int width, double scale, boolean romanized) {
        TextRenderer renderer = renderer();
        if (renderer == null) return new LineLayout(List.of(), 0, 0);
        List<LyricsRenderText.VisualWord> visualWords = LyricsRenderText.visualWords(line, romanized);
        int layoutWidth = width;
        List<Row> rows = new ArrayList<>();
        List<Token> current = new ArrayList<>();
        double rowWidth = 0;
        String previousWord = null;
        double spaceWidth = textWidth(" ") * scale;
        for (LyricsRenderText.VisualWord visualWord : visualWords) {
            List<LyricsRenderText.VisualWord> pieces = List.of(visualWord);
            for (LyricsRenderText.VisualWord piece : pieces) {
                if (piece.text().isEmpty()) continue;
                double visualWidth = textWidth(piece.text()) * scale;
                boolean separated = !current.isEmpty()
                        && LyricsRenderText.needsWordSeparator(previousWord, piece.text());
                double requiredWidth = visualWidth + (separated ? spaceWidth : 0);
                if (!current.isEmpty() && rowWidth + requiredWidth > layoutWidth) {
                    rows.add(new Row(List.copyOf(current), rowWidth));
                    current.clear();
                    rowWidth = 0;
                    previousWord = null;
                    separated = false;
                }
                if (separated) rowWidth += spaceWidth;
                current.add(new Token(piece, rowWidth));
                rowWidth += visualWidth;
                previousWord = piece.text();
            }
        }
        if (!current.isEmpty()) rows.add(new Row(List.copyOf(current), rowWidth));
        int height = Math.max(rowHeight(scale), (int) Math.ceil(rows.size() * rowHeight(scale)));
        int tokenCount = 0;
        for (Row row : rows) tokenCount += row.tokens.size();
        return new LineLayout(List.copyOf(rows), height, tokenCount);
    }

    private dev.fandroid.client.music.PopupResizeGesture.Start popupResizeStart;
    private int popupResizeEdgesAt(double mouseX, double mouseY) {
        if (dockPresentationActive()) return 0;
        var window = MinecraftClient.getInstance().getWindow();
        var bounds = popupBoundsSnapshot(window.getScaledWidth(), window.getScaledHeight());
        return dev.fandroid.client.music.PopupResizeGesture.edgesAt(mouseX, mouseY, bounds.x(), bounds.y(), bounds.width(), bounds.height(),
                Math.max(3, 6 * ResponsiveLyricsLayout.scale(window.getScaledWidth(), window.getScaledHeight())));
    }

    public boolean isPopupResizeHandleAt(double mouseX, double mouseY) {
        return popupResizeEdgesAt(mouseX, mouseY) != 0;
    }

    public boolean mousePressed(double mouseX, double mouseY, int button) {
        if (button != 0 || !preferences.isLyricsVisible()) return false;
        if (isPopupDocked()) {
            if(dockFrame!=null && dockFrame.folded()>=.98 && dockFrame.hover()>=.98 && dockFrame.grip().near(mouseX,mouseY,3*dockScale)) {
                dockGripDragging=true; dockGestureFrame=dockFrame; dockDownX=mouseX; dockDownY=mouseY; return true;
            }
            return false;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        PopupBounds bounds = popupBoundsSnapshot(client.getWindow().getScaledWidth(), client.getWindow().getScaledHeight());
        int x = bounds.x();
        int y = bounds.y();
        int width = bounds.width();
        int height = bounds.height();
        if (isPopupResizeHandleAt(mouseX, mouseY)) {
            gestureSizeProfile = popupSizeProfile();
            resizeVideoAspect = videoArtworkAspect();
            resizing = true;
            double pointerScale = ResponsiveLyricsLayout.scale(client.getWindow().getScaledWidth(), client.getWindow().getScaledHeight());
            popupResizeStart = new dev.fandroid.client.music.PopupResizeGesture.Start(x / pointerScale, y / pointerScale,
                    width / pointerScale, height / pointerScale, mouseX / pointerScale, mouseY / pointerScale,
                    popupResizeEdgesAt(mouseX, mouseY));
            preferences.setLyricsX(ResponsiveLyricsLayout.design(x, ResponsiveLyricsLayout.scale(client.getWindow().getScaledWidth(), client.getWindow().getScaledHeight())));
            preferences.setLyricsY(ResponsiveLyricsLayout.design(y, ResponsiveLyricsLayout.scale(client.getWindow().getScaledWidth(), client.getWindow().getScaledHeight())));
            return true;
        }
        if (mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height) {
            gestureSizeProfile = popupSizeProfile();
            dragging = true;
            // Begin at the visible frame, including an interrupted size/position transition.
            double pointerScale = ResponsiveLyricsLayout.scale(client.getWindow().getScaledWidth(), client.getWindow().getScaledHeight());
            preferences.setPopupPosition(x / pointerScale, y / pointerScale);
            dragOffsetX = mouseX - x; dragOffsetY = mouseY - y; dragStartX=mouseX; dragStartY=mouseY; popupDragMoved=false;
            return true;
        }
        return false;
    }

    public boolean hasPopupAlignmentGuide() { return dragging && alignmentHeld && popupGuides != null && popupGuides.snapped(); }

    public boolean mouseDragged(double mouseX, double mouseY) {
        return mouseDragged(mouseX, mouseY, GuiTransform.shiftDown());
    }
    public boolean mouseDragged(double mouseX, double mouseY, boolean align) {
        alignmentHeld = align;
        if(dockGripDragging) {
            if(Math.hypot(mouseX-dockDownX,mouseY-dockDownY)<14*dockScale) return true;
            restorePopupFromGrip(mouseX,mouseY);
        }
        MinecraftClient client = MinecraftClient.getInstance();
        int windowWidth = client.getWindow().getScaledWidth();
        int windowHeight = client.getWindow().getScaledHeight();
        double scale = ResponsiveLyricsLayout.scale(windowWidth, windowHeight);
        if (dragging) {
            PopupBounds bounds = configuredPopupBounds(windowWidth, windowHeight);
            int width = bounds.width();
            int height = bounds.height();
            int nextX = (int) Math.round(Math.clamp(mouseX - dragOffsetX, 0, Math.max(0, windowWidth - width)));
            int nextY = (int) Math.round(Math.clamp(mouseY - dragOffsetY, 0, Math.max(0, windowHeight - height)));
            if (align) {
                popupGuides = popupAlignment.align(new dev.fandroid.client.music.PopupAlignment.Rect(nextX, nextY, width, height),
                        windowWidth, windowHeight, dev.fandroid.client.InventoryLyricsController.popupAlignmentTargets(), Math.max(4, 8 * scale));
                nextX = popupGuides.bounds().x(); nextY = popupGuides.bounds().y();
            } else { popupAlignment.clear(); popupGuides = null; }
            preferences.setPopupPosition(nextX / scale, nextY / scale);
            popupDragMoved|=Math.hypot(mouseX-dragStartX,mouseY-dragStartY)>6*scale;
            boolean attached=!align && preferences.isPopupEdgeDocking() && popupDragMoved
                    && PopupDockLayout.dragAttachment(isPopupDocked(),mouseX,mouseY,windowWidth,windowHeight,scale*PopupDockLayout.PRESENTATION_SCALE);
            if(attached!=isPopupDocked())setDockedDuringDrag(attached,windowWidth,windowHeight);
            return true;
        }
        if (resizing) {
            var frame = popupResizeStart.resize(mouseX / scale, mouseY / scale, 0, 0,
                    windowWidth / scale, windowHeight / scale);
            preferences.setLyricsX(frame.x()); preferences.setLyricsY(frame.y());
            setPopupStoredWidth(frame.width()); setPopupStoredHeight(frame.height());
            return true;
        }
        return false;
    }

    public boolean mouseReleased() {
        boolean handled = dragging || resizing || dockGripDragging;
        popupDragMoved=false;

        dockGripDragging=false; if(!dockSeeking) dockGestureFrame=null;
        dragging = false;
        resizing = false;
        gestureSizeProfile = null; popupGuides = null; popupAlignment.clear(); alignmentHeld = false;
        if (handled) saveLayout.run();
        return handled;
    }

    private long positionMillis() {
        return currentSongSource == null ? clock.positionMillis() : currentSongSource.positionMillis();
    }

    private static String playbackTrackKey(PlaybackSnapshot snapshot) {
        if (snapshot.spotifyUri() != null && !snapshot.spotifyUri().isBlank()) {
            return snapshot.spotifyUri();
        }
        return String.valueOf(snapshot.title()) + '\n' + snapshot.artist() + '\n' + snapshot.album();
    }

    private PopupBounds targetPopupBounds(int windowWidth, int windowHeight) {
        return configuredPopupBounds(windowWidth, windowHeight);
    }

    private UnavailableLyricsTransition.State unavailablePresentation() {
        if (currentSongSource != null) return currentSongSource.unavailablePresentation();
        return document.groups().isEmpty() && !lyricsLoading
                ? unavailableTransition.sample(preferences.isLyricsAnimations())
                : new UnavailableLyricsTransition.State(1);
    }

    private PopupBounds configuredPopupBounds(int windowWidth, int windowHeight) {
        var box = ResponsiveLyricsLayout.popup(windowWidth, windowHeight, preferences.getPopupPreciseX(), preferences.getPopupPreciseY(),
                expandedPopupWidth(popupStoredWidth(), popupStoredHeight()), popupStoredHeight());
        int width = box.width(), height = box.height(), x = box.x(), y = box.y();
        if (isSquareVideo()) width = height = Math.min(width, height);
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.currentScreen == null && !preferences.isLyricsAllowGuiOverlap()) {
            HudSafeArea.Position safe = HudSafeArea.adjust(x, y, width, height, windowWidth, windowHeight);
            x = safe.x();
            y = safe.y();
        }
        return new PopupBounds(x, y, width, height);
    }

    private PopupBounds smoothPopupBounds(int windowWidth, int windowHeight) {
        PopupBounds target = targetPopupBounds(windowWidth, windowHeight);
        long now = System.nanoTime();
        if (!smoothBoundsInitialized || lastViewportWidth != windowWidth || lastViewportHeight != windowHeight || !preferences.isLyricsAnimations() || resizing || dragging) {
            smoothX = target.x();
            smoothY = target.y();
            smoothWidth = target.width();
            smoothHeight = target.height();
            smoothBoundsInitialized = true;
        } else {
            double elapsed = Math.clamp((now - smoothBoundsNanos) / 1_000_000_000.0, 0, .05);
            double positionBlend = 1 - Math.exp(-elapsed * 18.0);
            double sizeBlend = 1 - Math.exp(-elapsed * 14.0);
            smoothX += (target.x() - smoothX) * positionBlend;
            smoothY += (target.y() - smoothY) * positionBlend;
            smoothWidth += (target.width() - smoothWidth) * sizeBlend;
            smoothHeight += (target.height() - smoothHeight) * sizeBlend;
            if (Math.abs(target.x() - smoothX) < .05) smoothX = target.x();
            if (Math.abs(target.y() - smoothY) < .05) smoothY = target.y();
            if (Math.abs(target.width() - smoothWidth) < .05) smoothWidth = target.width();
            if (Math.abs(target.height() - smoothHeight) < .05) smoothHeight = target.height();
        }
        smoothBoundsNanos = now; lastViewportWidth = windowWidth; lastViewportHeight = windowHeight;
        return fullPopupBoundsSnapshot(windowWidth, windowHeight);
    }

    private int auxiliaryHeight(LyricLine line) {
        int height = 0;
        double scale = .78 * lyricsRenderScale();
        if (preferences.isLyricsRomanization() && preferences.isRomanizationUnderLine()
                && !LyricsRenderText.romanizationText(line).isBlank()) height += rowHeight(scale) + 2;
        if (preferences.isLyricsTranslation() && line.translation() != null
                && !line.translation().isBlank()) height += rowHeight(scale) + 2;
        return height;
    }

    private int accentColor() {
        if ((artworkAccent >>> 24) != 0) return artworkAccent;
        int hash = (String.valueOf(playback.title()) + playback.artist()).hashCode();
        int r = 155 + ((hash >>> 16) & 47);
        int g = 145 + ((hash >>> 8) & 47);
        int b = 168 + (hash & 47);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    private boolean enhancedEffects() {
        if (worldPopupShaderActive || preferences.isSimpleLyricsMode()) return false;
        return renderingInPhone ? !phoneEnhancedFailed : !hudEnhancedFailed;
    }

    private void markEnhancedFailed() {
        if (renderingInPhone) phoneEnhancedFailed = true;
        else hudEnhancedFailed = true;
    }

    private void enableLyricsScissor(DrawContext context, int left, int top, int right, int bottom) {
        GuiTransform.enableScissor(context, left, top, right, bottom);
    }

    private ClipBounds clipBounds(int left, int top, int right, int bottom) {
        return new ClipBounds(left, top, right, bottom);
    }

    private double lyricsRenderScale() {
        return lyricsTextScale() * (renderingInPhone ? PHONE_LYRICS_SCALE : 1.0);
    }

    private static String localizeEmptyMessage(String message) {
        if (message == null || message.equals("No synced lyrics found")) return ui("lyrics.fandroid.no_synced");
        if (message.equals("No lyrics in Spicy Lyrics")) return ui("music.fandroid.status_not_found");
        if (message.equals("Spotify DJ is talking. Lyrics return with the next song.")) {
            return ui("lyrics.fandroid.dj_talking");
        }
        return message;
    }

    private static String ui(String key) {
        return Text.translatable(key).getString();
    }

    private void resetAnimation() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!client.isOnThread()) {
            client.execute(this::resetAnimation);
            return;
        }
        phoneScroll.reset();
        hudScroll.reset();
        phoneManualScroll.reset();
        hudManualScroll.reset();
        phoneManualReadMotion.reset();
        hudManualReadMotion.reset();
        phoneCameraReady = false;
        hudCameraReady = false;
        frameClock.reset();
        popupMotion.reset();
        emptyStateMotion.reset();
        popupProgressScrubbing = false;
        popupScrubDuration = 0;
        clearPopupControlBounds();
        for (DotMotion dot : interludeDots) dot.reset();
        synchronized (layoutCache) { layoutCache.clear(); }
        phoneHitRegions.clear();
        hudHitRegions.clear();
        synchronized (sceneCache) { sceneCache.clear(); }
        synchronized (styledTextCache) { styledTextCache.clear(); }
        sceneDocument = null;
        sceneFont = null;
        renderStats = RenderStats.EMPTY;
    }

    @Override
    public void close() {
        releaseCurrentSongPreview();
        bloomRenderer.close();
        makerAvatarTexture.close();
        uploaderAvatarTexture.close();
    }

    private static int rowHeight(double scale) {
        TextRenderer renderer = renderer();
        return Math.max(1, (int) Math.ceil((renderer == null ? 9 : renderer.fontHeight + 3) * scale));
    }

    private void drawScaled(DrawContext context, TextRenderer renderer, String value,
                            double x, double y, int color, double scale) {
        color = multiplyAlpha(color, lyricsSurfaceOpacity);
        if (renderer == null || value == null || value.isEmpty()
                || (color >>> 24) < LyricsShadowKernel.MIN_TEXT_ALPHA) return;
        GuiTransform.push(context);
        GuiTransform.translate(context, (float) x, (float) y);
        GuiTransform.scale(context, (float) scale, (float) scale);
        if (drawingLyricsShadow && !GuiTransform.nativeTextShadow()) {
            drawShadowAtOrigin(context, renderer, value, color >>> 24);
        }
        context.drawText(renderer, styledText(value), 0, 0, color,
                drawingLyricsShadow && GuiTransform.nativeTextShadow());
        GuiTransform.pop(context);
    }

    private void drawScaledWithoutShadow(DrawContext context, TextRenderer renderer, String value,
                                         double x, double y, int color, double scale) {
        boolean previousShadow = drawingLyricsShadow;
        drawingLyricsShadow = false;
        try {
            drawScaled(context, renderer, value, x, y, color, scale);
        } finally {
            drawingLyricsShadow = previousShadow;
        }
    }

    private void drawScaledShadowOnly(DrawContext context, TextRenderer renderer, String value,
                                      double x, double y, double opacity, double scale) {
        int sourceAlpha = (int) Math.round(255 * Math.clamp(opacity * lyricsSurfaceOpacity, 0, 1));
        if (!drawingLyricsShadow || renderer == null || value == null || value.isEmpty()
                || sourceAlpha < LyricsShadowKernel.MIN_TEXT_ALPHA) return;
        GuiTransform.push(context);
        GuiTransform.translate(context, (float) x, (float) y);
        GuiTransform.scale(context, (float) scale, (float) scale);
        drawShadowAtOrigin(context, renderer, value, sourceAlpha);
        GuiTransform.pop(context);
    }

    private void drawShadowAtOrigin(DrawContext context, TextRenderer renderer, String value, int sourceAlpha) {
        if (renderingWorldPopup) {
            int alpha = Math.max(4, (int) Math.round(sourceAlpha * .7));
            context.drawText(renderer, styledText(value), 1, 1, alpha << 24, false);
            return;
        }
        bloomRenderer.drawShadow(context, renderer, styledText(value), 0, 0, 1,
                sourceAlpha << 24 | 0xFFFFFF, preferences.getLyricsShadowOpacity() / 100.0,
                preferences.getLyricsShadowDistance(), preferences.getLyricsShadowSoftness());
    }

    private void drawMarqueeText(DrawContext context, String value, int x, double y,
                                  int width, int color, double scale, boolean centerWhenFitted) {
        if (value == null || value.isBlank() || width <= 0) return;
        double renderedWidth = textWidth(value) * scale;
        double drawX = renderedWidth <= width
                ? centerWhenFitted ? x + (width - renderedWidth) / 2.0 : x
                : x - marqueeOffset(renderedWidth - width, System.currentTimeMillis());
        int clipTop = (int) Math.floor(y - 2);
        int clipBottom = (int) Math.ceil(y + renderer().fontHeight * scale + 2);
        GuiTransform.enableScissor(context, x, clipTop, x + width, clipBottom);
        try {
            drawScaled(context, renderer(), value, drawX, y, color, scale);
        } finally {
            context.disableScissor();
        }
    }

    private static double marqueeOffset(double overflow, long nowMillis) {
        if (overflow <= 0) return 0;
        double travelMillis = Math.max(2_600, overflow * 76.0);
        double pauseMillis = 1_000;
        double cycle = pauseMillis * 2 + travelMillis * 2;
        double phase = nowMillis % cycle;
        if (phase < pauseMillis) return 0;
        phase -= pauseMillis;
        if (phase < travelMillis) return overflow * smooth(phase / travelMillis);
        phase -= travelMillis;
        if (phase < pauseMillis) return overflow;
        phase -= pauseMillis;
        return overflow * (1 - smooth(phase / travelMillis));
    }

    private String fit(String value, int width, double scale) {
        if (value == null) return "";
        int available = Math.max(0, (int) (width / scale));
        if (textWidth(value) <= available) return value;
        String suffix = "…";
        int end = value.length();
        while (end > 0) {
            int codePoint = value.codePointBefore(end);
            end -= Character.charCount(codePoint);
            String candidate = value.substring(0, end).stripTrailing() + suffix;
            if (textWidth(candidate) <= available) return candidate;
        }
        return textWidth(suffix) <= available ? suffix : "";
    }

    private int textWidth(String value) {
        TextRenderer renderer = renderer();
        return renderer == null || value == null || value.isEmpty() ? 0 : renderer.getWidth(styledText(value));
    }

    private Text styledText(String value) {
        LyricsFont font = preferences.getLyricsFont();
        LyricsFontWeight weight = stylingLyrics
                ? preferences.getLyricsFontWeight() : LyricsFontWeight.REGULAR;
        boolean syntheticBold = weight.syntheticBold(font);
        StyledTextKey key = new StyledTextKey(font, weight, value);
        synchronized (styledTextCache) {
            Text cached = styledTextCache.get(key);
            if (cached != null) return cached;
            Text created = syntheticBold
                    ? PlatformTextStyles.withFontBold(Text.literal(value), font.id())
                    : PlatformTextStyles.withFont(value, font.id());
            styledTextCache.put(key, created);
            return created;
        }
    }

    private static TextRenderer renderer() { return MinecraftClient.getInstance().textRenderer; }

    private static int multiplyAlpha(int color, double opacity) {
        return withAlpha(color, (int) Math.round((color >>> 24) * Math.clamp(opacity, 0, 1)));
    }

    private int lyricsTextColor(int original) {
        int tint = preferences.getLyricsFontColor();
        if (tint == 0xFFFFFF) return original;
        int red = (original >> 16 & 255) * (tint >> 16 & 255) / 255;
        int green = (original >> 8 & 255) * (tint >> 8 & 255) / 255;
        int blue = (original & 255) * (tint & 255) / 255;
        return (original & 0xFF000000) | red << 16 | green << 8 | blue;
    }

    private static int multiplyBrightness(int color, double brightness) {
        double amount = Math.clamp(brightness, 0, 1);
        int red = (int) Math.round(((color >>> 16) & 0xFF) * amount);
        int green = (int) Math.round(((color >>> 8) & 0xFF) * amount);
        int blue = (int) Math.round((color & 0xFF) * amount);
        return color & 0xFF000000 | red << 16 | green << 8 | blue;
    }

    private static int withAlpha(int color, int alpha) {
        return Math.clamp(alpha, 0, 255) << 24 | color & 0xFFFFFF;
    }

    private static int blend(int first, int second, double amount) {
        double t = Math.clamp(amount, 0, 1);
        int a = mixChannel(first >>> 24 & 255, second >>> 24 & 255, t);
        int r = mixChannel(first >>> 16 & 255, second >>> 16 & 255, t);
        int g = mixChannel(first >>> 8 & 255, second >>> 8 & 255, t);
        int b = mixChannel(first & 255, second & 255, t);
        return a << 24 | r << 16 | g << 8 | b;
    }

    private static int mixChannel(int first, int second, double amount) {
        return (int) Math.round(first + (second - first) * amount);
    }

    private enum RenderPass {
        ACTIVE_GLOW_OUTER(true),
        ACTIVE_GLOW_CORE(true),
        DISTANT_BLOOM(false),
        BASE(false);

        private final boolean activeGlow;

        RenderPass(boolean activeGlow) {
            this.activeGlow = activeGlow;
        }

        boolean activeGlow() { return activeGlow; }
    }
    private record Row(List<Token> tokens, double width) { }
    private record ActiveGlowCommand(String text, double x, double y, double scale,
                                     double glowAmount, double opacity, boolean emphasized, boolean wholeLine) {
        private ActiveGlowCommand(String text, double x, double y, double scale,
                                  double glowAmount, double opacity, boolean emphasized) {
            this(text, x, y, scale, glowAmount, opacity, emphasized, false);
        }
    }
    private record LyricsFramePlan(LyricsScene scene, LyricsViewport.Range window,
                                   long now, int accent, int active, double scroll,
                                   boolean manualReading, boolean distantBloomNeeded,
                                   List<ActiveGlowCommand> glowCommands,
                                   InterludeLayout interlude) { }
    private record InterludeLayout(int afterGroup, double visibility, double expansion) {
        private static final InterludeLayout NONE = new InterludeLayout(Integer.MAX_VALUE, 0, 0);
        private boolean shifts(int groupIndex) { return groupIndex > afterGroup && expansion > 0; }
    }
    private record LineLayout(List<Row> rows, int height, int tokenCount, LyricsAnimation.LineGlowMotion lineGlow) {
        private LineLayout(List<Row> rows, int height, int tokenCount) {
            this(rows, height, tokenCount, new LyricsAnimation.LineGlowMotion());
        }
    }
    private record LayoutKey(LyricLine line, int width, long scaleBits, boolean romanized) { }
    private record SceneKey(int width, boolean phone) { }
    private record StyledTextKey(LyricsFont font, LyricsFontWeight weight, String value) { }
    private record HitRegion(int x, int y, int width, int height, long positionMillis) {
        private boolean contains(double pointerX, double pointerY) {
            return pointerX >= x && pointerX <= x + width
                    && pointerY >= y && pointerY <= y + height;
        }
    }
    public record PopupBounds(int x, int y, int width, int height) { }
    private record PhoneViewport(double screenX, double screenY, double scaleX, double scaleY) { }
    private record ClipBounds(int left, int top, int right, int bottom) { }
    public record RenderStats(int totalGroups, int visibleGroups, int visibleTokens,
                              int sceneRebuilds, int cachedLayouts) {
        private static final RenderStats EMPTY = new RenderStats(0, 0, 0, 0, 0);
    }

    private final class Token {
        private final TimedWord timing;
        private final String text;
        private final double x;
        private final double rawWidth;
        private final List<Glyph> glyphs;
        private final List<Segment> segments;
        private final long visualWordDurationMillis;
        private final SpringValue glow = new SpringValue(0, 1.18, .56);

        private Token(LyricsRenderText.VisualWord word, double x) {
            this.text = word.text();
            this.x = x;
            this.timing = new TimedWord(word.startMillis(), word.endMillis(), word.text());
            this.visualWordDurationMillis = word.durationMillis();
            List<Glyph> builtGlyphs = new ArrayList<>();
            List<Segment> builtSegments = new ArrayList<>();
            int fragmentOffset = 0;
            for (LyricsRenderText.Fragment fragment : word.fragments()) {
                int segmentIndex = builtSegments.size();
                double segmentX = textWidth(word.text().substring(0, fragmentOffset));
                for (LyricsRenderText.GraphemeSpan span : LyricsRenderText.timedGraphemes(
                        fragment.text(), fragment.timing().startMillis(), fragment.timing().endMillis())) {
                    int globalStart = fragmentOffset + span.startIndex();
                    int globalEnd = fragmentOffset + span.endIndex();
                    double glyphX = textWidth(word.text().substring(0, globalStart));
                    double glyphEndX = textWidth(word.text().substring(0, globalEnd));
                    builtGlyphs.add(new Glyph(span.text(), glyphX, Math.max(.01, glyphEndX - glyphX),
                            span.startMillis(), span.endMillis(), segmentIndex));
                }
                fragmentOffset += fragment.text().length();
                double segmentEndX = textWidth(word.text().substring(0, fragmentOffset));
                builtSegments.add(new Segment(fragment.timing().startMillis(), fragment.timing().endMillis(),
                        segmentX, Math.max(.01, segmentEndX - segmentX)));
            }
            rawWidth = Math.max(1, textWidth(word.text()));
            glyphs = List.copyOf(builtGlyphs);
            segments = List.copyOf(builtSegments);
        }

        private double fillOffset(long now) {
            double boundary = 0;
            for (Segment segment : segments) {
                if (now >= segment.endMillis) {
                    boundary = segment.x + segment.rawWidth;
                    continue;
                }
                if (now > segment.startMillis) {
                    // The word turns fully white slightly before it finishes being sung.
                    double duration = Math.max(1, segment.endMillis - segment.startMillis);
                    double lead = Math.min(duration * FILL_LEAD_FRACTION, MAX_FILL_LEAD_MILLIS);
                    double progress = smooth(Math.clamp(
                            (now - segment.startMillis + lead) / duration, 0, 1));
                    boundary = segment.x + segment.rawWidth * progress;
                }
                break;
            }
            return Math.clamp(boundary, 0, rawWidth);
        }

        private dev.fandroid.client.music.LyricsAnimation.TokenMotionPlan motionPlan(boolean simpleMode) {
            return dev.fandroid.client.music.LyricsAnimation.tokenMotionPlan(
                    visualWordDurationMillis, segments.size(), simpleMode);
        }
    }

    private static final class Glyph {
        private final String text;
        private final double x;
        private final double rawWidth;
        private final long startMillis;
        private final long endMillis;
        private final int segmentIndex;
        private final GlyphMotion motion = new GlyphMotion();

        private Glyph(String text, double x, double rawWidth, long startMillis, long endMillis,
                      int segmentIndex) {
            this.text = text;
            this.x = x;
            this.rawWidth = rawWidth;
            this.startMillis = startMillis;
            this.endMillis = endMillis;
            this.segmentIndex = segmentIndex;
        }
    }

    private static final class Segment {
        private final long startMillis;
        private final long endMillis;
        private final double x;
        private final double rawWidth;
        private final SegmentMotion motion = new SegmentMotion();

        private Segment(long startMillis, long endMillis, double x, double rawWidth) {
            this.startMillis = startMillis;
            this.endMillis = Math.max(startMillis, endMillis);
            this.x = x;
            this.rawWidth = rawWidth;
        }

        private long durationMillis() { return Math.max(0, endMillis - startMillis); }
    }

    private static final class PreparedLine {
        private final LyricLine line;
        private final LineLayout layout;
        private final double scale;
        private final double totalHeight;
        private PreparedLine(LyricLine line, LineLayout layout, double scale, double totalHeight) {
            this.line = line;
            this.layout = layout;
            this.scale = scale;
            this.totalHeight = totalHeight;
        }
    }

    private static final class PreparedGroup {
        private final LyricVocalGroup group;
        private final PreparedLine lead;
        private final PreparedLine[] backgrounds;
        private final double contentHeight;
        private final double height;
        private final double top;
        private final double leadToBackingGap;
        private final double backingGap;
        private final double interludeInset;
        private final double initialInterludeOffset;
        private final double interludeHeight;
        private final LineMotion motion = new LineMotion();
        private final HoverMotion phoneHoverMotion = new HoverMotion();
        private final HoverMotion hudHoverMotion = new HoverMotion();
        private PreparedGroup(LyricVocalGroup group, PreparedLine lead, PreparedLine[] backgrounds,
                              double contentHeight, double height, double top,
                              double leadToBackingGap, double backingGap, double interludeInset,
                              double initialInterludeOffset, double interludeHeight) {
            this.group = group;
            this.lead = lead;
            this.backgrounds = backgrounds;
            this.contentHeight = contentHeight;
            this.height = height;
            this.top = top;
            this.leadToBackingGap = leadToBackingGap;
            this.backingGap = backingGap;
            this.interludeInset = interludeInset;
            this.initialInterludeOffset = initialInterludeOffset;
            this.interludeHeight = interludeHeight;
        }
    }

    private record LyricsSpacing(double phraseGap, double leadToBackingGap, double backingGap,
                                 double interludeHeight,
                                 double interludeInset, double initialInterludeOffset) {
        private static LyricsSpacing forTextScale(double textScale) {
            double scale = Math.clamp(textScale, .6, 1.8);
            return new LyricsSpacing(
                    14 * scale,
                    6 * scale,
                    4 * scale,
                    INTERLUDE_LAYOUT_HEIGHT * scale,
                    10 * scale,
                    18 * scale);
        }
    }

    private record CreditProfile(String username, String avatar) { }
    private record CoverBounds(int x, int y, int width, int height) {
        private int right() { return x + width; }
        private int bottom() { return y + height; }
        private boolean contains(double px, double py) {
            return px >= x && px < right() && py >= y && py < bottom();
        }
        private static CoverBounds interpolate(CoverBounds from, CoverBounds to, double amount) {
            double progress = Math.clamp(amount, 0, 1);
            return new CoverBounds(
                    interpolate(from.x, to.x, progress),
                    interpolate(from.y, to.y, progress),
                    Math.max(1, interpolate(from.width, to.width, progress)),
                    Math.max(1, interpolate(from.height, to.height, progress)));
        }
        private static int interpolate(int from, int to, double amount) {
            return (int) Math.round(from + (to - from) * amount);
        }
    }
    private record ControlBounds(int x, int y, int width, int height) {
        private boolean contains(double px, double py) {
            return px >= x && px < x + width && py >= y && py < y + height;
        }
    }
    public enum PopupControl { NONE, CLOSE, RESTORE, SETTINGS, TOGGLE_ISLAND_LYRICS, TOGGLE_WORLD_LYRICS, TOGGLE_ROMANIZATION, TOGGLE_TRANSLATION, TOGGLE_SHUFFLE, PREVIOUS, PLAY_PAUSE, TOGGLE_HEART, NEXT, CYCLE_REPEAT, TOGGLE_MUTE }
    private enum TransportIcon { PREVIOUS, PLAY, PAUSE, NEXT }
    private record PreparedCredits(List<String> writerRows, String provider, List<String> communityRows,
                                   CreditProfile maker, CreditProfile uploader, double top,
                                   double textScale, double subtleScale, double height,
                                   long revealStartMillis) {
        private double center() { return top + height / 2; }
    }

    private static final class LyricsScene {
        private static final LyricsScene EMPTY = new LyricsScene(
                new PreparedGroup[0], new double[0], new double[0], new long[0], new long[0], null);
        private final PreparedGroup[] groups;
        private final double[] starts;
        private final double[] ends;
        private final long[] timeStarts;
        private final long[] prefixMaximumEnds;
        private final PreparedCredits credits;
        private final LyricsAnimation.ScrollTrail scrollTrail = new LyricsAnimation.ScrollTrail();
        private long lastMotionMillis = Long.MIN_VALUE;
        private int firstFocused = -1;
        private int lastFocused = -1;
        private int focusAnchor = -1;
        private double focusedCenter;

        private LyricsScene(PreparedGroup[] groups, double[] starts, double[] ends,
                            long[] timeStarts, long[] prefixMaximumEnds, PreparedCredits credits) {
            this.groups = groups;
            this.starts = starts;
            this.ends = ends;
            this.timeStarts = timeStarts;
            this.prefixMaximumEnds = prefixMaximumEnds;
            this.credits = credits;
        }

        private LyricsViewport.Range visibleRange(double minimum, double maximum) {
            return LyricsViewport.visibleRange(starts, ends, minimum, maximum, 1);
        }

        private int updateFocus(long now) {
            int fallback = LyricsViewport.activeIndex(timeStarts, now);
            focusAnchor = fallback;
            if (fallback < 0) {
                firstFocused = lastFocused = -1;
                focusedCenter = 0;
                return -1;
            }
            int firstCandidate = fallback;
            while (firstCandidate > 0 && prefixMaximumEnds[firstCandidate - 1] >= now) firstCandidate--;
            double sum = 0;
            int count = 0;
            firstFocused = -1;
            lastFocused = -1;
            for (int index = firstCandidate; index <= fallback; index++) {
                PreparedGroup group = groups[index];
                if (!group.group.activeAt(now)) continue;
                sum += group.top + group.contentHeight / 2;
                count++;
                if (firstFocused < 0) firstFocused = index;
                lastFocused = index;
            }
            if (count > 0) focusedCenter = sum / count;
            else {
                firstFocused = lastFocused = fallback;
                PreparedGroup group = groups[fallback];
                focusedCenter = group.top + group.contentHeight / 2;
            }
            return fallback;
        }

        private InterludeLayout interludeLayout(long now) {
            if (groups.length == 0) return InterludeLayout.NONE;
            PreparedGroup first = groups[0];
            if (now < first.group.startMillis()) {
                long gap = Math.max(1, first.group.startMillis());
                double visibility = interludeLayoutVisibility(Math.max(0, now), gap);
                return visibility <= 0 ? InterludeLayout.NONE
                        : new InterludeLayout(-1, visibility, first.interludeHeight * visibility);
            }
            int currentIndex = Math.clamp(focusAnchor, 0, groups.length - 1);
            if (currentIndex + 1 >= groups.length) return InterludeLayout.NONE;
            PreparedGroup current = groups[currentIndex];
            PreparedGroup next = groups[currentIndex + 1];
            long gap = next.group.startMillis() - current.group.endMillis();
            long elapsed = now - current.group.endMillis();
            double visibility = interludeLayoutVisibility(elapsed, gap);
            return visibility <= 0 ? InterludeLayout.NONE
                    : new InterludeLayout(currentIndex, visibility,
                    current.interludeHeight * visibility);
        }

        private double groupTop(int index, InterludeLayout interlude) {
            return groups[index].top + (interlude.shifts(index) ? interlude.expansion() : 0);
        }

        private double creditsTop(InterludeLayout interlude) {
            if (credits == null) return 0;
            return credits.top() + (interlude.expansion() > 0 ? interlude.expansion() : 0);
        }

        private double cameraTarget(long now, int viewportHeight, InterludeLayout interlude) {
            double currentCenter = focusedCenter
                    + (interlude.shifts(focusAnchor) ? interlude.expansion() : 0);
            if (focusAnchor < 0 || groups.length == 0) return currentCenter;
            // Keep the last sung line anchored while the dots open space beneath it.
            // The regular spring resumes the synchronized transition once the gap ends.
            if (interlude.visibility() > 0) return currentCenter;
            int nextIndex = Math.max(focusAnchor, lastFocused) + 1;
            if (nextIndex >= groups.length) {
                if (credits == null) return currentCenter;
                PreparedGroup current = groups[focusAnchor];
                double progress = smooth((now - current.group.endMillis()) / 1_250.0);
                double dynamicCreditsTop = creditsTop(interlude);
                double creditTarget = credits.height() <= Math.max(24, viewportHeight - 28)
                        ? dynamicCreditsTop + credits.height() / 2
                        : dynamicCreditsTop + viewportHeight * .43 - 10;
                return currentCenter + (creditTarget - currentCenter) * progress;
            }
            PreparedGroup current = groups[focusAnchor];
            PreparedGroup next = groups[nextIndex];
            return LyricsViewport.predictiveCenter(currentCenter,
                    groupTop(nextIndex, interlude) + next.contentHeight / 2, now,
                    current.group.startMillis(), current.group.endMillis(), next.group.startMillis());
        }

        private double minimumCamera(InterludeLayout interlude) {
            return groups.length == 0 ? 0
                    : groupTop(0, interlude) + groups[0].contentHeight / 2;
        }

        private double maximumCamera(InterludeLayout interlude) {
            if (credits != null) return creditsTop(interlude) + credits.height() / 2;
            if (groups.length == 0) return 0;
            PreparedGroup last = groups[groups.length - 1];
            return groupTop(groups.length - 1, interlude) + last.contentHeight / 2;
        }

        private int distanceToFocus(int index) {
            if (firstFocused < 0) return focusDistance(index, focusAnchor);
            return focusDistance(index, firstFocused, lastFocused);
        }

        private boolean needsDistantBloom(LyricsViewport.Range window, boolean simple, boolean manualReading) {
            if (manualReading) return false;
            for (int index = window.firstInclusive(); index < window.lastExclusive(); index++) {
                if (focusProfile(distanceToFocus(index), simple, false).renderPlan().needsBloom()) return true;
            }
            return false;
        }

    }

    private static final class FrameClock {
        private long lastNanos;
        private double cachedDelta = 1.0 / 60.0;
        private double nextDelta() { return nextDelta(0); }
        private double nextDelta(int frameRateLimit) {
            long now = System.nanoTime();
            if (lastNanos == 0) {
                lastNanos = now;
                return cachedDelta;
            }
            long elapsed = now - lastNanos;
            if (frameRateLimit > 0 && elapsed < 1_000_000_000L / frameRateLimit) return 0;
            if (elapsed < 2_000_000L) return cachedDelta;
            cachedDelta = Math.clamp(elapsed / 1_000_000_000.0, 1.0 / 240.0, .05);
            lastNanos = now;
            return cachedDelta;
        }
        private void reset() { lastNanos = 0; cachedDelta = 1.0 / 60.0; }
    }

    private static final class PopupMotion {
        private final SpringValue scale = new SpringValue(.88, 1.55, .72);
        private final SpringValue offset = new SpringValue(18, 1.45, .76);

        private State step(double delta, boolean animated) {
            if (!animated) {
                scale.snap(1);
                offset.snap(0);
            } else {
                scale.setGoal(1);
                offset.setGoal(0);
                scale.step(delta);
                offset.step(delta);
            }
            return new State(scale.position(), offset.position());
        }

        private void reset() {
            scale.snap(.88);
            offset.snap(18);
        }

        private record State(double scale, double offset) { }
    }

    private static final class EmptyStateMotion {
        private final SpringValue progress = new SpringValue(0, 1.35, .76);

        private double step(double delta, boolean empty, boolean animated) {
            double goal = empty ? 1 : 0;
            if (!animated) {
                progress.snap(goal);
                return goal;
            }
            progress.setGoal(goal);
            return progress.step(delta);
        }

        private void reset() { progress.snap(0); }
    }

    private static final class CoverFocusMotion {
        private double progress;

        private double step(double delta, boolean focused, boolean animated) {
            return step(delta, focused, animated, .8);
        }
        private double step(double delta, boolean focused, boolean animated, double duration) {
            double goal = focused ? 1 : 0;
            if (!animated) {
                progress = goal;
                return goal;
            }
            double direction = focused ? 1 : -1;
            progress = Math.clamp(progress + direction * Math.clamp(delta, 0, .05) / duration, 0, 1);
            return progress;
        }

        private void reset() { progress = 0; }
    }

    private static final class SpringScroll {
        // A slightly slower, near-critically-damped camera keeps phrase changes
        // flowing upward without the small snap/overshoot of the old spring.
        private final SpringValue spring = new SpringValue(0, 1, 1);
        private boolean initialized;
        private double step(double goal, double delta, boolean animated) {
            if (!initialized || !animated) {
                initialized = true;
                spring.snap(goal);
                return goal;
            }
            spring.setGoal(goal);
            return spring.step(delta);
        }
        private double position() { return spring.position(); }
        private void reset() { initialized = false; spring.snap(0); }
    }

    private static final class ManualReadMotion {
        private double amount;

        private double step(boolean reading, double delta) {
            double goal = reading ? 1 : 0;
            double response = 1 - Math.exp(-(reading ? 14 : 7) * Math.clamp(delta, 0, .05));
            amount += (goal - amount) * response;
            if (!reading && amount < .001) amount = 0;
            return amount;
        }

        private void reset() { amount = 0; }
    }

    private static final class GlyphMotion {
        private final dev.fandroid.client.music.LyricsAnimation.ElementMotion pose =
                new dev.fandroid.client.music.LyricsAnimation.ElementMotion(true);
        private void update(double progress, double durationStrength, boolean animated, double delta) {
            pose.update(progress, animated, delta);
        }
        private double scale() { return pose.scale(); }
        private double glow() { return pose.glow(); }
        private double elevation() { return pose.elevation(); }
    }

    private static final class SegmentMotion {
        private final dev.fandroid.client.music.LyricsAnimation.ElementMotion pose =
                new dev.fandroid.client.music.LyricsAnimation.ElementMotion(false);
        private void update(double progress, double durationStrength, boolean animated, double delta) {
            pose.update(progress, animated, delta);
        }
        private double scale() { return pose.scale(); }
        private double glow() { return pose.glow(); }
        private double elevation() { return pose.elevation(); }
    }

    private static final class LineMotion {
        private double scrollOffset;
        private final SpringValue scale = new SpringValue(.95, .84, .86);
        private final SpringValue opacity = new SpringValue(.35, .96, .84);
        private final SpringValue textBrightness = new SpringValue(.70, .96, .84);
        private final SpringValue elevation = new SpringValue(0, .86, .90);
        private final SpringValue blur = new SpringValue(1.2, .90, .88);
        private final SpringValue bloomOpacity = new SpringValue(0, .90, .88);
        private void update(dev.fandroid.client.music.LyricsAnimation.FocusProfile profile, double delta, boolean reduced) {
            if (reduced) {
                scale.snap(profile.scale()); opacity.snap(profile.opacity());
                textBrightness.snap(profile.textBrightness()); elevation.snap(profile.elevation());
                blur.snap(profile.blurRadius()); bloomOpacity.snap(profile.bloomOpacity());
                return;
            }
            scale.setGoal(profile.scale());
            opacity.setGoal(profile.opacity());
            textBrightness.setGoal(profile.textBrightness());
            elevation.setGoal(profile.elevation());
            blur.setGoal(profile.blurRadius());
            bloomOpacity.setGoal(profile.bloomOpacity());
            scale.step(delta); opacity.step(delta); textBrightness.step(delta);
            elevation.step(delta); blur.step(delta); bloomOpacity.step(delta);
        }
        private double scale() { return scale.position(); }
        private double opacity() { return opacity.position(); }
        private double textBrightness() { return textBrightness.position(); }
        private double elevation() { return elevation.position() + scrollOffset; }
        private double blur() { return blur.position(); }
        private double bloomOpacity() { return bloomOpacity.position(); }
    }

    private static final class HoverMotion {
        private double opacity;
        private double scale = .975;

        private State step(boolean hovered, double delta) {
            double opacityGoal = hovered ? 1 : 0;
            double scaleGoal = hovered ? 1 : .975;
            double response = 1 - Math.exp(-(hovered ? 13 : 9) * Math.clamp(delta, 0, .05));
            opacity += (opacityGoal - opacity) * response;
            scale += (scaleGoal - scale) * response;
            if (!hovered && opacity < .001) opacity = 0;
            return new State(opacity, scale);
        }

        private State state() { return new State(opacity, scale); }

        private record State(double opacity, double scale) { }
    }

    private static final class DotMotion {
        private final SpringValue scale = new SpringValue(.7, 1.05, .56);
        private final SpringValue opacity = new SpringValue(.25, 1.18, .60);
        private final SpringValue elevation = new SpringValue(2, 1.1, .55);
        private final SpringValue pulse = new SpringValue(0, 1.30, .62);
        private void update(double fill, double pulseAmount, double groupOpacity, double delta) {
            scale.setGoal(.88 + fill * .16 + pulseAmount * .26);
            opacity.setGoal((.46 + fill * .48 + pulseAmount * .06) * groupOpacity);
            elevation.setGoal(1 + pulseAmount * 4.6);
            pulse.setGoal(pulseAmount);
            scale.step(delta); opacity.step(delta); elevation.step(delta); pulse.step(delta);
        }
        private void reset() { scale.snap(.7); opacity.snap(0); elevation.snap(2); pulse.snap(0); }
        private double scale() { return scale.position(); }
        private double opacity() { return opacity.position(); }
        private double elevation() { return elevation.position(); }
        private double pulse() { return pulse.position(); }
    }

}
