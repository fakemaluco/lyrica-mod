package dev.fandroid.backend.auth;

import dev.fandroid.backend.BackendConfig;
import dev.fandroid.backend.BackendRepository;
import dev.fandroid.backend.auth.AuthModels.ChallengeResponse;
import dev.fandroid.backend.auth.AuthModels.CompleteRequest;
import dev.fandroid.backend.auth.AuthModels.Principal;
import dev.fandroid.backend.auth.AuthModels.SessionResponse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AuthService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final BackendRepository repository;
    private final MojangSessionVerifier verifier;
    private final BackendConfig config;
    private final Clock clock;
    private final ConcurrentHashMap<UUID, Challenge> challenges = new ConcurrentHashMap<>();

    public AuthService(BackendRepository repository, MojangSessionVerifier verifier, BackendConfig config) {
        this(repository, verifier, config, Clock.systemUTC());
    }

    AuthService(BackendRepository repository, MojangSessionVerifier verifier, BackendConfig config, Clock clock) {
        this.repository = repository;
        this.verifier = verifier;
        this.config = config;
        this.clock = clock;
    }

    public ChallengeResponse challenge(String username) {
        validateUsername(username);
        byte[] serverBytes = new byte[20];
        RANDOM.nextBytes(serverBytes);
        String serverId = HexFormat.of().formatHex(serverBytes);
        UUID id = UUID.randomUUID();
        Instant expires = clock.instant().plus(Duration.ofMinutes(2));
        challenges.put(id, new Challenge(username, serverId, expires));
        return new ChallengeResponse(id, serverId, expires.toEpochMilli());
    }

    public SessionResponse complete(CompleteRequest request) {
        Challenge challenge = challenges.remove(request.challengeId());
        if (challenge == null || challenge.expiresAt().isBefore(clock.instant())) {
            throw new IllegalArgumentException("authentication challenge is missing or expired");
        }
        if (!challenge.username().equalsIgnoreCase(request.username())) {
            throw new IllegalArgumentException("username does not match challenge");
        }
        MojangSessionVerifier.VerifiedProfile profile = verifier.verify(request.username(), challenge.serverId())
                .orElseThrow(() -> new IllegalArgumentException("Mojang session proof was not found"));
        repository.upsertAccountAndDevice(profile.uuid(), profile.username(), request.device());

        byte[] tokenBytes = new byte[32];
        RANDOM.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        Instant expires = clock.instant().plus(config.sessionLifetime());
        repository.createSession(hashToken(token), profile.uuid(), request.device().deviceId(), expires);
        return new SessionResponse(token, profile.uuid(), request.device().deviceId(), expires.toEpochMilli());
    }

    public Optional<Principal> authenticate(String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank()) return Optional.empty();
        return repository.findSession(hashToken(bearerToken));
    }

    public static String bearerValue(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        return authorizationHeader.substring(7).strip();
    }

    private static byte[] hashToken(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void validateUsername(String username) {
        if (username == null || !username.matches("[A-Za-z0-9_]{3,16}")) {
            throw new IllegalArgumentException("invalid Minecraft username");
        }
    }

    private record Challenge(String username, String serverId, Instant expiresAt) {
    }
}
