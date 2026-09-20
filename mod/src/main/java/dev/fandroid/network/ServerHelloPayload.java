package dev.fandroid.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.Uuids;

import java.util.UUID;

public record ServerHelloPayload(UUID deviceId, byte[] hpkePublicKey, byte[] signingPublicKey) implements CustomPayload {
    public static final Id<ServerHelloPayload> ID = new Id<>(Identifier.of("fandroid", "hello_v1"));
    public static final PacketCodec<RegistryByteBuf, ServerHelloPayload> CODEC = PacketCodec.tuple(
            Uuids.PACKET_CODEC, ServerHelloPayload::deviceId,
            PacketCodecs.BYTE_ARRAY, ServerHelloPayload::hpkePublicKey,
            PacketCodecs.BYTE_ARRAY, ServerHelloPayload::signingPublicKey,
            ServerHelloPayload::new
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
