package dev.fandroid.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record CapabilitiesPayload(int protocolVersion, int flags) implements CustomPayload {
    public static final Id<CapabilitiesPayload> ID = new Id<>(Identifier.of("fandroid", "capabilities_v1"));
    public static final PacketCodec<RegistryByteBuf, CapabilitiesPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.INTEGER, CapabilitiesPayload::protocolVersion,
            PacketCodecs.INTEGER, CapabilitiesPayload::flags,
            CapabilitiesPayload::new
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}

