package dev.relaylyrics.client;

import dev.relaylyrics.client.ui.WorldLyricsRenderer;
import dev.relaylyrics.client.ui.WorldBuffers;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

/** Minecraft 26.2 world-render submit adapter. */
public final class WorldLyricsRenderHook {
    private WorldLyricsRenderHook() { }
    public static net.minecraft.world.phys.Vec3 cameraPosition(net.minecraft.client.Minecraft client) { return client.gameRenderer.mainCamera().position(); }
    public static net.minecraft.resources.Identifier defaultFont() { return net.minecraft.resources.Identifier.fromNamespaceAndPath("relaylyrics", "world"); }
    public static boolean gliding(net.minecraft.client.player.LocalPlayer player) { return player.isFallFlying(); }

    public static void register() {
        LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
            WorldBuffers buffers = new WorldBuffers();
            dev.relaylyrics.client.ui.MusicPresenceRenderer.renderWorld(context.poseStack(), buffers, context.gameRenderer().mainCamera().position());
            dev.relaylyrics.client.karaoke.KaraokePlatform.render(context.poseStack(), buffers, context.gameRenderer().mainCamera().position(), context.gameRenderer().mainCamera().getCullFrustum());
            WorldLyricsRenderer.render(context.poseStack(), buffers,
                    context.gameRenderer().mainCamera().position());
            buffers.submit(context.submitNodeCollector());
        });
    }
}
