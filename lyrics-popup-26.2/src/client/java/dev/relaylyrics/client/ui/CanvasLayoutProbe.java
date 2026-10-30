package dev.relaylyrics.client.ui;

import dev.relaylyrics.FanDroidMod;
import dev.relaylyrics.client.ClientPreferences;
import dev.relaylyrics.client.LyricsSettingsDemo;
import dev.relaylyrics.client.music.CanvasPopupLayout;
import dev.relaylyrics.client.music.CoverArtTexture;
import dev.relaylyrics.client.music.ResponsiveLyricsLayout;
import dev.relaylyrics.protocol.lyrics.LyricAlignment;
import dev.relaylyrics.protocol.lyrics.SecureTtmlParser;
import dev.relaylyrics.protocol.music.MusicCapabilities;
import dev.relaylyrics.protocol.music.PlaybackSnapshot;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.function.Consumer;

/** Real, opt-in GUI fixture for duet rendering and the persistent video column. */
public final class CanvasLayoutProbe extends Screen {
    private static final boolean SLIDERS = Boolean.getBoolean("lyrica.slidersSelfTest");
    private static final boolean TOOLBAR = SLIDERS || Boolean.getBoolean("lyrica.toolbarSelfTest");
    private static final boolean GLASS = TOOLBAR || Boolean.getBoolean("lyrica.glassButtonsSelfTest");
    private int glassX = -1, glassY = -1;
    private int[] romanPoint, translationPoint;
    private boolean sliderPrimed;
    private double sliderX = -100, sliderY = -100;
    private LyricsOverlay.SliderSnapshot sliderBefore;
    private double sliderExpectedVolume;
    private static final String[] STAGES = SLIDERS ? new String[]{"sliders-idle", "sliders-volume-hover", "sliders-volume-drag", "sliders-muted", "sliders-progress-hover", "sliders-progress-drag", "sliders-progress-end", "sliders-compact", "sliders-landscape", "sliders-square", "sliders-static", "sliders-no-motion", "sliders-live"} : TOOLBAR ? new String[]{"toolbar-idle", "toolbar-roman-on", "toolbar-roman-press", "toolbar-original", "toolbar-no-translation", "toolbar-compact", "toolbar-latin", "toolbar-square", "toolbar-return", "toolbar-landscape"} : GLASS ? new String[]{"glass-idle", "glass-hover", "glass-press", "glass-release", "glass-reduced", "glass-compact"} : new String[]{"static", "portrait", "portrait-hover", "landscape", "landscape-hover", "gui3", "gui3-hover", "failed", "cleared", "resize-video", "solo", "solo-hover", "resize-solo", "lyrics-return"};
    private final ClientPreferences preferences = new ClientPreferences();
    private final CoverArtTexture artwork = new CoverArtTexture();
    private final LyricsOverlay overlay = new LyricsOverlay(preferences, () -> { });
    private final Path video = Path.of(System.getProperty("lyrica.motionProbeVideo")).toAbsolutePath().normalize();
    private int stage, ticks, totalTicks;
    private boolean capturePending;
    private LyricsOverlay.PopupBounds idleBounds;
    private dev.relaylyrics.protocol.lyrics.LyricsDocument fixtureLyrics;
    private boolean resizeStarted;
    private int resizeExpectedWidth, resizeExpectedHeight, squareSavedWidth;
    public CanvasLayoutProbe() {
        super(Component.literal("Canvas and duet probe"));
        if (!Boolean.getBoolean("lyrica.motionSelfTest")) throw new IllegalStateException("Canvas probe is opt-in");
        preferences.setModernMode(true); preferences.setLyricsAllowGuiOverlap(true);
        preferences.setLyricsX(72); preferences.setLyricsY(132);
        preferences.setLyricsWidth(420); preferences.setLyricsHeight(320);
        artwork.update(LyricsSettingsDemo.artwork()); overlay.setArtworkTexture(artwork);
        overlay.setArtworkAccent(0xFF8C579C);
        overlay.setPlayback(new PlaybackSnapshot("Dueto com Canvas", "Lyrica · verificação visual", "Local demo", null,
                "spotify:track:canvasDuetFixture", "Local fixture", 3_000, 60_000, false, .65, false, "off", false,
                .4, 0, 110, .5, new double[0], Instant.now(), MusicCapabilities.unavailable()));
        var lyrics = new SecureTtmlParser().parse("""
                <tt xmlns="http://www.w3.org/ns/ttml" xmlns:spicy="https://spicylyrics.org/ns/ttml"
                    xmlns:ttm="http://www.w3.org/ns/ttml#metadata"><body ttm:agent="v1000"><div>
                  <p begin="0s" end="1s"><span ttm:role="x-bg"><span begin="0s" end="1s">A música começa</span></span></p>
                  <p begin="1s" end="9s" spicy:oppositeAligned="false">
                    <span begin="1s" end="3s">Eu canto </span><span begin="3s" end="9s">deste lado</span>
                    <span ttm:role="x-bg"><span begin="2s" end="7s">Uma voz ao fundo</span></span></p>
                  <p begin="2s" end="10s" spicy:oppositeAligned="true">
                    <span begin="2s" end="4s">E a outra voz </span><span begin="4s" end="10s">responde</span></p>
                  <p begin="10s" end="15s">As duas vozes seguem juntas</p>
                  <p begin="16s" end="20s" ttm:agent="v2000">Cada uma no seu lado</p>
                </div></body></tt>
                """.getBytes(StandardCharsets.UTF_8));
        if (lyrics.groups().get(2).alignment() != LyricAlignment.OPPOSITE || lyrics.activeGroupsAt(3000).size() != 2)
            throw new IllegalStateException("Duet fixture lost its explicit side or simultaneous vocals");
        if (TOOLBAR) lyrics = new SecureTtmlParser().parse("""
                <tt xmlns:ttm="http://www.w3.org/ns/ttml#metadata"><body><div>
                  <p begin="1s" end="9s"><span begin="1s" end="3s">こんにちは<span ttm:role="x-roman">konnichiwa</span></span><span begin="3s" end="9s"> 世界<span ttm:role="x-roman">sekai</span></span><span ttm:role="x-translation">Olá, mundo</span></p>
                  <p begin="9s" end="15s"><span begin="9s" end="11s">歌<span ttm:role="x-roman">uta</span></span><span begin="11s" end="15s"> 声<span ttm:role="x-roman">koe</span></span><span ttm:role="x-translation">A música e a voz</span></p>
                </div></body></tt>
                """.getBytes(StandardCharsets.UTF_8));
        fixtureLyrics = lyrics; overlay.setLyrics(lyrics, "WORD SYNC");
        if (GLASS) {
             var client = Minecraft.getInstance(); client.options.guiScale().set(1); client.resizeGui(); 
            preferences.setLyricsWidth(620); preferences.setLyricsHeight(400);
            artwork.configureMotion(video.resolveSibling("motion-portrait-fixture.mp4").toUri().toString(), null, true, false, false, true);
        }
    }
    public static void start() { Minecraft.getInstance().gui.setScreen(new CanvasLayoutProbe()); }
    @Override public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        if (stage >= STAGES.length) return;
        context.fill(0, 0, width, height, 0xFF0B0E13);
        context.text(font, "CANVAS + DUETO / " + STAGES[stage], 20, 20, 0xFFCBC0DC, false);
        var bounds = overlay.popupBoundsSnapshot(width, height);
        double scale = ResponsiveLyricsLayout.scale(width, height);
        var columns = CanvasPopupLayout.columns(ResponsiveLyricsLayout.design(bounds.width(), scale),
                ResponsiveLyricsLayout.design(bounds.height(), scale), Math.max(.1, artwork.motionCoverAspectRatio()));
        var art = columns.artwork();
        double px = STAGES[stage].endsWith("hover") ? bounds.x() + (art.x() + art.width() / 2.0) * scale : -100;
        double py = STAGES[stage].endsWith("hover") ? bounds.y() + (art.y() + art.height() / 2.0) * scale : -100;
        if (GLASS) {
            px = stage == 0 || glassX < 0 ? bounds.x() + (art.x() + art.width() / 2.0) * scale : glassX;
            py = stage == 0 || glassY < 0 ? bounds.y() + (art.y() + art.height() / 2.0) * scale : glassY;
        }
        if (TOOLBAR && romanPoint != null && stage >= 1 && stage <= 3) { px = romanPoint[0]; py = romanPoint[1]; }
        if (TOOLBAR && translationPoint != null && stage == 4) { px = translationPoint[0]; py = translationPoint[1]; }
        if (SLIDERS && sliderPrimed) {
            px = sliderX; py = sliderY;
            if (stage == 2 || stage == 11) overlay.previewPopupVolume(sliderExpectedVolume);
            if (stage == 3) overlay.previewPopupVolume(0);
        }
        if (SLIDERS && stage == 10 && !sliderPrimed) { px = bounds.x() + 24 * scale; py = bounds.y() + 28 * scale; }
        overlay.renderHudOverScreen(context, px, py);
    }
    @Override public void tick() {
        if (stage >= STAGES.length || capturePending) return;
        if (++totalTicks > 1200) throw new IllegalStateException("Canvas layout probe timed out: " + STAGES[stage]);
        if (++ticks < 34) return;
        if (SLIDERS) { tickSliders(); return; }
        if (GLASS) {
            if (artwork.motionCoverAspectRatio() <= 0) return;
            var box = overlay.popupBoundsSnapshot(width, height);
            double gs = ResponsiveLyricsLayout.scale(width, height);
            int expected = (int) Math.round(overlay.expandedPopupWidth(overlay.popupStoredWidth(), overlay.popupStoredHeight()) * gs);
            expected = Math.min(expected, width - 2 * Math.max(2, (int) Math.round(12 * gs)));
            if (Math.abs(box.width() - expected) > 1) return;
            if (glassX < 0) {
                int left = Integer.MAX_VALUE, top = Integer.MAX_VALUE, right = -1, bottom = -1;
                for (int y = box.y(); y < box.y() + box.height(); y++) for (int x = box.x(); x < box.x() + box.width(); x++) {
                    if (overlay.popupControlAt(x, y) != LyricsOverlay.PopupControl.SETTINGS) continue;
                    left = Math.min(left, x); top = Math.min(top, y); right = Math.max(right, x); bottom = Math.max(bottom, y);
                }
                if (right < 0) return;
                glassX = (left + right) / 2; glassY = (top + bottom) / 2;
            }
            if (overlay.popupControlAt(glassX, glassY) != LyricsOverlay.PopupControl.SETTINGS)
                throw new IllegalStateException("Glass press changed its click target");
            if (stage < 5 && idleBounds != null && (Math.abs(box.width() - idleBounds.width()) > 2 || Math.abs(box.height() - idleBounds.height()) > 2))
                throw new IllegalStateException("Glass hover or press resized the popup: " + idleBounds + " -> " + box);
            if (TOOLBAR) {
                if ((glassX - box.x()) / gs > 29 || (glassY - box.y()) / gs > 29)
                    throw new IllegalStateException("Video utility controls were not anchored at the top-left");
                romanPoint = findUtility(LyricsOverlay.PopupControl.TOGGLE_ROMANIZATION, box);
                translationPoint = findUtility(LyricsOverlay.PopupControl.TOGGLE_TRANSLATION, box);
                boolean available = stage != 6 && stage != 7;
                if (available != (romanPoint != null) || available != (translationPoint != null))
                    throw new IllegalStateException("Text utility availability did not follow the current song");
                if (available && (romanPoint[0] <= glassX || translationPoint[0] <= romanPoint[0]
                        || Math.abs(romanPoint[1] - glassY) > 1 || Math.abs(translationPoint[1] - glassY) > 1))
                    throw new IllegalStateException("The utility row overlaps or lost its alignment");
                if (stage == 3 && preferences.isLyricsRomanization()) throw new IllegalStateException("Romanization toggle did not change the lyrics preference");
                if (stage == 4 && preferences.isLyricsTranslation()) throw new IllegalStateException("Translation toggle did not change the lyrics preference");
                if (stage == 7 && !overlay.isSquareVideo()) throw new IllegalStateException("No-lyrics video lost its square layout");
            }
            idleBounds = box;
            capture(); return;
        }
        if (stage >= 1 && stage <= 6 && artwork.motionCoverAspectRatio() <= 0) return;
        var bounds = overlay.popupBoundsSnapshot(width, height);
        if (bounds.x() < 0 || bounds.y() < 0 || bounds.x() + bounds.width() > width || bounds.y() + bounds.height() > height)
            throw new IllegalStateException("Expanded Canvas popup left the viewport");
        double scale = ResponsiveLyricsLayout.scale(width, height);
        int normalWidth = (int) Math.round(420 * scale);
        int targetWidth = (int) Math.round(overlay.expandedPopupWidth(overlay.popupStoredWidth(), overlay.popupStoredHeight()) * scale);
        targetWidth = Math.min(targetWidth, width - 2 * Math.max(2, (int) Math.round(12 * scale)));
        if (Math.abs(bounds.width() - targetWidth) > 1) return;
        if (stage >= 1 && stage <= 6 && bounds.width() <= normalWidth + 10) throw new IllegalStateException("Canvas did not widen the popup");
        if (stage == 7 || stage == 8) if (Math.abs(bounds.width() - normalWidth) > 2) throw new IllegalStateException("Static fallback did not restore saved width");
        if (stage == 9 || stage == 12) {
            if (!resizeStarted) {
                resizeStarted = true;
                squareSavedWidth = preferences.getLyricsWidth();
                resizeExpectedWidth = bounds.width() + 12;
                resizeExpectedHeight = bounds.height() + (stage == 12 ? 12 : 8);
                if (!overlay.isPopupResizeHandleAt(bounds.x() + bounds.width() - 1, bounds.y() + bounds.height() - 1)
                        || !overlay.mousePressed(bounds.x() + bounds.width() - 1, bounds.y() + bounds.height() - 1, 0))
                    throw new IllegalStateException("Video intercepted the resize handle");
                overlay.mouseDragged(bounds.x() + bounds.width() + 11, bounds.y() + bounds.height() + 7);
                return;
            }
            if (Math.abs(bounds.width() - resizeExpectedWidth) > 2 || Math.abs(bounds.height() - resizeExpectedHeight) > 2)
                throw new IllegalStateException("Resize grip drifted from pointer: " + bounds + " expected " + resizeExpectedWidth + "x" + resizeExpectedHeight);
            if (stage == 12 && preferences.getLyricsWidth() != squareSavedWidth) throw new IllegalStateException("Square resize overwrote lyrics width");
            overlay.mouseReleased();
        }
        if (stage >= 10 && stage <= 12 && Math.abs(bounds.width() - bounds.height()) > 1)
            throw new IllegalStateException("Video without lyrics was not square");
        if (STAGES[stage].endsWith("hover")) {
            if (idleBounds == null || Math.abs(idleBounds.width() - bounds.width()) > 2 || Math.abs(idleBounds.height() - bounds.height()) > 2)
                throw new IllegalStateException("Hover resized the Canvas artwork: " + idleBounds + " -> " + bounds);
            boolean playback = false, lyricsSeek = false;
            for (int y = bounds.y(); y < bounds.y() + bounds.height(); y++) for (int x = bounds.x(); x < bounds.x() + bounds.width(); x++) {
                playback |= overlay.popupControlAt(x, y) == LyricsOverlay.PopupControl.PLAY_PAUSE;
                lyricsSeek |= overlay.seekTargetAtHud(x, y).isPresent();
            }
            if (!playback || !overlay.isSquareVideo() && !lyricsSeek) throw new IllegalStateException("Canvas hover lost playback or blocked lyric seeking");
        } else idleBounds = bounds;
        if (stage < 9 && (preferences.getLyricsWidth() != 420 || preferences.getLyricsHeight() != 320)) throw new IllegalStateException("Automatic layout changed saved dimensions");
        capture();
    }
    private void tickSliders() {
        if (stage != 10 && artwork.motionCoverAspectRatio() <= 0) return;
        var state = overlay.sliderSnapshotForProbe();
        if (state.volume() == null || stage != 12 && state.progress() == null) return;
        var box = overlay.popupBoundsSnapshot(width, height);
        var volume = state.volume();
        if (stage == 10 ? volume.x() <= box.x() + box.width() / 2 : volume.x() >= box.x() + box.width() / 3)
            throw new IllegalStateException("Volume used the wrong side for the video/static cover");
        if (!sliderPrimed) {
            sliderBefore = state; sliderPrimed = true; ticks = 0;
            sliderX = volume.x() + volume.width() / 2.0; sliderY = volume.y() + volume.height() / 2.0;
            if (stage == 0) { sliderX = box.x() + box.width() / 5.0; sliderY = box.y() + box.height() / 2.0; }
            if (stage == 2 || stage == 11) {
                overlay.setPopupVolumeScrubbing(true);
                sliderY = volume.y() + volume.height() * .26;
                sliderExpectedVolume = overlay.popupVolumeTargetAtY(sliderY).orElseThrow();
                overlay.previewPopupVolume(sliderExpectedVolume);
            }
            if (stage == 3) {
                var mute = state.mute(); sliderX = mute.x() + mute.width() / 2.0; sliderY = mute.y() + mute.height() / 2.0;
                overlay.previewPopupVolume(0);
            }
            if (stage >= 4 && stage <= 6) {
                var progress = state.progress(); sliderX = progress.x() + progress.width() * .42;
                sliderY = progress.y() + progress.height() / 2.0;
                if (stage == 5) {
                    if (!overlay.beginPopupProgressScrub(sliderX, sliderY)) throw new IllegalStateException("Timeline could not start dragging");
                    sliderX = progress.x() + progress.width() * .7; overlay.updatePopupProgressScrub(sliderX);
                }
                if (stage == 6) { sliderX = progress.x() + progress.width() + 100; overlay.updatePopupProgressScrub(sliderX); }
            }
            return;
        }
        if (!state.volume().equals(sliderBefore.volume()) || !state.volumeTarget().equals(sliderBefore.volumeTarget())
                || stage != 12 && (!state.progress().equals(sliderBefore.progress()) || !state.progressTarget().equals(sliderBefore.progressTarget())))
            throw new IllegalStateException("Hover or drag moved the slider's hit area");
        if (overlay.popupVolumeTargetAtY(volume.y() - 100).orElseThrow() != 1
                || overlay.popupVolumeTargetAtY(volume.y() + volume.height() + 100).orElseThrow() != 0)
            throw new IllegalStateException("Volume endpoints were not clamped");
        if ((stage == 2 || stage == 11) && Math.abs(state.level() - sliderExpectedVolume) > .00001)
            throw new IllegalStateException("Volume fill lagged behind the drag");
        if (stage == 3 && (overlay.popupControlAt(sliderX, sliderY) != LyricsOverlay.PopupControl.TOGGLE_MUTE
                || overlay.popupVolumeTargetAt(sliderX, sliderY).isPresent()))
            throw new IllegalStateException("The slider intercepted the mute button");
        if (stage == 7 && overlay.popupControlAt(volume.x() + volume.width() / 2.0, volume.y() + volume.height() / 2.0) != LyricsOverlay.PopupControl.NONE)
            throw new IllegalStateException("Volume overlapped a player button");
        if (stage == 12 && overlay.beginPopupProgressScrub(box.x() + 20, box.y() + box.height() - 30))
            throw new IllegalStateException("An unknown duration was seekable");
        capture();
    }

    private int[] findUtility(LyricsOverlay.PopupControl control, LyricsOverlay.PopupBounds box) {
        int left = Integer.MAX_VALUE, top = Integer.MAX_VALUE, right = -1, bottom = -1;
        for (int y = box.y(); y < box.y() + box.height(); y++) for (int x = box.x(); x < box.x() + box.width(); x++) {
            if (overlay.popupControlAt(x, y) != control) continue;
            left = Math.min(left, x); right = Math.max(right, x); top = Math.min(top, y); bottom = Math.max(bottom, y);
        }
        return right < 0 ? null : new int[]{(left + right) / 2, (top + bottom) / 2};
    }

    private void advance() {
        if (++stage == STAGES.length) {
            FanDroidMod.LOGGER.info(SLIDERS ? "Glass sliders GUI probe passed: stable targets, volume left on Canvas/right on static, exact drag, endpoints, mute, seek on release, compact and no-motion" : TOOLBAR ? "Video toolbar GUI probe passed: top-left controls, romanization/translation toggle, compact, landscape, Latin fallback and square video" : GLASS ? "Liquid glass GUI probe passed: hover, press, release, reduced motion and compact scale" : "Canvas/duet GUI probe passed: persistent portrait/landscape art, playback hover without resize, independent lyric seeking, GUI 3, failure/static restore and unchanged preferences");
            Minecraft.getInstance().stop(); return;
        }
        if (SLIDERS) {
            if (stage != 6 && overlay.isPopupProgressScrubbing()) {
                long expected = stage == 7 ? 60_000 : 42_000;
                if (overlay.finishPopupProgressScrub().orElseThrow() != expected) throw new IllegalStateException("Seek release returned the wrong position");
            }
            overlay.setPopupVolumeScrubbing(false);
            if (stage == 4) overlay.previewPopupVolume(.65);
            if (stage == 7) {   var client = Minecraft.getInstance(); client.options.guiScale().set(3); client.resizeGui();   }
            if (stage == 8) {   var client = Minecraft.getInstance(); client.options.guiScale().set(1); client.resizeGui();   artwork.configureMotion(video.toUri().toString(), null, true, false, false, true); }
            if (stage == 9) { overlay.clearLyrics(); overlay.markUnavailable("No synced lyrics"); }
            if (stage == 10) { overlay.setLyrics(fixtureLyrics, "WORD SYNC"); artwork.clearMotion(); }
            if (stage == 11) { artwork.configureMotion(video.resolveSibling("motion-portrait-fixture.mp4").toUri().toString(), null, true, false, false, true); preferences.setLyricsAnimations(false); }
            if (stage == 12) {
                preferences.setLyricsAnimations(true);
                overlay.setPlayback(new PlaybackSnapshot("Live stream", "Local demo", "", null, "spotify:track:liveFixture", "Fixture", 0, 0, false, .65, false, "off", false, .4, 0, 110, .5, new double[0], Instant.now(), MusicCapabilities.unavailable()));
            }
            sliderPrimed = false; ticks = 0; capturePending = false; return;
        }
        if (TOOLBAR) {
            overlay.setPopupUtilityPressed(stage == 2 ? LyricsOverlay.PopupControl.TOGGLE_ROMANIZATION : LyricsOverlay.PopupControl.NONE);
            if (stage == 3 && !overlay.togglePopupUtility(LyricsOverlay.PopupControl.TOGGLE_ROMANIZATION)) throw new IllegalStateException("Romanization action was rejected");
            if (stage == 4 && !overlay.togglePopupUtility(LyricsOverlay.PopupControl.TOGGLE_TRANSLATION)) throw new IllegalStateException("Translation action was rejected");
            if (stage == 5) {  var client = Minecraft.getInstance(); client.options.guiScale().set(3); client.resizeGui();  }
            if (stage == 6) overlay.setLyrics(new SecureTtmlParser().parse("<tt><body><p begin='1s' end='9s'>Only Latin words</p></body></tt>".getBytes(StandardCharsets.UTF_8)), "LINE SYNC");
            if (stage == 7) { overlay.clearLyrics(); overlay.markUnavailable("No synced lyrics"); }
            if (stage == 8) {
                overlay.setLyrics(fixtureLyrics, "WORD SYNC");
                overlay.togglePopupUtility(LyricsOverlay.PopupControl.TOGGLE_ROMANIZATION);
                overlay.togglePopupUtility(LyricsOverlay.PopupControl.TOGGLE_TRANSLATION);
                 var client = Minecraft.getInstance(); client.options.guiScale().set(1); client.resizeGui(); 
            }
            if (stage == 9) artwork.configureMotion(video.toUri().toString(), null, true, false, false, true);
            if (stage >= 5) { glassX = glassY = -1; romanPoint = translationPoint = null; }
            ticks = 0; capturePending = false; return;
        }
        if (GLASS) {
            overlay.setPopupSettingsPressed(stage == 2 || stage == 4);
            preferences.setLyricsAnimations(stage != 4);
            if (stage == 5) {  var client = Minecraft.getInstance(); client.options.guiScale().set(3); client.resizeGui();  glassX = glassY = -1; }
            ticks = 0; capturePending = false; return;
        }
        if (stage == 1) artwork.configureMotion(video.resolveSibling("motion-portrait-fixture.mp4").toUri().toString(), null, true, false, false, true);
        if (stage == 3) artwork.configureMotion(video.toUri().toString(), null, true, false, false, true);
        if (stage == 5) { var client = Minecraft.getInstance(); client.options.guiScale().set(3); client.resizeGui(); }
        if (stage == 7) artwork.configureMotion(video.resolveSibling("missing-canvas-fixture.mp4").toUri().toString(), null, true, false, false, true);
        if (stage == 8) artwork.clearMotion();
        if (stage == 9) artwork.configureMotion(video.toUri().toString(), null, true, false, false, true);
        if (stage == 10) { overlay.clearLyrics(); overlay.markUnavailable("No synced lyrics"); }
        if (stage == 13) { overlay.setLyrics(fixtureLyrics, "WORD SYNC"); preferences.setLyricsWidth(420); preferences.setLyricsHeight(320); }
        resizeStarted = false;
        artwork.setMotionPlaying(!STAGES[stage].endsWith("hover"));
        ticks = 0; capturePending = false;
    }
    private void verifyCaption(Path file) throws Exception {
        if (GLASS || stage != 1 && stage != 3 && stage != 5) return;
        var pixels = javax.imageio.ImageIO.read(file.toFile());
        var bounds = overlay.popupBoundsSnapshot(width, height);
        double scale = ResponsiveLyricsLayout.scale(width, height);
        var art = CanvasPopupLayout.columns(ResponsiveLyricsLayout.design(bounds.width(), scale),
                ResponsiveLyricsLayout.design(bounds.height(), scale), artwork.motionCoverAspectRatio()).artwork();
        double dx = pixels.getWidth() / (double) width, dy = pixels.getHeight() / (double) height;
        int left = (int) ((bounds.x() + (art.x() + 10) * scale) * dx);
        int top = (int) ((bounds.y() + (art.y() + art.height() - 35) * scale) * dy);
        int right = Math.min(pixels.getWidth(), (int) ((bounds.x() + (art.x() + art.width() - 10) * scale) * dx));
        int bottom = Math.min(pixels.getHeight(), top + (int) Math.ceil(12 * scale * dy));
        int visible = 0;
        for (int y = top; y < bottom; y++) for (int x = left; x < right; x++) {
            int color = pixels.getRGB(x, y);
            if ((color >> 16 & 255) >= 210 && (color >> 8 & 255) >= 210 && (color & 255) >= 210) visible++;
        }
        if (visible < 5) throw new IllegalStateException("GUI scale clipped the video title: " + visible);
    }
    private void capture() {
        capturePending = true;
        var client = Minecraft.getInstance();
        Consumer<NativeImage> writer = image -> {
            try (image) {
                String version = FabricLoader.getInstance().getModContainer("minecraft").orElseThrow().getMetadata().getVersion().getFriendlyString();
                Path directory = Path.of(System.getProperty("lyrica.motionProbeOutput")).toAbsolutePath().normalize();
                Files.createDirectories(directory);
                Path output = directory.resolve("canvas-layout-" + version + "-" + STAGES[stage] + ".png");
                image.writeToFile(output);
                verifyCaption(output);
                FanDroidMod.LOGGER.info("Canvas/duet screenshot: {}", output);
                advance();
            } catch (Exception failure) { throw new IllegalStateException("Canvas screenshot failed", failure); }
        };
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), writer);
    }
    @Override public void removed() { overlay.close(); artwork.close(); }
    @Override public boolean shouldCloseOnEsc() { return false; }
}
