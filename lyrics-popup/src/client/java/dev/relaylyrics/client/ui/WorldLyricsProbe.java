package dev.relaylyrics.client.ui;

import dev.relaylyrics.FanDroidMod;
import dev.relaylyrics.client.*;
import dev.relaylyrics.client.music.*;
import dev.relaylyrics.protocol.lyrics.*;
import dev.relaylyrics.protocol.music.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.fabricmc.loader.api.FabricLoader;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

/** Native fixtures on a disposable world, with a real optional lighting runtime. */
public final class WorldLyricsProbe {
    private static final boolean CINEMATIC = Boolean.getBoolean("lyrica.worldCinematicSelfTest");
    private static int cinematicBefore;
    private static final String[] STAGES=CINEMATIC ? new String[]{"f1-world","ji-afk-caption","ji-afk-elytra","ji-afk-paused","ji-return"} : new String[]{"around","sky","glyph-lift-a","glyph-lift-b","popup-font-changed","duet",
            "night-unlit","night-lit","night-removed","elytra-short","elytra-long","elytra-turn"};
    private static int total,ticks,stage;
    private static boolean initialized,capturePending;
    private static WorldLyricsLights.Point lastLight;
    private static double darkFloor,shortFlight;
    private static java.awt.image.BufferedImage darkFrame;
    private WorldLyricsProbe() { }
    public static void tick(MinecraftClient client) {
        if (Boolean.getBoolean("lyrica.islandJadeSelfTest") || Boolean.getBoolean("lyrica.musicPresenceSelfTest") || !Boolean.getBoolean("lyrica.worldExperienceSelfTest")) return;
        if (++total>4000) throw new IllegalStateException("Spatial world probe timed out at "+STAGES[stage]);
        if (client.player==null && total%100==0) FanDroidMod.LOGGER.info("World fixture waiting: {}",client.currentScreen);
        if (client.player==null || client.world==null || client.getOverlay()!=null || capturePending) return;
        // Keep the disposable fixture deterministic even if a movement key is held when the window opens.
        client.player.setPosition(.5,81,.5); client.player.setYaw(stage==11 && ticks>=25 ? 41 : 0); client.player.setPitch(0);
        client.player.setVelocity(Vec3d.ZERO); client.player.setSprinting(false);
        if (!initialized) {
            initialized=true;
            client.options.pauseOnLostFocus=false;
            var server=client.getServer();
            if (server==null) throw new IllegalStateException("Use the disposable integrated QA world");
            server.execute(() -> {
                for (String command : List.of("fill -24 80 -24 24 80 24 minecraft:stone",
                        "fill -24 81 -24 -1 99 24 minecraft:air","fill 0 81 -24 24 99 24 minecraft:air","gamemode spectator @a","tp @a 0 81 0 0 0","time set day","weather clear"))
                    WorldLyricsRenderHook.probeCommand(server,command);
            });
            var p=RelayLyricsClient.preferences();
            p.setWorldLyrics(true);p.setLyricsVisible(true);p.setLyricsPinned(false);
            p.setWorldLyricsQuality(WorldLyricsDirector.Quality.HIGH);p.setWorldLyricsSize(1.2);
            p.setWorldLyricsLuminance(15);client.setScreen(null);apply();
        }
        client.inGameHud.getChatHud().clear(false);
        if (CINEMATIC) { tickCinematic(client); return; }
        if (stage==0 && ticks==39) apply();
        if (stage==11 && ticks==25) WorldLyricsRenderer.probeFlight(new Vec3d(-.95,-.06,.35),true);
        if (++ticks<100) return;
        if (!WorldLyricsRenderer.verifyProbe(stage==5 ? 3 : 1,false) || !WorldLyricsRenderer.probeHasVolume()) return;
        if (!WorldLyricsRenderer.probeFontMatchesPopup()) throw new IllegalStateException("World font differs from popup font");
        if (Boolean.getBoolean("lyrica.worldTerrainLightSelfTest") && !WorldLyricsLights.available()) throw new IllegalStateException("Lighting API did not initialize");
        if (stage==7 && Boolean.getBoolean("lyrica.worldTerrainLightSelfTest")) {
            var points=WorldLyricsLights.latestPoints();if (points.isEmpty()) throw new IllegalStateException("No word light emitter");
            lastLight=points.getFirst();double level=terrainLightLevel(lastLight);
            if (level<1) throw new IllegalStateException("Terrain engine reported no ground light: "+level+" at "+lastLight);
            FanDroidMod.LOGGER.info("Actual dynamic ground light: {}",level);
        }
        if (stage==8 && lastLight!=null && terrainLightLevel(lastLight)>.01) throw new IllegalStateException("Old word light was not removed");
        if (stage==9) shortFlight=WorldLyricsRenderer.probeAnchorDistance();
        if (stage==10 && (shortFlight<50 || WorldLyricsRenderer.probeAnchorDistance()<shortFlight+5))
            throw new IllegalStateException("Elytra runway did not grow: "+shortFlight+" -> "+WorldLyricsRenderer.probeAnchorDistance());
        if (stage==11 && (WorldLyricsRenderer.probeTravelAlignment()<.8 || WorldLyricsRenderer.probeCourseChanges()<1)) throw new IllegalStateException("Elytra turn left the phrase behind");
        capturePending=true;capture(client);
    }
    private static void tickCinematic(MinecraftClient client) {
        var overlay = RelayLyricsClient.overlay();
        if (ticks == 0) {
            cinematicBefore = overlay.cinematicCaptionProbeFrames();
            // Keep 3D enabled: Ji must suspend it rather than drawing a second set of lyrics.
            RelayLyricsClient.preferences().setWorldLyrics(true);
            WorldLyricsRenderer.probeFlight(stage == 2 ? new Vec3d(.5, -.08, 1.2) : null, stage == 2);
            client.options.hudHidden = stage == 0;
            overlay.setPlayback(new PlaybackSnapshot("AFK fixture", "Lyrica", "", null, "spotify:track:cinematic", "Local fixture",
                    1900, 60000, stage != 3, .65, false, "off", false, .4, 0, 110, .5, new double[0], Instant.now(), MusicCapabilities.unavailable()));
            try {
                var manager = Class.forName("com.ji.afkcinematic.cinematic.CinematicManager");
                var state = Class.forName("com.ji.afkcinematic.cinematic.CinematicState");
                var mode = java.util.Arrays.stream(state.getEnumConstants()).filter(x -> x.toString().equals(stage >= 1 && stage <= 3 ? "CINEMATIC_ACTIVE" : "IDLE")).findFirst().orElseThrow();
                manager.getMethod("setState", state).invoke(null, mode);
                Class.forName("com.ji.afkcinematic.render.HUDController").getMethod("setHidden", boolean.class).invoke(null, stage >= 1 && stage <= 3);
                Class.forName("com.ji.afkcinematic.render.LetterboxRenderer").getMethod(stage == 1 ? "fadeIn" : stage == 4 ? "reset" : "tick").invoke(null);
            } catch (ReflectiveOperationException error) { throw new IllegalStateException("Ji runtime API missing", error); }
        }
        if (++ticks < 100) return;
        if (stage == 0 && (!WorldLyricsRenderer.verifyProbe(1, false) || !WorldLyricsRenderer.probeHasVolume()))
            throw new IllegalStateException("F1 suppressed the world renderer");
        int frames = overlay.cinematicCaptionProbeFrames() - cinematicBefore;
        if (stage >= 1 && stage <= 3 && !WorldLyricsRenderer.probeCinematicSuspended())
            throw new IllegalStateException("Ji did not suspend world anchors during its camera shots");
        if (stage == 4 && !WorldLyricsRenderer.verifyProbe(1, false))
            throw new IllegalStateException("World lyrics did not resume after Ji returned control");
        if ((stage == 1 || stage == 2) && frames < 1) throw new IllegalStateException("Ji cancelled the lyric caption along with its HUD");
        if (stage == 1 && overlay.cinematicWordFillFrames() < 1) throw new IllegalStateException("AFK did not render progressive word fill");
        if (stage >= 3 && frames != 0) throw new IllegalStateException("Caption remained visible while paused or outside AFK");
        FanDroidMod.LOGGER.info("Cinematic fixture {}: caption frames={}, Ji active={}", STAGES[stage], frames, CompatibilityManager.jiAfkCinematicActive());
        capturePending = true; capture(client);
    }
    private static double terrainLightLevel(WorldLyricsLights.Point point) {
        try {
            Class<?> type=Class.forName("dev.lambdaurora.lambdynlights.LambDynLights");Object engine=type.getField("INSTANCE").get(null);
            return ((Number)type.getMethod("getDynamicLightLevel",BlockPos.class).invoke(engine,BlockPos.ofFloored(point.x(),81,point.z()))).doubleValue();
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Could not query actual lighting runtime",error); }
    }
    private static LyricLine phrase(String text,String agent,boolean background) {
        String[] parts=text.split(" ");List<TimedWord> words=new ArrayList<>();long end=0;
        for (int i=0;i<parts.length;i++) { long duration=i==0 ? 6000 : 700;words.add(new TimedWord(end,end+duration,(i==0 ? "" : " ")+parts[i]));end+=duration; }
        return new LyricLine(0,end,text,words,agent,background,null,null);
    }
    private static void apply() {
        var p=RelayLyricsClient.preferences();var overlay=RelayLyricsClient.overlay();
        if (stage==11) {
            overlay.setPlayback(new PlaybackSnapshot("Spatial fixture","Lyrica","",null,"spotify:track:spatial10","Local fixture",
                    1900,60000,true,.65,false,"off",false,.4,0,110,.5,new double[0],Instant.now(),MusicCapabilities.unavailable()));
            return;
        }

        p.setLyricsFont(stage==4 ? LyricsFont.UNIFORM : LyricsFont.MINECRAFT);
        p.setLyricsFontWeight(stage==4 ? LyricsFontWeight.BOLD : LyricsFontWeight.REGULAR);
        p.setLyricsAnimations(true);p.setLyricsRomanization(false);
        p.setWorldLyricsStyle(stage==2 || stage==3 ? WorldLyricsExperience.Style.SCULPTED : WorldLyricsExperience.Style.SPICY);
        p.setWorldLyricsPosition(stage==1 || stage>=9 ? WorldLyricsSpace.Position.SKY : WorldLyricsSpace.Position.AROUND);
        p.setWorldLyricsTerrainLight(stage!=6 && stage!=8);
        WorldLyricsRenderer.probeFlight(stage>=9 ? new Vec3d(.35,-.06,1.0) : null,stage>=9);
        if (stage==6) { var server=MinecraftClient.getInstance().getServer();server.execute(() -> WorldLyricsRenderHook.probeCommand(server,"time set midnight")); }
        LyricLine lead=phrase(stage>=6 && stage<=8 ? "Ilumina os blocos do mundo" : stage==9 ? "Voa comigo" : stage==10
                ? "Atravessando o céu com uma frase muito maior que precisa de mais espaço para continuar acompanhando a nossa trajetória" : "Movimento faz parte da música","v1",false);
        List<LyricVocalGroup> groups=new ArrayList<>();
        groups.add(new LyricVocalGroup(lead.startMillis(),lead.endMillis(),lead,stage==5 ? List.of(phrase("Voz de fundo","v1",true)) : List.of(),LyricAlignment.NORMAL));
        if (stage==5) { var duet=phrase("Outra voz na canção","v2",false);groups.add(new LyricVocalGroup(duet.startMillis(),duet.endMillis(),duet,List.of(),LyricAlignment.OPPOSITE)); }
        overlay.setLyrics(new LyricsDocument(groups,LyricsMetadata.unknown()));
        overlay.setPlayback(new PlaybackSnapshot("Spatial fixture","Lyrica","",null,"spotify:track:spatial"+stage,"Local fixture",
                stage==2 ? 1300 : 1900,60000,false,.65,false,"off",false,.4,0,110,.5,new double[0],Instant.now(),MusicCapabilities.unavailable()));
    }
    private static void capture(MinecraftClient client) {
        Consumer<NativeImage> writer=image -> {
            try (image) {
                String version=FabricLoader.getInstance().getModContainer("minecraft").orElseThrow().getMetadata().getVersion().getFriendlyString();
                Path dir=Path.of(System.getProperty("lyrica.worldProbeOutput","build/world-spatial-qa")).toAbsolutePath().normalize();Files.createDirectories(dir);
                Path output=dir.resolve("world-"+version+"-"+STAGES[stage]+".png");image.writeTo(output);
                if (stage==6 || stage==7) {
                    var pixels=ImageIO.read(output.toFile());long sum=0,count=0;
                    for (int y=pixels.getHeight()*72/100;y<pixels.getHeight()*98/100;y++) for (int x=0;x<pixels.getWidth();x++) { int rgb=pixels.getRGB(x,y);sum+=(rgb>>16&255)+(rgb>>8&255)+(rgb&255);count+=3; }
                    double mean=sum/(double)count;
                    if (stage==6) { darkFloor=mean;darkFrame=pixels; }
                    else if (Boolean.getBoolean("lyrica.worldTerrainLightSelfTest")) {
                        double brightest=0;
                        // Lighting is local: compare a floor patch, rather than sky/ambient brightness across the whole view.
                        for (int y=pixels.getHeight()*56/100;y<pixels.getHeight()-64;y+=32) for (int x=0;x<pixels.getWidth()-64;x+=32) {
                            long change=0;
                            for (int py=y;py<y+64;py++) for (int px=x;px<x+64;px++) {
                                int a=darkFrame.getRGB(px,py),b=pixels.getRGB(px,py);
                                change+=(b>>16&255)+(b>>8&255)+(b&255)-(a>>16&255)-(a>>8&255)-(a&255);
                            }
                            brightest=Math.max(brightest,change/(64.0*64*3));
                        }
                        FanDroidMod.LOGGER.info("Ground framebuffer local light increase: {} (whole floor {} -> {})",brightest,darkFloor,mean);
                        if (brightest<.5) throw new IllegalStateException("No floor patch received visible light");
                    }
                }
                FanDroidMod.LOGGER.info("Spatial world screenshot: {} ({}, distance={})",output,WorldLyricsRenderer.probeSummary(),WorldLyricsRenderer.probeAnchorDistance());
                if (++stage==STAGES.length) { FanDroidMod.LOGGER.info("Spatial world GL probe passed: closed volumes, popup fonts/glyph lift, sides/sky, terrain lighting lifecycle and predictive elytra runway");client.scheduleStop();return; }
                ticks=0;apply();capturePending=false;
            } catch (Exception error) { throw new IllegalStateException("Spatial screenshot failed",error); }
        };
        try {
            try { var method=ScreenshotRecorder.class.getMethod("takeScreenshot",net.minecraft.client.gl.Framebuffer.class);writer.accept((NativeImage)method.invoke(null,client.getFramebuffer())); }
            catch (NoSuchMethodException asynchronous) { ScreenshotRecorder.class.getMethod("takeScreenshot",net.minecraft.client.gl.Framebuffer.class,Consumer.class).invoke(null,client.getFramebuffer(),writer); }
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("World screenshot failed",error); }
    }
}
