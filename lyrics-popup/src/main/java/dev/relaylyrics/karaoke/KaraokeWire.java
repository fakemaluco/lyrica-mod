package dev.relaylyrics.karaoke;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

public final class KaraokeWire {
    public static final int MAX_BYTES = 30_000, MAX_LYRIC_PARTS = 12;
    public static final double RANGE = 24;
    public static final int WIDTH = 320, HEIGHT = 200;
    public static final double SCALE = 2.4 / WIDTH;
    public static final double MIN_ZOOM = .5, MAX_ZOOM = 2.5;
    public static final Gson GSON = new Gson();

    private KaraokeWire() { }

    public record Song(String title, String artist, String cover, String uri, long position, long duration,
                       boolean playing, boolean shuffled, String repeat) {
        public Song(String title, String artist, String cover, String uri, long position, long duration, boolean playing) {
            this(title, artist, cover, uri, position, duration, playing, false, "off");
        }
        public Song clean() {
            String track = uri != null && uri.matches("spotify:track:[A-Za-z0-9]{22}") ? uri : "";
            long length = Math.clamp(duration, 0, 86_400_000);
            return new Song(text(title, 256), text(artist, 256), text(cover, 2048), track,
                    Math.clamp(position, 0, length > 0 ? length : 86_400_000), length, playing, shuffled,
                    "track".equals(repeat) || "context".equals(repeat) ? repeat : "off");
        }
        public long at(long elapsed) {
            long value = position + (playing ? Math.max(0, elapsed) : 0);
            return duration > 0 ? Math.min(duration, value) : value;
        }
        public boolean sameTrack(Song other) {
            if (!uri.isBlank() && !other.uri.isBlank()) return uri.equals(other.uri);
            return title.equalsIgnoreCase(other.title) && artist.equalsIgnoreCase(other.artist);
        }
    }
    public record Panel(String dimension, double x, double y, double z, double yaw, double zoom, double heightZoom) {
        public Panel(String dimension, double x, double y, double z, double yaw) {
            this(dimension, x, y, z, yaw, 1);
        }
        public Panel(String dimension, double x, double y, double z, double yaw, double zoom) {
            this(dimension, x, y, z, yaw, zoom, zoom);
        }
        public Panel {
            if (zoom == 0) zoom = 1; // Packets from versions without resizing have no zoom field.
            if (heightZoom == 0) heightZoom = zoom;
        }
        public double pixelSize() { return SCALE * zoom; }
        public double pixelHeight() { return SCALE * heightZoom; }
        public boolean valid() {
            return dimension != null && Double.isFinite(yaw) && Math.abs(yaw) <= 360
                    && zoom >= MIN_ZOOM && zoom <= MAX_ZOOM
                    && heightZoom >= MIN_ZOOM && heightZoom <= MAX_ZOOM
                    && Math.abs(x) <= 30_000_000 && Math.abs(y) <= 4096 && Math.abs(z) <= 30_000_000;
        }
        public double distanceSquared(double px, double py, double pz) {
            return (x-px)*(x-px) + (y-py)*(y-py) + (z-pz)*(z-pz);
        }
    }
    public record Mark(long start, long end, String text, String romanization) { }
    public record Line(long start, long end, String text, List<Mark> marks,
                       String agent, boolean background, String translation, String romanization) {
        public Line(long start, long end, String text, List<Mark> marks) {
            this(start, end, text, marks, "", false, "", "");
        }
    }
    /** Wire coordinates remain independent of the popup's current logical size. */
    public record Pointer(double x, double y) {
        private static final double HALF_WIDTH = 100, HALF_HEIGHT = 48;

        public static Pointer fromPanel(double x, double y) {
            return new Pointer(x * HALF_WIDTH / (WIDTH / 2.0), y * HALF_HEIGHT / (HEIGHT / 2.0));
        }
        public double panelX() { return x * (WIDTH / 2.0) / HALF_WIDTH; }
        public double panelY() { return y * (HEIGHT / 2.0) / HALF_HEIGHT; }
        public boolean valid() { return Math.abs(x) <= HALF_WIDTH && Math.abs(y) <= HALF_HEIGHT; }
    }

    public static JsonObject message(String op, Object... fields) {
        var json = new JsonObject();
        json.addProperty("op", op);
        for (int i = 0; i < fields.length; i += 2) json.add((String) fields[i], GSON.toJsonTree(fields[i+1]));
        return json;
    }
    public static String string(JsonObject json, String key) {
        var value = json.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }
    public static UUID id(JsonObject json, String key) { return UUID.fromString(string(json, key)); }
    public static <T> T read(JsonObject json, String key, Class<T> type) { return GSON.fromJson(json.get(key), type); }
    public static long generation(JsonObject json) {
        var value = json.get("generation");
        return value == null || value.isJsonNull() ? 0 : value.getAsLong();
    }
    public static byte[] encode(JsonObject json) { return GSON.toJson(json).getBytes(StandardCharsets.UTF_8); }
    public static JsonObject decode(byte[] bytes) { return GSON.fromJson(new String(bytes, StandardCharsets.UTF_8), JsonObject.class); }
    public static String text(String value, int max) {
        if (value == null) return "";
        String clean = value.replaceAll("[\\p{Cc}\\p{Cf}§]", "").strip();
        return clean.substring(0, clean.offsetByCodePoints(0, Math.min(max, clean.codePointCount(0, clean.length()))));
    }
}
