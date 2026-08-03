package dev.fandroid.backend.auth;

import dev.fandroid.protocol.DeviceKeyBundle;

import java.util.UUID;

public final class AuthModels {
    private AuthModels() {
    }

    public record ChallengeRequest(String username) {
    }

    public record ChallengeResponse(UUID challengeId, String serverId, long expiresAtEpochMillis) {
    }

    public record CompleteRequest(UUID challengeId, String username, DeviceKeyBundle device) {
    }

    public record SessionResponse(String token, UUID accountId, UUID deviceId, long expiresAtEpochMillis) {
    }

    public record Principal(UUID accountId, UUID deviceId) {
    }
}

