package dev.fandroid.client.ui;

import dev.fandroid.client.ClientPreferences;
import dev.fandroid.client.LyricsFont;
import dev.fandroid.client.LyricsFontWeight;
import dev.fandroid.client.LyricsTextAlignment;
import dev.fandroid.client.music.CoverArtTexture;
import dev.fandroid.client.music.LyricsRenderText;
import dev.fandroid.client.music.LyricsViewport;
import dev.fandroid.client.music.ManualLyricsScroll;
import dev.fandroid.client.music.MusicPlaybackClock;
import dev.fandroid.client.music.PlaybackProgressTransition;
import dev.fandroid.client.music.SpringValue;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

import static dev.fandroid.client.music.LyricsAnimation.durationStrength;
import static dev.fandroid.client.music.LyricsAnimation.ACTIVE_TEXT;
import static dev.fandroid.client.music.LyricsAnimation.UPCOMING_TEXT;
import static dev.fandroid.client.music.LyricsAnimation.UNSYNCED_TEXT;
import static dev.fandroid.client.music.LyricsAnimation.activeGlowProfile;
import static dev.fandroid.client.music.LyricsAnimation.activeGlyphGlow;
import static dev.fandroid.client.music.LyricsAnimation.activeGlowEligible;
import static dev.fandroid.client.music.LyricsAnimation.boundaryGlow;
import static dev.fandroid.client.music.LyricsAnimation.fillInterval;
import static dev.fandroid.client.music.LyricsAnimation.focusDistance;
import static dev.fandroid.client.music.LyricsAnimation.focusProfile;
import static dev.fandroid.client.music.LyricsAnimation.glowKeyframe;
import static dev.fandroid.client.music.LyricsAnimation.interludeDotFill;
import static dev.fandroid.client.music.LyricsAnimation.interludeDotLift;
import static dev.fandroid.client.music.LyricsAnimation.isRightToLeft;
import static dev.fandroid.client.music.LyricsAnimation.keyframe;
import static dev.fandroid.client.music.LyricsAnimation.mix;
import static dev.fandroid.client.music.LyricsAnimation.smooth;
import static dev.fandroid.client.music.LyricsAnimation.smoother;
import static dev.fandroid.client.music.LyricsAnimation.shortWordLift;
import static dev.fandroid.client.music.LyricsAnimation.sustainedWordGlow;
import static dev.fandroid.client.music.LyricsAnimation.timedProgress;
import static dev.fandroid.client.music.LyricsAnimation.wordEmphasis;

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
    private static final long INTERLUDE_MIN_GAP_MILLIS = 1_500;
    private static final int INTERLUDE_LAYOUT_HEIGHT = 30;
    private static final double PHONE_LYRICS_SCALE = 1.72;
    private static final String[] HEART_MASK = {
            "01110001110",
            "11111011111",
            "11111111111",
            "11111111111",
            "01111111110",
            "00111111100",
            "00011111000",
            "00001110000",
            "00000100000"
    };

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
    private final CoverFocusMotion popupCoverMotion = new CoverFocusMotion();
    private final LyricsBloomRenderer bloomRenderer = new LyricsBloomRenderer();
    private final CoverArtTexture makerAvatarTexture = new CoverArtTexture();
    private final CoverArtTexture uploaderAvatarTexture = new CoverArtTexture();
    private volatile LyricsDocument document = new LyricsDocument(List.of());
    private volatile PlaybackSnapshot playback = PlaybackSnapshot.unavailable();
    private volatile String emptyMessage = ui("lyrics.fandroid.no_synced");
    private volatile String syncLabel = ui("lyrics.fandroid.synced");
    private volatile int artworkAccent;
    private volatile CoverArtTexture artworkTexture;
    private boolean hudEnhancedFailed;
    private boolean phoneEnhancedFailed;
    private boolean renderingInPhone;
    private boolean renderingOverScreen;
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
    private boolean dragging;
    private boolean resizing;
    private int dragOffsetX;
    private int dragOffsetY;
    private int resizeOffsetX;
    private int resizeOffsetY;
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
    private ControlBounds popupPlayPauseButtonBounds;
    private ControlBounds popupHeartButtonBounds;
    private ControlBounds popupNextButtonBounds;
    private ControlBounds popupProgressBounds;
    private boolean popupProgressScrubbing;
    private boolean popupCoverModeActive;
    private boolean popupCoverHoverLatched;
    private CoverBounds popupCoverInteractionBounds;
    private long popupScrubPosition;
    private long popupScrubDuration;
    private double popupPreviousHover;
    private double popupPlayPauseHover;
    private double popupHeartHover;
    private double popupNextHover;
    private double sceneTextScale = Double.NaN;
    private int sceneBuilds;
    private volatile RenderStats renderStats = RenderStats.EMPTY;
    private long cachedPositionSecond = Long.MIN_VALUE;
    private long cachedDurationSecond = Long.MIN_VALUE;
    private String cachedPositionLabel = "0:00";
    private String cachedDurationLabel = "0:00";

    public LyricsOverlay(ClientPreferences preferences) {
        this.preferences = preferences;
        this.cornerMorph = preferences.isModernMode() ? 1 : 0;
    }

    public void beginLookup() {
        document = new LyricsDocument(List.of());
        emptyMessage = ui("lyrics.fandroid.finding");
        syncLabel = ui("lyrics.fandroid.searching");
        resetAnimation();
    }

    public void clearLyrics() {
        document = new LyricsDocument(List.of());
        emptyMessage = ui("lyrics.fandroid.no_synced");
        syncLabel = ui("lyrics.fandroid.synced");
        resetAnimation();
    }

    public void markUnavailable() {
        markUnavailable(ui("lyrics.fandroid.no_synced"));
    }

    public void markUnavailable(String message) {
        if (document.lines().isEmpty()) emptyMessage = localizeEmptyMessage(message);
        syncLabel = ui("lyrics.fandroid.not_found");
    }

    public void setLyrics(LyricsDocument lyrics) {
        setLyrics(lyrics, ui("lyrics.fandroid.synced"));
    }

    public void setLyrics(LyricsDocument lyrics, String label) {
        document = lyrics == null ? new LyricsDocument(List.of()) : lyrics;
        updateCreditAvatars(document.metadata());
        emptyMessage = document.lines().isEmpty() ? ui("lyrics.fandroid.no_synced") : "";
        syncLabel = label == null || label.isBlank() ? ui("lyrics.fandroid.synced") : label;
        resetAnimation();
    }

    public LyricsDocument document() { return document; }
    public String syncLabel() { return syncLabel; }
    public RenderStats renderStats() { return renderStats; }

    public void setPlayback(PlaybackSnapshot playback) {
        this.playback = playback;
        clock.update(playback);
    }

    public void seekTo(long positionMillis) {
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
                signal = reactiveWordsPulse(positionMillis());
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

    /** Clears width and scene caches after the runtime font resource changes. */
    public void fontChanged() {
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

    public void tick() { }

    public void renderHud(DrawContext context) {
        if (!isHudVisible()) return;
        pointerX = Double.NaN;
        pointerY = Double.NaN;
        hudHitRegions.clear();
        PopupBounds bounds = smoothPopupBounds(context.getScaledWindowWidth(), context.getScaledWindowHeight());
        renderCompact(context, bounds.x(), bounds.y(), bounds.width(), bounds.height());
    }

    /** Draws a non-interactive reveal/collapse of the complete HUD surface. */
    public void renderHudTransition(DrawContext context, double visibility, boolean overScreen) {
        if (!isHudVisible()) return;
        double rawAmount = visibility;
        double amount = Math.clamp(rawAmount, 0, 1);
        if (amount <= .001) return;
        PopupBounds bounds = smoothPopupBounds(context.getScaledWindowWidth(), context.getScaledWindowHeight());
        pointerX = Double.NaN;
        pointerY = Double.NaN;
        hudHitRegions.clear();
        clearPopupControlBounds();
        renderingOverScreen = overScreen;
        int centerX = bounds.x() + bounds.width() / 2;
        int centerY = bounds.y() + bounds.height() / 2;
        int clipWidth = Math.max(1, (int) Math.round(bounds.width() * amount));
        int clipHeight = Math.max(1, (int) Math.round(bounds.height() * amount));
        int clipLeft = centerX - clipWidth / 2;
        int clipTop = centerY - clipHeight / 2;
        // Keep the Back curve's slight under/overshoot in the transform while
        // clipping remains constrained to the real popup rectangle.
        float scale = (float) (.82 + rawAmount * .18);
        GuiTransform.push(context);
        try {
            GuiTransform.translate(context, centerX, centerY);
            GuiTransform.scale(context, scale, scale);
            GuiTransform.translate(context, -centerX, -centerY);
            if (preferences.isModernMode()) {
                int radius = morphRadius(Math.clamp(Math.min(bounds.width(), bounds.height()) / 22, 9, 16));
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
                context.enableScissor(clipLeft, clipTop, clipLeft + clipWidth, clipTop + clipHeight);
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
                && playback.title() != null && !playback.title().equals("Nothing playing");
    }

    /** Renders above inventory screens using a stable, non-blurred editing surface. */
    public void renderHudOverScreen(DrawContext context, double mouseX, double mouseY) {
        if (!isHudVisible()) return;
        PopupBounds bounds = smoothPopupBounds(context.getScaledWindowWidth(), context.getScaledWindowHeight());
        pointerX = mouseX;
        pointerY = mouseY;
        hudHitRegions.clear();
        renderingOverScreen = true;
        try {
            renderCompact(context, bounds.x(), bounds.y(), bounds.width(), bounds.height());
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

    /** Bounds currently seen by the player, including drag/resize smoothing. */
    public PopupBounds popupBoundsSnapshot(int windowWidth, int windowHeight) {
        if (!smoothBoundsInitialized) return targetPopupBounds(windowWidth, windowHeight);
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
        if (!preferences.isLyricsClickSeek()) return OptionalLong.empty();
        for (HitRegion region : List.copyOf(phoneHitRegions)) {
            if (x >= region.x && x <= region.x + region.width && y >= region.y && y <= region.y + region.height) {
                return OptionalLong.of(region.positionMillis);
            }
        }
        return OptionalLong.empty();
    }

    public OptionalLong seekTargetAtHud(double x, double y) {
        if (!preferences.isLyricsClickSeek() || isPopupCoverInteractionActiveAt(x, y)) {
            return OptionalLong.empty();
        }
        for (HitRegion region : List.copyOf(hudHitRegions)) {
            if (region.contains(x, y)) return OptionalLong.of(region.positionMillis);
        }
        return OptionalLong.empty();
    }

    public PopupControl popupControlAt(double x, double y) {
        if (contains(popupPreviousButtonBounds, x, y)) return PopupControl.PREVIOUS;
        if (contains(popupPlayPauseButtonBounds, x, y)) return PopupControl.PLAY_PAUSE;
        if (contains(popupHeartButtonBounds, x, y)) return PopupControl.TOGGLE_HEART;
        if (contains(popupNextButtonBounds, x, y)) return PopupControl.NEXT;
        return PopupControl.NONE;
    }

    public boolean beginPopupProgressScrub(double x, double y) {
        if (!contains(popupProgressBounds, x, y) || playback.durationMillis() <= 0) return false;
        popupProgressScrubbing = true;
        popupScrubDuration = playback.durationMillis();
        updatePopupProgressScrub(x);
        return true;
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
        double surfaceScale = preferences.getLyricsTextScale() * (inPhone ? PHONE_LYRICS_SCALE : 1.0);
        double wheel = Math.clamp(verticalAmount, -3, 3);
        manual.nudge(motion.position(), -wheel * Math.max(30, 42 * surfaceScale),
                minimum, maximum, System.nanoTime());
        return true;
    }

    private void renderCompact(DrawContext context, int x, int y, int width, int height) {
        TextRenderer renderer = renderer();
        if (renderer == null) return;
        double frameDelta = bandedTransitionDrawing ? 0 : frameClock.nextDelta();
        double delta = frameDelta * preferences.getLyricsTransitionSpeed().deltaMultiplier();
        stepCornerMorph(delta);
        PopupMotion.State animation = popupMotion.step(delta,
                preferences.isLyricsAnimations() && !renderingOverScreen);
        GuiTransform.push(context);
        GuiTransform.translate(context, (float) (x + width / 2.0),
                (float) (y + height / 2.0 + animation.offset()));
        GuiTransform.scale(context, (float) animation.scale(), (float) animation.scale());
        GuiTransform.translate(context, (float) -(x + width / 2.0), (float) -(y + height / 2.0));
        int accent = accentColor();
        int surfaceRadius = morphRadius(preferences.isModernMode()
                ? Math.clamp(Math.min(width, height) / 22, 9, 16) : 0);
        boolean dynamic = false;
        if (preferences.isLyricsDynamicBackground() && artworkTexture != null) {
            double reactive = getLyricsBackgroundReactiveMultiplier();
            double backdropSpeed = preferences.getLyricsBackgroundSpeedMultiplier() * reactive;
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
            dynamic = artworkTexture.drawBackdrop(context, x, y, width, height,
                    preferences.getLyricsBlurStrength(), backdropSpeed,
                    preferences.getLyricsBackgroundOpacityMultiplier(), surfaceRadius, fx);
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
        boolean empty = document.groups().isEmpty();
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
        boolean coverFocusWanted = coverHovered || popupProgressScrubbing;
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
            double messageHeight = message.isBlank() ? 0 : renderer().fontHeight * .82 + gap;
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
            if (!message.isBlank()) {
                drawScaled(context, renderer, message, messageX,
                        textY + titleHeight + gap + artistHeight + gap,
                        multiplyAlpha(0xFF939AA4, textOpacity * .92), .82);
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
        if (artworkTexture == null || !artworkTexture.drawTransition(context, drawnCover.x(), drawnCover.y(),
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
        renderPopupCoverControls(context, drawnCover, coverTransition, delta);
        if (preferences.isLyricsBorder()) drawPopupBorder(context, x, y, width, height, accent, surfaceRadius);
        drawResizeGrip(context, x + width - 15, y + height - 15, 0xB8D7DADE);
        GuiTransform.pop(context);
    }

    private void renderPopupCoverControls(DrawContext context, CoverBounds cover,
                                          double focus, double delta) {        double visibility = renderingOverScreen ? smooth((focus - .06) / .82) : 0;
        int minimum = Math.min(cover.width(), cover.height());
        if (minimum < 54) visibility = 0;

        int controlSize = Math.clamp(minimum / 9, 14, 28);
        int controlGap = Math.clamp(minimum / 28, 3, 10);
        int controlsWidth = controlSize * 3 + controlGap * 2;
        int controlsX = cover.x() + (cover.width() - controlsWidth) / 2;
        int barY = cover.bottom() - Math.clamp(minimum / 15, 10, 17);
        int controlsY = barY - controlSize - Math.clamp(minimum / 22, 7, 12);
        ControlBounds previous = new ControlBounds(controlsX, controlsY, controlSize, controlSize);
        ControlBounds playPause = new ControlBounds(controlsX + controlSize + controlGap,
                controlsY, controlSize, controlSize);
        ControlBounds next = new ControlBounds(controlsX + (controlSize + controlGap) * 2,
                controlsY, controlSize, controlSize);

        int heartPixel = Math.clamp(minimum / 36, 3, 7);
        int heartWidth = HEART_MASK[0].length() * heartPixel;
        int heartHeight = HEART_MASK.length * heartPixel;
        int heartCenterY = cover.y() + Math.max(heartHeight / 2 + 5,
                (controlsY - cover.y()) / 2);
        ControlBounds heart = new ControlBounds(cover.x() + (cover.width() - heartWidth) / 2,
                heartCenterY - heartHeight / 2, heartWidth, heartHeight);

        boolean interactive = visibility > .32;
        popupPreviousHover = fadeControl(popupPreviousHover,
                interactive && previous.contains(pointerX, pointerY), delta, 13);
        popupPlayPauseHover = fadeControl(popupPlayPauseHover,
                interactive && playPause.contains(pointerX, pointerY), delta, 13);
        popupHeartHover = fadeControl(popupHeartHover,
                interactive && heart.contains(pointerX, pointerY), delta, 13);
        popupNextHover = fadeControl(popupNextHover,
                interactive && next.contains(pointerX, pointerY), delta, 13);

        popupPreviousButtonBounds = interactive ? previous : null;
        popupPlayPauseButtonBounds = interactive ? playPause : null;
        popupHeartButtonBounds = interactive ? heart : null;
        popupNextButtonBounds = interactive ? next : null;
        if (visibility <= .004) {
            clearPopupControlBounds();
            return;
        }

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
        drawPopupTransport(context, previous, TransportIcon.PREVIOUS,
                popupControlColor(popupPreviousHover, visibility, true));
        drawPopupTransport(context, playPause,
                playback.playing() ? TransportIcon.PAUSE : TransportIcon.PLAY,
                popupControlColor(popupPlayPauseHover, visibility, true));
        drawPopupTransport(context, next, TransportIcon.NEXT,
                popupControlColor(popupNextHover, visibility, true));
        drawPopupHeart(context, heart.x(), heart.y(), heartPixel, playback.liked(),
                popupControlColor(popupHeartHover, visibility, true));

        long duration = Math.max(0, playback.durationMillis());
        long position = popupProgressScrubbing ? popupScrubPosition : Math.max(0, positionMillis());
        if (duration > 0) position = Math.min(position, duration);
        String currentLabel = positionTimeLabel(position);
        String durationLabel = durationTimeLabel(duration);
        double timeScale = minimum < 100 ? .54 : .62;
        int currentWidth = (int) Math.ceil(textWidth(currentLabel) * timeScale);
        int durationWidth = (int) Math.ceil(textWidth(durationLabel) * timeScale);
        int horizontalMargin = Math.clamp(cover.width() / 32, 7, 14);
        int barLeft = cover.x() + horizontalMargin + currentWidth + 6;
        int barRight = cover.right() - horizontalMargin - durationWidth - 6;
        int barHeight = minimum >= 150 ? 4 : 3;
        double labelY = barY + (barHeight - renderer().fontHeight * timeScale) / 2.0;
        int textColor = multiplyAlpha(0xFFDCE2E4, visibility);
        drawScaled(context, renderer(), currentLabel, cover.x() + horizontalMargin, labelY,
                textColor, timeScale);
        drawScaled(context, renderer(), durationLabel, cover.right() - horizontalMargin - durationWidth,
                labelY, textColor, timeScale);
        if (barRight - barLeft < 12) {
            popupProgressBounds = null;
            return;
        }
        boolean canSeek = duration > 0;
        popupProgressBounds = interactive && canSeek
                ? new ControlBounds(barLeft, barY - 4, barRight - barLeft, barHeight + 8) : null;
        boolean progressHovered = contains(popupProgressBounds, pointerX, pointerY);
        int barRadius = morphRadius(preferences.isModernMode() ? Math.max(1, barHeight / 2) : 0);
        int railColor = multiplyAlpha(progressHovered || popupProgressScrubbing
                ? 0xA0BFC7CA : 0x805E686D, visibility);
        if (preferences.isModernMode()) {
            RoundedGui.smoothFill(context, barLeft, barY, barRight - barLeft, barHeight,
                    barRadius, railColor);
        } else {
            RoundedGui.fill(context, barLeft, barY, barRight - barLeft, barHeight,
                    barRadius, railColor);
        }
        double progress = duration <= 0 ? 0 : popupProgressScrubbing
                ? Math.clamp(position / (double) duration, 0, 1)
                : visualPlaybackProgress();
        int fillRight = barLeft + (int) Math.round((barRight - barLeft) * progress);
        if (fillRight > barLeft) {
            int fillColor = multiplyAlpha(0xFFF5F7F7, visibility);
            if (preferences.isModernMode()) {
                RoundedGui.smoothFill(context, barLeft, barY, fillRight - barLeft, barHeight,
                        barRadius, fillColor);
            } else {
                RoundedGui.fill(context, barLeft, barY, fillRight - barLeft, barHeight,
                        barRadius, fillColor);
            }
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
        double strength = enabled ? 1 : .30;
        int alpha = (int) Math.round((132 + 123 * Math.clamp(hover, 0, 1))
                * Math.clamp(visibility, 0, 1) * strength);
        return Math.clamp(alpha, 0, 255) << 24 | 0xFFFFFF;
    }

    private static void drawPopupTransport(DrawContext context, ControlBounds bounds,
                                           TransportIcon icon, int color) {
        int centerX = bounds.x() + bounds.width() / 2;
        int centerY = bounds.y() + bounds.height() / 2;
        int height = Math.clamp(bounds.height() - 8, 6, 15);
        int triangleWidth = Math.max(4, height / 2 + 1);
        switch (icon) {
            case PLAY -> drawPopupTriangle(context, centerX - triangleWidth / 2,
                    centerY, triangleWidth, height, true, color);
            case PAUSE -> {
                int barWidth = Math.max(2, bounds.width() / 7);
                int gap = Math.max(2, barWidth);
                context.fill(centerX - gap / 2 - barWidth, centerY - height / 2,
                        centerX - gap / 2, centerY + (height + 1) / 2, color);
                context.fill(centerX + gap / 2, centerY - height / 2,
                        centerX + gap / 2 + barWidth, centerY + (height + 1) / 2, color);
            }
            case PREVIOUS -> {
                int barX = centerX - triangleWidth / 2 - 2;
                context.fill(barX, centerY - height / 2, barX + 2,
                        centerY + (height + 1) / 2, color);
                drawPopupTriangle(context, centerX + 1, centerY,
                        triangleWidth, height, false, color);
            }
            case NEXT -> {
                drawPopupTriangle(context, centerX - 1, centerY,
                        triangleWidth, height, true, color);
                int barX = centerX + triangleWidth / 2 + 1;
                context.fill(barX, centerY - height / 2, barX + 2,
                        centerY + (height + 1) / 2, color);
            }
        }
    }

    private static void drawPopupTriangle(DrawContext context, int centerX, int centerY,
                                          int width, int height, boolean right, int color) {
        for (int column = 0; column < width; column++) {
            int distanceFromBase = right ? column : width - 1 - column;
            int halfHeight = Math.max(0,
                    (height - 1) * (width - distanceFromBase) / (2 * width));
            int drawX = centerX - width / 2 + column;
            context.fill(drawX, centerY - halfHeight,
                    drawX + 1, centerY + halfHeight + 1, color);
        }
    }

    private static void drawPopupHeart(DrawContext context, int x, int y, int pixel,
                                       boolean filled, int color) {
        for (int row = 0; row < HEART_MASK.length; row++) {
            for (int column = 0; column < HEART_MASK[row].length(); column++) {
                if (HEART_MASK[row].charAt(column) != '1') continue;
                boolean boundary = row == 0 || row == HEART_MASK.length - 1 || column == 0
                        || column == HEART_MASK[row].length() - 1
                        || HEART_MASK[row - 1].charAt(column) != '1'
                        || HEART_MASK[row + 1].charAt(column) != '1'
                        || HEART_MASK[row].charAt(column - 1) != '1'
                        || HEART_MASK[row].charAt(column + 1) != '1';
                if (filled || boundary) {
                    context.fill(x + column * pixel, y + row * pixel,
                            x + (column + 1) * pixel, y + (row + 1) * pixel, color);
                }
            }
        }
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
        popupPreviousButtonBounds = null;
        popupPlayPauseButtonBounds = null;
        popupHeartButtonBounds = null;
        popupNextButtonBounds = null;
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
            drawScaled(context, renderer, emptyMessage, x + 28, y + height / 2.0 - 8,
                    0xFFD1D5DA, 1.35);
            return;
        }
        renderLyricsBody(context, x + 6, y + 6, width - 12, height - 12, true,
                frameClock.nextDelta() * preferences.getLyricsTransitionSpeed().deltaMultiplier());
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
            long now = positionMillis();
            int accent = accentColor();
            LyricsScene currentScene = ensureScene(textWidth);
            int active = currentScene.updateFocus(now);
            InterludeLayout interlude = currentScene.interludeLayout(now);
            double activeCenter = currentScene.cameraTarget(now, height, interlude);
            double minimumCamera = currentScene.minimumCamera(interlude);
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
            double cameraGoal = manualScroll.target(activeCenter, minimumCamera, maximumCamera, frameNanos);
            ManualReadMotion readMotion = interactive ? phoneManualReadMotion : hudManualReadMotion;
            boolean manualScrolling = manualScroll.active(frameNanos);
            double manualRead = readMotion.step(manualScrolling, delta);
            boolean manualReading = manualScrolling || manualRead > .01;
            double scroll = scrollMotion.step(cameraGoal, delta);
            double minimum = scroll + y + 25 - focusY;
            double maximum = scroll + y + height - 12 - focusY;
            LyricsViewport.Range window = currentScene.visibleRange(
                    minimum - interlude.expansion(), maximum + interlude.expansion());
            updateFrameMotion(currentScene, window, active, now, delta, manualReading);
            boolean distantBloomNeeded = enhancedEffects()
                    && currentScene.needsDistantBloom(window, preferences.isSimpleLyricsMode(), manualReading);
            List<ActiveGlowCommand> glowCommands = enhancedEffects()
                    ? buildActiveGlowCommands(currentScene, x, textWidth, focusY, scroll,
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
            renderActiveGlowPass(context, glowCommands, x, y, width, height,
                    accent, RenderPass.ACTIVE_GLOW_OUTER, activeGlowOuterRadius());
            if (enhancedEffects()) {
                renderActiveGlowPass(context, glowCommands, x, y, width, height,
                        accent, RenderPass.ACTIVE_GLOW_CORE, activeGlowCoreRadius());
            }
        }
        if (distantBloomNeeded && enhancedEffects()) {
            renderEnhancedPass(context, currentScene, window, x, y, width, height,
                    textWidth, focusY, scroll, active, now, accent, manualReading,
                    RenderPass.DISTANT_BLOOM, distantBlurRadius(), interlude);
        }
        int visibleTokens;
        enableLyricsScissor(context, clipLeft, clipTop, clipRight, clipBottom);
        boolean previousShadow = drawingLyricsShadow;
        drawingLyricsShadow = preferences.isLyricsTextShadow();
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

    /** Reuses the same temporary framebuffer for the active halo and distant-line blur. */
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

    /** Submits an already planned active-line source; empty plans never touch a framebuffer. */
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
        var band = pass == RenderPass.ACTIVE_GLOW_CORE
                ? dev.fandroid.client.music.LyricsAnimation.GlowBand.CORE
                : dev.fandroid.client.music.LyricsAnimation.GlowBand.OUTER;
        double accentMix = pass == RenderPass.ACTIVE_GLOW_CORE ? .04 : .18;
        int color = blend(ACTIVE_TEXT, accent, accentMix);
        double intensity = preferences.getLyricsGlowIntensity().multiplier();
        for (ActiveGlowCommand command : commands) {
            var glow = activeGlowProfile(command.glowAmount(), intensity);
            drawActiveGlowGlyph(context, command.glyph(), command.x(), command.y(), command.scale(),
                    color, command.opacity() * glow.opacity(band), command.fillLeft(), command.fillRight());
        }
        if (!bloomRenderer.finish(context, bounds.left(), bounds.top(), bounds.right(), bounds.bottom(),
                radius, true, true)) {
            markEnhancedFailed();
        }
    }

    /** Builds active glow geometry once; outer and core bands reuse the same commands. */
    private List<ActiveGlowCommand> buildActiveGlowCommands(LyricsScene scene, int x, int textWidth,
                                                            double focusY, double scroll, int active,
                                                            long now, boolean manualReading) {
        activeGlowCommands.clear();
        boolean simple = preferences.isSimpleLyricsMode();
        if (active < 0 || active >= scene.groups.length || simple || manualReading) return activeGlowCommands;
        for (int index = scene.firstFocused; index <= scene.lastFocused; index++) {
            if (index < 0 || index >= scene.groups.length) continue;
            PreparedGroup group = scene.groups[index];
            if (!activeGlowEligible(index, index, group.group.activeAt(now), simple, false)) continue;
            collectGroupGlowCommands(group, x, textWidth, focusY, scroll, now);
        }
        return activeGlowCommands;
    }

    private void collectGroupGlowCommands(PreparedGroup group, int x, int textWidth,
                                          double focusY, double scroll, long now) {
        LyricsTextAlignment alignment = preferences.getLyricsAlignment();
        boolean centered = alignment == LyricsTextAlignment.CENTER;
        boolean opposite = alignment == LyricsTextAlignment.SOURCE
                && group.group.alignment() == LyricAlignment.OPPOSITE;
        PreparedLine prepared = group.lead;
        double focusScale = group.motion.scale();
        double baseScale = prepared.scale;
        double y = focusY + group.top - scroll + group.motion.elevation();
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
                collectTokenGlowCommands(token, rowX + token.x * focusScale, rowY,
                        baseScale * focusScale, now, group.motion.opacity());
            }
        }
    }

    private void collectTokenGlowCommands(Token token, double x, double y, double scale,
                                          long now, double opacity) {
        double wordProgress = token.timing.endMillis() <= token.timing.startMillis()
                ? now >= token.timing.startMillis() ? 1 : -1
                : (now - token.timing.startMillis())
                / (double) (token.timing.endMillis() - token.timing.startMillis());
        double durationStrength = durationStrength(token.visualWordDurationMillis);
        var motionPlan = token.motionPlan(false);
        boolean perGlyphMotion = motionPlan.glyphMotion();
        if (!perGlyphMotion) return;
        double wordScale = 1 + wordEmphasis(wordProgress) * mix(.016, .045, durationStrength);
        double wordCenterX = x + token.rawWidth * scale / 2;
        double wordGlyphScale = scale * wordScale;
        double fillOffset = token.fillOffset(now);
        var fill = fillInterval(token.rawWidth, fillOffset / token.rawWidth, isRightToLeft(token.text));
        double fillLeft = wordCenterX + (x + fill.start() * scale - wordCenterX) * wordScale;
        double fillRight = wordCenterX + (x + fill.end() * scale - wordCenterX) * wordScale;
        double wordSustainedGlow = sustainedWordGlow(token.visualWordDurationMillis, wordProgress);
        for (Glyph glyph : token.glyphs) {
            if (glyph.startMillis == Long.MIN_VALUE) continue;
            Segment segment = token.segments.get(glyph.segmentIndex);
            double restingX = x + glyph.x * scale;
            double baseX = wordCenterX + (restingX - wordCenterX) * wordScale;
            double characterScale = perGlyphMotion
                    ? segment.motion.scale() * glyph.motion.scale() : 1;
            double animatedScale = wordGlyphScale * characterScale;
            double drawX = baseX + glyph.rawWidth * wordGlyphScale * (1 - characterScale) / 2;
            double drawY = y + renderer().fontHeight * scale / 2
                    - renderer().fontHeight * animatedScale / 2
                    - (segment.motion.elevation() + glyph.motion.elevation()) * scale;
            double motionGlow = perGlyphMotion
                    ? Math.clamp(Math.max(glyph.motion.glow(), segment.motion.glow()) / 1.65, 0, 1) : 0;
            double boundaryPixels = Math.clamp(fillOffset, 0, Math.max(0, token.rawWidth));
            double edgeGlow = boundaryGlow(
                    (boundaryPixels - (glyph.x + glyph.rawWidth / 2)) / Math.max(glyph.rawWidth, 1));
            double glowAmount = activeGlyphGlow(
                    Math.max(motionGlow, Math.min(1, wordSustainedGlow / 1.45)),
                    token.visualWordDurationMillis, wordProgress, edgeGlow);
            if (!activeGlowProfile(glowAmount,
                    preferences.getLyricsGlowIntensity().multiplier()).needsBloom()) continue;
            double glyphRight = drawX + glyph.rawWidth * animatedScale;
            if (fillRight <= drawX + .01 || fillLeft >= glyphRight - .01) continue;
            activeGlowCommands.add(new ActiveGlowCommand(glyph, drawX, drawY, animatedScale,
                    glowAmount, opacity, fillLeft, fillRight));
        }
    }

    /** Advances every visible spring exactly once before any framebuffer or base pass draws it. */
    private void updateFrameMotion(LyricsScene scene, LyricsViewport.Range window, int active,
                                   long now, double delta, boolean manualReading) {
        boolean simple = preferences.isSimpleLyricsMode();
        for (int index = window.firstInclusive(); index < window.lastExclusive(); index++) {
            PreparedGroup group = scene.groups[index];
            group.motion.update(focusProfile(scene.distanceToFocus(index), simple, manualReading), delta);
        }
        if (active < 0 || active >= scene.groups.length) return;
        for (int index = scene.firstFocused; index <= scene.lastFocused; index++) {
            if (index < 0 || index >= scene.groups.length) continue;
            PreparedGroup focused = scene.groups[index];
            if (!focused.group.activeAt(now) && index != active) continue;
            updateLineTokenMotion(focused.lead, now, delta, simple);
            for (PreparedLine background : focused.backgrounds) {
                updateLineTokenMotion(background, now, delta, simple);
            }
        }
    }

    private static void updateLineTokenMotion(PreparedLine line, long now, double delta, boolean simple) {
        for (Row row : line.layout.rows) {
            for (Token token : row.tokens) {
                var motionPlan = token.motionPlan(simple);
                boolean segmentMotion = motionPlan.syllableMotion() || motionPlan.glyphMotion();
                for (Segment segment : token.segments) {
                    segment.motion.update(timedProgress(now, segment.startMillis, segment.endMillis),
                            durationStrength(segment.durationMillis()), segmentMotion, delta);
                }
                for (Glyph glyph : token.glyphs) {
                    if (glyph.startMillis == Long.MIN_VALUE) continue;
                    Segment segment = token.segments.get(glyph.segmentIndex);
                    glyph.motion.update(timedProgress(now, glyph.startMillis, glyph.endMillis),
                            durationStrength(segment.durationMillis()), motionPlan.glyphMotion(), delta);
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
            boolean simple = preferences.isSimpleLyricsMode();
            boolean singing = group.group.activeAt(now);
            double lineScale = group.motion.scale();
            double opacity = group.motion.opacity();
            double textBrightness = group.motion.textBrightness();
            double blur = group.motion.blur();
            double bloomOpacity = group.motion.bloomOpacity();
            double groupY = baseY + group.motion.elevation();
            LyricsTextAlignment alignment = preferences.getLyricsAlignment();
            boolean centered = alignment == LyricsTextAlignment.CENTER;
            boolean opposite = alignment == LyricsTextAlignment.SOURCE
                    && group.group.alignment() == LyricAlignment.OPPOSITE;

            int regionY = (int) Math.floor(groupY - 6);
            HitRegion region = new HitRegion(x + 7, regionY, width - 14,
                    Math.max(12, (int) Math.ceil(group.height)), group.group.lead().startMillis());
            if (pass == RenderPass.BASE) {
                boolean clickSeek = preferences.isLyricsClickSeek();
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
            boolean activeLine = singing || index == active;
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
                        opposite, centered, false, pass);
                visibleTokens += background.layout.tokenCount;
                backgroundY += background.totalHeight + group.backingGap;
            }
        }
        return visibleTokens;
    }

    private LyricsScene ensureScene(int width) {
        LyricsDocument current = document;
        boolean translation = preferences.isLyricsTranslation();
        boolean romanization = preferences.isLyricsRomanization();
        LyricsFont font = preferences.getLyricsFont();
        LyricsFontWeight weight = preferences.getLyricsFontWeight();
        double preferenceScale = preferences.getLyricsTextScale();
        if (sceneDocument != current || sceneTranslation != translation || sceneRomanization != romanization
                || sceneFont != font || sceneFontWeight != weight
                || Double.compare(sceneTextScale, preferenceScale) != 0) {
            synchronized (layoutCache) { layoutCache.clear(); }
            synchronized (sceneCache) { sceneCache.clear(); }
            sceneDocument = current;
            sceneTranslation = translation;
            sceneRomanization = romanization;
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
        boolean romanized = preferences.isLyricsRomanization() && LyricsRenderText.hasRomanization(line);
        LineLayout layout = layout(line, width, scale, romanized);
        int auxiliaryHeight = auxiliary ? auxiliaryHeight(line) : 0;
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
        double reveal = .30 + .70 * smooth((now - credits.revealStartMillis) / 900.0);
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
        drawScaled(context, renderer(), value, drawX, y, multiplyAlpha(color, opacity), scale);
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
        drawScaled(context, renderer(), value, drawX, y,
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
            drawScaled(context, renderer(), initial,
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
                ? null : metadata.makerAvatar());
        uploaderAvatarTexture.update(metadata == null || metadata.uploaderAvatar().isBlank()
                ? null : metadata.uploaderAvatar());
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
                        drawDistant(context, token.text, tokenX, rowY, FUTURE_TEXT, baseScale * focusScale,
                                opacity, textBrightness, blur, bloomOpacity, pass);
                    } finally {
                        drawingLyricsShadow = previousShadow;
                    }
                } else {
                    drawTimedToken(context, token, tokenX, rowY, baseScale * focusScale, now, accent,
                            line.background() ? 0xFF746B80 : UNSYNCED_TEXT, opacity, pass);
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
        double durationStrength = durationStrength(token.visualWordDurationMillis);
        double wordEnvelope = wordEmphasis(wordProgress);
        boolean simple = preferences.isSimpleLyricsMode();
        var motionPlan = token.motionPlan(simple);
        boolean perGlyphMotion = motionPlan.glyphMotion();
        boolean segmentMotion = motionPlan.syllableMotion() || perGlyphMotion;
        double wordLift = motionPlan.wholeWordMotion()
                ? shortWordLift(token.visualWordDurationMillis, wordProgress) : 0;
        // A syllable-timed word keeps a stable overall shape. Its current
        // syllable owns the lift, rather than pulling unsung syllables along.
        double wordScale = motionPlan.syllableMotion() ? 1
                : 1 + wordEnvelope * mix(.016, .045, durationStrength);
        double wordCenterX = x + token.rawWidth * scale / 2;
        double wordGlyphScale = scale * wordScale;
        double fillOffset = token.fillOffset(now);
        var fill = fillInterval(token.rawWidth, fillOffset / token.rawWidth, isRightToLeft(token.text));
        double fillLeft = wordCenterX + (x + fill.start() * scale - wordCenterX) * wordScale;
        double fillRight = wordCenterX + (x + fill.end() * scale - wordCenterX) * wordScale;
        double wordSustainedGlow = sustainedWordGlow(token.visualWordDurationMillis, wordProgress);
        for (Glyph glyph : token.glyphs) {
            Segment segment = token.segments.get(glyph.segmentIndex);
            double restingX = x + glyph.x * scale;
            double baseX = wordCenterX + (restingX - wordCenterX) * wordScale;
            double segmentGlyphScale = wordGlyphScale;
            double segmentProgress = timedProgress(now, segment.startMillis, segment.endMillis);
            if (glyph.startMillis == Long.MIN_VALUE) {
                if (pass != RenderPass.BASE) continue;
                int color = segmentProgress >= 1 ? ACTIVE_TEXT : futureColor;
                double drawY = centerY - renderer().fontHeight * segmentGlyphScale / 2
                        - wordLift * scale;
                drawScaled(context, renderer(), glyph.text, baseX, drawY,
                        multiplyAlpha(color, opacity), segmentGlyphScale);
                continue;
            }

            double characterScale = segmentMotion
                    ? segment.motion.scale() * (perGlyphMotion ? glyph.motion.scale() : 1) : 1;
            double animatedScale = segmentGlyphScale * characterScale;
            double drawX = baseX + glyph.rawWidth * segmentGlyphScale * (1 - characterScale) / 2;
            double drawY = centerY - renderer().fontHeight * animatedScale / 2
                    - (segmentMotion
                    ? segment.motion.elevation() + (perGlyphMotion ? glyph.motion.elevation() : 0)
                    : wordLift) * scale;
            double motionGlow = perGlyphMotion
                    ? Math.clamp(Math.max(glyph.motion.glow(), segment.motion.glow()) / 1.65, 0, 1) : 0;
            double boundaryPixels = Math.clamp(fillOffset, 0, Math.max(0, token.rawWidth));
            double boundaryGlow = boundaryGlow(
                    (boundaryPixels - (glyph.x + glyph.rawWidth / 2)) / Math.max(glyph.rawWidth, 1));
            double glowAmount = simple || !perGlyphMotion ? 0 : activeGlyphGlow(
                    Math.max(motionGlow, Math.min(1, wordSustainedGlow / 1.45)),
                    token.visualWordDurationMillis, wordProgress, boundaryGlow);
            if (simple) glowAmount = 0;
            int activeColor = ACTIVE_TEXT;
            if (pass.activeGlow()) {
                var glow = activeGlowProfile(glowAmount, preferences.getLyricsGlowIntensity().multiplier());
                if (glow.needsBloom()) {
                    double accentMix = pass == RenderPass.ACTIVE_GLOW_CORE ? .04 : .18;
                    var band = pass == RenderPass.ACTIVE_GLOW_CORE
                            ? dev.fandroid.client.music.LyricsAnimation.GlowBand.CORE
                            : dev.fandroid.client.music.LyricsAnimation.GlowBand.OUTER;
                    drawActiveGlowGlyph(context, glyph, drawX, drawY, animatedScale,
                            blend(ACTIVE_TEXT, accent, accentMix),
                            opacity * glow.opacity(band), fillLeft, fillRight);
                }
                continue;
            }
            if (pass != RenderPass.BASE) continue;
            drawProgressiveGlyph(context, glyph, drawX, drawY, animatedScale,
                    futureColor, activeColor, opacity, fillLeft, fillRight);
        }
    }

    /** Emits one clipped source for the shader; it never fakes a halo with offset text draws. */
    private void drawActiveGlowGlyph(DrawContext context, Glyph glyph, double x, double y, double scale,
                                     int color, double opacity, double fillLeft, double fillRight) {
        double glyphRight = x + glyph.rawWidth * scale;
        if (fillRight <= x + .01 || fillLeft >= glyphRight - .01) return;
        int clipLeft = (int) Math.floor(Math.max(x, fillLeft) - 1);
        int clipTop = (int) Math.floor(y - 2);
        int clipRight = (int) Math.ceil(Math.min(glyphRight, fillRight) + 1);
        int clipBottom = (int) Math.ceil(y + renderer().fontHeight * scale + 2);
        if (clipRight <= clipLeft || clipBottom <= clipTop) return;
        bloomRenderer.drawGlyph(context, renderer(), styledText(glyph.text), x, y, scale,
                multiplyAlpha(color, opacity * lyricsSurfaceOpacity),
                clipLeft, clipTop, clipRight, clipBottom);
    }

    private void drawProgressiveGlyph(DrawContext context, Glyph glyph, double x, double y, double scale,
                                      int futureColor, int activeColor, double opacity,
                                      double fillLeft, double fillRight) {
        double glyphRight = x + glyph.rawWidth * scale;
        double visibleLeft = Math.max(x, fillLeft);
        double visibleRight = Math.min(glyphRight, fillRight);
        double coverage = Math.clamp((visibleRight - visibleLeft) / Math.max(glyphRight - x, .001), 0, 1);
        double futureAlpha = (futureColor >>> 24) / 255.0;
        double activeAlpha = (activeColor >>> 24) / 255.0;
        // The shadow is one stable layer whose opacity follows fill coverage. Drawing
        // it once for the future text and again for the clipped active text caused
        // the black flash at the instant a word started.
        drawScaledShadowOnly(context, renderer(), glyph.text, x, y,
                opacity * mix(futureAlpha, activeAlpha, smoother(coverage)), scale);
        if (fillRight <= x + .01 || fillLeft >= glyphRight - .01) {
            drawScaledWithoutShadow(context, renderer(), glyph.text, x, y,
                    multiplyAlpha(futureColor, opacity), scale);
            return;
        }
        if (fillLeft <= x + .01 && fillRight >= glyphRight - .01) {
            drawScaledWithoutShadow(context, renderer(), glyph.text, x, y,
                    multiplyAlpha(activeColor, opacity), scale);
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
            drawScaledWithoutShadow(context, renderer(), glyph.text, x, y,
                    multiplyAlpha(activeColor, opacity), scale);
        } finally {
            context.disableScissor();
        }
    }

    private void drawAuxiliaryText(DrawContext context, PreparedLine prepared, int x, double y, int width,
                                   boolean opposite, boolean centered, double opacity,
                                   double textBrightness, double focusScale) {
        double currentY = y + prepared.layout.height + 5 * preferences.getLyricsTextScale();
        double auxiliaryScale = .78 * lyricsRenderScale() * focusScale;
        double auxiliaryOpacity = opacity * .58;
        if (preferences.isLyricsTranslation() && prepared.line.translation() != null) {
            String text = fit(prepared.line.translation(), width, auxiliaryScale);
            double drawX = centered ? x + (width - textWidth(text) * auxiliaryScale) / 2.0
                    : opposite ? x + width - textWidth(text) * auxiliaryScale : x;
            drawScaled(context, renderer(), text, drawX, currentY,
                    multiplyAlpha(multiplyBrightness(AUXILIARY_TEXT, textBrightness), auxiliaryOpacity), auxiliaryScale);
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
        double progress = Math.clamp(elapsed / (double) gap, 0, 1);
        double visibility = interlude.visibility();
        LyricsTextAlignment selectedAlignment = preferences.getLyricsAlignment();
        boolean opposite = selectedAlignment == LyricsTextAlignment.SOURCE
                && alignment == LyricAlignment.OPPOSITE;
        int dotsStartX = selectedAlignment == LyricsTextAlignment.CENTER
                ? x + width / 2 - 21 : opposite ? x + width - 66 : x + 24;
        int dotsY = (int) Math.clamp(dotsPosition, y + 14, y + height - 14);
        boolean simple = preferences.isSimpleLyricsMode();
        for (int index = 0; index < 3; index++) {
            double fill = interludeDotFill(progress, index);
            double lift = simple ? 0 : interludeDotLift(progress, index);
            if (updateMotion) interludeDots[index].update(fill, lift, visibility, delta);
            DotMotion dot = interludeDots[index];
            int size = simple ? 7 : Math.max(7, (int) Math.round(9 * dot.scale()));
            int radius = Math.max(3, size / 2);
            int dotY = (int) Math.round(dotsY - lift * 5.0);
            int dotX = dotsStartX + index * 21;
            drawDotOutline(context, dotX, dotY, radius,
                    multiplyAlpha(blend(0xFF747C87, ACTIVE_TEXT, .20 + fill * .24),
                            (.62 + lift * .24) * dot.opacity() * lyricsSurfaceOpacity));
            if (fill > .01) {
                int fillRadius = Math.max(1, (int) Math.round((radius - 1) * smooth(fill)));
                RetroDraw.circle(context, dotX, dotY, fillRadius,
                        multiplyAlpha(blend(ACTIVE_TEXT, accent, .16),
                                dot.opacity() * fill * lyricsSurfaceOpacity));
            }
        }
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
        if (gapMillis < INTERLUDE_MIN_GAP_MILLIS || elapsedMillis <= 0
                || elapsedMillis >= gapMillis) return 0;
        double transition = Math.min(320, Math.max(140, gapMillis * .18));
        double enter = smooth(elapsedMillis / transition);
        double leave = smooth((gapMillis - elapsedMillis) / transition);
        return Math.min(enter, leave);
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

    public boolean mousePressed(double mouseX, double mouseY, int button) {
        if (button != 0 || !preferences.isLyricsVisible()) return false;
        MinecraftClient client = MinecraftClient.getInstance();
        PopupBounds bounds = popupBoundsSnapshot(client.getWindow().getScaledWidth(), client.getWindow().getScaledHeight());
        int x = bounds.x();
        int y = bounds.y();
        int width = bounds.width();
        int height = bounds.height();
        if (mouseX >= x + width - 30 && mouseX <= x + width + 10
                && mouseY >= y + height - 30 && mouseY <= y + height + 10) {
            resizing = true;
            resizeOffsetX = (int) Math.round(mouseX) - (x + width);
            resizeOffsetY = (int) Math.round(mouseY) - (y + height);
            return true;
        }
        if (mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height) {
            dragging = true;
            dragOffsetX = (int) mouseX - x;
            dragOffsetY = (int) mouseY - y;
            return true;
        }
        return false;
    }

    public boolean mouseDragged(double mouseX, double mouseY) {
        MinecraftClient client = MinecraftClient.getInstance();
        int windowWidth = client.getWindow().getScaledWidth();
        int windowHeight = client.getWindow().getScaledHeight();
        if (dragging) {
            PopupBounds bounds = popupBoundsSnapshot(windowWidth, windowHeight);
            int width = bounds.width();
            int height = bounds.height();
            preferences.setLyricsX(Math.clamp((int) Math.round(mouseX) - dragOffsetX,
                    0, Math.max(0, windowWidth - width)));
            preferences.setLyricsY(Math.clamp((int) Math.round(mouseY) - dragOffsetY,
                    0, Math.max(0, windowHeight - height)));
            return true;
        }
        if (resizing) {
            int maximumWidth = Math.max(160, windowWidth - preferences.getLyricsX());
            int maximumHeight = Math.max(100, windowHeight - preferences.getLyricsY());
            int newWidth = (int) Math.round(mouseX) - preferences.getLyricsX() - resizeOffsetX;
            int newHeight = (int) Math.round(mouseY) - preferences.getLyricsY() - resizeOffsetY;
            preferences.setLyricsWidth(Math.clamp(newWidth, 160, Math.min(900, maximumWidth)));
            preferences.setLyricsHeight(Math.clamp(newHeight, 100, Math.min(600, maximumHeight)));
            return true;
        }
        return false;
    }

    public boolean mouseReleased() {
        boolean handled = dragging || resizing;
        dragging = false;
        resizing = false;
        if (handled) preferences.save();
        return handled;
    }

    private long positionMillis() { return clock.positionMillis(); }

    private static String playbackTrackKey(PlaybackSnapshot snapshot) {
        if (snapshot.spotifyUri() != null && !snapshot.spotifyUri().isBlank()) {
            return snapshot.spotifyUri();
        }
        return String.valueOf(snapshot.title()) + '\n' + snapshot.artist() + '\n' + snapshot.album();
    }

    private PopupBounds targetPopupBounds(int windowWidth, int windowHeight) {
        int width = Math.min(preferences.getLyricsWidth(), Math.max(1, windowWidth));
        int height = Math.min(preferences.getLyricsHeight(), Math.max(1, windowHeight));
        int x = Math.clamp(preferences.getLyricsX(), 0, Math.max(0, windowWidth - width));
        int y = Math.clamp(preferences.getLyricsY(), 0, Math.max(0, windowHeight - height));
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
        if (!smoothBoundsInitialized) {
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
        smoothBoundsNanos = now;
        return popupBoundsSnapshot(windowWidth, windowHeight);
    }

    private int auxiliaryHeight(LyricLine line) {
        int height = 0;
        double scale = .78 * lyricsRenderScale();
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
        if (preferences.isSimpleLyricsMode()) return false;
        return renderingInPhone ? !phoneEnhancedFailed : !hudEnhancedFailed;
    }

    private void markEnhancedFailed() {
        if (renderingInPhone) phoneEnhancedFailed = true;
        else hudEnhancedFailed = true;
    }

    private void enableLyricsScissor(DrawContext context, int left, int top, int right, int bottom) {
        ClipBounds bounds = clipBounds(left, top, right, bottom);
        context.enableScissor(bounds.left(), bounds.top(), bounds.right(), bounds.bottom());
    }

    private ClipBounds clipBounds(int left, int top, int right, int bottom) {
        PhoneViewport viewport = phoneViewport;
        if (viewport == null) return new ClipBounds(left, top, right, bottom);
        return new ClipBounds(
                (int) Math.floor(viewport.screenX() + left * viewport.scaleX()),
                (int) Math.floor(viewport.screenY() + top * viewport.scaleY()),
                (int) Math.ceil(viewport.screenX() + right * viewport.scaleX()),
                (int) Math.ceil(viewport.screenY() + bottom * viewport.scaleY()));
    }

    private double lyricsRenderScale() {
        return preferences.getLyricsTextScale() * (renderingInPhone ? PHONE_LYRICS_SCALE : 1.0);
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
        if (renderer == null || value == null || value.isEmpty() || (color >>> 24) == 0) return;
        GuiTransform.push(context);
        GuiTransform.translate(context, (float) x, (float) y);
        GuiTransform.scale(context, (float) scale, (float) scale);
        if (drawingLyricsShadow) drawShadowAtOrigin(context, renderer, value, color >>> 24);
        context.drawText(renderer, styledText(value), 0, 0, color, false);
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
        if (!drawingLyricsShadow || renderer == null || value == null || value.isEmpty() || sourceAlpha <= 0) return;
        GuiTransform.push(context);
        GuiTransform.translate(context, (float) x, (float) y);
        GuiTransform.scale(context, (float) scale, (float) scale);
        drawShadowAtOrigin(context, renderer, value, sourceAlpha);
        GuiTransform.pop(context);
    }

    private void drawShadowAtOrigin(DrawContext context, TextRenderer renderer, String value, int sourceAlpha) {
        int shadowAlpha = Math.min(230, (int) Math.round(sourceAlpha
                * preferences.getLyricsShadowOpacity() / 100.0));
        if (shadowAlpha <= 0) return;
        Text styled = styledText(value);
        int distance = preferences.getLyricsShadowDistance();
        int softness = preferences.getLyricsShadowSoftness();
        if (softness > 0) {
            int haloAlpha = Math.max(1, shadowAlpha / (softness == 1 ? 5 : 7));
            int halo = haloAlpha << 24;
            context.drawText(renderer, styled, distance - 1, distance, halo, false);
            context.drawText(renderer, styled, distance + 1, distance, halo, false);
            context.drawText(renderer, styled, distance, distance - 1, halo, false);
            context.drawText(renderer, styled, distance, distance + 1, halo, false);
            if (softness > 1) {
                context.drawText(renderer, styled, distance - 1, distance - 1, halo, false);
                context.drawText(renderer, styled, distance + 1, distance - 1, halo, false);
                context.drawText(renderer, styled, distance - 1, distance + 1, halo, false);
                context.drawText(renderer, styled, distance + 1, distance + 1, halo, false);
            }
        }
        context.drawText(renderer, styled, distance, distance, shadowAlpha << 24, false);
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
        context.enableScissor(x, clipTop, x + width, clipBottom);
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
    private record ActiveGlowCommand(Glyph glyph, double x, double y, double scale,
                                     double glowAmount, double opacity,
                                     double fillLeft, double fillRight) { }
    private record LyricsFramePlan(LyricsScene scene, LyricsViewport.Range window,
                                   long now, int accent, int active, double scroll,
                                   boolean manualReading, boolean distantBloomNeeded,
                                   List<ActiveGlowCommand> glowCommands,
                                   InterludeLayout interlude) { }
    private record InterludeLayout(int afterGroup, double visibility, double expansion) {
        private static final InterludeLayout NONE = new InterludeLayout(Integer.MAX_VALUE, 0, 0);
        private boolean shifts(int groupIndex) { return groupIndex > afterGroup && expansion > 0; }
    }
    private record LineLayout(List<Row> rows, int height, int tokenCount) { }
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
    public enum PopupControl { NONE, PREVIOUS, PLAY_PAUSE, TOGGLE_HEART, NEXT }
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
        private double nextDelta() {
            long now = System.nanoTime();
            if (lastNanos == 0) {
                lastNanos = now;
                return cachedDelta;
            }
            long elapsed = now - lastNanos;
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
            double goal = focused ? 1 : 0;
            if (!animated) {
                progress = goal;
                return goal;
            }
            double duration = .8;
            double direction = focused ? 1 : -1;
            progress = Math.clamp(progress + direction * Math.clamp(delta, 0, .05) / duration, 0, 1);
            return progress;
        }

        private void reset() { progress = 0; }
    }

    private static final class SpringScroll {
        // A slightly slower, near-critically-damped camera keeps phrase changes
        // flowing upward without the small snap/overshoot of the old spring.
        private final SpringValue spring = new SpringValue(0, .72, .97);
        private boolean initialized;
        private double step(double goal, double delta) {
            if (!initialized) {
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
        private final SpringValue scale = new SpringValue(1, 6.0, .92);
        private final SpringValue glow = new SpringValue(0, 7.0, .88);
        private final SpringValue elevation = new SpringValue(0, 5.4, .90);
        private void update(double progress, double durationStrength, boolean animated, double delta) {
            if (!animated) {
                scale.setGoal(1);
                glow.setGoal(0);
                elevation.setGoal(0);
                scale.step(delta);
                glow.step(delta);
                elevation.step(delta);
                return;
            }
            double peak = mix(1.018, 1.125, durationStrength);
            scale.setGoal(keyframe(progress, 1, .52, peak, 1));
            glow.setGoal(glowKeyframe(progress) * mix(.16, 1.08, durationStrength));
            elevation.setGoal(glowKeyframe(progress) * mix(.35, .70, durationStrength));
            scale.step(delta);
            glow.step(delta);
            elevation.step(delta);
        }
        private double scale() { return scale.position(); }
        private double glow() { return glow.position(); }
        private double elevation() { return elevation.position(); }
    }

    private static final class SegmentMotion {
        private final SpringValue scale = new SpringValue(1, 4.8, .92);
        private final SpringValue glow = new SpringValue(0, 5.8, .88);
        private final SpringValue elevation = new SpringValue(0, 4.6, .90);

        private void update(double progress, double durationStrength, boolean animated, double delta) {
            if (!animated) {
                scale.setGoal(1);
                glow.setGoal(0);
                elevation.setGoal(0);
                scale.step(delta);
                glow.step(delta);
                elevation.step(delta);
                return;
            }
            double peak = mix(1.016, 1.080, durationStrength);
            scale.setGoal(keyframe(progress, 1, .50, peak, 1));
            glow.setGoal(glowKeyframe(progress) * mix(.24, 1.28, durationStrength));
            elevation.setGoal(glowKeyframe(progress) * mix(.30, .60, durationStrength));
            scale.step(delta);
            glow.step(delta);
            elevation.step(delta);
        }

        private double scale() { return scale.position(); }
        private double glow() { return glow.position(); }
        private double elevation() { return elevation.position(); }
    }

    private static final class LineMotion {
        private final SpringValue scale = new SpringValue(.95, .84, .86);
        private final SpringValue opacity = new SpringValue(.35, .96, .84);
        private final SpringValue textBrightness = new SpringValue(.70, .96, .84);
        private final SpringValue elevation = new SpringValue(0, .86, .90);
        private final SpringValue blur = new SpringValue(1.2, .90, .88);
        private final SpringValue bloomOpacity = new SpringValue(0, .90, .88);
        private void update(dev.fandroid.client.music.LyricsAnimation.FocusProfile profile, double delta) {
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
        private double elevation() { return elevation.position(); }
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
