package dev.fandroid.protocol.crypto;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ReplayGuard {
    private final ConcurrentHashMap<UUID, Long> seen = new ConcurrentHashMap<>();
    private final Clock clock;
    private final long retentionMillis;

    public ReplayGuard(Duration retention) {
        this(retention, Clock.systemUTC());
    }

    ReplayGuard(Duration retention, Clock clock) {
        this.clock = clock;
        this.retentionMillis = retention.toMillis();
    }

    public boolean accept(UUID messageId) {
        long now = clock.millis();
        if (seen.size() > 10_000) {
            seen.entrySet().removeIf(entry -> entry.getValue() < now - retentionMillis);
        }
        return seen.putIfAbsent(messageId, now) == null;
    }
}
