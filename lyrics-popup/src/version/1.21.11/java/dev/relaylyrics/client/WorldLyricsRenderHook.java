package dev.relaylyrics.client;

import dev.relaylyrics.client.ui.WorldLyricsRenderer;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;

/** Minecraft 1.21.11 world-render adapter. */
public final class WorldLyricsRenderHook {
    private WorldLyricsRenderHook() { }
    public static boolean gliding(net.minecraft.client.network.ClientPlayerEntity player) { return player.isGliding(); }

    public static void register() {
        WorldRenderEvents.AFTER_ENTITIES.register(context -> WorldLyricsRenderer.render(
                context.matrices(), context.consumers(), context.gameRenderer().getCamera().getCameraPos()));
    }
}
