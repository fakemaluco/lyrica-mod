package dev.fandroid.backend;

import io.javalin.websocket.WsContext;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class WebSocketHub {
    private final ConcurrentHashMap<UUID, Set<WsContext>> sessions = new ConcurrentHashMap<>();

    public void add(UUID deviceId, WsContext context) {
        sessions.computeIfAbsent(deviceId, ignored -> ConcurrentHashMap.newKeySet()).add(context);
    }

    public void remove(UUID deviceId, WsContext context) {
        sessions.computeIfPresent(deviceId, (ignored, values) -> {
            values.remove(context);
            return values.isEmpty() ? null : values;
        });
    }

    public void pending(UUID deviceId, UUID messageId) {
        String event = "{\"type\":\"PENDING_ENVELOPE\",\"messageId\":\"" + messageId + "\"}";
        for (WsContext session : sessions.getOrDefault(deviceId, Set.of())) {
            try {
                session.send(event);
            } catch (RuntimeException ignored) {
                remove(deviceId, session);
            }
        }
    }
}

