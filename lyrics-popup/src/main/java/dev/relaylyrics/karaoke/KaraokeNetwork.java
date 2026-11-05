package dev.relaylyrics.karaoke;

import com.google.gson.JsonObject;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;

import static dev.relaylyrics.karaoke.KaraokeWire.*;

public final class KaraokeNetwork implements ModInitializer {
    private static final Map<MinecraftServer, KaraokeHub> HUBS = new IdentityHashMap<>();
    private static KaraokeHub hub(MinecraftServer server) {
        return HUBS.computeIfAbsent(server, ignored -> new KaraokeHub());
    }

    public record Packet(byte[] data) implements CustomPayload {
        public static final Id<Packet> ID = new Id<>(Identifier.of("lyrica", "karaoke_v1"));
        private static final PacketCodec<RegistryByteBuf, Packet> CODEC = PacketCodec.ofStatic(
                (buffer, packet) -> buffer.writeByteArray(packet.data), buffer -> new Packet(buffer.readByteArray(MAX_BYTES)));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    @Override public void onInitialize() {
        PayloadTypeRegistry.playC2S().register(Packet.ID, Packet.CODEC);
        PayloadTypeRegistry.playS2C().register(Packet.ID, Packet.CODEC);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            var player = handler.player;
            if (ServerPlayNetworking.canSend(player, Packet.ID)) hub(server).join(new Player(player));
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            var state = HUBS.get(server);
            if (state != null) state.leave(handler.player.getUuid());
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(HUBS::remove);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            var state = HUBS.get(server);
            if (state != null) state.tick(System.currentTimeMillis());
        });
        ServerPlayNetworking.registerGlobalReceiver(Packet.ID, (packet, context) -> {
            try {
                var json = decode(packet.data());
                if (json != null) hub(context.server()).receive(new Player(context.player()), json, System.currentTimeMillis());
            } catch (RuntimeException ignored) { /* Invalid peer data must not affect the server. */ }
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> dispatcher.register(
                CommandManager.literal("karaoke")
                        .then(CommandManager.literal("pop-up").executes(ctx -> {
                            var player = ctx.getSource().getPlayerOrThrow();
                            var peer = new Player(player);
                            var pos = peer.position();
                            double yaw = Math.toRadians(pos.yaw());
                            var panel = new Panel(pos.dimension(), pos.x() - Math.sin(yaw) * 3.5,
                                    pos.y() + 1.65, pos.z() + Math.cos(yaw) * 3.5, pos.yaw() + (pos.yaw() <= 0 ? 180 : -180));
                            if (!hub(ctx.getSource().getServer()).open(peer, panel, System.currentTimeMillis())) {
                                ctx.getSource().sendError(Text.translatable("karaoke.lyrica.client_required"));
                                return 0;
                            }
                            return 1;
                        }))
                        .then(CommandManager.literal("stop").executes(ctx ->
                                hub(ctx.getSource().getServer()).stop(ctx.getSource().getPlayerOrThrow().getUuid()) ? 1 : 0))));
    }

    private record Player(ServerPlayerEntity player) implements KaraokeHub.Peer {
        @Override public UUID id() { return player.getUuid(); }
        @Override public String name() { return player.getName().getString(); }
        @Override public Panel position() {
            return new Panel(player.getCommandSource().getWorld().getRegistryKey().getValue().toString(),
                    player.getX(), player.getY(), player.getZ(), Math.IEEEremainder(player.getYaw(), 360));
        }
        @Override public void send(JsonObject json) {
            byte[] bytes = encode(json);
            if (bytes.length <= MAX_BYTES && ServerPlayNetworking.canSend(player, Packet.ID))
                ServerPlayNetworking.send(player, new Packet(bytes));
        }
    }
}
