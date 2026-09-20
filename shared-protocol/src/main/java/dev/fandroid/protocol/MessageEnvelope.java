package dev.fandroid.protocol;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MessageEnvelope(
        int version,
        UUID messageId,
        UUID conversationId,
        UUID senderUuid,
        UUID senderDeviceId,
        ChatTransport transport,
        MessageEventType eventType,
        UUID targetMessageId,
        Instant createdAt,
        Instant expiresAt,
        byte[] nonce,
        byte[] ciphertext,
        byte[] ciphertextHash,
        List<RecipientKeyEnvelope> recipients,
        byte[] signature
) {
    public static final int CURRENT_VERSION = 1;

    public MessageEnvelope {
        recipients = List.copyOf(recipients);
    }
}

