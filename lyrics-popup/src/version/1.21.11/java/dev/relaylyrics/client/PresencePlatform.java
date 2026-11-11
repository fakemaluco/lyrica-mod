package dev.relaylyrics.client;

import dev.relaylyrics.client.karaoke.KaraokeClient;

import dev.relaylyrics.client.presence.*;
import dev.relaylyrics.client.ui.MusicPresenceRenderer;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.util.hit.HitResult;
import java.util.*;

/** Yarn platform adapter; never sends the Minecraft access token to Supabase. */
public final class PresencePlatform {
    private PresencePlatform() { }
    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registries) -> dispatcher.register(
                ClientCommandManager.literal("music")
                .executes(ctx -> MusicPresence.show(MinecraftClient.getInstance().getSession().getUsername()))
                .then(ClientCommandManager.literal("listen")
                        .then(ClientCommandManager.literal("stop").executes(ctx -> KaraokeClient.stopListening()))
                        .then(ClientCommandManager.argument("host", StringArgumentType.string()).suggests((ctx, builder) -> {
                            for (String name : names())
                                if (name.toLowerCase(Locale.ROOT).startsWith(builder.getRemainingLowerCase())) builder.suggest(name);
                            return builder.buildFuture();
                        }).executes(ctx -> KaraokeClient.listen(StringArgumentType.getString(ctx, "host")))))
                .then(ClientCommandManager.literal("sharing")
                        .then(ClientCommandManager.literal("on").executes(ctx -> MusicPresence.sharing(true)))
                        .then(ClientCommandManager.literal("off").executes(ctx -> MusicPresence.sharing(false))))
                .then(ClientCommandManager.argument("player", StringArgumentType.word()).suggests((ctx, builder) -> {
                    for (String name : names()) if (name.toLowerCase(Locale.ROOT).startsWith(builder.getRemainingLowerCase())) builder.suggest(name);
                    return builder.buildFuture();
                }).executes(ctx -> MusicPresence.show(StringArgumentType.getString(ctx, "player"))))));
        HudRenderCallback.EVENT.register((context, counter) -> MusicPresenceRenderer.render(context, counter.getTickProgress(false)));
    }
    public static MusicPresence.Context context() {
        var client = MinecraftClient.getInstance(); var network = client.getNetworkHandler();
        var server = client.getCurrentServerEntry();
        if (client.world == null || client.player == null || network == null) return null;
        String address = server == null ? client.getServer() != null && client.getServer().isRemote() ? "localhost:" + client.getServer().getServerPort() : null : server.address;
        if (address == null) return null;
        var account = client.getSession().getUuidOrNull(); if (account == null) account = client.player.getUuid();
        try {
            Set<UUID> players = new HashSet<>();
            network.getPlayerList().forEach(p -> players.add(p.getProfile().id()));
            return new MusicPresence.Context(new MusicPresenceClient.Identity(account, client.player.getUuid(),
                    client.getSession().getUsername(), PresenceScope.serverHash(address)), Set.copyOf(players));
        } catch (IllegalArgumentException ignored) { return null; }
    }
    public static void prove(String serverId) throws Exception {
        var client = MinecraftClient.getInstance(); var session = client.getSession();
        client.getApiServices().sessionService().joinServer(session.getUuidOrNull(), session.getAccessToken(), serverId);
    }
    public static UUID lookingAt() {
        var client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null || client.currentScreen != null || client.options.hudHidden) return null;
        Vec3d eye = WorldLyricsRenderHook.cameraPosition(client);
        var direction = new org.joml.Vector3f(0, 0, -1).rotate(client.gameRenderer.getCamera().getRotation());
        Vec3d end = eye.add(direction.x * 24, direction.y * 24, direction.z * 24);
        var block = client.world.raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, client.player));
        double closest = block.getType() == HitResult.Type.MISS ? 24 * 24 : eye.squaredDistanceTo(block.getPos());
        UUID target = null;
        for (var player : client.world.getPlayers()) {
            if (player == client.player || !player.isAlive() || player.isInvisibleTo(client.player) || MusicPresence.track(player.getUuid()) == null) continue;
            var hit = player.getBoundingBox().expand(.12).raycast(eye, end);
            if (hit.isPresent()) { double distance = eye.squaredDistanceTo(hit.get());
                if (distance < closest) { closest = distance; target = player.getUuid(); }
            }
        }
        double card = MusicPresenceRenderer.hoveredCardDistance();
        if (card * card < closest) return MusicPresence.pinned();
        return target;
    }
    public static double[] anchor(UUID id, float delta) {
        var client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) return null;
        for (var p : client.world.getPlayers()) if (p.getUuid().equals(id) && p.isAlive() && !p.isInvisibleTo(client.player) && p.squaredDistanceTo(client.player) <= 24 * 24) {
            var pos = p.getLerpedPos(delta).add(0, p.getHeight() * .75, 0);
            var eye = WorldLyricsRenderHook.cameraPosition(client);
            var block = client.world.raycast(new RaycastContext(eye, pos, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, client.player));
            if (block.getType() != HitResult.Type.MISS && eye.squaredDistanceTo(block.getPos()) + .1 < eye.squaredDistanceTo(pos)) return null;
            return new double[]{pos.x - eye.x, pos.y - eye.y, pos.z - eye.z};
        }
        return null;
    }
    /** Pose follows the remote player's body, independent of the viewer's camera rotation. */
    public static double[] cardPose(UUID id, float delta, int side) {
        var client = MinecraftClient.getInstance();
        var anchor = anchor(id, delta); if (anchor == null) return null;
        for (var p : client.world.getPlayers()) if (p.getUuid().equals(id)) {
            double yaw = Math.PI - Math.toRadians(p.bodyYaw);
            return new double[]{anchor[0], anchor[1] + .55, anchor[2], yaw, side};
        }
        return null;
    }
    public static boolean cardBlocked(double[] pose) {
        var client = MinecraftClient.getInstance();
        var eye = WorldLyricsRenderHook.cameraPosition(client);
        double x = pose[0] - Math.cos(pose[3]) * .90 * pose[4] + eye.x;
        double y = pose[1] - .38 + eye.y;
        double z = pose[2] + Math.sin(pose[3]) * .90 * pose[4] + eye.z;
        return client.world.getBlockCollisions(null, new net.minecraft.util.math.Box(x-.39,y-.43,z-.39,x+.39,y+.43,z+.39)).iterator().hasNext();
    }
    public static float renderDelta() { return MinecraftClient.getInstance().getRenderTickCounter().getTickProgress(false); }
    public static double fov(float delta) { var c = MinecraftClient.getInstance(); return ((dev.relaylyrics.mixin.PresenceCameraMixin)c.gameRenderer).lyrica$presenceFov(c.gameRenderer.getCamera(), delta, true); }
    public static List<String> names() {
        var network = MinecraftClient.getInstance().getNetworkHandler();
        return network == null ? List.of() : network.getPlayerList().stream().map(p -> p.getProfile().name()).toList();
    }
    public static UUID findPlayer(String name) {
        var network = MinecraftClient.getInstance().getNetworkHandler();
        if (network != null) for (var p : network.getPlayerList()) if (p.getProfile().name().equalsIgnoreCase(name)) return p.getProfile().id();
        return null;
    }
    public static void message(String key, Object... args) {
        var client = MinecraftClient.getInstance(); client.execute(() -> client.inGameHud.getChatHud().addMessage(Text.translatable(key, args)));
    }
    public static void clearArtwork() { MusicPresenceRenderer.clear(); }
    public static void probeTick() { dev.relaylyrics.client.ui.MusicPresenceProbe.tick(); }
}
