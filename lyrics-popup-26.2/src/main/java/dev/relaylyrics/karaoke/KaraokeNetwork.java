package dev.relaylyrics.karaoke;

import com.google.gson.JsonObject;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;

import static dev.relaylyrics.karaoke.KaraokeWire.*;

public final class KaraokeNetwork implements ModInitializer {
    private static final Map<MinecraftServer, KaraokeHub> HUBS = new IdentityHashMap<>();
    private static KaraokeHub hub(MinecraftServer server) {
        return HUBS.computeIfAbsent(server, ignored -> new KaraokeHub());
    }

    public record Packet(byte[] data) implements CustomPacketPayload {
        public static final Type<Packet> ID = new Type<>(Identifier.fromNamespaceAndPath("lyrica", "karaoke_v1"));
        private static final StreamCodec<RegistryFriendlyByteBuf, Packet> CODEC = StreamCodec.of(
                (buffer, packet) -> buffer.writeByteArray(packet.data), buffer -> new Packet(buffer.readByteArray(MAX_BYTES)));
        @Override public Type<? extends CustomPacketPayload> type() { return ID; }
    }

    @Override public void onInitialize() {
        PayloadTypeRegistry.serverboundPlay().register(Packet.ID, Packet.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Packet.ID, Packet.CODEC);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            var player = handler.player;
            if (ServerPlayNetworking.canSend(player, Packet.ID)) hub(server).join(new Player(player));
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            var state = HUBS.get(server);
            if (state != null) state.leave(handler.player.getUUID());
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
                Commands.literal("karaoke")
                        .then(Commands.literal("pop-up").executes(ctx -> {
                            var player = ctx.getSource().getPlayerOrException();
                            var peer = new Player(player);
                            var pos = peer.position();
                            double yaw = Math.toRadians(pos.yaw());
                            var panel = new Panel(pos.dimension(), pos.x() - Math.sin(yaw) * 3.5,
                                    pos.y() + 1.65, pos.z() + Math.cos(yaw) * 3.5, pos.yaw() + (pos.yaw() <= 0 ? 180 : -180));
                            if (!hub(ctx.getSource().getServer()).open(peer, panel, System.currentTimeMillis())) {
                                ctx.getSource().sendFailure(Component.translatable("karaoke.lyrica.client_required"));
                                return 0;
                            }
                            return 1;
                        }))
                        .then(Commands.literal("stop").executes(ctx ->
                                hub(ctx.getSource().getServer()).stop(ctx.getSource().getPlayerOrException().getUUID()) ? 1 : 0))));
    }

    private record Player(ServerPlayer player) implements KaraokeHub.Peer {
        @Override public UUID id() { return player.getUUID(); }
        @Override public String name() { return player.getName().getString(); }
        @Override public Panel position() {
            return new Panel(player.level().dimension().identifier().toString(),
                    player.getX(), player.getY(), player.getZ(), Math.IEEEremainder(player.getYRot(), 360));
        }
        @Override public void send(JsonObject json) {
            byte[] bytes = encode(json);
            if (bytes.length <= MAX_BYTES && ServerPlayNetworking.canSend(player, Packet.ID))
                ServerPlayNetworking.send(player, new Packet(bytes));
        }
    }
}
