package dev.relaylyrics.client;

import dev.relaylyrics.client.ui.WorldLyricsRenderer;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;

/** Minecraft 1.21.11 world-render adapter. */
public final class WorldLyricsRenderHook {
    private WorldLyricsRenderHook() { }
    public static net.minecraft.util.Identifier defaultFont() { return net.minecraft.util.Identifier.of("relaylyrics", "world"); }
    public static void probeCommand(net.minecraft.server.MinecraftServer server, String command) { server.getCommandManager().parseAndExecute(server.getCommandSource(), command); }
    public static net.minecraft.util.math.Vec3d cameraPosition(net.minecraft.client.MinecraftClient client) { return client.gameRenderer.getCamera().getCameraPos(); }
    public static boolean gliding(net.minecraft.client.network.ClientPlayerEntity player) { return player.isGliding(); }

    public static void register() {
        WorldRenderEvents.AFTER_ENTITIES.register(context -> WorldLyricsRenderer.render(
                context.matrices(), context.consumers(), context.gameRenderer().getCamera().getCameraPos()));
    }
}
