package dev.fandroid.client;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Small in-memory notification inbox shared by the phone and the HUD. */
public final class ClientNotificationCenter {
    private static final int MAX_ENTRIES = 24;
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private long nextId = 1;

    public synchronized Entry push(Kind kind, String title, String body) {
        Entry entry = new Entry(nextId++, kind, title, body, Instant.now(), false);
        entries.addFirst(entry);
        while (entries.size() > MAX_ENTRIES) entries.removeLast();
        return entry;
    }

    public synchronized List<Entry> recent(int limit) {
        List<Entry> result = new ArrayList<>(Math.min(Math.max(0, limit), entries.size()));
        int remaining = Math.max(0, limit);
        for (Entry entry : entries) {
            if (remaining-- == 0) break;
            result.add(entry);
        }
        return List.copyOf(result);
    }

    public synchronized int unreadCount() {
        return (int) entries.stream().filter(entry -> !entry.read()).count();
    }

    public synchronized void markRead(long id) {
        replaceMatching(id, null);
    }

    public synchronized void markKindRead(Kind kind) {
        replaceMatching(-1, kind);
    }

    private void replaceMatching(long id, Kind kind) {
        List<Entry> updated = entries.stream()
                .map(entry -> !entry.read() && (entry.id() == id || entry.kind() == kind)
                        ? new Entry(entry.id(), entry.kind(), entry.title(), entry.body(), entry.createdAt(), true)
                        : entry)
                .toList();
        entries.clear();
        entries.addAll(updated);
    }

    public enum Kind {
        SERVER_MESSAGE,
        GLOBAL_MESSAGE,
        INCOMING_CALL,
        MISSED_CALL,
        CALL_STATUS
    }

    public record Entry(long id, Kind kind, String title, String body, Instant createdAt, boolean read) { }
}
