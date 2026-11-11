package dev.relaylyrics.client.karaoke;

import com.google.gson.JsonObject;
import dev.relaylyrics.client.RelayLyricsClient;
import dev.relaylyrics.client.presence.PresenceTrack;
import dev.relaylyrics.client.ui.KaraokePanelMotion;
import dev.relaylyrics.karaoke.KaraokeWire.*;
import dev.relaylyrics.protocol.music.MusicAction;
import dev.relaylyrics.protocol.music.PlaybackSnapshot;

import java.util.*;
import java.util.concurrent.TimeUnit;

import static dev.relaylyrics.karaoke.KaraokeWire.*;
import static org.lwjgl.glfw.GLFW.*;

public final class KaraokeClient {
    private static final Map<UUID, Room> ROOMS = new LinkedHashMap<>();
    private static final Map<UUID, PresenceTrack> PRESENCE = new HashMap<>();
    private static UUID hosting;
    private static boolean placing;
    private static volatile Room following;
    private static Hit hit;
    private static Drag drag;
    private static QueuePull queuePull;
    private static Room queueFocus;
    private static boolean consumedEscape;
    private static Room pointerRoom;
    private static boolean usedAttack, usedInteract;
    private static boolean queueSupported;
    private static boolean listenSupported;
    private static long ticks;
    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);
    private static final long CURSOR_TRAVEL = TimeUnit.MILLISECONDS.toNanos(150);
    static final int HANDLE_HALF_WIDTH = 30, HANDLE_TOP = HEIGHT / 2 + 3, HANDLE_BOTTOM = HEIGHT / 2 + 21;
    static final int RESIZE_SIZE = 19;

    private KaraokeClient() { }

    public static final class Room {
        double lyricsZoom = 1;
        final KaraokePanelMotion motion = new KaraokePanelMotion();
        Preview preview;
        public final UUID id;
        public final String name;
        public Panel panel;
        public boolean accepted, dismissed;
        public Song song;
        public String status = "";
        double lastAudibleVolume = .5;
        volatile long generation;
        private long received;
        private long nextSync, mismatchSince;
        private boolean loading, matched, spotifyFailed, spotifySync;
        private String resolvedUri;
        public final Map<UUID, Cursor> cursors = new HashMap<>();
        final KaraokeQueueView queue = new KaraokeQueueView();
        volatile UUID executingQueue;

        private Room(UUID id, String name, Panel panel) { this.id = id; this.name = name; this.panel = panel; }
        public long position() { return song == null ? 0 : song.at(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - received)); }
        public boolean owned() { return id.equals(hosting); }
        boolean isListeningOnSpotify() { return spotifySync && matched && !spotifyFailed; }
        private void resetTrack(long generation) {
            this.generation = generation;
            loading = matched = spotifyFailed = false;
            nextSync = mismatchSince = 0;
            resolvedUri = null;
            status = "";
        }
    }
    public record Cursor(Pointer from, Pointer to, long received) {
        public Pointer at(long now) {
            double progress = Math.clamp((now - received) / (double) CURSOR_TRAVEL, 0, 1);
            return new Pointer(Math.fma(to.x() - from.x(), progress, from.x()),
                    Math.fma(to.y() - from.y(), progress, from.y()));
        }
    }
    public record Ray(String dimension, double x, double y, double z, double dx, double dy, double dz, double limit) { }
    public record Hit(Room room, double x, double y, double distance) { }
    private record Drag(Room room, boolean right, boolean resizing, Panel start,
                        double x, double y, double distance) { }
    private record QueuePull(Room room, boolean right, int side, double start) { }

    public static void initialize() { KaraokePlatform.register(); }
    public static boolean supported() { return KaraokePlatform.supported(); }
    public static Collection<Room> rooms() { return ROOMS.values(); }
    public static Hit hit() { return hit; }
    public static boolean consumesAttack() { return usedAttack || drag != null || queuePull != null; }
    static Room draggedRoom() { return drag == null ? null : drag.room; }
    static boolean handleAt(double x, double y) {
        return Math.abs(x) <= HANDLE_HALF_WIDTH && y >= HANDLE_TOP && y <= HANDLE_BOTTOM;
    }
    static boolean resizeAt(double x, double y) {
        return x >= WIDTH / 2.0 - RESIZE_SIZE && x <= WIDTH / 2.0
                && y >= HEIGHT / 2.0 - RESIZE_SIZE && y <= HEIGHT / 2.0;
    }
    public static PresenceTrack track(UUID id) {
        var track = PRESENCE.get(id);
        return track != null && track.alive(System.nanoTime()) ? track : null;
    }
    public static void clear() {
        stopFollowing();
        blurQueue(queueFocus);
        KaraokePlatform.clearPopups();
        ROOMS.values().forEach(room -> room.executingQueue = null);
        ROOMS.clear(); PRESENCE.clear(); hosting = null; placing = false; hit = null; drag = null; ticks = 0;
        pointerRoom = null;
        queuePull = null;
        consumedEscape = false;
        usedAttack = usedInteract = queueSupported = listenSupported = false;
    }
    private static void send(String op, Object... fields) { KaraokePlatform.send(message(op, fields)); }

    static void receive(JsonObject data) {
        try {
            String op = string(data, "op");
            if (op.equals("hello")) {
                queueSupported = data.has("queue") && data.get("queue").getAsBoolean();
                listenSupported = data.has("listen") && data.get("listen").getAsBoolean();
                return;
            }
            switch (op) {
                case "listen_started" -> {
                    if (!canSyncSpotify()) { send("listen_stop"); return; }
                    if (following != null && following.panel != null) leave(following, true);
                    var listener = new Room(id(data, "player"), text(string(data, "name"), 16), null);
                    listener.accepted = listener.spotifySync = true;
                    following = listener;
                    RelayLyricsClient.music().setKaraokeSpotifyWatching(true);
                    KaraokePlatform.listenNotice("started", listener.name);
                    return;
                }
                case "listen_track" -> {
                    var listener = following;
                    if (listener != null && listener.panel == null && listener.id.equals(id(data, "player"))) applyTrack(listener, data);
                    return;
                }
                case "listen_stopped" -> {
                    if (following != null && following.panel == null) {
                        stopFollowing();
                        KaraokePlatform.listenNotice("stopped");
                    }
                    return;
                }
                case "listen_error" -> {
                    KaraokePlatform.listenNotice("cycle".equals(string(data, "reason")) ? "cycle" : "unavailable");
                    return;
                }
                default -> { }
            }
            if (op.equals("presence")) {
                UUID player = id(data, "player");
                var song = read(data, "song", Song.class);
                if (song == null) PRESENCE.remove(player);
                else PRESENCE.put(player, new PresenceTrack(player, string(data, "name"), song.title(), song.artist(),
                        song.cover(), System.nanoTime() + 16 * SECOND));
                return;
            }
            UUID id = id(data, "room");
            if (op.equals("panel")) {
                var panel = read(data, "panel", Panel.class);
                if (panel == null || !panel.valid()) return;
                var room = ROOMS.get(id);
                if (room == null) {
                    room = new Room(id, text(string(data, "name"), 16), panel);
                    ROOMS.put(id, room);
                } else if (drag == null || drag.room != room) room.panel = panel;
                room.preview = read(data, "preview", Preview.class);
                return;
            }
            var room = ROOMS.get(id);
            if (room == null) return;
            switch (op) {
                case "preview" -> room.preview = read(data, "preview", Preview.class);
                case "close" -> {
                    blurQueue(room);
                    room.executingQueue = null;
                    KaraokeSurfaces.beginClose(room);
                    ROOMS.remove(id);
                    if (room.owned()) { hosting = null; placing = false; }
                    if (following == room) stopFollowing();
                    if (drag != null && drag.room == room) drag = null;
                }
                case "left" -> leave(room, false);
                case "hosting" -> {
                    hosting = id;
                    placing = true;
                    room.accepted = true;
                    publish(room);
                }
                case "accepted" -> {
                    if (room.dismissed || following != room) { send("leave", "room", id); return; }
                    room.accepted = true;
                    var music = RelayLyricsClient.music();
                    if (music != null && room.spotifySync) music.setKaraokeSpotifyWatching(true);
                }
                case "track" -> {
                    if (!room.accepted) return;
                    boolean changed = applyTrack(room, data);
                    if (changed && room.queue.side != 0 && !room.queue.searching && room.queue.request == null)
                        requestQueue(room, "list", "", 0);
                }
                case "queue_execute" -> { if (room.owned()) executeQueue(room, data); }
                case "queue_result" -> {
                    if (!room.accepted || !id(data, "request").equals(room.queue.request)) return;
                    var result = read(data, "result", QueueResult.class);
                    room.queue.request = null;
                    if (result == null) return;
                    boolean search = string(data, "action").equals("search");
                    if (search && (!room.queue.searching || !room.queue.submittedQuery.equals(text(room.queue.field.value(), 100)))) return;
                    room.queue.status = result.status().equals("ok") ? "" : result.status();
                    if (search) room.queue.results = result.tracks();
                    else if (Set.of("ok", "changed", "added", "jumped").contains(result.status())) room.queue.queue = result;
                    if (search || string(data, "action").equals("list")) room.queue.first = 0;
                }
                case "queue_snapshot" -> {
                    if (!room.accepted) return;
                    var result = read(data, "result", QueueResult.class);
                    if (result != null) {
                        room.queue.queue = result;
                    }
                }
                case "cursor" -> {
                    UUID player = id(data, "player");
                    if (KaraokePlatform.isLocalPlayer(player)) return;
                    Pointer pointer = read(data, "pointer", Pointer.class);
                    if (pointer == null) room.cursors.remove(player);
                    else if (pointer.valid()) {
                        var previous = room.cursors.get(player);
                        long now = System.nanoTime();
                        room.cursors.put(player, new Cursor(previous == null ? pointer : previous.at(now), pointer, now));
                    }
                }
                case "control" -> {
                    if (room.owned()) controlHost(string(data, "action"), data.get("position").getAsLong());
                }
            }
        } catch (RuntimeException ignored) { /* Malformed or obsolete packet. */ }
    }

    private static boolean applyTrack(Room room, JsonObject data) {
        long generation = generation(data);
        if (generation < room.generation) return false;
        boolean changed = generation != room.generation;
        var song = read(data, "song", Song.class);
        if (changed) room.resetTrack(generation);
        room.song = song == null ? null : song.clean();
        room.received = System.nanoTime();
        return changed;
    }

    public static int listen(String name) {
        if (!supported() || !listenSupported) { KaraokePlatform.listenNotice("unsupported"); return 0; }
        if (!canSyncSpotify()) { KaraokePlatform.listenNotice("spotify"); return 0; }
        send("listen", "name", name);
        return 1;
    }

    public static int stopListening() {
        if (following == null || following.panel != null) {
            KaraokePlatform.listenNotice("idle");
            return 0;
        }
        leave(following, true);
        return 1;
    }

    public static void tick() {
        if (!supported()) {
            if (!ROOMS.isEmpty() || !PRESENCE.isEmpty()) clear();
            return;
        }
        ticks++;
        if (!KaraokePlatform.buttonHeld(false)) usedAttack = false;
        if (!KaraokePlatform.buttonHeld(true)) usedInteract = false;
        if (ticks % 60 == 1) {
            var p = RelayLyricsClient.preferences();
            var music = RelayLyricsClient.music();
            boolean sharing = music != null && p.isMusicPresenceEnabled() && p.isMusicPresenceSharing();
            var shared = sharing ? song(music.snapshot(), listenSupported ? music.positionMillis() : 0) : null;
            if (shared != null && !listenSupported)
                shared = new Song(shared.title(), shared.artist(), shared.cover(), "", 0, 0, shared.playing());
            // The server strips playback details from the public presence, as with older clients.
            send("presence", "listen", listenSupported && sharing, "song", shared);
            PRESENCE.values().removeIf(track -> !track.alive(System.nanoTime()));
        }
        Ray ray = ROOMS.isEmpty() ? null : KaraokePlatform.ray();
        if (queueFocus != null && (ray == null || KaraokePlatform.visibleDistance(queueFocus) > RANGE * RANGE))
            blurQueue(queueFocus);
        if (placing && ray != null) {
            var room = ROOMS.get(hosting);
            if (room != null && room.panel.dimension().equals(ray.dimension)) {
                double distance = Math.clamp(ray.limit - .15, .4, 3.5);
                room.panel = new Panel(ray.dimension, ray.x + ray.dx * distance,
                        ray.y + ray.dy * distance, ray.z + ray.dz * distance, aimYaw(ray, room.panel.yaw()));
                send("place", "room", room.id, "panel", room.panel);
            }
            placing = false;
        }
        var target = ray == null ? null : find(ray);
        if (queuePull != null) pullQueue(ray);
        if (drag != null) {
            boolean held = ray != null && KaraokePlatform.buttonHeld(drag.right);
            if (ray != null && (!held || ticks % 2 == 0)) move(ray);
            if (!held) drag = null;
        }
        var pointed = drag == null && target != null && target.room.accepted && !handleAt(target.x, target.y)
                && Math.abs(target.x) <= WIDTH / 2.0
                ? target.room : null;
        if (pointerRoom != pointed && pointerRoom != null) send("cursor_leave", "room", pointerRoom.id);
        if (pointed != null && (pointed != pointerRoom || ticks % 3 == 0))
            send("cursor", "room", pointed.id, "pointer", Pointer.fromPanel(target.x, target.y));
        pointerRoom = pointed;
        if (hosting != null && ticks % 16 == 0) {
            Room room = ROOMS.get(hosting);
            if (room != null) publish(room);
        }
        if (following != null && following.accepted && following.song != null) syncSpotify(following);
        long now = System.nanoTime();
        for (Room room : ROOMS.values()) {
            room.cursors.values().removeIf(cursor -> now - cursor.received > SECOND);
            if (room.queue.request != null && now >= room.queue.deadline) {
                room.queue.request = null;
                room.queue.status = "timeout";
            }
            room.queue.submit(room, now);
        }
    }

    private static Song song(PlaybackSnapshot snapshot, long position) {
        if (snapshot.title() == null || snapshot.title().isBlank() || "Nothing playing".equals(snapshot.title())) return null;
        return new Song(snapshot.title(), snapshot.artist(), snapshot.album(), snapshot.artworkUrl(), snapshot.spotifyUri(),
                position, snapshot.durationMillis(), snapshot.playing(), snapshot.shuffled(), snapshot.repeatMode()).clean();
    }

    private static void publish(Room room) {
        var music = RelayLyricsClient.music();
        var song = music == null ? null : song(music.snapshot(), music.positionMillis());
        send("update", "room", room.id, "song", song, "queue", music != null && music.spotifyAccountConnected());
    }

    static boolean hasQueue(Room room) { return queueSupported && room.accepted; }

    static void requestQueue(Room room, String action, String value, int index) {
        if (!hasQueue(room) || room.queue.request != null || ROOMS.get(room.id) != room
                || KaraokePlatform.visibleDistance(room) > RANGE * RANGE) return;
        var queue = room.queue;
        queue.request = UUID.randomUUID();
        queue.lastRequestAt = System.nanoTime();
        queue.deadline = queue.lastRequestAt + TimeUnit.MILLISECONDS.toNanos(QUEUE_TIMEOUT_MILLIS + 1_500);
        queue.status = "loading";
        send("queue_request", "room", room.id, "request", queue.request, "action", action,
                "value", text(value, 100), "index", index, "version", queue.queue.version());
    }

    static void focusQueue(Room room) {
        if (queueFocus == room) return;
        blurQueue(queueFocus);
        queueFocus = room;
        room.queue.focused = true;
        room.queue.field.end(false);
        KaraokePlatform.releaseKeys();
        KaraokePlatform.queueTextInput(true);
    }

    static void blurQueue(Room room) {
        if (room == null || queueFocus != room) return;
        room.queue.focused = false;
        queueFocus = null;
        KaraokePlatform.queueTextInput(false);
    }

    public static boolean queueKey(int key, int action, int modifiers) {
        if (key == GLFW_KEY_ESCAPE && consumedEscape) {
            if (action == GLFW_RELEASE) consumedEscape = false;
            return true;
        }
        Room room = queueFocus;
        if (room == null) return false;
        if (action == GLFW_RELEASE) return true;
        var queue = room.queue;
        var field = queue.field;
        boolean extend = (modifiers & GLFW_MOD_SHIFT) != 0;
        boolean shortcut = (modifiers & (GLFW_MOD_CONTROL | GLFW_MOD_SUPER)) != 0;
        boolean changed = false;
        switch (key) {
            case GLFW_KEY_ESCAPE -> {
                blurQueue(room);
                consumedEscape = true;
            }
            case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> {
                String query = text(field.value(), 100);
                if (!query.isBlank() && (queue.request == null || !query.equals(queue.submittedQuery)))
                    queue.searchAt = System.nanoTime();
            }
            case GLFW_KEY_BACKSPACE -> changed = field.erase(-1);
            case GLFW_KEY_DELETE -> changed = field.erase(1);
            case GLFW_KEY_LEFT -> field.step(-1, extend);
            case GLFW_KEY_RIGHT -> field.step(1, extend);
            case GLFW_KEY_HOME -> field.home(extend);
            case GLFW_KEY_END -> field.end(extend);
            case GLFW_KEY_A -> { if (shortcut) field.selectAll(); }
            case GLFW_KEY_V -> { if (shortcut) changed = field.insert(KaraokePlatform.clipboard()); }
            case GLFW_KEY_C, GLFW_KEY_X -> {
                if (shortcut && !field.selection().isEmpty()) {
                    KaraokePlatform.clipboard(field.selection());
                    if (key == GLFW_KEY_X) changed = field.insert("");
                }
            }
            default -> { }
        }
        if (changed) queue.edited(System.nanoTime());
        return true;
    }

    public static boolean queueCharacter(int codepoint) {
        Room room = queueFocus;
        if (room == null) return false;
        if (Character.isValidCodePoint(codepoint) && room.queue.field.insert(Character.toString(codepoint)))
            room.queue.edited(System.nanoTime());
        return true;
    }

    private static void executeQueue(Room room, JsonObject packet) {
        UUID request = id(packet, "request");
        var music = RelayLyricsClient.music();
        if (music == null || room.executingQueue != null) {
            send("queue_result", "room", room.id, "request", request, "result",
                    new QueueResult(List.of(), 0, music == null ? "inactive" : "busy"));
            return;
        }
        String action = string(packet, "action");
        String current = string(packet, "current");
        if (!current.equals(music.snapshot().spotifyUri())) {
            send("queue_result", "room", room.id, "request", request, "result", new QueueResult(List.of(), 0, "inactive"));
            return;
        }
        room.executingQueue = request;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(QUEUE_TIMEOUT_MILLIS - 1_000);
        music.karaokeQueue(action, string(packet, "value"), packet.get("index").getAsInt(),
                packet.get("version").getAsInt(), current, QUEUE_LIMIT,
                () -> request.equals(room.executingQueue) && System.nanoTime() < deadline)
                .whenComplete((reply, failure) -> KaraokePlatform.execute(() -> {
                    if (!request.equals(room.executingQueue)) return;
                    room.executingQueue = null;
                    var result = failure == null ? new QueueResult(reply.entries().stream()
                            .map(entry -> new QueueTrack(entry.uri(), entry.title(), entry.artist())).toList(),
                            reply.version(), reply.status()) : new QueueResult(List.of(), 0, "error");
                    send("queue_result", "room", room.id, "request", request, "result", result);
                }));
    }

    private static Hit find(Ray ray) {
        Hit closest = null;
        for (Room room : ROOMS.values()) {
            var pose = drag != null && drag.room == room ? draggedPanel(ray) : null;
            if (pose == null) pose = room.panel;
            if (room.dismissed || !ray.dimension.equals(pose.dimension())) continue;
            double yaw = Math.toRadians(pose.yaw()), sin = Math.sin(yaw), cos = Math.cos(yaw);
            double dot = sin * ray.dx + cos * ray.dz;
            if (Math.abs(dot) < .0001) continue;
            double distance = planeDistance(ray, pose, sin, cos, dot);
            if (distance <= 0 || distance > ray.limit || (closest != null && distance >= closest.distance)) continue;
            double x = planeX(ray, pose, distance, sin, cos) / pose.pixelSize();
            double y = (pose.y() - ray.y - ray.dy * distance) / pose.pixelHeight();
            boolean handle = room.owned() && room.accepted && handleAt(x, y);
            boolean panel = dot < 0 && Math.abs(x) <= WIDTH / 2.0 && Math.abs(y) <= HEIGHT / 2.0;
            boolean wing = dot < 0 && hasQueue(room) && KaraokeQueueView.contains(x, y, room.queue.side);
            if (panel || handle || wing) closest = new Hit(room, x, y, distance);
        }
        return closest;
    }

    private static double planeDistance(Ray ray, Panel panel, double sin, double cos, double dot) {
        return (sin * (panel.x() - ray.x) + cos * (panel.z() - ray.z)) / dot;
    }

    private static double planeX(Ray ray, Panel panel, double distance, double sin, double cos) {
        return (ray.x + ray.dx * distance - panel.x()) * cos - (ray.z + ray.dz * distance - panel.z()) * sin;
    }

    static Hit captureAim(Ray ray) {
        hit = ray == null ? null : find(ray);
        return hit;
    }

    private static void pullQueue(Ray ray) {
        var pull = queuePull;
        if (ray == null || !hasQueue(pull.room) || ROOMS.get(pull.room.id) != pull.room
                || !ray.dimension.equals(pull.room.panel.dimension())) {
            queuePull = null;
            return;
        }
        var panel = pull.room.panel;
        double yaw = Math.toRadians(panel.yaw()), sin = Math.sin(yaw), cos = Math.cos(yaw);
        double dot = sin * ray.dx + cos * ray.dz;
        if (dot >= -.0001) {
            queuePull = null;
            return;
        }
        double distance = planeDistance(ray, panel, sin, cos, dot);
        double x = planeX(ray, panel, distance, sin, cos) / panel.pixelSize();
        if (distance <= 0 || distance > ray.limit) {
            if (!KaraokePlatform.buttonHeld(pull.right)) queuePull = null;
            return;
        }
        if (!KaraokePlatform.buttonHeld(pull.right) || (x - pull.start) * pull.side >= 7) {
            pull.room.queue.open(pull.room, pull.side);
            queuePull = null;
        }
    }

    public static boolean hideVanillaCrosshair() {
        var target = hit;
        return target != null && KaraokePlatform.popup(target.room) != null;
    }

    public static boolean click(boolean right) {
        if (drag != null || queuePull != null || (right ? usedInteract : usedAttack)) return true;
        if (ROOMS.isEmpty()) return false;
        Ray ray = KaraokePlatform.ray();
        Hit current = ray == null ? null : find(ray);
        if (current == null) {
            blurQueue(queueFocus);
            return false;
        }
        Room room = current.room;
        if (right) usedInteract = true;
        else usedAttack = true;
        int tab = hasQueue(room) ? KaraokeQueueView.tabAt(current.x, current.y, room.queue.side) : 0;
        if (hasQueue(room) && (tab != 0 || Math.abs(current.x) > WIDTH / 2.0)) {
            var view = KaraokePlatform.popup(room);
            if (view != null && !view.invitation) {
                if (tab != 0) {
                    blurQueue(queueFocus);
                    queuePull = new QueuePull(room, right, tab, current.x);
                } else if (view.drawerProgress >= .98)
                    room.queue.click(room, current.x, current.y, view.height, view.wingPixels(KaraokeQueueView.DRAWER_WIDTH));
            }
            return true;
        }
        blurQueue(queueFocus);
        boolean resizing = resizeAt(current.x, current.y);
        if (room.owned() && room.accepted && (handleAt(current.x, current.y) || resizing)) {
            drag = new Drag(room, right, resizing, room.panel, current.x, current.y, current.distance);
            return true;
        }
        if (!room.accepted) {
            var popup = KaraokePlatform.popup(room);
            if (popup == null) return true;
            switch (popup.invitationLayout().actionAt(popup.pointerX(current.x), popup.pointerY(current.y))) {
                case ACCEPT -> {
                    popup.buttons.pressAccept(System.nanoTime());
                    if (following != null && following != room) leave(following, true);
                    following = room;
                    room.spotifySync = popup.spotifySync && canSyncSpotify();
                    send("accept", "room", room.id);
                }
                case DECLINE -> {
                    KaraokeSurfaces.beginClose(room);
                    room.dismissed = true;
                }
                case NONE -> { }
            }
        } else clickPopup(current);
        return true;
    }

    static boolean canSyncSpotify() {
        var music = RelayLyricsClient.music();
        return music != null && music.spotifyAccountConnected();
    }

    private static void clickPopup(Hit hit) {
        Room room = hit.room;
        var view = KaraokePlatform.popup(room);
        if (view == null || view.invitation) return;
        var overlay = view.overlay;
        double x = view.pointerX(hit.x), y = view.pointerY(hit.y);
        var control = overlay.popupControlAt(x, y);
        var music = RelayLyricsClient.music();
        boolean localPlayback = music != null && (room.owned() || room.isListeningOnSpotify());
        overlay.animatePopupControl(control);
        switch (control) {
            case CLOSE -> leave(room, true);
            case SETTINGS -> KaraokePlatform.openSettings();
            case TOGGLE_WORLD_LYRICS, TOGGLE_ISLAND_LYRICS, TOGGLE_ROMANIZATION, TOGGLE_TRANSLATION ->
                    overlay.togglePopupUtility(control);
            case TOGGLE_HEART -> {
                if (localPlayback) {
                    overlay.animatePopupHeartLike();
                    music.command(MusicAction.TOGGLE_HEART);
                }
            }
            case TOGGLE_MUTE -> {
                if (localPlayback) {
                    double volume = overlay.popupVolumeLevel();
                    if (volume > .001) room.lastAudibleVolume = volume;
                    music.command(MusicAction.SET_VOLUME, volume > .001 ? 0 : room.lastAudibleVolume);
                }
            }
            case PREVIOUS -> request(room, "previous", 0);
            case NEXT -> request(room, "next", 0);
            case PLAY_PAUSE -> request(room, "toggle", 0);
            case TOGGLE_SHUFFLE -> request(room, "shuffle", 0);
            case CYCLE_REPEAT -> request(room, "repeat", 0);
            case NONE -> {
                var volume = overlay.popupVolumeTargetAt(x, y);
                if (volume.isPresent() && localPlayback) {
                    music.command(MusicAction.SET_VOLUME, volume.getAsDouble());
                    return;
                }
                var seek = overlay.beginPopupProgressScrub(x, y) ? overlay.finishPopupProgressScrub() : overlay.seekTargetAtHud(x, y);
                seek.ifPresent(position -> request(room, "seek", position));
            }
            default -> { }
        }
    }

    private static void request(Room room, String action, long position) {
        send("control", "room", room.id, "action", action, "position", position);
    }

    static Panel draggedPanel(Ray ray) {
        var target = drag;
        if (target == null || ray == null) return null;
        var start = target.start;
        Panel panel;
        if (target.resizing) {
            double yaw = Math.toRadians(start.yaw()), sin = Math.sin(yaw), cos = Math.cos(yaw);
            double dot = sin * ray.dx + cos * ray.dz;
            if (dot >= -.0001) return null;
            double distance = planeDistance(ray, start, sin, cos, dot);
            if (distance <= 0 || distance > RANGE) return null;
            // Each axis grows from the opposite corner independently.
            double x = planeX(ray, start, distance, sin, cos) + WIDTH / 2.0 * start.pixelSize();
            double y = start.y() - ray.y - ray.dy * distance + HEIGHT / 2.0 * start.pixelHeight();
            double width = Math.clamp(x / ((WIDTH / 2.0 + target.x) * SCALE), MIN_ZOOM, MAX_ZOOM);
            double height = Math.clamp(y / ((HEIGHT / 2.0 + target.y) * SCALE), MIN_ZOOM, MAX_ZOOM);
            double shiftX = WIDTH * (width - start.zoom()) * SCALE / 2;
            double shiftY = HEIGHT * (height - start.heightZoom()) * SCALE / 2;
            panel = new Panel(start.dimension(), start.x() + shiftX * cos, start.y() - shiftY,
                    start.z() - shiftX * sin, start.yaw(), width, height);
        } else {
            double yaw = aimYaw(ray, start.yaw());
            double angle = Math.toRadians(yaw);
            double grip = target.x * start.pixelSize();
            panel = new Panel(start.dimension(), ray.x + ray.dx * target.distance - grip * Math.cos(angle),
                    ray.y + ray.dy * target.distance + target.y * start.pixelHeight(),
                    ray.z + ray.dz * target.distance + grip * Math.sin(angle), yaw, start.zoom(), start.heightZoom());
        }
        if (!panel.dimension().equals(ray.dimension) || !panel.valid()) return null;
        var previous = target.room.panel;
        double step = Math.sqrt(panel.distanceSquared(previous.x(), previous.y(), previous.z()));
        if (step > 3.5) {
            if (target.resizing) return null;
            double t = 3.5 / step;
            panel = new Panel(panel.dimension(), Math.fma(panel.x() - previous.x(), t, previous.x()),
                    Math.fma(panel.y() - previous.y(), t, previous.y()),
                    Math.fma(panel.z() - previous.z(), t, previous.z()),
                    panel.yaw(), panel.zoom(), panel.heightZoom());
        }
        return panel;
    }

    public static boolean scrollPopup(double amount) {
        if (amount == 0 || !Double.isFinite(amount)) return false;
        if (drag != null) return scrollDrag(amount);
        if (ROOMS.isEmpty()) return false;
        Hit target = captureAim(KaraokePlatform.ray());
        if (target != null && hasQueue(target.room) && Math.abs(target.x) > WIDTH / 2.0) {
            var popup = KaraokePlatform.popup(target.room);
            if (popup != null && target.room.queue.side == (target.x < 0 ? -1 : 1)) {
                target.room.queue.scroll(amount < 0 ? 1 : -1, popup.wingPixels(KaraokeQueueView.DRAWER_WIDTH), popup.height);
                return true;
            }
        }
        if (!KaraokePlatform.popupZoomModifierHeld()) return false;
        if (target == null || !target.room.accepted || KaraokePlatform.popup(target.room) == null) return false;
        target.room.lyricsZoom = Math.clamp(target.room.lyricsZoom * Math.pow(1.1, Math.clamp(amount, -4, 4)), .65, 2.5);
        return true;
    }

    private static boolean scrollDrag(double amount) {
        var target = drag;
        if (target == null || target.resizing || amount == 0 || !Double.isFinite(amount)
                || !KaraokePlatform.buttonHeld(target.right)) return false;
        double distance = Math.clamp(target.distance + Math.clamp(amount, -4, 4) * .45, .65, RANGE - 4);
        drag = new Drag(target.room, target.right, false, target.start, target.x, target.y, distance);
        return true;
    }

    private static double aimYaw(Ray ray, double fallback) {
        return Math.hypot(ray.dx, ray.dz) < .0001 ? fallback : Math.toDegrees(Math.atan2(-ray.dx, -ray.dz));
    }

    private static void move(Ray ray) {
        var panel = draggedPanel(ray);
        if (panel == null || panel.equals(drag.room.panel)) return;
        drag.room.panel = panel;
        send("move", "room", drag.room.id, "panel", panel);
    }

    private static void leave(Room room, boolean notify) {
        if (room.panel == null) {
            if (notify) send("listen_stop");
            if (following == room) stopFollowing();
            KaraokePlatform.listenNotice("stopped");
            return;
        }
        blurQueue(room);
        room.executingQueue = null;
        KaraokeSurfaces.beginClose(room);
        if (notify) send("leave", "room", room.id);
        room.accepted = false;
        room.dismissed = true;
        room.song = null;
        if (following == room) stopFollowing();
    }

    private static void controlHost(String action, long position) {
        var music = RelayLyricsClient.music();
        if (music == null) return;
        switch (action) {
            case "toggle" -> music.command(MusicAction.PLAY_PAUSE);
            case "previous" -> music.command(MusicAction.PREVIOUS);
            case "next" -> music.command(MusicAction.NEXT);
            case "shuffle" -> music.command(MusicAction.TOGGLE_SHUFFLE);
            case "repeat" -> music.command(MusicAction.CYCLE_REPEAT);
            case "seek" -> music.command(MusicAction.SEEK, Math.clamp(position, 0, Math.max(0, music.snapshot().durationMillis())));
        }
    }

    private static void stopFollowing() {
        following = null;
        var music = RelayLyricsClient.music();
        if (music != null) music.setKaraokeSpotifyWatching(false);
    }

    private static void syncSpotify(Room room) {
        var music = RelayLyricsClient.music();
        if (music == null || !room.spotifySync || !music.spotifyAccountConnected()
                || room.spotifyFailed || room.loading) return;
        long generation = room.generation;
        String uri = room.song.uri().isBlank() ? room.resolvedUri : room.song.uri();
        if (uri == null) {
            room.loading = true;
            music.resolveKaraokeSpotify(room.song.title(), room.song.artist(), room.song.duration()).whenComplete((resolved, failure) ->
                    KaraokePlatform.execute(() -> {
                        if (following != room || room.generation != generation) return;
                        room.loading = false;
                        room.resolvedUri = resolved;
                        if (following == room && (failure != null || resolved == null)) {
                            room.spotifyFailed = true;
                            room.status = "karaoke.lyrica.spotify_match";
                            if (room.panel == null) KaraokePlatform.listenNotice("match");
                        }
                    }));
            return;
        }
        var local = music.karaokeSpotifySnapshot();
        boolean same = uri.equals(local.spotifyUri());
        long now = System.nanoTime();
        if (same) {
            room.matched = true;
            room.mismatchSince = 0;
        } else if (room.matched && local.spotifyUri() != null && now > room.nextSync) {
            if (room.mismatchSince == 0) room.mismatchSince = now;
            else if (now - room.mismatchSince > 3 * SECOND) leave(room, true);
            return;
        }
        if (now < room.nextSync) return;
        long elapsed = local.playing() && local.observedAt() != null
                ? Math.max(0, java.time.Duration.between(local.observedAt(), java.time.Instant.now()).toMillis()) : 0;
        long difference = Math.abs(local.positionMillis() + elapsed - room.position());
        if (same && local.playing() == room.song.playing() && difference < 1_800) return;
        if (!same && room.matched) return;
        room.loading = true;
        room.nextSync = now + 4 * SECOND;
        music.syncKaraokeSpotify(uri, room.position(), room.song.playing(), !same,
                !same || difference >= 1_800, () -> following == room && room.generation == generation).whenComplete((result, failure) ->
                KaraokePlatform.execute(() -> {
                    if (following != room || room.generation != generation) return;
                    room.loading = false;
                    if (following == room && failure != null) {
                        room.spotifyFailed = true;
                        room.status = "karaoke.lyrica.spotify_error";
                        if (room.panel == null) KaraokePlatform.listenNotice("playback");
                    }
                }));
    }
}
