package dev.fandroid.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.Uuids;

import java.util.UUID;

public record ServerAckPayload(UUID messageId) implements CustomPayload {
    public static final Id<ServerAckPayload> ID = new Id<>(Identifier.of("fandroid", "ack_v1"));
    public static final PacketCodec<RegistryByteBuf, ServerAckPayload> CODEC = PacketCodec.tuple(
            Uuids.PACKET_CODEC, ServerAckPayload::messageId, ServerAckPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
