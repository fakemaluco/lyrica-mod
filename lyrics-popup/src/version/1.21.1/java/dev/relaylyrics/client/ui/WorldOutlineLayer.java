package dev.relaylyrics.client.ui;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.util.Identifier;

final class WorldOutlineLayer {
    private WorldOutlineLayer() { }

    static RenderLayer create(Identifier id) { return RenderLayer.getText(id); }
}
