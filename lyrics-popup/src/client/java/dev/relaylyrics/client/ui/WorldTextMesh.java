package dev.relaylyrics.client.ui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.*;
import net.minecraft.text.Text;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import java.util.*;
import java.util.concurrent.*;

/** Captures the actual shaped font quads on a tick; builds closed glyph geometry on one worker. */
public final class WorldTextMesh {
    private static final int MAX_FACES = 16_384, CACHE_FACES = 180_000;
    private static final LinkedHashMap<Text, Entry> CACHE = new LinkedHashMap<>(32, .75f, true);
    private static final ArrayDeque<Text> QUEUE = new ArrayDeque<>();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "lyrica-world-mesh"); thread.setDaemon(true); return thread;
    });
    private WorldTextMesh() { }
    public static void clear() { CACHE.clear(); QUEUE.clear(); }

    /** Opt-in integration probe requiring a real GL context and Minecraft's loaded fonts. */
    public static void verifyFontCapture() {
        long start = System.nanoTime();
        Text sample = Text.literal("OB ag Áy مرحبا");
        List<Glyph> glyphs = capture(sample);
        Mesh mesh = build(glyphs);
        if (mesh == null || mesh.faces < 24) throw new IllegalStateException("World glyph atlas/mesh integration failed");
        if (glyphs.stream().map(glyph -> Arrays.deepHashCode(glyph.mask)).distinct().count() < 6)
            throw new IllegalStateException("Fonts have not finished loading; only fallback glyphs were captured");
        for (var side : WorldGlyphMesh.Side.values()) {
            if (mesh.parts.stream().flatMap(part -> part.faces.stream()).noneMatch(face -> face.side() == side))
                throw new IllegalStateException("Missing geometric face: " + side);
        }
        Entry probe = new Entry(); probe.mesh = CompletableFuture.completedFuture(mesh); CACHE.put(sample, probe);
        Capture sink = new Capture();
        try {
            if (!draw(sample, new Matrix4f(), ignored -> sink, 0, 3, 0xFFFFFF, 0x56647A, 1, 12f, false)
                    || sink.minZ != 0 || sink.maxZ != 3)
                throw new IllegalStateException("Extruded vertices or synchronized clipping failed");
        } finally { CACHE.remove(sample); }
        dev.relaylyrics.FanDroidMod.LOGGER.info("World mesh GPU self-test passed: {} glyphs, {} quads, {} ms",
                glyphs.size(), mesh.faces, (System.nanoTime() - start) / 1_000_000.0);
    }

    public static void tick() {
        if (MinecraftClient.getInstance().world == null) { clear(); return; }
        for (int i = 0; i < 2 && !QUEUE.isEmpty(); i++) {
            Text text = QUEUE.removeFirst();
            Entry entry = CACHE.get(text);
            if (entry == null) continue;
            try {
                List<Glyph> glyphs = capture(text);
                entry.mesh = CompletableFuture.supplyAsync(() -> build(glyphs), WORKER);
            } catch (RuntimeException exception) {
                entry.mesh = CompletableFuture.completedFuture(null);
            }
        }
        int count = 0;
        for (Entry entry : CACHE.values()) {
            Mesh mesh = ready(entry);
            if (mesh != null) count += mesh.faces;
        }
        var iterator = CACHE.entrySet().iterator();
        while ((count > CACHE_FACES || CACHE.size() > 256) && iterator.hasNext()) {
            var old = iterator.next(); Mesh mesh = ready(old.getValue());
            if (mesh != null) count -= mesh.faces;
            QUEUE.remove(old.getKey()); iterator.remove();
        }
    }

    private static Mesh ready(Entry entry) {
        return entry.mesh == null || !entry.mesh.isDone() || entry.mesh.isCompletedExceptionally()
                ? null : entry.mesh.getNow(null);
    }

    static boolean draw(Text text, Matrix4f matrix, VertexConsumerProvider target, float xOffset, float depth,
                        int faceColor, int sideColor, float alpha, Float fillBoundary, boolean rtl) {
        Entry entry = CACHE.get(text);
        if (entry == null) {
            if (QUEUE.size() < 128) { Text key = text.copy(); CACHE.put(key, new Entry()); QUEUE.add(key); }
            return false;
        }
        Mesh mesh = ready(entry);
        if (mesh == null) return false;
        Matrix4f inverse = fillBoundary == null ? null : new Matrix4f(matrix).invert();
        Vector3f point = new Vector3f();
        for (Part part : mesh.parts) {
            VertexConsumer consumer = target.getBuffer(part.glyph.layer);
            WorldTextFill.Clipper clipper = fillBoundary == null ? null
                    : new WorldTextFill.Clipper(consumer, inverse, fillBoundary, rtl);
            if (clipper != null) consumer = clipper;
            Glyph g = part.glyph;
            for (WorldGlyphMesh.Face face : part.faces) {
                boolean front = face.side() == WorldGlyphMesh.Side.FRONT;
                boolean cap = front || face.side() == WorldGlyphMesh.Side.BACK;
                float shade = switch (face.side()) {
                    case FRONT -> 1; case BACK -> .6f; case TOP -> .90f;
                    case BOTTOM -> .50f; case LEFT -> .68f; case RIGHT -> .80f;
                };
                int rgb = front ? faceColor : sideColor;
                int r = Math.round((rgb >> 16 & 255) * shade), b = Math.round((rgb & 255) * shade);
                int green = Math.round((rgb >> 8 & 255) * shade);
                // Consistent outward winding in local coordinates. The font render layer is double-sided.
                for (int v = 0; v < 4; v++) {
                    int px, py; float z;
                    switch (face.side()) {
                        case FRONT, BACK -> {
                            int corner = front ? v : 3 - v;
                            px = corner == 1 || corner == 2 ? face.x1() : face.x0();
                            py = corner >= 2 ? face.y1() : face.y0(); z = front ? depth : 0;
                        }
                        case LEFT, RIGHT -> {
                            int corner = face.side() == WorldGlyphMesh.Side.LEFT ? v : 3 - v;
                            px = face.x0(); py = corner >= 2 ? face.y1() : face.y0();
                            z = corner == 1 || corner == 2 ? depth : 0;
                        }
                        default -> {
                            int corner = face.side() == WorldGlyphMesh.Side.BOTTOM ? v : 3 - v;
                            px = corner >= 2 ? face.x1() : face.x0(); py = face.y0();
                            z = corner == 1 || corner == 2 ? depth : 0;
                        }
                    }
                    float x = g.x0 + (g.x1 - g.x0) * px / g.width + xOffset;
                    float y = g.y0 + (g.y1 - g.y0) * py / g.height;
                    matrix.transformPosition(x, y, z, point);
                    float u = (g.atlasX + (cap ? px : face.sampleX() + .5f)) / g.atlasWidth;
                    float vv = (g.atlasY + (cap ? py : face.sampleY() + .5f)) / g.atlasHeight;
                    consumer.vertex(point.x, point.y, point.z).color(r, green, b, Math.clamp(Math.round(alpha * 255), 0, 255))
                            .texture(u, vv).overlay(0).light(LightmapTextureManager.MAX_LIGHT_COORDINATE);
                }
            }
            if (clipper != null) clipper.flush();
        }
        return true;
    }

    private static Mesh build(List<Glyph> glyphs) {
        if (glyphs == null) return null;
        List<Part> parts = new ArrayList<>(); int count = 0;
        for (Glyph glyph : glyphs) {
            List<WorldGlyphMesh.Face> faces = WorldGlyphMesh.build(glyph.mask);
            count += faces.size(); if (count > MAX_FACES) return null;
            parts.add(new Part(glyph, faces));
        }
        return new Mesh(List.copyOf(parts), count);
    }

    private static List<Glyph> capture(Text text) {
        Map<RenderLayer, Capture> captures = new LinkedHashMap<>();
        MinecraftClient.getInstance().textRenderer.draw(text, 0, 0, 0xFFFFFFFF, false, new Matrix4f(),
                layer -> captures.computeIfAbsent(layer, ignored -> new Capture()), TextRenderer.TextLayerType.NORMAL,
                0, LightmapTextureManager.MAX_LIGHT_COORDINATE);
        List<Glyph> glyphs = new ArrayList<>();
        for (var item : captures.entrySet()) {
            item.getValue().flush();
            var atlas = WorldGlyphAtlas.snapshot(item.getKey());
            if (atlas == null) return null;
            for (Quad quad : item.getValue().quads) {
                int x0 = Math.round(quad.u0 * atlas.width()), x1 = Math.round(quad.u1 * atlas.width());
                int y0 = Math.round(quad.v0 * atlas.height()), y1 = Math.round(quad.v1 * atlas.height());
                int width = x1 - x0, height = y1 - y0;
                if (width <= 0 || height <= 0 || width > 512 || height > 512) continue;
                boolean[][] mask = new boolean[height][width];
                for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) mask[y][x] = atlas.solid(x0 + x, y0 + y);
                glyphs.add(new Glyph(item.getKey(), quad.x0, quad.y0, quad.x1, quad.y1,
                        width, height, x0, y0, atlas.width(), atlas.height(), mask));
            }
        }
        return glyphs;
    }

    private static final class Capture implements VertexConsumer {
        final List<Quad> quads = new ArrayList<>();
        float x0, y0, x1, y1, u0, v0, u1, v1; int count;
        float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        public VertexConsumer vertex(float x, float y, float z) {
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
            if (count == 4) flush();
            if (count++ == 0) { x0 = x1 = x; y0 = y1 = y; u0 = v0 = Float.MAX_VALUE; u1 = v1 = -Float.MAX_VALUE; }
            else { x0 = Math.min(x0, x); y0 = Math.min(y0, y); x1 = Math.max(x1, x); y1 = Math.max(y1, y); }
            return this;
        }
        public VertexConsumer texture(float u, float v) { u0 = Math.min(u0, u); v0 = Math.min(v0, v); u1 = Math.max(u1, u); v1 = Math.max(v1, v); return this; }
        public VertexConsumer color(int r, int g, int b, int a) { return this; }
        public VertexConsumer color(int argb) { return this; }
        public VertexConsumer overlay(int u, int v) { return this; }
        public VertexConsumer light(int u, int v) { return this; }
        public VertexConsumer normal(float x, float y, float z) { return this; }
        public VertexConsumer lineWidth(float width) { return this; }
        void flush() { if (count == 4) quads.add(new Quad(x0, y0, x1, y1, u0, v0, u1, v1)); count = 0; }
    }
    private record Quad(float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1) { }
    private record Glyph(RenderLayer layer, float x0, float y0, float x1, float y1, int width, int height,
                         int atlasX, int atlasY, int atlasWidth, int atlasHeight, boolean[][] mask) { }
    private record Part(Glyph glyph, List<WorldGlyphMesh.Face> faces) { }
    private record Mesh(List<Part> parts, int faces) { }
    private static final class Entry { CompletableFuture<Mesh> mesh; }
}
