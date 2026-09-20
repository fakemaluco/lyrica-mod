package dev.fandroid.protocol;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record ConversationDescriptor(
        UUID id,
        ChatTransport transport,
        ConversationKind kind,
        String title,
        UUID ownerId,
        Set<UUID> members,
        Instant createdAt
) {
    public ConversationDescriptor {
        members = Set.copyOf(members);
    }
}

