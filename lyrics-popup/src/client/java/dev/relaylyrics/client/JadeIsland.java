package dev.relaylyrics.client;

import dev.relaylyrics.client.compat.JadeIslandAccess;
import dev.relaylyrics.client.compat.JadeIslandMotion;
import dev.relaylyrics.client.music.PopupDockLayout;
import dev.relaylyrics.client.ui.GuiTransform;
import dev.relaylyrics.client.ui.RoundedGui;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Drawable;
import net.minecraft.client.util.math.Rect2i;

/** Presents Jade's original, complete tooltip inside a separate island below the music. */
public final class JadeIsland {
    private static final JadeIslandAccess ACCESS=new JadeIslandAccess();
    private static final JadeIslandMotion MOTION=new JadeIslandMotion();
    private static long lastFrameNanos;
    private static JadeIslandMotion.Frame frame;
    private JadeIsland() { }
    public static void register(Object registration) { ACCESS.install(registration,JadeIsland::eligible,JadeIsland::size); }
    public static boolean ready() { return ACCESS.ready(); }
    public static long capturedFrames() { return ACCESS.capturedFrames(); }
    public static String diagnostics() { return ACCESS.diagnostics(); }
    public static long drawnFrames() { return ACCESS.drawnFrames(); }
    public static JadeIslandMotion.Frame frame() { return frame; }
    private static boolean eligible() {
        if(RelayLyricsClient.disabledByRelay() || RelayLyricsClient.overlay()==null) return false;
        var client=MinecraftClient.getInstance();var p=RelayLyricsClient.preferences();
        return client.world!=null && client.currentScreen==null && !client.options.hudHidden
                && p.isJadeIsland() && p.isPopupDocked() && p.isLyricsVisible()
                && p.getLyricsRenderType().rendersWorld(client.options.playerListKey.isPressed())
                && !CompatibilityManager.jiAfkCinematicActive();
    }
    private static double[] size(Object animation) throws ReflectiveOperationException {
        Class<?> type=animation.getClass();
        Object rect=type.getField("expectedRect").get(animation);
        double scale=Math.max(.1,((Number)type.getField("scale").get(animation)).doubleValue());
        double w,h;
        if(rect instanceof Rect2i value) { w=value.getWidth();h=value.getHeight(); }
        else {
            w=((Number)rect.getClass().getMethod("getWidth").invoke(rect)).doubleValue();
            h=((Number)rect.getClass().getMethod("getHeight").invoke(rect)).doubleValue();
        }
        return new double[]{w/scale,h/scale,scale};
    }
    public static void render(DrawContext context) {
        long now=System.nanoTime();double dt=lastFrameNanos==0?0:Math.clamp((now-lastFrameNanos)/1e9,0,.05);lastFrameNanos=now;
        if(!eligible()) { ACCESS.clear();frame=null;return; }
        var content=ACCESS.content();if(content==null) return;
        var client=MinecraftClient.getInstance();var window=client.getWindow();
        int width=window.getScaledWidth(),height=window.getScaledHeight();
        double top=6,padding=8;
        var overlay=RelayLyricsClient.overlay();
        if(overlay!=null && overlay.isHudVisible() && overlay.dockFrameSnapshot()!=null)
            top=overlay.dockFrameSnapshot().bounds().bottom()+6;
        if(top>height-40) top=6;
        double scale=Math.max(.05,Math.min(content.scale(),Math.min((width-24.)/Math.max(1,content.width()),(height-top-24.)/Math.max(1,content.height()))));
        double targetWidth=content.width()*scale+2*padding,targetHeight=content.height()*scale+2*padding;
        boolean visible=now-content.receivedNanos()<120_000_000L && !client.options.playerListKey.isPressed();
        frame=MOTION.step(visible,targetWidth,targetHeight,top,width/2.,dt,RelayLyricsClient.preferences().isLyricsAnimations());
        if(frame.opacity()<.005) return;
        var box=frame.bounds();
        int x=(int)Math.round(box.x()),y=(int)Math.round(box.y()),w=Math.max(1,(int)Math.round(box.width())),h=Math.max(1,(int)Math.round(box.height()));
        int alpha=(int)Math.round(255*frame.opacity());
        RoundedGui.smoothFill(context,x,y,w,h,Math.max(1,Math.min(14,h/2)),alpha<<24|0x101010);
        context.enableScissor(x,y,x+w,y+h);
        GuiTransform.push(context);
        try {
            GuiTransform.translate(context,(float)Math.round(box.centerX()-content.width()*scale/2),
                    (float)Math.round(box.centerY()-content.height()*scale/2-3*(1-frame.opacity())));
            GuiTransform.scale(context,(float)scale,(float)scale);
            ACCESS.render(content,frame.opacity(),()->{
                if(content.root() instanceof Drawable drawable) drawable.render(context,-1,-1,0);
                else ACCESS.renderLegacy(content,context);
            });
        } catch(ReflectiveOperationException | RuntimeException error) { ACCESS.fail(error); }
        finally { GuiTransform.pop(context);context.disableScissor(); }
    }
}
