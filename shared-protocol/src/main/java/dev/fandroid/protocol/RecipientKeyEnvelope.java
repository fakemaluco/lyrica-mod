package dev.fandroid.protocol;

import java.util.UUID;

public record RecipientKeyEnvelope(UUID deviceId, byte[] encapsulation, byte[] ciphertext) {
}

