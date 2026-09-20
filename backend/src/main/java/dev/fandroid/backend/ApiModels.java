package dev.fandroid.backend;

import dev.fandroid.protocol.ConversationKind;

import java.util.Set;
import java.util.UUID;

public final class ApiModels {
    private ApiModels() {
    }

    public record ContactRequest(UUID accountId) {
    }

    public record ConversationRequest(ConversationKind kind, String title, Set<UUID> members) {
    }

    public record MemberRequest(UUID accountId) {
    }

    public record IdResponse(UUID id) {
    }

    public record ApiError(String code, String message) {
    }
}

