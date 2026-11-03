package dev.relaylyrics.client;
public final class PresenceWorldLayer {
    private PresenceWorldLayer() { }
    public static net.minecraft.client.render.RenderLayer cover(net.minecraft.util.Identifier id, boolean fading) { return fading ? net.minecraft.client.render.RenderLayer.getEntityTranslucent(id) : net.minecraft.client.render.RenderLayer.getEntityCutoutNoCull(id); }
}
