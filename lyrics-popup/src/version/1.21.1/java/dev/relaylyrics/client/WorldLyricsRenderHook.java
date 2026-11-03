package dev.relaylyrics.client;

import dev.relaylyrics.client.ui.WorldLyricsRenderer;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

/** Minecraft 1.21.1 world-render adapter. */
public final class WorldLyricsRenderHook {
    private WorldLyricsRenderHook() { }
    public static net.minecraft.util.Identifier defaultFont() { return net.minecraft.util.Identifier.of("relaylyrics", "world"); }
    public static void probeCommand(net.minecraft.server.MinecraftServer server, String command) { server.getCommandManager().executeWithPrefix(server.getCommandSource(), command); }
    public static net.minecraft.util.math.Vec3d cameraPosition(net.minecraft.client.MinecraftClient client) { return client.gameRenderer.getCamera().getPos(); }
    public static boolean gliding(net.minecraft.client.network.ClientPlayerEntity player) { return player.isFallFlying(); }

    public static void register() {
        WorldRenderEvents.AFTER_ENTITIES.register(context -> {
            dev.relaylyrics.client.ui.MusicPresenceRenderer.renderWorld(context.matrixStack(), context.consumers(), context.camera().getPos());
            WorldLyricsRenderer.render(context.matrixStack(), context.consumers(), context.camera().getPos());
        });
    }
}
