package dev.relaylyrics.karaoke;

import com.google.gson.JsonObject;
import java.util.*;

import static dev.relaylyrics.karaoke.KaraokeWire.*;

/** All state is scoped to the running Minecraft server and accessed on its game thread. */
final class KaraokeHub {
    interface Peer {
        UUID id();
        String name();
        Panel position();
        void send(JsonObject packet);
    }
    private record Presence(Song song, long updated) { }
    private static final class ListenSource {
        final Set<UUID> listeners = new HashSet<>();
        Song song;
        long generation;
        long updated;
    }
    private record QueueTask(UUID id, UUID requester, String action, long expires) { }
    private static final class Room {
        final UUID owner;
        final Set<UUID> joined = new HashSet<>();
        final Map<UUID, Long> pointers = new HashMap<>();
        final Map<UUID, Long> inputs = new HashMap<>();
        Panel panel;
        boolean placed;
        Song song;
        long generation;
        long lastControl;
        long updated;
        boolean queueReady;
        long lastQueueRequest;
        QueueTask queueTask;
        Room(UUID owner, Panel panel, long now) {
            this.owner = owner;
            this.panel = panel;
            this.updated = now;
            joined.add(owner);
        }
    }
    private final Map<UUID, Peer> peers = new HashMap<>();
    private final Map<UUID, Presence> presence = new HashMap<>();
    private final Map<UUID, Room> rooms = new HashMap<>();
    private final Map<UUID, Long> presenceInputs = new HashMap<>();
    private final Map<UUID, ListenSource> listenSources = new HashMap<>();
    private final Map<UUID, UUID> listening = new HashMap<>();

    void join(Peer peer) {
        peers.put(peer.id(), peer);
        peer.send(message("hello", "queue", true, "listen", true));
        presence.forEach((id, value) -> {
            var player = peers.get(id);
            if (player != null) peer.send(presence(player, value.song));
        });
        rooms.values().forEach(room -> peer.send(panel(room)));
    }
    void leave(UUID id) {
        peers.remove(id);
        presenceInputs.remove(id);
        stopListening(id, false);
        closeListeningSource(id);
        removePresence(id);
        close(id);
        for (var room : rooms.values()) {
            room.joined.remove(id);
            room.inputs.remove(id);
            clearPointer(room, id);
        }
    }

    boolean open(Peer peer, Panel position, long now) {
        if (!peers.containsKey(peer.id()) || !position.valid() || !near(peer, position)) return false;
        close(peer.id());
        var room = new Room(peer.id(), position, now);
        rooms.put(peer.id(), room);
        broadcast(panel(room));
        peer.send(message("hosting", "room", room.owner));
        return true;
    }

    boolean stop(UUID id) {
        boolean changed = close(id);
        for (var room : rooms.values()) {
            if (!room.joined.remove(id)) continue;
            room.inputs.remove(id);
            clearPointer(room, id);
            var peer = peers.get(id);
            if (peer != null) peer.send(message("left", "room", room.owner));
            changed = true;
        }
        return changed;
    }

    boolean close(UUID id) {
        if (rooms.remove(id) == null) return false;
        broadcast(message("close", "room", id));
        return true;
    }

    void tick(long now) {
        listenSources.entrySet().removeIf(entry -> {
            if (now - entry.getValue().updated <= 15_000) return false;
            endListeners(entry.getValue());
            return true;
        });
        for (var entry : List.copyOf(presence.entrySet()))
            if (now - entry.getValue().updated > 15_000) removePresence(entry.getKey());
        for (var room : List.copyOf(rooms.values())) {
            if (now - room.updated > 15_000) {
                close(room.owner);
                continue;
            }
            for (var cursor : List.copyOf(room.pointers.entrySet()))
                if (now - cursor.getValue() > 600) clearPointer(room, cursor.getKey());
            if (room.queueTask != null && now >= room.queueTask.expires) {
                var task = room.queueTask;
                room.queueTask = null;
                queueReply(room, task.requester, task.id, task.action, new QueueResult(List.of(), 0, "timeout"));
            }
        }
    }

