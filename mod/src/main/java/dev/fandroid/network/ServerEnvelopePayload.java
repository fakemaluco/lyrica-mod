package dev.fandroid.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record ServerEnvelopePayload(String json) implements CustomPayload {
    public static final Id<ServerEnvelopePayload> ID = new Id<>(Identifier.of("fandroid", "envelope_v1"));
    public static final PacketCodec<RegistryByteBuf, ServerEnvelopePayload> CODEC = PacketCodec.tuple(
            PacketCodecs.string(1_500_000), ServerEnvelopePayload::json, ServerEnvelopePayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
