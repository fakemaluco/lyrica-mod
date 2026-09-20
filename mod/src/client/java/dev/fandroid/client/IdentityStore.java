package dev.fandroid.client;

import dev.fandroid.protocol.ProtocolCodec;
import dev.fandroid.protocol.crypto.DeviceIdentity;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public final class IdentityStore {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("fandroid/device-identity.json");

    private IdentityStore() {
    }

    public static DeviceIdentity loadOrCreate() {
        try {
            if (Files.exists(FILE)) {
                StoredIdentity stored = ProtocolCodec.mapper().readValue(Files.readAllBytes(FILE), StoredIdentity.class);
                return DeviceIdentity.restore(stored.deviceId(), stored.hpkePublicKey(), stored.hpkePrivateKey(),
                        stored.signingPrivateKey());
            }
            DeviceIdentity identity = DeviceIdentity.generate(UUID.randomUUID());
            Files.createDirectories(FILE.getParent());
            var bundle = identity.publicBundle();
            Files.write(FILE, ProtocolCodec.mapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(
                    new StoredIdentity(identity.deviceId(), bundle.hpkePublicKey(), identity.hpkePrivateKey(),
                            identity.signingPrivateKey())));
            return identity;
        } catch (Exception e) {
            throw new IllegalStateException("could not load FanDroid device identity", e);
        }
    }

    private record StoredIdentity(UUID deviceId, byte[] hpkePublicKey, byte[] hpkePrivateKey,
                                  byte[] signingPrivateKey) {
    }
}

