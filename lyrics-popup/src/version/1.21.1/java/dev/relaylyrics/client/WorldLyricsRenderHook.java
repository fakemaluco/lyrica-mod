package dev.relaylyrics.client;

import dev.relaylyrics.client.ui.WorldLyricsRenderer;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

/** Minecraft 1.21.1 world-render adapter. */
public final class WorldLyricsRenderHook {
    private WorldLyricsRenderHook() { }

    public static void register() {
        WorldRenderEvents.AFTER_ENTITIES.register(context -> WorldLyricsRenderer.render(
                context.matrixStack(), context.consumers(), context.camera().getPos()));
    }
}
