package dev.fandroid.backend;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

public final class RateLimiter {
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final int limit;
    private final Clock clock;

    public RateLimiter(int limit) {
        this(limit, Clock.systemUTC());
    }

    RateLimiter(int limit, Clock clock) {
        this.limit = limit;
        this.clock = clock;
    }

    public boolean allow(String key) {
        long minute = clock.millis() / 60_000;
        Bucket bucket = buckets.compute(key, (ignored, old) -> {
            if (old == null || old.minute != minute) return new Bucket(minute, 1);
            return new Bucket(minute, old.count + 1);
        });
        if (buckets.size() > 50_000) buckets.entrySet().removeIf(entry -> entry.getValue().minute < minute - 2);
        return bucket.count <= limit;
    }

    private record Bucket(long minute, int count) {
    }
}

