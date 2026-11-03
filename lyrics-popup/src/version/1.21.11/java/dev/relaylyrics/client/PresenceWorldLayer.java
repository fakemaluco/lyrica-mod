package dev.relaylyrics.client;
public final class PresenceWorldLayer {
    private PresenceWorldLayer() { }
    public static net.minecraft.client.render.RenderLayer cover(net.minecraft.util.Identifier id, boolean fading) { return fading ? net.minecraft.client.render.RenderLayers.entityTranslucent(id) : net.minecraft.client.render.RenderLayers.entityCutoutNoCull(id); }
}
