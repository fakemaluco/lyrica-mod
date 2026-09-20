package dev.fandroid.server;

import dev.fandroid.FanDroidMod;
import dev.fandroid.network.CallSignalPayload;
import dev.fandroid.network.CapabilitiesPayload;
import dev.fandroid.network.CapabilityFlags;
import dev.fandroid.network.ServerAckPayload;
import dev.fandroid.network.ServerDirectoryPayload;
import dev.fandroid.network.ServerEnvelopePayload;
import dev.fandroid.network.ServerHelloPayload;
import dev.fandroid.protocol.DeviceKeyBundle;
import dev.fandroid.protocol.MessageEnvelope;
import dev.fandroid.protocol.ProtocolCodec;
import dev.fandroid.voice.VoiceCallRegistry;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.WorldSavePath;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class FanDroidServer {
    private static final Map<UUID, ConnectedDevice> DEVICES_BY_PLAYER = new ConcurrentHashMap<>();
    private static volatile ServerEnvelopeQueue queue;

    private FanDroidServer() {
    }

    public static void register() {
        PayloadTypeRegistry.playS2C().register(CapabilitiesPayload.ID, CapabilitiesPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ServerDirectoryPayload.ID, ServerDirectoryPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ServerEnvelopePayload.ID, ServerEnvelopePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(CallSignalPayload.ID, CallSignalPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(ServerHelloPayload.ID, ServerHelloPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(ServerEnvelopePayload.ID, ServerEnvelopePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(ServerAckPayload.ID, ServerAckPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(CallSignalPayload.ID, CallSignalPayload.CODEC);

        ServerLifecycleEvents.SERVER_STARTED.register(server ->
                queue = new ServerEnvelopeQueue(server.getSavePath(WorldSavePath.ROOT).resolve("fandroid/queue.db")));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (queue != null) queue.close();
            queue = null;
            DEVICES_BY_PLAYER.clear();
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            int flags = CapabilityFlags.SERVER_CHAT | CapabilityFlags.LOCAL_GROUPS;
            if (FabricLoader.getInstance().isModLoaded("voicechat")) {
                flags |= CapabilityFlags.VOICE_MESSAGES | CapabilityFlags.CALLS;
            }
            ServerPlayNetworking.send(handler.player, new CapabilitiesPayload(1, flags));
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            DEVICES_BY_PLAYER.remove(handler.player.getUuid());
            broadcastDirectory(server);
        });

        ServerPlayNetworking.registerGlobalReceiver(ServerHelloPayload.ID, (payload, context) -> {
            if (payload.hpkePublicKey().length > 128 || payload.signingPublicKey().length != 32) return;
            DEVICES_BY_PLAYER.put(context.player().getUuid(), new ConnectedDevice(
                    context.player().getUuid(), context.player().getName().getString(),
                    new DeviceKeyBundle(payload.deviceId(), payload.hpkePublicKey(), payload.signingPublicKey())));
            sendPending(context.player().getUuid(), context.player());
            broadcastDirectory(context.server());
        });
        ServerPlayNetworking.registerGlobalReceiver(ServerEnvelopePayload.ID, (payload, context) -> {
            if (payload.json().length() > 1_500_000 || queue == null) return;
            MessageEnvelope envelope = ProtocolCodec.read(payload.json().getBytes(java.nio.charset.StandardCharsets.UTF_8), MessageEnvelope.class);
            if (!envelope.senderUuid().equals(context.player().getUuid())) return;
            if (envelope.expiresAt().isAfter(Instant.now().plus(7, ChronoUnit.DAYS))) return;
            queue.store(envelope);
            for (var recipient : envelope.recipients()) {
                DEVICES_BY_PLAYER.values().stream()
                        .filter(device -> device.keys().deviceId().equals(recipient.deviceId()))
                        .findFirst()
                        .map(device -> context.server().getPlayerManager().getPlayer(device.playerId()))
                        .filter(java.util.Objects::nonNull)
                        .ifPresent(player -> ServerPlayNetworking.send(player, payload));
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(ServerAckPayload.ID, (payload, context) -> {
            ConnectedDevice device = DEVICES_BY_PLAYER.get(context.player().getUuid());
            if (device != null && queue != null) queue.acknowledge(device.keys().deviceId(), payload.messageId());
        });
        ServerPlayNetworking.registerGlobalReceiver(CallSignalPayload.ID, (payload, context) -> {
            try {
                String type = ProtocolCodec.mapper().readTree(payload.json()).path("type").asText();
                if (type.equals("RING") && VoiceCallRegistry.inCall(payload.peerId())) {
                    ServerPlayNetworking.send(context.player(), new CallSignalPayload(payload.peerId(), "{\"type\":\"BUSY\"}"));
                    return;
                }
                if (type.equals("RING")) VoiceCallRegistry.ring(context.player().getUuid(), payload.peerId());
                else if (type.equals("ANSWER")) VoiceCallRegistry.answer(context.player().getUuid(), payload.peerId());
                else if (type.equals("HANG_UP") || type.equals("BUSY") || type.equals("CANCEL") || type.equals("MISSED"))
                    VoiceCallRegistry.leave(context.player().getUuid());
                else if (type.equals("MUTE"))
                    VoiceCallRegistry.setMuted(context.player().getUuid(),
                            ProtocolCodec.mapper().readTree(payload.json()).path("muted").asBoolean());
            } catch (Exception ignored) { }
            var target = context.server().getPlayerManager().getPlayer(payload.peerId());
            if (target != null && payload.json().length() <= 16_384) {
                ServerPlayNetworking.send(target, new CallSignalPayload(context.player().getUuid(), payload.json()));
            }
        });
    }

    private static void sendPending(UUID playerId, net.minecraft.server.network.ServerPlayerEntity player) {
        ConnectedDevice device = DEVICES_BY_PLAYER.get(playerId);
        if (device == null || queue == null) return;
        for (MessageEnvelope envelope : queue.pending(device.keys().deviceId())) {
            ServerPlayNetworking.send(player, new ServerEnvelopePayload(
                    new String(ProtocolCodec.write(envelope), java.nio.charset.StandardCharsets.UTF_8)));
        }
    }

    private static void broadcastDirectory(net.minecraft.server.MinecraftServer server) {
        String json = new String(ProtocolCodec.write(DEVICES_BY_PLAYER.values()), java.nio.charset.StandardCharsets.UTF_8);
        ServerDirectoryPayload payload = new ServerDirectoryPayload(json);
        for (var player : server.getPlayerManager().getPlayerList()) {
            if (ServerPlayNetworking.canSend(player, ServerDirectoryPayload.ID)) {
                ServerPlayNetworking.send(player, payload);
            }
        }
    }

    public record ConnectedDevice(UUID playerId, String username, DeviceKeyBundle keys) {
    }
}
