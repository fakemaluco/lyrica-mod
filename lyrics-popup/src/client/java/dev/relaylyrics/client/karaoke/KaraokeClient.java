package dev.relaylyrics.client.karaoke;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.relaylyrics.client.RelayLyricsClient;
import dev.relaylyrics.client.presence.PresenceTrack;
import dev.relaylyrics.karaoke.KaraokeWire.*;
import dev.relaylyrics.protocol.lyrics.LyricLine;
import dev.relaylyrics.protocol.lyrics.LyricsDocument;
import dev.relaylyrics.protocol.lyrics.LyricsMetadata;
import dev.relaylyrics.protocol.lyrics.LyricsSource;
import dev.relaylyrics.protocol.lyrics.LyricsSyncMode;
import dev.relaylyrics.protocol.lyrics.TimedWord;
import dev.relaylyrics.protocol.music.MusicAction;
import dev.relaylyrics.protocol.music.PlaybackSnapshot;

import java.util.*;
import java.util.concurrent.TimeUnit;

import static dev.relaylyrics.karaoke.KaraokeWire.*;

public final class KaraokeClient {
    private static final Map<UUID, Room> ROOMS = new LinkedHashMap<>();
    private static final Map<UUID, PresenceTrack> PRESENCE = new HashMap<>();
    private static UUID hosting;
    private static boolean placing;
    private static volatile Room following;
    private static Hit hit;
    private static Drag drag;
    private static Room pointerRoom;
    private static boolean usedAttack, usedInteract;
    private static long ticks;
    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);
    private static final long CURSOR_TRAVEL = TimeUnit.MILLISECONDS.toNanos(150);
    static final int HANDLE_HALF_WIDTH = 30, HANDLE_TOP = HEIGHT / 2 + 3, HANDLE_BOTTOM = HEIGHT / 2 + 21;
    static final int RESIZE_SIZE = 19;

    private KaraokeClient() { }

    public static final class Room {
        public final UUID id;
        public final String name;
        public Panel panel;
        public boolean accepted, dismissed;
        public Song song;
        public String status = "";
        long lyricRevision;
        double lastAudibleVolume = .5;
        private volatile long generation;
        private long received;
        private long nextSync, mismatchSince;
        private boolean loading, matched, spotifyFailed, spotifySync;
        private String resolvedUri;
        private final TreeMap<Integer, List<Line>> lyrics = new TreeMap<>();
        private LyricsSyncMode lyricSync;
        public final Map<UUID, Cursor> cursors = new HashMap<>();

        private Room(UUID id, String name, Panel panel) { this.id = id; this.name = name; this.panel = panel; }
        public long position() { return song == null ? 0 : song.at(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - received)); }
        public boolean owned() { return id.equals(hosting); }
        boolean isListeningOnSpotify() { return spotifySync && matched && !spotifyFailed; }
        private void resetTrack(long generation) {
            this.generation = generation;
            lyrics.clear();
            lyricSync = null;
            lyricRevision++;
            loading = matched = spotifyFailed = false;
            nextSync = mismatchSince = 0;
            resolvedUri = null;
            status = "";
        }
        LyricsDocument document() {
            var document = new LyricsDocument(lyrics.values().stream().flatMap(List::stream)
                    .map(line -> new LyricLine(line.start(), line.end(), line.text(), line.marks().stream()
                            .map(mark -> new TimedWord(mark.start(), mark.end(), mark.text().stripLeading(),
                                    !mark.text().startsWith(" "), mark.romanization())).toList(),
                            line.agent(), line.background(), line.translation(), line.romanization())).toList());
            return lyricSync == null ? document
                    : document.withMetadata(new LyricsMetadata(LyricsSource.REMOTE, lyricSync, "", "", "", ""));
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

    public static void initialize() { KaraokePlatform.register(); }
    public static boolean supported() { return KaraokePlatform.supported(); }
    public static Collection<Room> rooms() { return ROOMS.values(); }
    public static Hit hit() { return hit; }
    public static boolean consumesAttack() { return usedAttack || drag != null; }
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
        KaraokePlatform.clearPopups();
        ROOMS.clear(); PRESENCE.clear(); hosting = null; placing = false; hit = null; drag = null; ticks = 0;
        pointerRoom = null;
        usedAttack = usedInteract = false;
    }
    private static void send(String op, Object... fields) { KaraokePlatform.send(message(op, fields)); }

    static void receive(JsonObject data) {
        try {
            String op = string(data, "op");
            if (op.equals("hello")) return;
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
                if (room == null) ROOMS.put(id, new Room(id, text(string(data, "name"), 16), panel));
                else if (drag == null || drag.room != room) room.panel = panel;
                return;
            }
            var room = ROOMS.get(id);
            if (room == null) return;
            switch (op) {
                case "close" -> {
                    ROOMS.remove(id);
                    if (room.owned()) { hosting = null; placing = false; }
                    if (following == room) stopFollowing();
                    if (drag != null && drag.room == room) drag = null;
                }
                case "left" -> leave(room, false);
                case "hosting" -> {
                    if (following != null) leave(following, true);
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
                    var song = read(data, "song", Song.class);
                    long generation = generation(data);
                    if (generation < room.generation) return;
                    if (generation != room.generation) room.resetTrack(generation);
                    room.song = song == null ? null : song.clean();
                    room.received = System.nanoTime();
                }
                case "lyrics" -> {
                    if (room.accepted && room.song != null && generation(data) == room.generation) readLyrics(room, data);
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
            var shared = music != null && p.isMusicPresenceEnabled() && p.isMusicPresenceSharing()
                    ? song(music.snapshot(), 0) : null;
            send("presence", "song", shared == null ? null : new Song(shared.title(), shared.artist(),
                    shared.cover(), "", 0, 0, shared.playing()));
            PRESENCE.values().removeIf(track -> !track.alive(System.nanoTime()));
        }
        Ray ray = ROOMS.isEmpty() ? null : KaraokePlatform.ray();
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
        if (drag != null) {
            boolean held = ray != null && KaraokePlatform.buttonHeld(drag.right);
            if (ray != null && (!held || ticks % 2 == 0)) move(ray);
            if (!held) drag = null;
        }
        var pointed = drag == null && target != null && target.room.accepted && !handleAt(target.x, target.y)
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
        for (Room room : ROOMS.values()) room.cursors.values().removeIf(cursor -> now - cursor.received > SECOND);
    }

    private static Song song(PlaybackSnapshot snapshot, long position) {
        if (snapshot.title() == null || snapshot.title().isBlank() || "Nothing playing".equals(snapshot.title())) return null;
        return new Song(snapshot.title(), snapshot.artist(), snapshot.artworkUrl(), snapshot.spotifyUri(),
                position, snapshot.durationMillis(), snapshot.playing(), snapshot.shuffled(), snapshot.repeatMode()).clean();
    }

    private static void publish(Room room) {
        var music = RelayLyricsClient.music();
        var overlay = RelayLyricsClient.overlay();
        var song = music == null ? null : song(music.snapshot(), music.positionMillis());
        send("update", "room", room.id, "song", song);
        // Wait for the server's track acknowledgement before sending that track's lyrics.
        if (song != null && room.song != null && room.song.sameTrack(song) && room.lyrics.isEmpty()
                && overlay != null && !overlay.document().lines().isEmpty()) {
            var document = overlay.document();
            String sync = document.metadata().syncMode().name();
            var batch = new JsonArray();
            int part = 0, bytes = 128;
            for (var line : document.lines()) {
                List<Mark> marks = line.words().stream().limit(160).map(word -> new Mark(word.startMillis(), word.endMillis(),
                        (word.joinPrevious() ? "" : " ") + text(word.text(), 120), text(word.romanization(), 120))).toList();
                var encoded = GSON.toJsonTree(new Line(line.startMillis(), line.endMillis(), text(line.text(), 500), marks,
                        text(line.agent(), 48), line.background(), text(line.translation(), 500), text(line.romanization(), 500)));
                int size = GSON.toJson(encoded).getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1;
                if (size > MAX_BYTES - 256) continue;
                if (bytes + size > MAX_BYTES || batch.size() >= 400) {
                    if (!batch.isEmpty()) send("lyrics", "room", room.id, "generation", room.generation,
                            "part", part++, "sync", sync, "lines", batch);
                    batch = new JsonArray();
                    bytes = 128;
                    if (part == MAX_LYRIC_PARTS) break;
                }
                batch.add(encoded);
                bytes += size;
            }
            if (!batch.isEmpty()) send("lyrics", "room", room.id, "generation", room.generation,
                    "part", part, "sync", sync, "lines", batch);
        }
    }

    private static void readLyrics(Room room, JsonObject data) {
        int part = data.get("part").getAsInt();
        var input = data.getAsJsonArray("lines");
        if (part < 0 || part >= MAX_LYRIC_PARTS || input.size() > 400) return;
        List<Line> lines = new ArrayList<>();
        for (var element : input) {
            var line = GSON.fromJson(element, Line.class);
            if (line == null || line.start() < 0 || line.end() < line.start() || line.end() > 86_400_000) continue;
            var marks = line.marks() == null ? List.<Mark>of() : line.marks().stream().filter(Objects::nonNull)
                    .filter(mark -> mark.start() >= 0 && mark.end() >= mark.start() && mark.end() <= 86_400_000)
                    .limit(160).map(mark -> new Mark(mark.start(), mark.end(),
                            (mark.text() != null && mark.text().startsWith(" ") ? " " : "") + text(mark.text(), 120),
                            text(mark.romanization(), 120))).toList();
            lines.add(new Line(line.start(), line.end(), text(line.text(), 500), marks,
                    text(line.agent(), 48), line.background(), text(line.translation(), 500), text(line.romanization(), 500)));
        }
        if (room.lyrics.putIfAbsent(part, List.copyOf(lines)) == null) {
            String sync = string(data, "sync");
            for (var mode : LyricsSyncMode.values()) if (mode.name().equals(sync)) room.lyricSync = mode;
            room.lyricRevision++;
        }
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
            if (panel || handle) closest = new Hit(room, x, y, distance);
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

    public static boolean hideVanillaCrosshair() {
        var target = captureAim(ROOMS.isEmpty() ? null : KaraokePlatform.ray());
        return target != null && (!target.room.accepted || KaraokePlatform.popup(target.room) != null);
    }

    public static boolean click(boolean right) {
        if (drag != null || (right ? usedInteract : usedAttack)) return true;
        if (ROOMS.isEmpty()) return false;
        Ray ray = KaraokePlatform.ray();
        Hit current = ray == null ? null : find(ray);
        if (current == null) return false;
        Room room = current.room;
        if (right) usedInteract = true;
        else usedAttack = true;
        boolean resizing = resizeAt(current.x, current.y);
        if (room.owned() && room.accepted && (handleAt(current.x, current.y) || resizing)) {
            drag = new Drag(room, right, resizing, room.panel, current.x, current.y, current.distance);
            return true;
        }
        if (!room.accepted) {
            if (current.y >= 24 && current.y <= 63 && Math.abs(current.x) >= 18 && Math.abs(current.x) <= 125) {
                if (current.x > 0) {
                    if (following != null && following != room) leave(following, true);
                    following = room;
                    room.spotifySync = RelayLyricsClient.music() != null && RelayLyricsClient.music().spotifyAccountConnected();
                    send("accept", "room", room.id);
                } else room.dismissed = true;
            }
        } else clickPopup(current);
        return true;
    }

    private static void clickPopup(Hit hit) {
        Room room = hit.room;
        var view = KaraokePlatform.popup(room);
        if (view == null) return;
        var overlay = view.overlay;
        double x = hit.x + WIDTH / 2.0, y = hit.y + HEIGHT / 2.0;
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

    public static boolean scrollDrag(double amount) {
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
        if (notify) send("leave", "room", room.id);
        room.accepted = false;
        room.dismissed = true;
        room.song = null;
        room.lyrics.clear();
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
                    }
                }));
    }
}
