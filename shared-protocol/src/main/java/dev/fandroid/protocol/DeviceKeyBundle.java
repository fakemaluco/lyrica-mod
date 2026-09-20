package dev.fandroid.protocol;

import java.util.UUID;

public record DeviceKeyBundle(UUID deviceId, byte[] hpkePublicKey, byte[] signingPublicKey) {
}

