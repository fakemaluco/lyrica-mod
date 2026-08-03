package dev.fandroid.protocol.crypto;

import dev.fandroid.protocol.DeviceKeyBundle;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.hpke.HPKE;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;

import java.security.SecureRandom;
import java.util.UUID;

public final class DeviceIdentity {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HPKE HPKE_SUITE = new HPKE(
            HPKE.mode_base,
            HPKE.kem_X25519_SHA256,
            HPKE.kdf_HKDF_SHA256,
            HPKE.aead_AES_GCM256
    );

    private final UUID deviceId;
    private final AsymmetricCipherKeyPair hpkeKeyPair;
    private final Ed25519PrivateKeyParameters signingKey;

    private DeviceIdentity(UUID deviceId, AsymmetricCipherKeyPair hpkeKeyPair,
                           Ed25519PrivateKeyParameters signingKey) {
        this.deviceId = deviceId;
        this.hpkeKeyPair = hpkeKeyPair;
        this.signingKey = signingKey;
    }

    public static DeviceIdentity generate(UUID deviceId) {
        return new DeviceIdentity(deviceId, HPKE_SUITE.generatePrivateKey(),
                new Ed25519PrivateKeyParameters(RANDOM));
    }

    public static DeviceIdentity restore(UUID deviceId, byte[] hpkePublicKey,
                                         byte[] hpkePrivateKey, byte[] signingPrivateKey) {
        return new DeviceIdentity(
                deviceId,
                HPKE_SUITE.deserializePrivateKey(hpkePublicKey, hpkePrivateKey),
                new Ed25519PrivateKeyParameters(signingPrivateKey, 0)
        );
    }

    public DeviceKeyBundle publicBundle() {
        return new DeviceKeyBundle(
                deviceId,
                HPKE_SUITE.serializePublicKey(hpkeKeyPair.getPublic()),
                signingKey.generatePublicKey().getEncoded()
        );
    }

    public byte[] hpkePrivateKey() {
        return HPKE_SUITE.serializePrivateKey(hpkeKeyPair.getPrivate());
    }

    public byte[] signingPrivateKey() {
        return signingKey.getEncoded();
    }

    public UUID deviceId() {
        return deviceId;
    }

    AsymmetricCipherKeyPair hpkeKeyPair() {
        return hpkeKeyPair;
    }

    Ed25519PrivateKeyParameters signingKey() {
        return signingKey;
    }
}

