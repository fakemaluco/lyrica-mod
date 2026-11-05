package dev.relaylyrics.client.ui;

import dev.relaylyrics.FanDroidMod;
import dev.relaylyrics.client.music.LyricsAnimation;
import dev.relaylyrics.client.music.LyricsEffectRaster;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.text.Text;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.function.Consumer;

/** Opt-in GL screenshot fixture; it never opens during ordinary use. */
public final class LyricsEffectsProbe extends Screen {
    private static final int CANVAS_WIDTH = 800;
    private static final int CANVAS_HEIGHT = 480;
    private static final String[] SAMPLES = {
            "Take all your", "O B Ág e\u0301", "مرحبا بالعالم", "faithlessness", "partial fill", "very low alpha"
    };
    private static final double[] OPACITIES = {1, .75, .5, .25, 1, .01};
    private static final double[] GLOW_SIGNALS = {1, 1, 1, .2, 1.4, 1};
    private static final boolean[] EMPHASIZED = {false, true, false, false, true, false};
    private static final int[] SHADOW_SOFTNESS = {0, 1, 2, 2, 2, 2};
    private final LyricsBloomRenderer effects = new LyricsBloomRenderer();
    private int frames;
    private int ticks;
    private boolean captureRequested;
    private boolean captured;

    public LyricsEffectsProbe() { super(Text.literal("Lyrics effects probe")); }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        frames++;
        if (Boolean.getBoolean("lyrica.islandCreditsSelfTest")) { renderCreditsComparison(context); return; }
        if (Boolean.getBoolean("lyrica.islandWaitingSelfTest")) { renderWaitingComparison(context); return; }
        if (Boolean.getBoolean("lyrica.islandLyricsSelfTest")) { renderIslandComparison(context); return; }
        if (Boolean.getBoolean("lyrica.lineAnimationSelfTest")) { renderLineComparison(context); return; }
        if (frames == 1) verifyGlyphMasks();
        effects.tick();
        int originX = (width - CANVAS_WIDTH) / 2;
        int originY = (height - CANVAS_HEIGHT) / 2;
        context.fill(0, 0, width, height, 0xFF0B0E13);
        context.drawText(textRenderer, "GLYPH EFFECTS / identical font, opacity, fill and coordinates", originX + 22,
                originY + 18, 0xFFF3F4F6, false);
        String[] labels = {"FACE", "FACE + SHADOW", "FACE + GLOW", "ALL EFFECTS"};
        for (int column = 0; column < labels.length; column++) {
            context.drawText(textRenderer, labels[column], originX + 22 + column * 194,
                    originY + 44, 0xFFABB6C7, false);
        }
        for (int row = 0; row < SAMPLES.length; row++) {
            int top = originY + 65 + row * 66;
            int background = row % 2 == 0 ? 0xFF161C25 : 0xFF778313;
            context.fill(originX + 12, top, originX + 788, top + 60, background);
            for (int column = 0; column < 4; column++) {
                drawSample(context, Text.literal(SAMPLES[row]), originX + 24 + column * 194,
                        top + 11, 2.0, OPACITIES[row], row == 4 ? .52 : 1,
                        column == 1 || column == 3, column >= 2, row);
                context.drawText(textRenderer, "alpha " + OPACITIES[row] + (row == 4 ? " / fill .52" : ""),
                        originX + 24 + column * 194, top + 46, 0xFFAEB8C8, false);
            }
        }
    }




    private LyricsOverlay[] creditSamples;
    private void renderCreditsComparison(DrawContext context) {
        String[] labels={"Last lyric / still singing","Credits / maker","Credits / uploader","Credits / writers",
                "Credits / provider","Five seconds later / title + bars","No credits / title","Community / no names supplied",
                "Long contributor / scrolling","Long contributor / ending","Backing vocals / credits wait","Lyrics mode off / title"};
        long[] positions={8999,9500,10750,12000,13250,14000,10000,11500,10500,13500,10500,9500};
        if(creditSamples==null) {
            creditSamples=new LyricsOverlay[positions.length];
            var metadata=new dev.relaylyrics.protocol.lyrics.LyricsMetadata(
                    dev.relaylyrics.protocol.lyrics.LyricsSource.SPICY_TTML,dev.relaylyrics.protocol.lyrics.LyricsSyncMode.LINE,
                    "fixture:credits","Song","Artist","Album","Songwriter","Spicy Lyrics",true,"Alice","","Bob","");
            var line=new dev.relaylyrics.protocol.lyrics.LyricLine(1000,9000,"A última frase da música",java.util.List.of(),"",false,null,null);
            for(int i=0;i<positions.length;i++) {
                var p=new dev.relaylyrics.client.ClientPreferences();
                p.setModernMode(true);p.setLyricsAnimations(true);p.setLyricsVisible(true);p.setLyricsPinned(true);
                p.setPopupDocked(true);p.setIslandLyrics(i!=11);p.setIslandVideoCover(false);p.setIslandKawarpBackground(false);
                p.setWorldLyrics(false);
                var overlay=new LyricsOverlay(p,()->{});
                overlay.setPlayback(new dev.relaylyrics.protocol.music.PlaybackSnapshot("Nome da música","Local fixture","",null,
                        "fixture:credits","Local fixture",positions[i],60000,i==5,.65,false,"off",false,.4,0,110,.5,
                        new double[0],java.time.Instant.now(),dev.relaylyrics.protocol.music.MusicCapabilities.unavailable()));
                var credits=metadata;
                if(i==6) credits=dev.relaylyrics.protocol.lyrics.LyricsMetadata.unknown();
                if(i==7 || i==8 || i==9) credits=new dev.relaylyrics.protocol.lyrics.LyricsMetadata(
                        dev.relaylyrics.protocol.lyrics.LyricsSource.UNKNOWN,dev.relaylyrics.protocol.lyrics.LyricsSyncMode.LINE,
                        "fixture:credits","Song","Artist","Album","","",true,
                        i==7?"":"ContribuidorComUmNomeMuitoLongoAtéOFinalDaLinha","","","");
                var lines=i==10?java.util.List.of(line,new dev.relaylyrics.protocol.lyrics.LyricLine(
                        8500,11000,"Último vocal de fundo",java.util.List.of(),"",true,null,null)):java.util.List.of(line);
                overlay.setLyrics(new dev.relaylyrics.protocol.lyrics.LyricsDocument(lines,null,credits));
                overlay.seekTo(positions[i]);creditSamples[i]=overlay;
            }
        }
        context.fill(0,0,width,height,0xFF0B0E13);
        for(int i=0;i<creditSamples.length;i++) {
            double cx=(i%3+.5)*width/3.0,top=24+(i/3)*(height-30)/4.0;
            context.drawText(textRenderer,labels[i],(int)(cx-width/6.0+20),(int)top,0xFFF2F2F2,false);
            GuiTransform.push(context);GuiTransform.translate(context,(float)(cx-width/2.0),(float)(top+28));
            try {
                creditSamples[i].setIslandAudioLevels(new dev.relaylyrics.protocol.music.MusicAudioLevels(.5,.9,.65,1,.4,System.nanoTime()));
                creditSamples[i].renderHud(context);
            } finally {GuiTransform.pop(context);}
            var kind=creditSamples[i].islandLyricsFrameSnapshot().kind();
            var expected=i==0 || i==10?dev.relaylyrics.client.music.IslandLyrics.Kind.LYRICS:
                    i==5 || i==6 || i==11?dev.relaylyrics.client.music.IslandLyrics.Kind.METADATA:
                            dev.relaylyrics.client.music.IslandLyrics.Kind.CREDITS;
            if(kind!=expected) throw new IllegalStateException("Native credit timeline failed: "+labels[i]+" -> "+kind);
        }
    }
    private LyricsOverlay[] waitingSamples;
    private java.util.concurrent.atomic.AtomicLong[] waitingTimes;
    private boolean waitingPrimed;
    private void renderWaitingComparison(DrawContext context) {
        long[] phases={0,90,280,480,580,760,1100,1190,1280,1100,1100,580};
        String[] labels={"Lyrics -> waiting / 0 ms","Dots entering / 90 ms","Centered dots / 280 ms",
                "Centered hold / 480 ms","Title entering / 580 ms","Title settling / 760 ms",
                "Waiting / fully shown","Waiting -> lyric / 90 ms","Interrupted return / 90 ms",
                "Simple / fade only","Animations off / immediate","Small / fractional scale"};
        if(waitingSamples==null) {
            waitingSamples=new LyricsOverlay[phases.length];
            waitingTimes=new java.util.concurrent.atomic.AtomicLong[phases.length];
            var doc=new dev.relaylyrics.protocol.lyrics.LyricsDocument(java.util.List.of(
                    new dev.relaylyrics.protocol.lyrics.LyricLine(1000,9000,"A frase chega ao fim",java.util.List.of(),"",false,null,null),
                    new dev.relaylyrics.protocol.lyrics.LyricLine(14000,20000,"A próxima frase começa",java.util.List.of(),"",false,null,null)));
            for(int i=0;i<phases.length;i++) {
                var p=new dev.relaylyrics.client.ClientPreferences();
                p.setModernMode(true);p.setLyricsAnimations(i!=10);p.setSimpleLyricsMode(i==9);
                p.setLyricsVisible(true);p.setLyricsPinned(true);p.setPopupDocked(true);p.setIslandLyrics(true);
                p.setIslandVideoCover(false);p.setIslandKawarpBackground(false);p.setWorldLyrics(false);
                var clock=new java.util.concurrent.atomic.AtomicLong();
                var overlay=new LyricsOverlay(p,()->{},clock::get);
                overlay.setPlayback(new dev.relaylyrics.protocol.music.PlaybackSnapshot("Nome da música","Local fixture","",null,
                        "fixture:waiting","Local fixture",8999,60000,false,.65,false,"off",false,.4,0,110,.5,
                        new double[0],java.time.Instant.now(),dev.relaylyrics.protocol.music.MusicCapabilities.unavailable()));
                overlay.setLyrics(doc);overlay.seekTo(8999);
                waitingSamples[i]=overlay;waitingTimes[i]=clock;
            }
        }
        context.fill(0,0,width,height,0xFF0B0E13);
        for(int i=0;i<phases.length;i++) {
            double cx=(i%3+.5)*width/3.0,top=24+(i/3)*(height-30)/4.0;
            context.drawText(textRenderer,labels[i],(int)(cx-width/6.0+20),(int)top,0xFFF2F2F2,false);
            GuiTransform.push(context);
            GuiTransform.translate(context,(float)(cx-width/2.0),(float)(top+28));
            if(i==11) {
                GuiTransform.translate(context,width/2.0F,0);
                GuiTransform.scale(context,.7F,.7F);
                GuiTransform.translate(context,-width/2.0F+.37F,.41F);
            }
            try {
                var overlay=waitingSamples[i];var clock=waitingTimes[i];
                if(!waitingPrimed) {
                    overlay.renderHud(context);
                    overlay.seekTo(11000);overlay.renderHud(context);
                    if(i==7 || i==8) {
                        clock.set(1100_000_000L);overlay.renderHud(context);
                        overlay.seekTo(14500);overlay.renderHud(context);
                        if(i==8) {
                            clock.set(1190_000_000L);overlay.renderHud(context);
                            overlay.seekTo(11000);overlay.renderHud(context);
                        }
                    }
                    clock.set(phases[i]*1_000_000L);
                }
                overlay.renderHud(context);
            } finally {GuiTransform.pop(context);}
        }
        waitingPrimed=true;
        var centered=waitingSamples[2].islandWaitingFrameSnapshot();
        var entering=waitingSamples[4].islandWaitingFrameSnapshot();
        var shown=waitingSamples[6].islandWaitingFrameSnapshot();
        if(centered.visibility()<.99 || centered.title()!=0 || centered.placement()!=0
                || entering.title()<=0 || entering.title()>=1 || shown.title()<.99)
            throw new IllegalStateException("Native waiting stages did not preserve dots-first, centered hold, and title reveal");
        double exit=waitingSamples[7].islandWaitingFrameSnapshot().visibility();
        if(exit<=0 || exit>=1) throw new IllegalStateException("Waiting exit snapped instead of fading");
    }
    private LyricsOverlay[] islandSamples;
    private dev.relaylyrics.client.ClientPreferences[] islandPreferences;
    private int islandToggleSaves;
    private boolean islandButtonVerified;
    private void renderIslandComparison(DrawContext context) {
        if(islandSamples==null) {
            islandSamples=new LyricsOverlay[12];
            islandPreferences=new dev.relaylyrics.client.ClientPreferences[12];
            long[] positions={3000,1000,5000,8999,1001,6500,11000,6000,3000,3000,2000,3000};
            var line=new dev.relaylyrics.protocol.lyrics.LyricLine(1000,9000,
                    "Uma linha comprida passeia pela ilha até o fim do seu tempo",java.util.List.of(),"",false,null,null);
            var next=new dev.relaylyrics.protocol.lyrics.LyricLine(14000,18000,"A próxima frase",java.util.List.of(),"",false,null,null);
            String[] words={"Quando","a","música","chegar","aqui,","troca","o","trecho","na","hora"};
            var timings=new java.util.ArrayList<dev.relaylyrics.protocol.lyrics.TimedWord>();
            for(int j=0;j<words.length;j++) timings.add(new dev.relaylyrics.protocol.lyrics.TimedWord(1000+j*700,1700+j*700,words[j]));
            var wordLine=new dev.relaylyrics.protocol.lyrics.LyricLine(1000,9000,String.join(" ",words),timings,"",false,null,null);
            for(int i=0;i<islandSamples.length;i++) {
                var p=new dev.relaylyrics.client.ClientPreferences();
                p.setModernMode(true);p.setLyricsAnimations(true);p.setLyricsVisible(true);p.setLyricsPinned(true);
                p.setPopupDocked(true);p.setIslandVideoCover(false);p.setIslandKawarpBackground(false);p.setWorldLyrics(false);
                p.setIslandLyrics(i!=0 && i!=11); islandPreferences[i]=p;
                var overlay=new LyricsOverlay(p,()->islandToggleSaves++);
                overlay.setPlayback(new dev.relaylyrics.protocol.music.PlaybackSnapshot("Nome da música","Local fixture","",null,
                        "fixture:island","Local fixture",positions[i],60000,i==0 || i==9,.65,false,"off",false,.4,0,110,.5,
                        new double[0],java.time.Instant.now(),dev.relaylyrics.protocol.music.MusicCapabilities.unavailable()));
                var doc=new dev.relaylyrics.protocol.lyrics.LyricsDocument(java.util.List.of(i==4 || i==5 || i==7?wordLine:line,next));
                if(i==8) doc=new dev.relaylyrics.protocol.lyrics.LyricsDocument(java.util.List.of(
                        new dev.relaylyrics.protocol.lyrics.LyricLine(1000,9000,"Oi, ação e emoção!",java.util.List.of(),"",false,null,null)));
                if(i==9) doc=new dev.relaylyrics.protocol.lyrics.LyricsDocument(java.util.List.of());
                overlay.setLyrics(doc);overlay.seekTo(positions[i]);
                if(i==10) overlay.beginLookup();
                islandSamples[i]=overlay;
            }
        }
        context.fill(0,0,width,height,0xFF0B0E13);
        String[] labels={"OFF / default title + bars","LINE / beginning","LINE / halfway","LINE / end",
                "WORDS / first chunk","WORDS / later chunk","WAIT / title + dots","PAUSED / current chunk",
                "SHORT / whole line","NO LYRICS / title + bars","LOADING / title + dots","BUTTON / click to enable"};
        for(int i=0;i<islandSamples.length;i++) {
            double cx=(i%3+.5)*width/3.0,top=24+(i/3)*(height-30)/4.0;
            context.drawText(textRenderer,labels[i],(int)(cx-width/6.0+20),(int)top,0xFFF2F2F2,false);
            var overlay=islandSamples[i];
            overlay.setIslandAudioLevels(new dev.relaylyrics.protocol.music.MusicAudioLevels(.5,.9,.65,1,.4,System.nanoTime()));
            GuiTransform.push(context);
            GuiTransform.translate(context,(float)(cx-width/2.0),(float)(top+20));
            try {
                if(i==11) {
                    var f=overlay.dockFrameSnapshot();
                    overlay.renderHudOverScreen(context,width/2.0,f==null?16:f.bounds().centerY());
                    f=overlay.dockFrameSnapshot();
                    if(!islandButtonVerified && f!=null && f.hover()>.99 && frames>30) {
                        double s=dev.relaylyrics.client.music.ResponsiveLyricsLayout.scale(width,height)*dev.relaylyrics.client.music.PopupDockLayout.PRESENTATION_SCALE;
                        var button=dev.relaylyrics.client.music.PopupDockLayout.lyrics(f.contents(),s);
                        var control=overlay.popupControlAt(button.centerX(),button.centerY());
                        if(control!=LyricsOverlay.PopupControl.TOGGLE_ISLAND_LYRICS || !LyricsOverlay.isPopupUtilityControl(control))
                            throw new IllegalStateException("Lyrics button missing from native hit testing");
                        if(islandPreferences[i].isIslandLyrics() || !overlay.togglePopupUtility(control) || !islandPreferences[i].isIslandLyrics() || islandToggleSaves!=1)
                            throw new IllegalStateException("Lyrics button failed to toggle and save");
                        islandButtonVerified=true;
                    }
                } else overlay.renderHud(context);
            } finally {GuiTransform.pop(context);}
        }
        if(frames>50) {
            if(!islandButtonVerified) throw new IllegalStateException("Native island button was not exercised");
            if(islandSamples[0].islandLyricsFrameSnapshot().kind()!=dev.relaylyrics.client.music.IslandLyrics.Kind.METADATA)
                throw new IllegalStateException("Default mode lost title");
            if(islandSamples[6].islandLyricsFrameSnapshot().kind()!=dev.relaylyrics.client.music.IslandLyrics.Kind.WAITING)
                throw new IllegalStateException("Instrumental gap lost waiting dots");
            double start=islandSamples[1].islandLyricsViewportSnapshot().offset(),middle=islandSamples[2].islandLyricsViewportSnapshot().offset(),end=islandSamples[3].islandLyricsViewportSnapshot().offset();
            if(start!=0 || middle<=start || end<=middle) throw new IllegalStateException("Native line viewport failed to scroll");
            if(islandSamples[5].islandLyricsViewportSnapshot().first()<=islandSamples[4].islandLyricsViewportSnapshot().first())
                throw new IllegalStateException("Native word viewport failed to change chunks");
        }
    }
    private LyricsOverlay[] lineSamples;
    private void renderLineComparison(DrawContext context) {
        if (lineSamples == null) {
            if (Boolean.getBoolean("lyrica.lineSmallSelfTest")) {
                var client = MinecraftClient.getInstance();
                client.options.getGuiScale().setValue(1); client.onResolutionChanged();
            }
            lineSamples = new LyricsOverlay[4];
            long[] positions = {2000, 4000, 8000, 4000};
            for (int i = 0; i < lineSamples.length; i++) {
                var p = new dev.relaylyrics.client.ClientPreferences();
                p.setModernMode(true); p.setLyricsAnimations(true); p.setLyricsTextShadow(false);
                p.setLyricsDynamicBackground(false); p.setLyricsTextScale(Boolean.getBoolean("lyrica.lineSmallSelfTest") ? .6 : 1.1);
                if (i == 3) p.setLineLyricsAnimation(dev.relaylyrics.client.ClientPreferences.LineLyricsAnimation.TIMED);
                var overlay = new LyricsOverlay(p, () -> { });
                overlay.setLyrics(new dev.relaylyrics.protocol.lyrics.LyricsDocument(java.util.List.of(
                        new dev.relaylyrics.protocol.lyrics.LyricLine(0, 8000,
                                Boolean.getBoolean("lyrica.lineSmallSelfTest") ? "Texto pequeno: ação e emoção" : "Todas as palavras acendem juntas nesta frase", java.util.List.of(), null, false, null, null))));
                overlay.setPlayback(new dev.relaylyrics.protocol.music.PlaybackSnapshot("Line animation", "Local fixture", "", null,
                        "fixture:line", "Local fixture", positions[i], 8000, false, .65, false, "off", false, .4, 0, 110, .5,
                        new double[0], java.time.Instant.now(), dev.relaylyrics.protocol.music.MusicCapabilities.unavailable()));
                overlay.seekTo(positions[i]); lineSamples[i] = overlay;
            }
        }
        context.fill(0, 0, width, height, 0xFF0B0E13);
        String[] labels = {"VERTICAL / 25% (2s / 8s)", "VERTICAL / 50% (4s / 8s)", "VERTICAL / 100% (8s / 8s)", "TIMED / 50% (4s / 8s)"};
        for (int i = 0; i < lineSamples.length; i++) {
            int x = 18 + (i % 2) * (width / 2), y = 18 + (i / 2) * (height / 2);
            context.drawText(textRenderer, labels[i], x, y, 0xFFFFFFFF, false);
            if (Boolean.getBoolean("lyrica.lineSmallSelfTest")) {
                GuiTransform.push(context);
                GuiTransform.translate(context, x + .37F, y + 20.41F);
                GuiTransform.scale(context, .7F, .7F);
                lineSamples[i].renderDemoPreview(context, 0, 0, width / 2 - 36, height / 2 - 44);
                GuiTransform.pop(context);
            } else lineSamples[i].renderDemoPreview(context, x, y + 20, width / 2 - 36, height / 2 - 44);
        }
    }

    private static void verifyGlyphMasks() {
        var counter = LyricsGlyphRasterizer.capture(Text.literal("O"));
        if (counter == null || counter.width() < 4 || counter.height() < 4)
            throw new IllegalStateException("Could not capture loaded glyph atlas for effects");
        exportCapturedMask("counter", counter);
        int solid = 0;
        int inkLeft = counter.width(), inkRight = -1, inkTop = counter.height(), inkBottom = -1;
        for (int y = 0; y < counter.height(); y++) for (int x = 0; x < counter.width(); x++) {
            if ((counter.alpha()[y * counter.width() + x] & 255) < 128) continue;
            solid++;
            inkLeft = Math.min(inkLeft, x); inkRight = Math.max(inkRight, x);
            inkTop = Math.min(inkTop, y); inkBottom = Math.max(inkBottom, y);
        }
        // Newer font atlases retain transparent bitmap columns after the glyph.
        // The counter belongs to the center of the ink, not that larger quad.
        int centerAlpha = solid == 0 ? 255 : counter.alpha()[((inkTop + inkBottom) / 2) * counter.width()
                + (inkLeft + inkRight) / 2] & 255;
        if (solid < 4 || centerAlpha != 0)
            throw new IllegalStateException("Effects capture lost glyph silhouette/counter: solid=" + solid
                    + ", center=" + centerAlpha + ", bounds=" + counter.width() + "x" + counter.height()
                    + ", origin=" + counter.left() + "," + counter.top());
        for (int index = 0; index < SAMPLES.length; index++) {
            String text = SAMPLES[index];
            var mask = LyricsGlyphRasterizer.capture(Text.literal(text));
            if (mask == null) throw new IllegalStateException("Could not capture effects sample: " + text);
            exportCapturedMask(Integer.toString(index), mask);
        }
        FanDroidMod.LOGGER.info("Lyrics effects glyph masks passed: bitmap counters, accents, composed glyphs and RTL");
    }

    private static void exportCapturedMask(String sample, LyricsEffectRaster.Mask mask) {
        try {
            String version = FabricLoader.getInstance().getModContainer("minecraft").orElseThrow()
                    .getMetadata().getVersion().getFriendlyString();
            Path directory = Path.of(System.getProperty("lyrica.effectsProbeOutput", "build/visual-qa"))
                    .toAbsolutePath().normalize();
            Files.createDirectories(directory);
            String json = "{\"width\":" + mask.width() + ",\"height\":" + mask.height()
                    + ",\"left\":" + mask.left() + ",\"top\":" + mask.top()
                    + ",\"ppu\":" + mask.pixelsPerUnit() + ",\"alpha\":\""
                    + Base64.getEncoder().encodeToString(mask.alpha()) + "\"}";
            Files.writeString(directory.resolve("glyph-mask-" + version + "-" + sample + ".json"), json);
        } catch (Exception failure) {
            throw new IllegalStateException("Could not export captured glyph mask", failure);
        }
    }

    private void drawSample(DrawContext context, Text text, int x, int y, double scale,
                            double opacity, double fill, boolean shadow, boolean glow, int sample) {
        int wordWidth = (int) Math.ceil(textRenderer.getWidth(text) * scale);
        int fillRight = x + (int) Math.ceil(wordWidth * fill);
        int bottom = y + (int) Math.ceil(textRenderer.fontHeight * scale) + 6;
        int color = (int) Math.round(opacity * 255) << 24 | 0xFFFFFF;
        if (glow) {
            var profile = LyricsAnimation.activeGlowProfile(GLOW_SIGNALS[sample], 1, EMPHASIZED[sample]);
            int glowColor = (int) Math.round(opacity * profile.coreOpacity() * 255) << 24 | 0xFFFFFF;
            effects.begin(context, x - 16, y - 12, x + 185, bottom + 12, false);
            effects.drawGlyph(context, textRenderer, text, x, y, scale, glowColor,
                    x - 1, y - 2, fillRight + 1, bottom, x - 1, fillRight + 1, profile.coreRadius());
            effects.finish(context, x - 16, y - 12, x + 185, bottom + 12,
                    (float) profile.coreRadius(), true, true);
        }
        if (shadow) effects.drawShadow(context, textRenderer, text, x, y, scale,
                0xFFFFFFFF, opacity * .65, 1, SHADOW_SOFTNESS[sample]);
        if (fill < 1) drawFace(context, text, x, y, scale, 0xFF718093);
        // Alpha 1-3 is intentionally omitted from the vanilla face; the generated effect
        // texture is still drawn there, checking 1.21.1's opaque-text-alpha regression.
        if ((color >>> 24) < 4) return;
        context.enableScissor(x - 2, y - 2, fillRight + 1, bottom);
        try { drawFace(context, text, x, y, scale, color); }
        finally { context.disableScissor(); }
    }

    private void drawFace(DrawContext context, Text text, int x, int y, double scale, int color) {
        GuiTransform.push(context);
        GuiTransform.translate(context, x, y);
        GuiTransform.scale(context, (float) scale, (float) scale);
        context.drawText(textRenderer, text, 0, 0, color, false);
        GuiTransform.pop(context);
    }

    @Override
    public void tick() {
        if (lineSamples != null) for (var sample : lineSamples) sample.tick();
        if (islandSamples != null) for (var sample : islandSamples) sample.tick();
        if (waitingSamples != null) for (var sample : waitingSamples) sample.tick();
        if (creditSamples != null) for (var sample : creditSamples) sample.tick();
        if (++ticks > 400 && !captured) {
            throw new IllegalStateException("Lyrics effects screenshot did not complete");
        }
        if (captureRequested || frames < 50 || ticks < 40) return;
        captureRequested = true;
        MinecraftClient client = MinecraftClient.getInstance();
        Consumer<NativeImage> writer = image -> {
            try (image) {
                String version = FabricLoader.getInstance().getModContainer("minecraft").orElseThrow()
                        .getMetadata().getVersion().getFriendlyString();
                Path directory = Path.of(System.getProperty("lyrica.effectsProbeOutput", "build/visual-qa"))
                        .toAbsolutePath().normalize();
                Files.createDirectories(directory);
                Path output = directory.resolve((Boolean.getBoolean("lyrica.islandCreditsSelfTest") ? "island-credits-" : Boolean.getBoolean("lyrica.islandWaitingSelfTest") ? "island-waiting-" : Boolean.getBoolean("lyrica.islandLyricsSelfTest") ? "island-lyrics-" : Boolean.getBoolean("lyrica.lineSmallSelfTest") ? "line-small-" : Boolean.getBoolean("lyrica.lineAnimationSelfTest") ? "line-animation-" : "lyrics-effects-") + version + ".png");
                image.writeTo(output);
                captured = true;
                FanDroidMod.LOGGER.info("Lyrics effects GL screenshot passed: {} ({}x{}, {} frames)",
                        output, image.getWidth(), image.getHeight(), frames);
            } catch (Exception failure) {
                throw new IllegalStateException("Could not save lyrics effects screenshot", failure);
            } finally {
                client.execute(client::scheduleStop);
            }
        };
        try {
            // 1.21.1 returns the image; 1.21.11 schedules GPU readback into a callback.
            try {
                var synchronous = ScreenshotRecorder.class.getMethod("takeScreenshot", net.minecraft.client.gl.Framebuffer.class);
                writer.accept((NativeImage) synchronous.invoke(null, client.getFramebuffer()));
            } catch (NoSuchMethodException callbackApi) {
                var asynchronous = ScreenshotRecorder.class.getMethod("takeScreenshot", net.minecraft.client.gl.Framebuffer.class,
                        Consumer.class);
                asynchronous.invoke(null, client.getFramebuffer(), writer);
            }
        } catch (ReflectiveOperationException failure) {
            Throwable cause = failure instanceof InvocationTargetException ? failure.getCause() : failure;
            throw new IllegalStateException("Could not capture lyrics effects framebuffer", cause);
        }
    }

    @Override public void removed() { effects.close(); }
    @Override public boolean shouldCloseOnEsc() { return false; }
}
