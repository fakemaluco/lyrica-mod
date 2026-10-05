package dev.relaylyrics.client;

import dev.relaylyrics.client.ui.WorldLyricsRenderer;
import dev.relaylyrics.client.ui.WorldBuffers;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

/** Minecraft 26.2 world-render submit adapter. */
public final class WorldLyricsRenderHook {
    private WorldLyricsRenderHook() { }
    public static boolean gliding(net.minecraft.client.player.LocalPlayer player) { return player.isFallFlying(); }

    public static void register() {
        LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
            WorldBuffers buffers = new WorldBuffers();
            WorldLyricsRenderer.render(context.poseStack(), buffers,
                    context.gameRenderer().mainCamera().position());
            buffers.submit(context.submitNodeCollector());
        });
    }
}
