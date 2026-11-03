package dev.relaylyrics.client.presence;

import java.util.Objects;
import java.util.UUID;

/** A dwell belongs to one continuously visible player, and never carries between worlds. */
public final class PresenceGaze {
    public static final long DWELL = 3_000_000_000L, GRACE = 2_000_000_000L;
    private UUID candidate, pinned;
    private long started, lastSeen;
    public void update(UUID lookingAt, long now) {
        if (!Objects.equals(candidate, lookingAt)) { candidate = lookingAt; started = now; }
        if (lookingAt != null && (Objects.equals(lookingAt, pinned) || now - started >= DWELL)) { pinned = lookingAt; lastSeen = now; }
        if (pinned != null && now - lastSeen > GRACE) pinned = null;
    }
    public UUID pinned() { return pinned; }
    public double opacity(long now) {
        return pinned == null ? 0 : Objects.equals(candidate, pinned) ? 1 : Math.clamp((GRACE - (now - lastSeen)) / 400_000_000.0, 0, 1);
    }
    public double progress(long now) { return candidate == null ? 0 : Math.clamp((now - started) / (double) DWELL, 0, 1); }
    public void clear() { candidate = pinned = null; started = lastSeen = 0; }
}
