package dev.fandroid.voice;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class VoiceCallRegistry {
    private static final int MAX_PARTICIPANTS = 8;
    private static final ConcurrentHashMap<UUID, PendingCall> PENDING_CALLER = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Set<UUID>> CALLS = new ConcurrentHashMap<>();
    private static final Set<UUID> MUTED = ConcurrentHashMap.newKeySet();

    private VoiceCallRegistry() {
    }

    public static void ring(UUID caller, UUID target) {
        if (!caller.equals(target)) PENDING_CALLER.put(target,
                new PendingCall(caller, java.time.Instant.now().plusSeconds(30)));
    }

    public static void answer(UUID answering, UUID expectedCaller) {
        PendingCall pending = PENDING_CALLER.remove(answering);
        if (pending == null || pending.expiresAt().isBefore(java.time.Instant.now())
                || !pending.caller().equals(expectedCaller)) return;
        UUID caller = pending.caller();
        Set<UUID> existing = CALLS.get(caller);
        if (existing != null) {
            if (existing.size() >= MAX_PARTICIPANTS) return;
            existing.add(answering);
            CALLS.put(answering, existing);
            return;
        }
        Set<UUID> participants = ConcurrentHashMap.newKeySet();
        participants.add(caller);
        participants.add(answering);
        CALLS.put(caller, participants);
        CALLS.put(answering, participants);
    }

    public static boolean add(UUID actor, UUID participant) {
        Set<UUID> call = CALLS.get(actor);
        if (call == null || call.size() >= MAX_PARTICIPANTS) return false;
        call.add(participant);
        CALLS.put(participant, call);
        return true;
    }

    public static void leave(UUID player) {
        MUTED.remove(player);
        PENDING_CALLER.remove(player);
        Set<UUID> call = CALLS.remove(player);
        if (call == null) return;
        call.remove(player);
        if (call.size() < 2) {
            for (UUID participant : new HashSet<>(call)) CALLS.remove(participant);
        }
    }

    public static void setMuted(UUID player, boolean muted) {
        if (!CALLS.containsKey(player)) return;
        if (muted) MUTED.add(player); else MUTED.remove(player);
    }

    public static boolean isMuted(UUID player) {
        return MUTED.contains(player);
    }

    public static boolean inCall(UUID player) {
        return CALLS.containsKey(player);
    }

    public static Set<UUID> targets(UUID player) {
        Set<UUID> call = CALLS.get(player);
        if (call == null) return Set.of();
        return call.stream().filter(id -> !id.equals(player)).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public static UUID channelId(UUID player) {
        Set<UUID> call = CALLS.get(player);
        if (call == null) return null;
        String members = call.stream().sorted().map(UUID::toString).collect(java.util.stream.Collectors.joining("|"));
        return UUID.nameUUIDFromBytes(("fandroid-call:" + members).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private record PendingCall(UUID caller, java.time.Instant expiresAt) { }
}
