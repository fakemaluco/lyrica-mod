package dev.relaylyrics.client;

import dev.relaylyrics.client.presence.*;
import dev.relaylyrics.client.ui.MusicPresenceRenderer;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.resources.Identifier;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import java.util.*;

/** Yarn platform adapter; never sends the Minecraft access token to Supabase. */
public final class PresencePlatform {
    private PresencePlatform() { }
    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registries) -> dispatcher.register(
                ClientCommands.literal("music")
                .executes(ctx -> MusicPresence.show(Minecraft.getInstance().getUser().getName()))
                .then(ClientCommands.literal("sharing")
                        .then(ClientCommands.literal("on").executes(ctx -> MusicPresence.sharing(true)))
                        .then(ClientCommands.literal("off").executes(ctx -> MusicPresence.sharing(false))))
                .then(ClientCommands.argument("player", StringArgumentType.word()).suggests((ctx, builder) -> {
                    for (String name : names()) if (name.toLowerCase(Locale.ROOT).startsWith(builder.getRemainingLowerCase())) builder.suggest(name);
                    return builder.buildFuture();
                }).executes(ctx -> MusicPresence.show(StringArgumentType.getString(ctx, "player"))))));
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("relaylyrics", "music_presence"), (context, counter) -> MusicPresenceRenderer.render(context, counter.getGameTimeDeltaPartialTick(false)));
    }
    public static MusicPresence.Context context() {
        var client = Minecraft.getInstance(); var network = client.getConnection();
        var server = client.getCurrentServer();
        if (client.level == null || client.player == null || network == null) return null;
        String address = server == null ? client.getSingleplayerServer() != null && client.getSingleplayerServer().isPublished() ? "localhost:" + client.getSingleplayerServer().getPort() : null : server.ip;
        if (address == null) return null;
        var account = client.getUser().getProfileId(); if (account == null) account = client.player.getUUID();
        try {
            Set<UUID> players = new HashSet<>();
            network.getOnlinePlayers().forEach(p -> players.add(p.getProfile().id()));
            return new MusicPresence.Context(new MusicPresenceClient.Identity(account, client.player.getUUID(),
                    client.getUser().getName(), PresenceScope.serverHash(address)), Set.copyOf(players));
        } catch (IllegalArgumentException ignored) { return null; }
    }
    public static void prove(String serverId) throws Exception {
        var client = Minecraft.getInstance(); var session = client.getUser();
        client.services().sessionService().joinServer(session.getProfileId(), session.getAccessToken(), serverId);
    }
    public static UUID lookingAt() {
        var client = Minecraft.getInstance();
        if (client.level == null || client.player == null || client.gui.screen() != null || client.gui.hud.isHidden()) return null;
        Vec3 eye = WorldLyricsRenderHook.cameraPosition(client);
        var direction = new org.joml.Vector3f(0, 0, -1).rotate(client.gameRenderer.mainCamera().rotation());
        Vec3 end = eye.add(direction.x * 24, direction.y * 24, direction.z * 24);
        var block = client.level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, client.player));
        double closest = block.getType() == HitResult.Type.MISS ? 24 * 24 : eye.distanceToSqr(block.getLocation());
        UUID target = null;
        for (var player : client.level.players()) {
            if (player == client.player || !player.isAlive() || player.isInvisibleTo(client.player) || MusicPresence.track(player.getUUID()) == null) continue;
            var hit = player.getBoundingBox().inflate(.12).clip(eye, end);
            if (hit.isPresent()) { double distance = eye.distanceToSqr(hit.get());
                if (distance < closest) { closest = distance; target = player.getUUID(); }
            }
        }
        double card = MusicPresenceRenderer.hoveredCardDistance();
        if (card * card < closest) return MusicPresence.pinned();
        return target;
    }
    public static double[] anchor(UUID id, float delta) {
        var client = Minecraft.getInstance();
        if (client.level == null || client.player == null) return null;
        for (var p : client.level.players()) if (p.getUUID().equals(id) && p.isAlive() && !p.isInvisibleTo(client.player) && p.distanceToSqr(client.player) <= 24 * 24) {
            var pos = p.getPosition(delta).add(0, p.getBbHeight() * .75, 0);
            var eye = WorldLyricsRenderHook.cameraPosition(client);
            var block = client.level.clip(new ClipContext(eye, pos, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, client.player));
            if (block.getType() != HitResult.Type.MISS && eye.distanceToSqr(block.getLocation()) + .1 < eye.distanceToSqr(pos)) return null;
            return new double[]{pos.x - eye.x, pos.y - eye.y, pos.z - eye.z};
        }
        return null;
    }
    /** Pose follows the remote player's body, independent of the viewer's camera rotation. */
    public static double[] cardPose(UUID id, float delta, int side) {
        var client = Minecraft.getInstance();
        var anchor = anchor(id, delta); if (anchor == null) return null;
        for (var p : client.level.players()) if (p.getUUID().equals(id)) {
            double yaw = Math.PI - Math.toRadians(p.yBodyRot);
            return new double[]{anchor[0], anchor[1] + .55, anchor[2], yaw, side};
        }
        return null;
    }
    public static boolean cardBlocked(double[] pose) {
        var client = Minecraft.getInstance();
        var eye = WorldLyricsRenderHook.cameraPosition(client);
        double x = pose[0] - Math.cos(pose[3]) * .90 * pose[4] + eye.x;
        double y = pose[1] - .38 + eye.y;
        double z = pose[2] + Math.sin(pose[3]) * .90 * pose[4] + eye.z;
        return client.level.getBlockCollisions(null, new net.minecraft.world.phys.AABB(x-.39,y-.43,z-.39,x+.39,y+.43,z+.39)).iterator().hasNext();
    }
    public static float renderDelta() { return Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false); }
    public static double fov(float delta) { var c = Minecraft.getInstance(); return c.gameRenderer.mainCamera().getFov(); }
    public static List<String> names() {
        var network = Minecraft.getInstance().getConnection();
        return network == null ? List.of() : network.getOnlinePlayers().stream().map(p -> p.getProfile().name()).toList();
    }
    public static UUID findPlayer(String name) {
        var network = Minecraft.getInstance().getConnection();
        if (network != null) for (var p : network.getOnlinePlayers()) if (p.getProfile().name().equalsIgnoreCase(name)) return p.getProfile().id();
        return null;
    }
    public static void message(String key, Object... args) {
        var client = Minecraft.getInstance(); client.execute(() -> client.gui.hud.getChat().addClientSystemMessage(Component.translatable(key, args)));
    }
    public static void clearArtwork() { MusicPresenceRenderer.clear(); }
    public static void probeTick() { dev.relaylyrics.client.ui.MusicPresenceProbe.tick(); }
}
