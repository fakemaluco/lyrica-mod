package dev.fandroid.protocol.crypto;

import dev.fandroid.protocol.ChatTransport;
import dev.fandroid.protocol.MessageEventType;

import java.time.Instant;
import java.util.UUID;

public record EnvelopeMetadata(
        UUID messageId,
        UUID conversationId,
        UUID senderUuid,
        ChatTransport transport,
        MessageEventType eventType,
        UUID targetMessageId,
        Instant createdAt,
        Instant expiresAt
) {
}

