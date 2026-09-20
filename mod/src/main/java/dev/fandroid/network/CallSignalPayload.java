package dev.fandroid.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.Uuids;

import java.util.UUID;

public record CallSignalPayload(UUID peerId, String json) implements CustomPayload {
    public static final Id<CallSignalPayload> ID = new Id<>(Identifier.of("fandroid", "call_signal_v1"));
    public static final PacketCodec<RegistryByteBuf, CallSignalPayload> CODEC = PacketCodec.tuple(
            Uuids.PACKET_CODEC, CallSignalPayload::peerId,
            PacketCodecs.STRING, CallSignalPayload::json,
            CallSignalPayload::new
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