    void receive(Peer peer, JsonObject packet, long now) {
        UUID sender = peer.id();
        if (!peers.containsKey(sender)) return;
        String op = string(packet, "op");
        if (op.equals("presence")) {
            if (now - presenceInputs.getOrDefault(sender, 0L) < 300) return;
            presenceInputs.put(sender, now);
            Song song = read(packet, "song", Song.class);
            if (packet.has("listen") && packet.get("listen").getAsBoolean()) updateListening(sender, song, now);
            else closeListeningSource(sender);
            if (song == null || !song.playing() || song.clean().title().isBlank()) removePresence(sender);
            else {
                Song clean = song.clean();
                presence.put(sender, new Presence(clean, now));
                broadcast(presence(peer, clean));
            }
            return;
        }
        if (op.equals("listen")) {
            startListening(peer, text(string(packet, "name"), 16), now);
            return;
        }
        if (op.equals("listen_stop")) {
            stopListening(sender, true);
            return;
        }
        Room room = rooms.get(id(packet, "room"));
        if (room == null) return;
        boolean owner = room.owner.equals(sender);
        if (op.equals("leave")) {
            if (owner) close(sender);
            else {
                room.joined.remove(sender);
                room.inputs.remove(sender);
                clearPointer(room, sender);
            }
            return;
        }
        boolean nearby = near(peer, room.panel);
        if (op.equals("accept") && nearby) {
            if (room.joined.add(sender)) {
                peer.send(message("accepted", "room", room.owner));
                peer.send(track(room, now));
            }
            return;
        }
        if (!room.joined.contains(sender)) return;
        if (op.equals("update") && owner) {
            if (now - room.updated < 220 && room.generation > 0) return;
            Song song = read(packet, "song", Song.class);
            Song clean = song == null ? null : song.clean();
            if (room.song == null ? clean != null : clean == null || !room.song.sameTrack(clean)) {
                room.generation++;
                room.lastControl = 0;
            }
            boolean previewChanged = !java.util.Objects.equals(preview(room.song), preview(clean));
            room.song = clean;
            room.queueReady = packet.has("queue") && packet.get("queue").getAsBoolean();
            room.updated = now;
            if (previewChanged) broadcast(message("preview", "room", room.owner, "preview", preview(clean)));
            forMembers(room, track(room, now));
            return;
        }
        if (op.equals("queue_result") && owner) {
            var task = room.queueTask;
            if (task == null || now >= task.expires || !task.id.equals(id(packet, "request"))) return;
            var result = read(packet, "result", QueueResult.class);
            if (result == null) return;
            room.queueTask = null;
            queueReply(room, task.requester, task.id, task.action, result);
            if (Set.of("added", "jumped").contains(result.status()))
                forMembers(room, message("queue_snapshot", "room", room.owner, "result", result));
            return;
        }
        if (op.equals("queue_request") && nearby) {
            requestQueue(peer, room, packet, now);
            return;
        }
        if (!nearby || now - room.inputs.getOrDefault(sender, 0L) < 45) return;
        room.inputs.put(sender, now);
        if (op.equals("cursor")) {
            Pointer cursor = read(packet, "pointer", Pointer.class);
            if (cursor == null || !cursor.valid()) return;
            room.pointers.put(sender, now);
            nearby(room, sender, message("cursor", "room", room.owner, "player", sender, "pointer", cursor));
        } else if (op.equals("cursor_leave")) {
            clearPointer(room, sender);
        } else if ((op.equals("move") || op.equals("place") && !room.placed) && owner) {
            Panel next = read(packet, "panel", Panel.class);
            if (next != null && next.valid() && near(peer, next)
                    && next.dimension().equals(room.panel.dimension())
                    && (op.equals("place") || next.distanceSquared(room.panel.x(), room.panel.y(), room.panel.z()) <= 16)) {
                room.panel = next;
                room.placed = true;
                broadcast(panel(room));
            }
        } else if (op.equals("control")) {
            String action = string(packet, "action");
            if (room.song == null || (room.queueTask != null && room.queueTask.action.equals("jump")) || now - room.lastControl < 500
                    || !Set.of("toggle", "seek", "previous", "next", "shuffle", "repeat").contains(action)) return;
            room.lastControl = now;
            long position = packet.has("position") ? Math.clamp(packet.get("position").getAsLong(), 0, 86_400_000) : 0;
            Peer host = peers.get(room.owner);
            if (host != null) host.send(message("control", "room", room.owner, "action", action, "position", position));
        }
    }

    private void startListening(Peer peer, String name, long now) {
        var host = peers.values().stream().filter(player -> player.name().equalsIgnoreCase(name)).findFirst().orElse(null);
        var source = host == null ? null : listenSources.get(host.id());
        if (source == null || now - source.updated > 15_000) {
            peer.send(message("listen_error", "reason", "unavailable"));
            return;
        }
        for (UUID cursor = host.id(); cursor != null; cursor = listening.get(cursor)) {
            if (cursor.equals(peer.id())) {
                peer.send(message("listen_error", "reason", "cycle"));
                return;
            }
        }
        stopListening(peer.id(), false);
        listening.put(peer.id(), host.id());
        source.listeners.add(peer.id());
        peer.send(message("listen_started", "player", host.id(), "name", host.name()));
        peer.send(listenTrack(host.id(), source, now));
    }

    private void stopListening(UUID listener, boolean notify) {
        var host = listening.remove(listener);
        if (host != null) {
            var source = listenSources.get(host);
            if (source != null) source.listeners.remove(listener);
        }
        var peer = peers.get(listener);
        if (notify && peer != null) peer.send(message("listen_stopped"));
    }

