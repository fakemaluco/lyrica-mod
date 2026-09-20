package dev.fandroid.backend;

import java.time.Duration;
import java.util.Map;

public record BackendConfig(
        int port,
        String databaseUrl,
        String databaseUser,
        String databasePassword,
        Duration envelopeRetention,
        Duration sessionLifetime,
        int requestsPerMinute
) {
    public static BackendConfig fromEnvironment() {
        return from(System.getenv());
    }

    static BackendConfig from(Map<String, String> env) {
        return new BackendConfig(
                Integer.parseInt(env.getOrDefault("FANDROID_PORT", "8080")),
                env.getOrDefault("FANDROID_DATABASE_URL", "jdbc:postgresql://localhost:5432/fandroid"),
                env.getOrDefault("FANDROID_DATABASE_USER", "fandroid"),
                env.getOrDefault("FANDROID_DATABASE_PASSWORD", "fandroid-development-only"),
                Duration.ofDays(Long.parseLong(env.getOrDefault("FANDROID_RETENTION_DAYS", "7"))),
                Duration.ofDays(Long.parseLong(env.getOrDefault("FANDROID_SESSION_DAYS", "30"))),
                Integer.parseInt(env.getOrDefault("FANDROID_RATE_LIMIT_PER_MINUTE", "120"))
        );
    }
}

