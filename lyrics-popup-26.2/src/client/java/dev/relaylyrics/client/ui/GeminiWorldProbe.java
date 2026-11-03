package dev.relaylyrics.client.ui;

import dev.relaylyrics.client.RelayLyricsClient;
import dev.relaylyrics.client.WorldLyricsRenderHook;
import dev.relaylyrics.client.music.*;
import dev.relaylyrics.protocol.music.*;
import dev.relaylyrics.FanDroidMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.world.phys.Vec3;
import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicLong;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

/** Real, disposable-world GPU checks: the scene changes while the HUD stays untouched. */
public final class GeminiWorldProbe {
    private static final boolean ART=Boolean.getBoolean("lyrica.geminiArtSelfTest");
    private static final String[] STAGES=ART?java.util.stream.Stream.concat(java.util.stream.Stream.of("normal"),GeminiArtLibrary.STYLES.stream().map(GeminiArtLibrary.Style::id)).toArray(String[]::new):new String[]{"normal","monochrome","vivid","sky","paused-a","paused-b","bad-glsl","recovered","disabled"};
    private static final AtomicLong NANOS=new AtomicLong();
    private static GeminiShaderSession session;
    private static int stage,ticks,total;
    private static boolean pending;
    private static BufferedImage baseline;
    private static float pausedTime;
    private static Path output;
    private static String code() {
        if(ART && stage>0) {
            String function=GeminiArtLibrary.STYLES.stream().filter(s->s.id().equals(STAGES[stage])).findFirst().orElseThrow().function();
            return "vec3 musicWorld(vec3 sceneColor, vec2 uv, float depth) { return "+function+"(sceneColor,uv,depth,1.,1.,.3,1.,0.); }";
        }
        return switch(STAGES[stage]) {
            case "monochrome" -> "vec3 musicWorld(vec3 sceneColor, vec2 uv, float depth) { return vec3(dot(sceneColor, vec3(.2126,.7152,.0722))); }";
            case "vivid" -> "vec3 musicWorld(vec3 sceneColor, vec2 uv, float depth) { float gray=dot(sceneColor,vec3(.2126,.7152,.0722)); return vec3(gray)+(sceneColor-vec3(gray))*2.0; }";
            case "bad-glsl" -> "vec3 musicWorld(vec3 sceneColor, vec2 uv, float depth) { return missingVariable; }";
            default -> "vec3 musicWorld(vec3 sceneColor, vec2 uv, float depth) { float sky=step(.999999,depth); vec3 tint=vec3(.88,.09,.48)*(.8+.2*uv.y); return mix(sceneColor,tint,sky); }";
        };
    }
    public static void tick() {
        if(!Boolean.getBoolean("lyrica.geminiWorldSelfTest")) return;
        var client=Minecraft.getInstance();
        if(++total>5000) throw new IllegalStateException("World Gemini probe timed out");
        if(client.level==null || client.player==null || client.gui.overlay()!=null || pending) return;
        if(session==null) {
            try {
                output=Path.of(System.getProperty("lyrica.geminiWorldProbeOutput"));Files.createDirectories(output);
                var p=RelayLyricsClient.preferences();p.setLyricsVisible(false);p.setWorldLyrics(false);p.setGeminiWorldStrength(100);
                client.options.pauseOnLostFocus=false;
                client.options.guiScale().set(1);client.resizeGui();if(client.gui.hud.isHidden())client.gui.hud.toggle();
                session=GeminiShaderSession.offlineProbe(p,Files.createTempDirectory(output,"fixture-"),
                        (key,model,track,direction)->new GeminiShaderSpec(STAGES[stage],"Offline world fixture",code()),NANOS::get);
                session.saveKey("fixture_key_not_a_real_gemini_key");WorldGeminiRenderer.probeSession(session);
                var server=client.getSingleplayerServer();
                if(server==null) throw new IllegalStateException("Disposable world required");
                server.execute(()->{
                    for(String command:List.of("gamemode spectator @a","time set day","weather clear",
                            "gamerule doDaylightCycle false","gamerule doWeatherCycle false",
                            "fill -16 80 -8 16 80 24 minecraft:grass_block",
                            "fill -12 81 12 -5 86 12 minecraft:red_wool","fill -4 81 12 3 86 12 minecraft:lime_wool",
                            "fill 4 81 12 11 86 12 minecraft:blue_wool","tp @a 0 82 0 0 -8"))
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),command);
                });
                client.gui.setScreen(null); select();
            } catch(Exception e) {throw new IllegalStateException(e);}
        }
        client.player.setPos(.5,82,.5);client.player.setYRot(0);client.player.setXRot(-8);client.player.setDeltaMovement(Vec3.ZERO);
        client.gui.hud.getChat().clearMessages(false);
        NANOS.addAndGet(50_000_000L);
        var p=RelayLyricsClient.preferences();boolean playing=!STAGES[stage].startsWith("paused");
        long position=playing?NANOS.get()/1_000_000:90_000;
        session.tick(new PlaybackSnapshot("World music fixture","Lyrica","",null,"spotify:track:worldGemini","Offline fixture",
                position,600_000,playing,.5,false,"off",false,.6,.3,120,.5,new double[]{.4,.3,.6},Instant.now(),MusicCapabilities.unavailable()),position,0xFFAA66CC);
        if(++ticks<(stage==0?160:65))return;
        if((ART?stage>0:stage>0 && stage<8 && stage!=6) && session.worldEffect()==null) {if(ticks<300)return;throw new IllegalStateException("No world shader: "+session.status());}
        if(!ART && stage==6 && !session.status().equals("glsl")) {if(ticks<300)return;throw new IllegalStateException("Invalid world GLSL did not fail: "+session.status());}
        if(!ART && stage==4)pausedTime=WorldGeminiRenderer.lastTime();
        if(!ART && stage==5 && WorldGeminiRenderer.lastTime()!=pausedTime)throw new IllegalStateException("Paused world effect kept advancing");
        pending=true;capture(client);
    }
    private static void select() {
        var p=RelayLyricsClient.preferences();p.setGeminiShaderEnabled(stage!=0 && (ART || stage!=8));NANOS.addAndGet(25_000_000_000L);
        if(ART?stage>0:stage>0 && stage<8 && stage!=4 && stage!=5)session.regenerate();
    }
    public static void hud(GuiGraphicsExtractor context) {
        if(!Boolean.getBoolean("lyrica.geminiWorldSelfTest"))return;
        context.fill(8,8,24,24,0xFFFF0000);context.fill(30,8,46,24,0xFF00FF00);context.fill(52,8,68,24,0xFF0000FF);
    }
    private static double chroma(BufferedImage image,int top,int bottom) {
        double sum=0;int count=0;
        for(int y=top;y<bottom;y+=3)for(int x=100;x<image.getWidth()-100;x+=3) {
            int c=image.getRGB(x,y),r=c>>16&255,g=c>>8&255,b=c&255;sum+=Math.max(r,Math.max(g,b))-Math.min(r,Math.min(g,b));count++;
        }return sum/Math.max(1,count);
    }
    private static void verify(BufferedImage image) {
        if((image.getRGB(16,16)&0xFFFFFF)!=0xFF0000 || (image.getRGB(38,16)&0xFFFFFF)!=0x00FF00)
            throw new IllegalStateException("World shader recolored the HUD");
        double color=chroma(image,40,image.getHeight()-100);
        if(stage==0){baseline=image;if(color<12)throw new IllegalStateException("Fixture has no original colors");}
        if(ART) {
            if(stage>0) {
                double change=0;int count=0;
                for(int y=80;y<image.getHeight()-100;y+=4)for(int x=100;x<image.getWidth()-100;x+=4){int a=image.getRGB(x,y),b=baseline.getRGB(x,y);change+=Math.abs((a>>16&255)-(b>>16&255))+Math.abs((a>>8&255)-(b>>8&255))+Math.abs((a&255)-(b&255));count++;}
                if(change/Math.max(1,count)<8)throw new IllegalStateException("Art style did not visibly change world: "+STAGES[stage]);
                if(!session.status().equals("ready"))throw new IllegalStateException("Art failed: "+session.status());
            }
            FanDroidMod.LOGGER.info("World art {} verified: chroma={}, applied frames={}",STAGES[stage],color,WorldGeminiRenderer.frames());return;
        }
        if(stage==1 && color>1.5)throw new IllegalStateException("Actual world did not become monochrome: "+color);
        if(stage==2 && color<chroma(baseline,40,baseline.getHeight()-100)*1.08)throw new IllegalStateException("Actual world did not become more vivid");
        if(stage==3 || stage==7) {
            int c=image.getRGB(image.getWidth()/2,80);if((c>>16&255)<(c>>8&255)+70)throw new IllegalStateException("Sky did not change color");
            double change=0;int count=0;
            for(int y=image.getHeight()*65/100;y<image.getHeight()*85/100;y+=4)for(int x=150;x<image.getWidth()-150;x+=4){int a=image.getRGB(x,y),b=baseline.getRGB(x,y);change+=Math.abs((a>>16&255)-(b>>16&255))+Math.abs((a>>8&255)-(b>>8&255))+Math.abs((a&255)-(b&255));count++;}
            if(change/Math.max(1,count)>30)throw new IllegalStateException("Sky shader repainted terrain: "+change/count);
        }
        if((stage==6||stage==8)&&color<12)throw new IllegalStateException("Failed/disabled world effect did not restore original colors");
        FanDroidMod.LOGGER.info("World Gemini {} verified: chroma={}, applied frames={}",STAGES[stage],color,WorldGeminiRenderer.frames());
    }
    private static void capture(Minecraft client) {
        Consumer<NativeImage> writer=image->{try(image){
            String version=FabricLoader.getInstance().getModContainer("minecraft").orElseThrow().getMetadata().getVersion().getFriendlyString();
            Path file=output.resolve("world-gemini-"+version+"-"+STAGES[stage]+".png");image.writeToFile(file);verify(ImageIO.read(file.toFile()));
            if(++stage==STAGES.length){session.close();WorldGeminiRenderer.close();FanDroidMod.LOGGER.info(ART?"World art GPU probe passed: all registered styles, original world and unchanged HUD":"World Gemini GPU probe passed: monochrome, vivid colors, sky-only transform, normal HUD, pause, invalid GLSL recovery and disable");client.stop();return;}
            ticks=0;select();pending=false;
        }catch(Exception e){throw new IllegalStateException("World Gemini screenshot failed",e);}};
        net.minecraft.client.Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(),writer);
    }
}
