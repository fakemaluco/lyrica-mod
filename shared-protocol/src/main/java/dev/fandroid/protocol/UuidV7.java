package dev.fandroid.protocol;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

public final class UuidV7 {
    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {
    }

    public static UUID randomUuid() {
        return randomUuid(Clock.systemUTC().millis());
    }

    static UUID randomUuid(long unixMillis) {
        if (unixMillis < 0 || unixMillis > 0xFFFFFFFFFFFFL) {
            throw new IllegalArgumentException("timestamp does not fit UUIDv7");
        }
        long most = (unixMillis << 16) | 0x7000L | RANDOM.nextInt(1 << 12);
        long least = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(most, least);
    }
}

