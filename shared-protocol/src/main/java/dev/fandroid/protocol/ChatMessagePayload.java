package dev.fandroid.protocol;

import java.time.Instant;
import java.util.UUID;

public record ChatMessagePayload(
        String text,
        UUID replyTo,
        VoiceAttachment voice,
        Instant clientCreatedAt
) {
    public ChatMessagePayload {
        if ((text == null || text.isBlank()) && voice == null) {
            throw new IllegalArgumentException("a message needs text or voice");
        }
        if (text != null && text.length() > 8_192) {
            throw new IllegalArgumentException("message is too long");
        }
    }
}

