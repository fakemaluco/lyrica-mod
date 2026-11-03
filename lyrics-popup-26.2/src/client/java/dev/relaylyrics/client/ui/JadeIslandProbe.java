package dev.relaylyrics.client.ui;

import dev.relaylyrics.FanDroidMod;
import dev.relaylyrics.client.*;
import dev.relaylyrics.protocol.music.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.world.phys.Vec3;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

/** Real optional-Jade regression fixture on the disposable integrated world. */
public final class JadeIslandProbe {
    private static final String[] STAGES={"stone","long-block","look-away","look-return","tab-closing","tab-hidden","tab-return","original-jade"};
    private static boolean initialized,pending;
    private static int total,ticks,stage;
    private static double stoneWidth;
    private JadeIslandProbe() { }
    public static void tick(Minecraft client) {
        if(!Boolean.getBoolean("lyrica.islandJadeSelfTest")) return;
        if(++total>1800) throw new IllegalStateException("Jade island probe timed out: "+STAGES[stage]);
        if(client.player==null || client.level==null || client.gui.overlay()!=null || pending) return;
        client.gui.setScreen(null);
        client.options.pauseOnLostFocus=false;
        client.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
        if(!initialized) {
            initialized=true;client.gui.setScreen(null);client.options.guiScale().set(1);client.resizeGui();
            if(!JadeIsland.ready()) throw new IllegalStateException("Actual Jade callback did not initialize");
            var p=RelayLyricsClient.preferences();p.setPopupDocked(true);p.setLyricsVisible(true);p.setLyricsPinned(true);p.setWorldLyrics(false);p.setLyricsAnimations(true);p.setJadeIsland(true);
            var server=client.getSingleplayerServer();server.execute(()->{
                for(String command:List.of("fill -6 80 -6 6 80 6 minecraft:stone","fill -6 81 -6 6 84 6 minecraft:air","setblock 0 82 4 minecraft:stone","gamemode creative @a","time set day","weather clear")) server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),command);
            });
            RelayLyricsClient.overlay().setPlayback(new PlaybackSnapshot("Listening with Jade","Lyrica","",null,"spotify:track:jadeProbe","Local fixture",3000,60000,true,.65,false,"off",false,.4,0,110,.5,new double[0],Instant.now(),MusicCapabilities.unavailable()));
        }
        client.player.setPos(.5,81,.5);client.player.setYRot(stage==2?90:0);client.player.setXRot(0);client.player.setDeltaMovement(Vec3.ZERO);
        client.gui.hud.getChat().clearMessages(false);
        client.options.keyPlayerList.setDown(stage==4 || stage==5);
        if(ticks++==0 && stage==1) {var server=client.getSingleplayerServer();server.execute(()->server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"setblock 0 82 4 minecraft:chiseled_polished_blackstone"));}
        if(stage==7) RelayLyricsClient.preferences().setJadeIsland(false);
        int wait=stage==4?4:stage==0?60:40;
        if(stage==0 && ticks%100==0) FanDroidMod.LOGGER.info("Jade island waiting: captures={}, draws={}, frame={}, diagnostics={}, target={}",JadeIsland.capturedFrames(),JadeIsland.drawnFrames(),JadeIsland.frame(),JadeIsland.diagnostics(),client.hitResult);
        if(ticks<wait) return;
        if(stage==0 && ticks==600 && JadeIsland.capturedFrames()==0) throw new IllegalStateException("Jade callback diagnostic: "+JadeIsland.diagnostics());
        var frame=JadeIsland.frame();
        if(stage==0) {
            if(JadeIsland.capturedFrames()==0 || JadeIsland.drawnFrames()==0 || frame==null || frame.opacity()<.99) return;
            stoneWidth=frame.bounds().width();
            var music=RelayLyricsClient.overlay().dockFrameSnapshot();
            if(music==null || frame.bounds().y()<music.bounds().bottom()+3) throw new IllegalStateException("Jade overlaps music island");
        }
        if(stage==1 && (frame==null || frame.bounds().width()<=stoneWidth+10)) throw new IllegalStateException("Jade island did not adapt to real block content");
        if((stage==2 || stage==5) && frame!=null && frame.opacity()>.02) throw new IllegalStateException("Hidden Jade content remained visible");
        if((stage==3 || stage==6) && (frame==null || frame.opacity()<.99)) return;
        if(stage==4 && (frame==null || frame.opacity()<=0 || frame.opacity()>=1)) throw new IllegalStateException("Tab closure snapped instead of animating");
        if(stage==7 && frame!=null) throw new IllegalStateException("Disabling integration retained the secondary island");
        pending=true;
        Consumer<NativeImage> writer=image->{
            try(image) {
                String version=FabricLoader.getInstance().getModContainer("minecraft").orElseThrow().getMetadata().getVersion().getFriendlyString();
                Path folder=Path.of(System.getProperty("lyrica.islandJadeProbeOutput")).toAbsolutePath();Files.createDirectories(folder);
                Path output=folder.resolve("jade-island-"+version+"-"+STAGES[stage]+".png");image.writeToFile(output);
                FanDroidMod.LOGGER.info("Jade island native stage {} passed: {}",STAGES[stage],output);
                if(++stage==STAGES.length) {FanDroidMod.LOGGER.info("Jade island native probe passed: actual Jade callback/content, adaptive dimensions, look exit/return, animated Tab and original overlay fallback");client.stop();return;}
                ticks=0;pending=false;
            } catch(Exception error) {throw new IllegalStateException(error);}
        };
        net.minecraft.client.Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(),writer);
    }
}