    private void closeListeningSource(UUID host) {
        var source = listenSources.remove(host);
        if (source != null) endListeners(source);
    }

    private void endListeners(ListenSource source) {
        for (UUID listener : source.listeners) {
            listening.remove(listener);
            var peer = peers.get(listener);
            if (peer != null) peer.send(message("listen_stopped"));
        }
    }

    private void updateListening(UUID host, Song input, long now) {
        var source = listenSources.computeIfAbsent(host, ignored -> new ListenSource());
        var song = input == null ? null : input.clean();
        if (song != null && song.title().isBlank()) song = null;
        if (source.song == null ? song != null : song == null || !source.song.sameTrack(song)) source.generation++;
        source.song = song == null ? null : new Song(song.title(), song.artist(), "", song.uri(),
                song.position(), song.duration(), song.playing());
        source.updated = now;
        if (source.listeners.isEmpty()) return;
        var packet = listenTrack(host, source, now);
        for (UUID listener : source.listeners) {
            var peer = peers.get(listener);
            if (peer != null) peer.send(packet);
        }
    }

    private static JsonObject listenTrack(UUID host, ListenSource source, long now) {
        var song = source.song;
        return message("listen_track", "player", host, "generation", source.generation,
                "song", song == null ? null : new Song(song.title(), song.artist(), "", song.uri(),
                        song.at(now - source.updated), song.duration(), song.playing()));
    }

    private void requestQueue(Peer peer, Room room, JsonObject packet, long now) {
        UUID request = id(packet, "request");
        String action = string(packet, "action");
        String value = string(packet, "value");
        int index = packet.get("index").getAsInt();
        int version = packet.get("version").getAsInt();
        boolean valid = switch (action) {
            case "list" -> true;
            case "search" -> !(value = text(value, 100)).isBlank();
            case "add" -> queueUri(value);
            case "jump" -> queueUri(value) && index >= 0 && index < QUEUE_LIMIT;
            default -> false;
        };
        if (!valid) return;
        String status = !room.queueReady || room.song == null || room.song.uri().isBlank() ? "inactive"
                : room.queueTask != null || now - room.lastQueueRequest < 650 ? "busy" : null;
        if (status != null) {
            queueReply(room, peer.id(), request, action, new QueueResult(List.of(), 0, status));
            return;
        }
        var host = peers.get(room.owner);
        if (host == null) return;
        room.lastQueueRequest = now;
        room.queueTask = new QueueTask(request, peer.id(), action, now + QUEUE_TIMEOUT_MILLIS);
        host.send(message("queue_execute", "room", room.owner, "request", request, "action", action,
                "value", value, "index", index, "version", version, "current", room.song.uri()));
    }

    private void queueReply(Room room, UUID requester, UUID request, String action, QueueResult result) {
        var peer = peers.get(requester);
        if (peer != null && room.joined.contains(requester) && near(peer, room.panel))
            peer.send(message("queue_result", "room", room.owner, "request", request, "action", action, "result", result));
    }

    private boolean near(Peer peer, Panel panel) {
        Panel player = peer.position();
        return panel.dimension().equals(player.dimension())
                && panel.distanceSquared(player.x(), player.y(), player.z()) <= RANGE * RANGE;
    }
    private void clearPointer(Room room, UUID sender) {
        if (room.pointers.remove(sender) != null)
            nearby(room, sender, message("cursor", "room", room.owner, "player", sender));
    }
    private void nearby(Room room, UUID sender, JsonObject packet) {
        for (Peer p : peers.values()) if (!p.id().equals(sender) && near(p, room.panel)) p.send(packet);
    }
    private void forMembers(Room room, JsonObject packet) {
        for (UUID id : room.joined) {
            Peer peer = peers.get(id);
            if (peer != null) peer.send(packet);
        }
    }
    private void removePresence(UUID id) {
        if (presence.remove(id) != null) broadcast(message("presence", "player", id));
    }
    private void broadcast(JsonObject packet) { for (Peer p : peers.values()) p.send(packet); }
    private JsonObject panel(Room room) {
        Peer owner = peers.get(room.owner);
        return message("panel", "room", room.owner, "panel", room.panel, "name", owner == null ? "" : owner.name(),
                "preview", preview(room.song));
    }
    private static Preview preview(Song song) {
        return song == null ? null : new Preview(song.title(), song.artist());
    }
    private JsonObject track(Room room, long now) {
        Song song = room.song;
        return message("track", "room", room.owner, "generation", room.generation,
                "song", song == null ? null : new Song(song.title(), song.artist(), song.album(), song.cover(),
                        song.uri(), song.at(now - room.updated), song.duration(), song.playing(), song.shuffled(), song.repeat()));
    }
    private JsonObject presence(Peer peer, Song song) {
        return message("presence", "player", peer.id(), "name", peer.name(),
                "song", new Song(song.title(), song.artist(), song.cover(), "", 0, 0, true));
    }
}
