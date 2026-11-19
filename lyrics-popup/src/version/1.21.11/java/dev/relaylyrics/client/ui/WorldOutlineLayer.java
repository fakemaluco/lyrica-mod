package dev.relaylyrics.client.ui;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.util.Identifier;

final class WorldOutlineLayer {
    private WorldOutlineLayer() { }

    static RenderLayer create(Identifier id) { return RenderLayers.text(id); }
}
