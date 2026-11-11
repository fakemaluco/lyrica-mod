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
    private static final boolean IDLE = Boolean.getBoolean("lyrica.islandIdleSelfTest");
    private long idleStageStarted;
    private boolean liveDragPrimed;
    private long autoplaySequence,autoplayHash;
    private double nearSpringPeak;
    private static final boolean DOCK = IDLE || Boolean.getBoolean("lyrica.popupDockSelfTest");
    private boolean pendingDockClose, dockStagePrimed;
    private static final boolean ADAPTIVE = Boolean.getBoolean("lyrica.adaptivePopupSelfTest");
    private static final boolean SLIDERS = Boolean.getBoolean("lyrica.slidersSelfTest");
    private static final boolean TOOLBAR = SLIDERS || Boolean.getBoolean("lyrica.toolbarSelfTest");
    private static final boolean GLASS = TOOLBAR || Boolean.getBoolean("lyrica.glassButtonsSelfTest");
    private int glassX = -1, glassY = -1;
    private int[] romanPoint, translationPoint;
    private boolean sliderPrimed;
    private double sliderX = -100, sliderY = -100;
    private LyricsOverlay.SliderSnapshot sliderBefore;
    private double sliderExpectedVolume;
    private static final String[] STAGES = IDLE ? new String[]{"named","art-only-photo","hover","after-hover-4s","after-hover-5s","new-song","undocked","return-target","redocked","art-only-video","reduced","docking-disabled","playing-over-5s","paused-again","resumed","held-dock","held-pull-out","held-return","released-island","held-again","held-out-again","released-popup","held-disabled","video-paused-normal","video-collapsed-a","video-collapsed-b","video-hover-paused-a","video-hover-paused-b","video-no-animation","video-recollapsed","video-album-a","video-album-b","video-disabled","spring-near-before","spring-near-return","spring-far-before","spring-far-return","spring-restore-pop","spring-closed","spring-hover-open","spring-hover-close","spring-no-animation"} : DOCK ? new String[]{"dock-open","dock-left","dock-hover","dock-seek","dock-grip-nudge","dock-restored","dock-right","dock-top","dock-bottom","dock-gui3","dock-disabled","dock-unknown","dock-reduced","island-long-title","island-close","island-closed-idle","island-reopen","island-restore-button","island-button-restored","island-paused","island-stopped","island-resumed","island-silence","island-stale","island-kawarp","island-kawarp-hover","island-video","island-video-hover","island-album-video","island-photo-fallback","island-inventory-closing","island-inventory-hidden","island-inventory-returning","island-inventory-restored","island-black-default","island-photo-full-height-hover","island-video-full-height-hover","popup-3d-hover","popup-3d-enabled-hover","island-settings-hover"} : ADAPTIVE ? new String[]{"adaptive-video", "adaptive-photo-lyrics", "adaptive-photo-player", "adaptive-text-player", "adaptive-minimum", "adaptive-grow", "adaptive-landscape", "adaptive-live", "adaptive-solo-video", "adaptive-solo-photo", "adaptive-gui3"} : SLIDERS ? new String[]{"sliders-idle", "sliders-volume-hover", "sliders-volume-drag", "sliders-muted", "sliders-progress-hover", "sliders-progress-drag", "sliders-progress-end", "sliders-compact", "sliders-landscape", "sliders-square", "sliders-static", "sliders-no-motion", "sliders-live"} : TOOLBAR ? new String[]{"toolbar-idle", "toolbar-roman-on", "toolbar-roman-press", "toolbar-original", "toolbar-no-translation", "toolbar-compact", "toolbar-latin", "toolbar-square", "toolbar-return", "toolbar-landscape"} : GLASS ? new String[]{"glass-idle", "glass-hover", "glass-press", "glass-release", "glass-reduced", "glass-compact"} : new String[]{"static", "portrait", "portrait-hover", "landscape", "landscape-hover", "gui3", "gui3-hover", "failed", "cleared", "resize-video", "solo", "solo-hover", "resize-solo", "lyrics-return"};
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
        preferences.setModernMode(true); preferences.setPopupDocked(false); preferences.setLyricsAllowGuiOverlap(true);
        preferences.setLyricsX(72); preferences.setLyricsY(132);
        preferences.setLyricsWidth(620); preferences.setLyricsHeight(320);
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
        if(IDLE){if(Boolean.getBoolean("lyrica.islandSpringOnly"))stage=33;else if(Boolean.getBoolean("lyrica.islandVideoOnly"))stage=23;else if(Boolean.getBoolean("lyrica.islandLiveDragOnly"))stage=15;configureIdleStage();} else if (DOCK) { if(Boolean.getBoolean("lyrica.islandControlsSelfTest")) stage=37; configureDockStage(); }
        if (ADAPTIVE) configureAdaptiveStage();
        if (GLASS) {
             var client = Minecraft.getInstance(); client.options.guiScale().set(1); client.resizeGui(); 
            preferences.setLyricsWidth(680); preferences.setLyricsHeight(400);
            artwork.configureMotion(video.resolveSibling("motion-portrait-fixture.mp4").toUri().toString(), null, true, false, false, true);
        }
    }
    public static void start() { Minecraft.getInstance().gui.setScreen(new CanvasLayoutProbe()); }
    @Override public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        if (stage >= STAGES.length) return;
        if(IDLE){renderIdle(context);return;}
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
        if(DOCK && overlay.dockFrameSnapshot()!=null && (stage==2 || stage==3 || stage==4 || stage==12 || stage==13 || stage==16 || stage==17)) {
            var dock=overlay.dockFrameSnapshot(); px=dock.bounds().centerX(); py=dock.bounds().y()+dock.bounds().height()*.25;
        }
        if(DOCK && stage==14 && overlay.dockFrameSnapshot()!=null) {
            var dock=overlay.dockFrameSnapshot(); px=dock.contents().centerX(); py=dock.contents().y()+12*ResponsiveLyricsLayout.scale(width,height);
        }
        if(DOCK && stage!=22 && stage!=23) overlay.setIslandAudioLevels(dev.relaylyrics.protocol.music.MusicAudioLevels.fromMeter(2000,5500,9000,3500,7500,System.nanoTime()));
        if(DOCK && stage>=30 && stage<=33) overlay.renderHudTransition(context,stage==31?0:stage==33?1:.5,true);
        else overlay.renderHudOverScreen(context, px, py);
    }
    @Override public void tick() {
        if (stage >= STAGES.length || capturePending) return;
        if (++totalTicks > 1200) throw new IllegalStateException("Canvas layout probe timed out: " + STAGES[stage]);
        if(IDLE){overlay.tick();ticks++;tickIdle();return;}
        if(DOCK) { overlay.tick(); ticks++; tickDock(); return; }
        if (++ticks < 34) return;
        if (ADAPTIVE) { tickAdaptive(); return; }
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
                assertCloseRow(box, gs);
                int[] closePoint = findUtility(LyricsOverlay.PopupControl.CLOSE, box);
                if (available && closePoint[0] <= translationPoint[0])
                    throw new IllegalStateException("Close is not after the language controls in the toolbar");
                if (stage == 3 && preferences.isLyricsRomanization()) throw new IllegalStateException("Romanization toggle did not change the lyrics preference");
                if (stage == 4 && preferences.isLyricsTranslation()) throw new IllegalStateException("Translation toggle did not change the lyrics preference");
                if (stage == 7 && overlay.popupPresentation()!=dev.relaylyrics.client.music.AdaptivePopupLayout.Mode.VIDEO_ONLY) throw new IllegalStateException("No-lyrics video lost its square layout");
            }
            idleBounds = box;
            capture(); return;
        }
        if (stage >= 1 && stage <= 6 && artwork.motionCoverAspectRatio() <= 0) return;
        var bounds = overlay.popupBoundsSnapshot(width, height);
        if (bounds.x() < 0 || bounds.y() < 0 || bounds.x() + bounds.width() > width || bounds.y() + bounds.height() > height)
            throw new IllegalStateException("Expanded Canvas popup left the viewport");
        double scale = ResponsiveLyricsLayout.scale(width, height);
        int normalWidth = (int) Math.round(620 * scale);
        int targetWidth = (int) Math.round(overlay.expandedPopupWidth(overlay.popupStoredWidth(), overlay.popupStoredHeight()) * scale);
        targetWidth = Math.min(targetWidth, width - 2 * Math.max(2, (int) Math.round(12 * scale)));
        if (Math.abs(bounds.width() - targetWidth) > 1) return;
        if (stage >= 1 && stage <= 6 && Math.abs(bounds.width()-normalWidth)>2) throw new IllegalStateException("Canvas changed the chosen outer frame width");
        if (stage == 7 || stage == 8) if (Math.abs(bounds.width() - normalWidth) > 2) throw new IllegalStateException("Static fallback did not restore saved width");
        if (stage == 9 || stage == 12) {
            if (!resizeStarted) {
                resizeStarted = true;
                squareSavedWidth = preferences.getLyricsWidth();
                resizeExpectedWidth = bounds.width() + 12;
                resizeExpectedHeight = bounds.height() + 8;
                if (!overlay.isPopupResizeHandleAt(bounds.x() + bounds.width() - 1, bounds.y() + bounds.height() - 1)
                        || !overlay.mousePressed(bounds.x() + bounds.width() - 1, bounds.y() + bounds.height() - 1, com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT))
                    throw new IllegalStateException("Video intercepted the resize handle");
                overlay.mouseDragged(bounds.x() + bounds.width() + 11, bounds.y() + bounds.height() + 7);
                return;
            }
            if (Math.abs(bounds.width() - resizeExpectedWidth) > 2 || Math.abs(bounds.height() - resizeExpectedHeight) > 2)
                throw new IllegalStateException("Resize grip drifted from pointer: " + bounds + " expected " + resizeExpectedWidth + "x" + resizeExpectedHeight);
            if (stage == 12 && preferences.getLyricsWidth() != squareSavedWidth) throw new IllegalStateException("Square resize overwrote lyrics width");
            overlay.mouseReleased();
        }
        if (stage >= 10 && stage <= 12 && overlay.popupPresentation()!=dev.relaylyrics.client.music.AdaptivePopupLayout.Mode.VIDEO_ONLY)
            throw new IllegalStateException("Video without lyrics did not fill the chosen frame");
        if (STAGES[stage].endsWith("hover")) {
            if (idleBounds == null || Math.abs(idleBounds.width() - bounds.width()) > 2 || Math.abs(idleBounds.height() - bounds.height()) > 2)
                throw new IllegalStateException("Hover resized the Canvas artwork: " + idleBounds + " -> " + bounds);
            boolean playback = false, lyricsSeek = false;
            for (int y = bounds.y(); y < bounds.y() + bounds.height(); y++) for (int x = bounds.x(); x < bounds.x() + bounds.width(); x++) {
                playback |= overlay.popupControlAt(x, y) == LyricsOverlay.PopupControl.PLAY_PAUSE;
                lyricsSeek |= overlay.seekTargetAtHud(x, y).isPresent();
            }
            if (!playback || overlay.popupPresentation().lyrics() && !lyricsSeek) throw new IllegalStateException("Canvas hover lost playback or blocked lyric seeking");
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

    private int[] utilityArea(LyricsOverlay.PopupControl control, LyricsOverlay.PopupBounds box) {
        int left=Integer.MAX_VALUE, top=Integer.MAX_VALUE, right=-1, bottom=-1;
        for(int y=box.y();y<box.y()+box.height();y++) for(int x=box.x();x<box.x()+box.width();x++) {
            if(overlay.popupControlAt(x,y)!=control) continue;
            left=Math.min(left,x);top=Math.min(top,y);right=Math.max(right,x);bottom=Math.max(bottom,y);
        }
        if(right<0) throw new IllegalStateException("Missing utility: "+control);
        return new int[]{left,top,right-left+1,bottom-top+1};
    }
    private void assertCloseRow(LyricsOverlay.PopupBounds box,double scale) {
        var settings=utilityArea(LyricsOverlay.PopupControl.SETTINGS,box);
        var close=utilityArea(LyricsOverlay.PopupControl.CLOSE,box);
        if(Math.abs(close[1]-settings[1])>1 || close[2]!=settings[2] || close[3]!=settings[3]
                || close[2]>24*scale+2 || close[0]<=settings[0])
            throw new IllegalStateException("Close is oversized or not aligned with settings");
    }


    private void configureIdleStage(){
        idleStageStarted=System.nanoTime();ticks=0;liveDragPrimed=false;
        var client=Minecraft.getInstance();
        if(stage==0){client.options.guiScale().set(1);client.resizeGui(); client.options.pauseOnLostFocus=false;
            preferences.setPopupEdgeDocking(true);preferences.setLyricsAnimations(true);
            preferences.setLyricsWidth(300);preferences.setLyricsHeight(180);preferences.setLyricsX(stage==19?ResponsiveLyricsLayout.design(width/2.,ResponsiveLyricsLayout.scale(width,height))-150:60);preferences.setLyricsY(stage==19?0:160);
            overlay.minimizePopup();
        }
        if(stage==5)overlay.setPlayback(new PlaybackSnapshot("Outra música · espera renovada","Lyrica","",null,"spotify:track:anotherIdleFixture","Fixture",3000,60000,false,.65,false,"off",false,.4,0,110,.5,new double[0],Instant.now(),MusicCapabilities.unavailable()));
        if(stage==6)overlay.restoreMinimizedPopup();
        if(stage==7 || stage==11){
            if(stage==11){overlay.restoreMinimizedPopup();preferences.setPopupEdgeDocking(false);}
            var box=overlay.popupBoundsSnapshot(width,height);
            if(!overlay.mousePressed(box.x()+box.width()/2.,box.y()+box.height()/2., com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT))throw new IllegalStateException("Could not start popup drag");
            overlay.mouseDragged(width/2.,8*ResponsiveLyricsLayout.scale(width,height));
            if(stage==11)overlay.mouseReleased();
        }
        if(stage==8)overlay.mouseReleased();
        if(stage==9){preferences.setIslandVideoCover(true);artwork.setIslandMotionEnabled(true);artwork.configureMotion(video.resolveSibling("motion-portrait-fixture.mp4").toUri().toString(),null,false,false,false,true);}
        if(stage==15 || stage==19){
            client.options.guiScale().set(1);client.resizeGui(); client.options.pauseOnLostFocus=false;
            preferences.setPopupEdgeDocking(true);preferences.setLyricsAnimations(true);
            preferences.setLyricsWidth(300);preferences.setLyricsHeight(180);preferences.setLyricsX(stage==19?ResponsiveLyricsLayout.design(width/2.,ResponsiveLyricsLayout.scale(width,height))-150:60);preferences.setLyricsY(stage==19?0:160);
            overlay.restoreMinimizedPopup();
        }
        if(stage==16 || stage==20)overlay.mouseDragged(width*.75,height*.55);
        if(stage==17)overlay.mouseDragged(width/2.,8*ResponsiveLyricsLayout.scale(width,height));
        if(stage==18 || stage==21)overlay.mouseReleased();
        if(stage==22){
            preferences.setPopupEdgeDocking(false);var box=overlay.popupBoundsSnapshot(width,height);
            if(!overlay.mousePressed(box.x()+box.width()/2.,box.y()+box.height()/2., com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT))throw new IllegalStateException("Could not start disabled dock drag");
            overlay.mouseDragged(width/2.,8*ResponsiveLyricsLayout.scale(width,height));
        }
        if(stage==10)preferences.setLyricsAnimations(false);
        if(stage==12){preferences.setPopupEdgeDocking(true);preferences.setLyricsAnimations(true);overlay.minimizePopup();}
        if(stage>=12 && (stage<23 || stage>=33))overlay.setPlayback(new PlaybackSnapshot("Reprodução mantém o nome","Lyrica","",null,"spotify:track:playingIdleFixture","Fixture",3000,60000,stage!=13,.65,false,"off",false,.4,0,110,.5,new double[0],Instant.now(),MusicCapabilities.unavailable()));
        if(stage==23){
            client.options.guiScale().set(1);client.resizeGui(); client.options.pauseOnLostFocus=false;
            preferences.setPopupEdgeDocking(true);preferences.setLyricsAnimations(true);preferences.setIslandVideoCover(true);
            artwork.setIslandMotionEnabled(true);artwork.configureMotion(video.resolveSibling("motion-portrait-fixture.mp4").toUri().toString(),null,false,false,false,true);
            overlay.minimizePopup();
        }
        if(stage>=23 && stage<=32){
            overlay.setPlayback(new PlaybackSnapshot("Vídeo na cápsula","Lyrica","",null,"spotify:track:islandAutoplayFixture","Fixture",3000,60000,false,.65,false,"off",false,.4,0,110,.5,new double[0],Instant.now(),MusicCapabilities.unavailable()));
            artwork.setMotionPlaying(false);
        }
        if(stage==28)preferences.setLyricsAnimations(false);
        if(stage==29)preferences.setLyricsAnimations(true);
        if(stage==30)artwork.configureMotion(null,video.toUri().toString(),false,false,false,true);
        if(stage==32)preferences.setIslandVideoCover(false);
        if(stage==33 || stage==35){
            client.options.guiScale().set(1);client.resizeGui(); client.options.pauseOnLostFocus=false;
            preferences.setPopupEdgeDocking(true);preferences.setLyricsAnimations(true);
            preferences.setLyricsWidth(210);preferences.setLyricsHeight(130);
            int sw=client.getWindow().getGuiScaledWidth(),sh=client.getWindow().getGuiScaledHeight();double gs=ResponsiveLyricsLayout.scale(sw,sh);
            preferences.setLyricsX(stage==33?ResponsiveLyricsLayout.design(sw/2.,gs)-105:ResponsiveLyricsLayout.design(sw*.72,gs));
            preferences.setLyricsY(stage==33?20:ResponsiveLyricsLayout.design(sh*.65,gs));
            overlay.restoreMinimizedPopup();
        }
        if(stage==34 || stage==36 || stage==38)overlay.minimizePopup();
        if(stage==37)overlay.restoreMinimizedPopup();
        if(stage==41){preferences.setLyricsAnimations(false);overlay.restoreMinimizedPopup();}

    }
    private void renderIdle(GuiGraphicsExtractor context){
        context.fill(0,0,width,height,0xFF0B0E13);context.text(font, STAGES[stage], 20, 100, 0xFFCBC0DC, false);
        double px=-100,py=-100;
        var frame=overlay.dockFrameSnapshot();
        if((stage==2 || stage==10 || stage==26 || stage==27 || stage==39) && frame!=null){px=frame.bounds().centerX();py=frame.bounds().centerY();}
        if(stage>=23 && stage<=32)artwork.setMotionPlaying(false);
        overlay.renderHudOverScreen(context,px,py);
    }
    private void tickIdle(){
        long elapsed=System.nanoTime()-idleStageStarted;
        long wait=switch(stage){case 1,9,13,24,29->5_800_000_000L;case 25,27,31->650_000_000L;case 28->1_000_000_000L;case 12->6_000_000_000L;case 34,36,37,39,40->100_000_000L;case 3->4_000_000_000L;case 4->1_800_000_000L;default->800_000_000L;};
        if(elapsed<wait)return;
        if((stage==15 || stage==19) && !liveDragPrimed){
            var box=overlay.popupBoundsSnapshot(width,height);
            double gs=ResponsiveLyricsLayout.scale(width,height),px=box.x()+box.width()/2.,py=stage==19?box.y()+12*gs:box.y()+box.height()/2.;
            if(!overlay.mousePressed(px,py, com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT))throw new IllegalStateException("Could not start held popup drag");
            if(stage==19){overlay.mouseDragged(px+10*gs,py);overlay.mouseDragged(px,py);}
            else overlay.mouseDragged(width/2.,8*gs);
            liveDragPrimed=true;idleStageStarted=System.nanoTime();return;
        }
        var frame=overlay.dockFrameSnapshot();
        if(stage>=33){
            double s=ResponsiveLyricsLayout.scale(width,height)*dev.relaylyrics.client.music.PopupDockLayout.PRESENTATION_SCALE;
            if(stage==34 || stage==36){
                double minimum=(stage==34?1.002:1.03)*dev.relaylyrics.client.music.PopupDockLayout.IDLE_WIDTH*s;
                if(frame==null || frame.folded()<.999 || frame.bounds().width()<minimum){if(elapsed<1_000_000_000L)return;throw new IllegalStateException("Arrival bounce was not rendered");}
                if(stage==34)nearSpringPeak=frame.bounds().width()/s;
                else if(frame.bounds().width()/s<nearSpringPeak+2)throw new IllegalStateException("Far return did not bounce more than near return");
            }
            if(stage==37){
                if(preferences.isPopupDocked())throw new IllegalStateException("Restore failed");
                if(frame==null || frame.bounds().width()<211*ResponsiveLyricsLayout.scale(width,height)){if(elapsed<1_000_000_000L)return;throw new IllegalStateException("Restore pop was clipped");}
            }
            if(stage==39 && (frame==null || frame.bounds().width()<dev.relaylyrics.client.music.PopupDockLayout.EXPANDED_WIDTH*s+.5)){if(elapsed<1_000_000_000L)return;throw new IllegalStateException("Island opening rebound was clipped");}
            if(stage==40 && (frame==null || frame.bounds().height()>dev.relaylyrics.client.music.PopupDockLayout.IDLE_HEIGHT*s-.5)){if(elapsed<1_000_000_000L)return;throw new IllegalStateException("Island closing rebound was clipped");}
            var b=overlay.popupBoundsSnapshot(width,height);
            if(b.x()<0 || b.y()<0 || b.x()+b.width()>width || b.y()+b.height()>height)throw new IllegalStateException("Spring escaped viewport");
            if(stage==41 && (preferences.isPopupDocked() || frame!=null))throw new IllegalStateException("Disabled animations retained bounce");
            capturePending=true;captureIdle();return;
        }
        if(stage>=23 && stage<=32){
            if(!preferences.isPopupDocked() || frame==null)throw new IllegalStateException("Autoplay fixture lost island");
            boolean noVideo=stage==28 || stage==32;
            if(noVideo){if(artwork.islandFrameSequence()!=0)throw new IllegalStateException("Disabled animation/video still draws motion");}
            else {
                if(artwork.islandFrameSequence()==0){if(ticks<240)return;throw new IllegalStateException("Island video never became ready");}
                if((stage==24 || stage==25 || stage==29 || stage==30 || stage==31) && frame.titlePresence()>.01)throw new IllegalStateException("Video fixture did not collapse");
                if((stage==25 || stage==31) && artwork.islandFrameSequence()<=autoplaySequence+1)throw new IllegalStateException("Collapsed video stayed paused");
                if(stage==27 && artwork.islandFrameSequence()!=autoplaySequence)throw new IllegalStateException("Hover failed to restore music pause");
            }
            capturePending=true;captureIdle();return;
        }
        if(stage>=15){
            boolean expectedDock=stage==15 || stage==17 || stage==18 || stage==19;
            boolean expectedHeld=stage!=18 && stage!=21;
            if(preferences.isPopupDocked()!=expectedDock || overlay.isPopupDragActive()!=expectedHeld)throw new IllegalStateException("Held drag lost capture or changed state incorrectly: "+STAGES[stage]);
            if(expectedDock && (frame==null || frame.folded()<.98 || frame.hover()>.02))throw new IllegalStateException("Held island expanded controls or failed to fold");
            capturePending=true;captureIdle();return;
        }
        if(stage==6 || stage==11){if(preferences.isPopupDocked())throw new IllegalStateException("Unexpected docking while restored/disabled");}
        else if(stage==7){if(!overlay.isPopupDragActive() || !preferences.isPopupDocked())throw new IllegalStateException("Popup did not fold while still held");}
        else {
            if(!preferences.isPopupDocked() || frame==null)throw new IllegalStateException("Missing island");
            if(stage==1 || stage==4 || stage==9 || stage==13){
                if(frame.titlePresence()>.01 || Math.abs(frame.bounds().width()-dev.relaylyrics.client.music.PopupDockLayout.MEDIA_WIDTH*ResponsiveLyricsLayout.scale(width,height)*dev.relaylyrics.client.music.PopupDockLayout.PRESENTATION_SCALE)>1)throw new IllegalStateException("Island did not collapse to media only after 5 seconds");
            }else if(frame.titlePresence()<.99)throw new IllegalStateException("Island did not reveal/hold title");
            if(stage==2 || stage==10){if(frame.hover()<.99)throw new IllegalStateException("Hover did not expand island");}
            if(stage==3 && frame.hover()>.01)throw new IllegalStateException("Leaving hover did not restore normal island");
            if(stage==9 && !artwork.islandVideoReady())throw new IllegalStateException("Media-only island did not retain video");
        }
        capturePending=true;captureIdle();
    }
    private void captureIdle(){
        var client=Minecraft.getInstance();
        Consumer<NativeImage> writer=image->{try(image){
            String version=FabricLoader.getInstance().getModContainer("minecraft").orElseThrow().getMetadata().getVersion().getFriendlyString();
            Path folder=Path.of(System.getProperty("lyrica.motionProbeOutput"));Files.createDirectories(folder);
            Path file=folder.resolve("island-interaction-"+version+"-"+STAGES[stage]+".png");image.writeToFile(file);
            if(stage==24 || stage==25 || stage==26 || stage==27 || stage==30 || stage==31){
                var pixels=javax.imageio.ImageIO.read(file.toFile());var box=overlay.dockFrameSnapshot().bounds();long hash=1;
                for(int y=(int)box.y()+8;y<box.bottom()-8;y+=2)for(int x=(int)box.x()+8;x<Math.min(box.right()-8,box.x()+48);x+=2)hash=hash*31+pixels.getRGB(x,y);
                if((stage==25 || stage==31) && hash==autoplayHash)throw new IllegalStateException("Decoded frames did not change rendered island pixels");
                if(stage==27 && hash!=autoplayHash)throw new IllegalStateException("Paused hover video pixels kept changing");
                autoplayHash=hash;autoplaySequence=artwork.islandFrameSequence();
            }
            if(stage==1 || stage==9){
                var pixels=javax.imageio.ImageIO.read(file.toFile());var box=overlay.dockFrameSnapshot().bounds();int colored=0;
                for(int y=(int)box.y()+4;y<box.bottom()-4;y++)for(int x=(int)box.x()+4;x<box.right()-4;x++){int c=pixels.getRGB(x,y);if(Math.max(c>>16&255,Math.max(c>>8&255,c&255))>40)colored++;}
                if(colored<20)throw new IllegalStateException("Media-only island is blank");
            }
            FanDroidMod.LOGGER.info("Island idle/redock native {} verified: {}",STAGES[stage],file);
            if(++stage==STAGES.length){artwork.close();FanDroidMod.LOGGER.info("Island idle/redock native probe passed");client.stop(); return;}
            capturePending=false;configureIdleStage();
        }catch(Exception e){throw new IllegalStateException(e);}};
                Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), writer);
    }

    private void configureDockStage() {
        dockStagePrimed=false;
        if(stage==0) {
            overlay.setPlayback(new PlaybackSnapshot("Dueto com Canvas", "Lyrica · verificação visual", "", null, "spotify:track:islandFixture", "Fixture",3_000,60_000,true,.65,false,"off",false,.4,0,110,.5,new double[0],Instant.now(),MusicCapabilities.unavailable()));
            preferences.setLyricsWidth(210); preferences.setLyricsHeight(130); preferences.setPopupWidth(true,false,210); preferences.setPopupHeight(true,false,130);
            preferences.setLyricsX(24); preferences.setLyricsY(220); preferences.setPopupEdgeDocking(true); preferences.setPopupDocked(false); preferences.setLyricsAnimations(true);
        }
        if(stage==1) pendingDockClose=true;
        if(stage==6 || stage==7 || stage==8 || stage==11) {
            preferences.setPopupEdgeDocking(true); preferences.setPopupDocked(false); preferences.setLyricsAnimations(false);
            double gs=ResponsiveLyricsLayout.scale(width,height);
            int dw=ResponsiveLyricsLayout.design(width,gs),dh=ResponsiveLyricsLayout.design(height,gs);
            preferences.setLyricsX(stage==6?dw-222:stage==11?12:dw/2-105);
            preferences.setLyricsY(stage==7?8:stage==8?dh-138:dh/2-65);
            pendingDockClose=true;
        }
        if(stage==9) { var client=Minecraft.getInstance(); client.options.guiScale().set(3); client.resizeGui(); }
        if(stage==10) preferences.setPopupEdgeDocking(false);
        if(stage==11) {
            var client=Minecraft.getInstance(); client.options.guiScale().set(1); client.resizeGui();
            overlay.setPlayback(new PlaybackSnapshot("Live stream", "Local demo", "", null, "spotify:track:dockLive", "Fixture",0,0,false,.65,false,"off",false,.4,0,110,.5,new double[0],Instant.now(),MusicCapabilities.unavailable()));
        }
        if(stage==12) preferences.setLyricsAnimations(false);
        if(stage==13 || stage==19) {
            preferences.setLyricsAnimations(true);
            overlay.setPlayback(new PlaybackSnapshot(stage==13?"Uma música muito longa com 日本語 e palavras que precisam caber na ilha":"Dueto com Canvas", "Lyrica · verificação visual", "", null, "spotify:track:islandFixture", "Fixture",3_000,60_000,stage!=19,.65,false,"off",false,.4,0,110,.5,new double[0],Instant.now(),MusicCapabilities.unavailable()));
            if(stage==19) overlay.minimizePopup();
        }
        if(stage==14) overlay.togglePopupUtility(LyricsOverlay.PopupControl.CLOSE);
        if(stage==18) overlay.togglePopupUtility(LyricsOverlay.PopupControl.RESTORE);
        if(stage==20) overlay.setPlayback(PlaybackSnapshot.unavailable());
        if(stage==21) overlay.setPlayback(new PlaybackSnapshot("Dueto com Canvas", "Lyrica · verificação visual", "", null, "spotify:track:islandFixture", "Fixture",3_000,60_000,true,.65,false,"off",false,.4,0,110,.5,new double[0],Instant.now(),MusicCapabilities.unavailable()));
        if(stage==22) overlay.setIslandAudioLevels(dev.relaylyrics.protocol.music.MusicAudioLevels.fromMeter(0,0,0,0,0,System.nanoTime()));
        if(stage==23) overlay.setIslandAudioLevels(dev.relaylyrics.protocol.music.MusicAudioLevels.fromMeter(9000,9000,9000,9000,9000,System.nanoTime()));
        if(stage==24) preferences.setIslandKawarpBackground(true);
        if(stage==26 || stage==28) {
            preferences.setIslandVideoCover(true);artwork.setIslandMotionEnabled(true);
            artwork.configureMotion(stage==26?video.resolveSibling("motion-portrait-fixture.mp4").toUri().toString():null,
                    stage==28?video.toUri().toString():null,false,false,false,true);
        }
        if(stage==29) { artwork.clearMotion();artwork.setIslandMotionEnabled(false); }
        if(stage==34) { preferences.setIslandKawarpBackground(false);preferences.setIslandVideoCover(false); }
        if(stage==36) { preferences.setIslandVideoCover(true);artwork.setIslandMotionEnabled(true);artwork.configureMotion(video.resolveSibling("motion-portrait-fixture.mp4").toUri().toString(),null,false,false,false,true); }
        if(stage==37) {
            preferences.setPopupDocked(false);preferences.setLyricsAnimations(false);preferences.setWorldLyrics(false);
            preferences.setPopupWidth(true,false,380);preferences.setPopupHeight(true,false,280);
            artwork.configureMotion(video.resolveSibling("motion-portrait-fixture.mp4").toUri().toString(),null,true,false,false,true);
        }
        if(stage==38) overlay.togglePopupUtility(LyricsOverlay.PopupControl.TOGGLE_WORLD_LYRICS);
        if(stage==39) {
            preferences.setPopupDocked(true);preferences.setLyricsAnimations(true);preferences.setWorldLyrics(false);
            artwork.configureMotion(video.resolveSibling("motion-portrait-fixture.mp4").toUri().toString(),null,false,false,false,true);
        }
    }
    private void tickDock() {
        if(pendingDockClose && ticks>=3) {
            var box=overlay.popupBoundsSnapshot(width,height);
            if(findUtility(LyricsOverlay.PopupControl.CLOSE,box)==null) throw new IllegalStateException("Close button has no click target");
            overlay.togglePopupUtility(LyricsOverlay.PopupControl.CLOSE); pendingDockClose=false; preferences.setLyricsAnimations(true); ticks=0; return;
        }
        if(pendingDockClose || ticks<20) return;
        var box=overlay.popupBoundsSnapshot(width,height);
        if(stage==37 || stage==38) {
            if(artwork.motionCoverAspectRatio()<=0 || ticks<40) return;
            if(findUtility(LyricsOverlay.PopupControl.TOGGLE_WORLD_LYRICS,box)==null) {
                if(ticks<120) return;
                throw new IllegalStateException("Popup 3D control is missing after video readiness: "+box);
            }
            if(preferences.isWorldLyrics()!=(stage==38)) throw new IllegalStateException("Popup 3D control did not toggle world lyrics");
            capture();return;
        }
        if(stage==39) {
            var island=overlay.dockFrameSnapshot();
            if(island==null || island.hover()<.99) return;
            if(findUtility(LyricsOverlay.PopupControl.SETTINGS,box)==null) throw new IllegalStateException("Island settings control is missing");
            var button=dev.relaylyrics.client.music.PopupDockLayout.settings(island.contents(),ResponsiveLyricsLayout.scale(width,height)*dev.relaylyrics.client.music.PopupDockLayout.PRESENTATION_SCALE);
            if(overlay.popupControlAt(button.centerX(),button.centerY())!=LyricsOverlay.PopupControl.SETTINGS) throw new IllegalStateException("Island settings hit target overlaps restore");
            capture();return;
        }
        if(stage==30 || stage==31 || stage==32 || stage==33) {
            if(findUtility(LyricsOverlay.PopupControl.RESTORE,box)!=null || findUtility(LyricsOverlay.PopupControl.PLAY_PAUSE,box)!=null)
                throw new IllegalStateException("Inventory transition published invisible click targets");
            capture(); return;
        }
        if(stage==26 || stage==27 || stage==28 || stage==36) {
            if(!artwork.islandVideoReady()) return;
            if(artwork.motionCoverAspectRatio()!=0) throw new IllegalStateException("Island-only video also enabled the full popup video profile");
        }
        if(stage==0) { assertCloseRow(box,ResponsiveLyricsLayout.scale(width,height)); capture(); return; }
        if(stage==5 || stage==10 || stage==18) {
            if(preferences.isPopupDocked()) throw new IllegalStateException("Popup failed to restore");
            if(stage==10 && findUtility(LyricsOverlay.PopupControl.CLOSE,box)!=null) throw new IllegalStateException("Disabled docking still shows close");
            capture(); return;
        }
        var frame=overlay.dockFrameSnapshot();
        if(!preferences.isPopupDocked() || frame==null || frame.folded()<.99) return;
        if(box.x()<0 || box.y()<0 || box.x()+box.width()>width || box.y()+box.height()>height) throw new IllegalStateException("Dock left the viewport");
        var expected=dev.relaylyrics.client.music.PopupDockLayout.Edge.TOP;
        if(expected!=null && preferences.getPopupDockEdge()!=expected) throw new IllegalStateException("Wrong dock edge: "+preferences.getPopupDockEdge());
        if(!frame.horizontal() || Math.abs(frame.bounds().centerX()-width/2.0)>1) throw new IllegalStateException("Rail is not fixed at the top center");
        if(frame.grip().bottom()>=frame.track().y()) throw new IllegalStateException("Restore overlaps the playback line");
        if(stage==1 && frame.bounds().height()>33*ResponsiveLyricsLayout.scale(width,height)+1) throw new IllegalStateException("Compact island is too tall");
        if((stage==1 || stage==19) && (findUtility(LyricsOverlay.PopupControl.RESTORE,box)!=null || overlay.beginPopupProgressScrub(frame.track().centerX(),frame.track().centerY()))) throw new IllegalStateException("Compact island has invisible interactive targets");
        if((stage==2 || stage==13 || stage==16 || stage==17) && frame.hover()<.99) return;
        if(stage==2 || stage==13 || stage==16 || stage==17) {
            for(var control:new LyricsOverlay.PopupControl[]{LyricsOverlay.PopupControl.PREVIOUS,LyricsOverlay.PopupControl.PLAY_PAUSE,LyricsOverlay.PopupControl.NEXT,LyricsOverlay.PopupControl.RESTORE,LyricsOverlay.PopupControl.SETTINGS,LyricsOverlay.PopupControl.CLOSE})
                if(findUtility(control,box)==null) throw new IllegalStateException("Hovered dock lacks playback control: "+control);
        }
        if(stage==3 && !dockStagePrimed) {
            if(!overlay.beginPopupProgressScrub(frame.horizontal()?frame.track().x()+frame.track().width()*.25:frame.track().centerX(),frame.horizontal()?frame.track().centerY():frame.track().y()+frame.track().height()*.25)) throw new IllegalStateException("Dock rail seek did not start");
            overlay.updatePopupProgressScrub(frame.horizontal()?frame.track().x()+frame.track().width()*.75:frame.track().centerX(),frame.horizontal()?frame.track().centerY():frame.track().y()+frame.track().height()*.75); dockStagePrimed=true; return;
        }
        if(stage==4 && !dockStagePrimed) {
            var grip=frame.grip();
            if(!overlay.mousePressed(grip.centerX(),grip.centerY(), com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT)) throw new IllegalStateException("External tab did not capture drag");
            overlay.mouseDragged(grip.centerX()+2,grip.centerY());
            if(!preferences.isPopupDocked()) throw new IllegalStateException("Click or tiny drag restored the popup"); dockStagePrimed=true;
        }
        if(stage==11 && overlay.beginPopupProgressScrub(frame.horizontal()?frame.track().x()+frame.track().width()*.25:frame.track().centerX(),frame.horizontal()?frame.track().centerY():frame.track().y()+frame.track().height()*.25)) throw new IllegalStateException("Unknown-duration dock became seekable");
        if(stage==12 && frame.hover()!=1) return;
        if((stage==14 || stage==15) && frame.hover()>.01) return;
        if(stage==19 || stage==20) {
            if(frame.audioPresence()>.001) return;
            double scale=ResponsiveLyricsLayout.scale(width,height)*dev.relaylyrics.client.music.PopupDockLayout.PRESENTATION_SCALE;
            if(Math.abs(frame.bounds().width()-194*scale)>1 || overlay.wantsIslandAudio(System.nanoTime())) throw new IllegalStateException("Paused island did not contract/stop metering");
            if(stage==20 && !overlay.isHudVisible()) throw new IllegalStateException("Source disappearance hid the paused island before its transition");
        }
        if(stage==21 && (frame.audioPresence()<.99 || !overlay.wantsIslandAudio(System.nanoTime()))) return;
        if(stage==27 && frame.hover()<.99 || (stage==35 || stage==36) && frame.hover()<.99) return;
        if(stage==27 && !artwork.islandBackdropUsesVideo()) throw new IllegalStateException("Kawarp did not use the current video frame");
        if(stage==23 && ticks<32) return; // Expiration is followed by the deliberate release fade.
        capture();
    }
    private void configureAdaptiveStage() {
        int[][] sizes = {{480,280},{320,200},{210,130},{140,86},{96,64},{480,280},{560,260},{210,130},{400,260},{210,130},{96,64}};
        int[] size = sizes[stage];
        if (stage==1 || stage==5) {
            var old = overlay.popupBoundsSnapshot(width,height);
            double gs = ResponsiveLyricsLayout.scale(width,height);
            double downX = old.x()+old.width()-1, downY = old.y()+old.height()-1;
            if (!overlay.mousePressed(downX,downY, com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT)) throw new IllegalStateException("Adaptive resize handle missing");
            overlay.mouseDragged(downX+(size[0]-ResponsiveLyricsLayout.design(old.width(),gs))*gs,
                    downY+(size[1]-ResponsiveLyricsLayout.design(old.height(),gs))*gs);
            overlay.mouseReleased();
            if (Math.abs(preferences.getPopupWidth(true,false)-size[0])>1 || Math.abs(preferences.getPopupHeight(true,false)-size[1])>1)
                throw new IllegalStateException("Adaptive resize grip drifted while crossing content modes");
        }
        preferences.setLyricsWidth(size[0]); preferences.setLyricsHeight(size[1]);
        preferences.setPopupWidth(true,false,size[0]); preferences.setPopupHeight(true,false,size[1]);
        preferences.setLyricsAnimations(false); preferences.setLyricsBackgroundSpeed(0);
        preferences.setSpotifyCanvas(true); preferences.setCanvasBackground(true); preferences.setAnimatedCovers(true);
        if (stage==8 || stage==9) { overlay.clearLyrics(); overlay.markUnavailable("No synced lyrics"); }
        else overlay.setLyrics(fixtureLyrics,"WORD SYNC");
        if (stage==7) overlay.setPlayback(new PlaybackSnapshot("Live stream", "Local demo", "", null, "spotify:track:liveFixture", "Fixture", 0, 0, false, .65, false, "off", false, .4, 0, 110, .5, new double[0], Instant.now(), MusicCapabilities.unavailable()));
        else overlay.setPlayback(new PlaybackSnapshot("Minha música favorita", "Lyrica · player adaptável", "Local demo", null, "spotify:track:adaptiveFixture", "Fixture", 3_000, 60_000, false, .65, false, "off", false, .4, 0, 110, .5, new double[0], Instant.now(), MusicCapabilities.unavailable()));
        if (stage==0 || stage==6 || stage==8) {
            Path selected = stage==6 ? video : video.resolveSibling("motion-portrait-fixture.mp4");
            artwork.configureMotion(selected.toUri().toString(),null,true,false,true,true);
            artwork.setMotionPlaying(false);
        }
        var client = Minecraft.getInstance(); client.options.guiScale().set(stage==10 ? 3 : 1); client.resizeGui();
    }
    private void tickAdaptive() {
        if (artwork.motionCoverAspectRatio()<=0) return;
        var mode = overlay.popupPresentation();
        var expected = switch(stage) {
            case 0,1,2,5,6,7 -> dev.relaylyrics.client.music.AdaptivePopupLayout.Mode.VIDEO_LYRICS;
            default -> dev.relaylyrics.client.music.AdaptivePopupLayout.Mode.VIDEO_ONLY;
        };
        if (mode!=expected) { if(ticks<120) return; throw new IllegalStateException("Wrong adaptive mode at "+STAGES[stage]+": "+mode+" / "+overlay.popupBoundsSnapshot(width,height)+" / aspect="+artwork.motionCoverAspectRatio()); }
        var box = overlay.popupBoundsSnapshot(width,height);
        double gs = ResponsiveLyricsLayout.scale(width,height);
        if (stage == 0) {
            long actual = overlay.currentPositionMillis();
            preferences.setLyricsDelayMillis(375);
            if (overlay.currentLyricsPositionMillis() != actual - 375 || overlay.worldPlayback().positionMillis() != actual - 375
                    || overlay.currentPositionMillis() != actual) throw new IllegalStateException("Delay changed transport or missed a lyric renderer");
            preferences.setLyricsDelayMillis(0);
            double right = box.x() + box.width(), bottom = box.y() + box.height();
            if (!overlay.mousePressed(box.x() + 1, box.y() + 1, com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT)) throw new IllegalStateException("Top-left resize handle missing");
            overlay.mouseDragged(box.x() - 10 * gs + 1, box.y() - 8 * gs + 1);
            overlay.mouseReleased();
            var resized = overlay.popupBoundsSnapshot(width,height);
            if (Math.abs(resized.x() + resized.width() - right) > 2 || Math.abs(resized.y() + resized.height() - bottom) > 2)
                throw new IllegalStateException("Top-left resize moved the opposite corner");
            preferences.setLyricsX(24); preferences.setLyricsY(24);
            preferences.setPopupWidth(true,false,480); preferences.setPopupHeight(true,false,280);
        }
        if (Math.abs(box.width()-preferences.getPopupWidth(true,false)*gs)>1) throw new IllegalStateException("Canvas changed the outer popup width");
        if (mode.compact()) {
            for (var control : new LyricsOverlay.PopupControl[]{LyricsOverlay.PopupControl.SETTINGS,LyricsOverlay.PopupControl.PREVIOUS,LyricsOverlay.PopupControl.PLAY_PAUSE,LyricsOverlay.PopupControl.NEXT})
                if (findUtility(control,box)==null) throw new IllegalStateException("Compact control is missing: "+control);
            double px = box.x()+box.width()/2.0, py = box.y()+box.height()-Math.max(4,Math.round(8*gs));
            if (stage==7 && overlay.beginPopupProgressScrub(px,py)) throw new IllegalStateException("Unknown duration became seekable");
            if (stage==4) {
                var resized = overlay.resizePopup(120,80,96);
                if(resized.width()!=120 || resized.height()!=80) throw new IllegalStateException("Resize subtracted a video column");
            }
        }
        capture();
    }
    private void advance() {
        if(DOCK) {
            if(stage==3 && overlay.finishPopupProgressScrub().orElseThrow()!=45_000) throw new IllegalStateException("Rail seek used the wrong axis or changed during hover");
            if(stage==4) {
                var grip=overlay.dockFrameSnapshot().grip(); overlay.mouseDragged(grip.centerX()+160,grip.centerY()+140); overlay.mouseReleased();
                if(preferences.isPopupDocked()) throw new IllegalStateException("Pulling the external tab did not restore popup");
            }
            if(++stage==STAGES.length) { FanDroidMod.LOGGER.info(Boolean.getBoolean("lyrica.islandControlsSelfTest") ? "Music island controls probe passed: island settings and popup 3D toggles" : "Music island probe passed: black compact state, hover-only video Kawarp, full-height left media, centered timeline, fast audio release, inventory close/return, seek and GUI resize"); Minecraft.getInstance().stop(); return; }
            configureDockStage(); ticks=0; capturePending=false; return;
        }
        if (ADAPTIVE) {
            if (++stage==STAGES.length) { FanDroidMod.LOGGER.info("Adaptive popup probe passed: video/photo/lyrics/metadata, live stream, compact controls, stable outer frame and GUI 3"); Minecraft.getInstance().stop(); return; }
            configureAdaptiveStage(); ticks=0; capturePending=false; return;
        }
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
        if (stage == 13) { overlay.setLyrics(fixtureLyrics, "WORD SYNC"); preferences.setLyricsWidth(620); preferences.setLyricsHeight(320); }
        resizeStarted = false;
        artwork.setMotionPlaying(!STAGES[stage].endsWith("hover"));
        ticks = 0; capturePending = false;
    }
    private void verifyCaption(Path file) throws Exception {
        if(DOCK && (stage==1 || stage>=19 && stage<=23)) {
            var pixels=javax.imageio.ImageIO.read(file.toFile());var shell=overlay.dockFrameSnapshot().bounds();
            double dx=pixels.getWidth()/(double)width,dy=pixels.getHeight()/(double)height;int green=0;
            for(int y=(int)(shell.y()*dy);y<(int)(shell.bottom()*dy);y++) for(int x=(int)(shell.x()*dx);x<(int)(shell.right()*dx);x++) {
                int c=pixels.getRGB(x,y),r=c>>16&255,g=c>>8&255,b=c&255;
                if(g>70 && g>r*1.5 && g>b*1.5) green++;
            }
            boolean expected=stage==1 || stage==21;
            if(expected?green<3:green>0) throw new IllegalStateException("Audio bars do not follow signal/pause/silence/stale state: "+stage+" / "+green);
        }
        if(DOCK && stage==31) {
            var pixels=javax.imageio.ImageIO.read(file.toFile());
            int changed=0;
            for(int y=0;y<Math.min(90,pixels.getHeight());y++) for(int x=pixels.getWidth()/2-200;x<pixels.getWidth()/2+200;x++) {
                if((pixels.getRGB(x,y)&0xFFFFFF)!=0x0B0E13) changed++;
            }
            if(changed>0) throw new IllegalStateException("Hidden inventory island still paints pixels: "+changed);
        }
        if(DOCK && (stage==24 || stage==26)) {
            var pixels=javax.imageio.ImageIO.read(file.toFile());var shell=overlay.dockFrameSnapshot().bounds();
            int x=(int)((shell.centerX()+shell.width()*.25)*pixels.getWidth()/width),y=(int)((shell.bottom()-4)*pixels.getHeight()/height);
            int c=pixels.getRGB(x,y),r=c>>16&255,g=c>>8&255,b=c&255;
            if(r>24 || g>24 || b>24 || Math.abs(r-g)>2 || Math.abs(g-b)>2) throw new IllegalStateException("Compact island still shows Kawarp");
        }
        if(DOCK && (stage==1 || stage==2)) {
            var pixels=javax.imageio.ImageIO.read(file.toFile()); var shell=overlay.dockFrameSnapshot().bounds();
            double dx=pixels.getWidth()/(double)width,dy=pixels.getHeight()/(double)height;
            for(double py:new double[]{shell.y()+2,shell.bottom()-3}) {
                int c=pixels.getRGB((int)(shell.centerX()*dx),(int)(py*dy));
                int r=c>>16&255,g=c>>8&255,b=c&255;
                if(r>24 || g>24 || b>24 || Math.abs(r-g)>2 || Math.abs(g-b)>2) throw new IllegalStateException("Island shell has a tinted border or nonblack background");
            }
        }
        if (DOCK || ADAPTIVE || GLASS || stage != 1 && stage != 3 && stage != 5) return;
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
