package dev.fandroid.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record ServerDirectoryPayload(String json) implements CustomPayload {
    public static final Id<ServerDirectoryPayload> ID = new Id<>(Identifier.of("fandroid", "directory_v1"));
    public static final PacketCodec<RegistryByteBuf, ServerDirectoryPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, ServerDirectoryPayload::json, ServerDirectoryPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}

