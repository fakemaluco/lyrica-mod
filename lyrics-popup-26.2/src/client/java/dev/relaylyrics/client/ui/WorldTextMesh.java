package dev.relaylyrics.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.*;
import java.util.concurrent.*;

/** Captures the actual shaped font quads on a tick; builds closed glyph geometry on one worker. */
public final class WorldTextMesh {
    private static final int MAX_FACES = 16_384, CACHE_FACES = 180_000;
    private static final LinkedHashMap<Component, Entry> CACHE = new LinkedHashMap<>(32, .75f, true);
    private static final ArrayDeque<Component> QUEUE = new ArrayDeque<>();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "lyrica-world-mesh"); thread.setDaemon(true); return thread;
    });
    private WorldTextMesh() { }
    public static void clear() { CACHE.clear(); QUEUE.clear(); }

    /** Opt-in integration probe requiring a real GL context and Minecraft's loaded fonts. */
    public static void verifyFontCapture() {
        long start = System.nanoTime();
        Component sample = Component.literal("OB ag Áy مرحبا");
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
            // World lyrics flip local Y, so test the winding after that reflection.
            if (!draw(sample, new Matrix4f().scale(1, -1, 1), ignored -> sink, 0, 3,
                    0xFFFFFF, 0x56647A, 1, 12f, false)
                    || sink.minZ != 0 || sink.maxZ != 3 || !sink.sawFront || !sink.sawSide
                    || sink.sideAfterFront || sink.frontWinding <= 0)
                throw new IllegalStateException("Extruded vertices or synchronized clipping failed");
            Capture beveled = new Capture();
            if (!draw(sample, new Matrix4f().scale(1, -1, 1), ignored -> beveled, 0, 3,
                    0xFFFFFF, 0x56647A, 1, null, false, true) || beveled.maxZ <= 3)
                throw new IllegalStateException("Beveled rim was not emitted");
        } finally { CACHE.remove(sample); }
        dev.relaylyrics.FanDroidMod.LOGGER.info("World mesh GPU self-test passed: {} glyphs, {} quads, {} ms",
                glyphs.size(), mesh.faces, (System.nanoTime() - start) / 1_000_000.0);
    }

    public static void tick() {
        if (Minecraft.getInstance().level == null) { clear(); return; }
        for (int i = 0; i < 2 && !QUEUE.isEmpty(); i++) {
            Component text = QUEUE.removeFirst();
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

    static boolean draw(Component text, Matrix4f matrix, MultiBufferSource target, float xOffset, float depth,
                        int faceColor, int sideColor, float alpha, Float fillBoundary, boolean rtl) {
        return draw(text, matrix, target, xOffset, depth, faceColor, sideColor, alpha, fillBoundary, rtl, false);
    }

    static boolean draw(Component text, Matrix4f matrix, MultiBufferSource target, float xOffset, float depth,
                        int faceColor, int sideColor, float alpha, Float fillBoundary, boolean rtl, boolean bevel) {
        return draw(text, matrix, target, xOffset, depth, faceColor, sideColor, alpha, fillBoundary, rtl, bevel, .45f);
    }

    static boolean draw(Component text, Matrix4f matrix, MultiBufferSource target, float xOffset, float depth,
                        int faceColor, int sideColor, float alpha, Float fillBoundary, boolean rtl,
                        boolean bevel, float bevelWidth) {
        Entry entry = CACHE.get(text);
        if (entry == null) {
            if (QUEUE.size() < 128) { Component key = text.copy(); CACHE.put(key, new Entry()); QUEUE.add(key); }
            return false;
        }
        Mesh mesh = ready(entry);
        if (mesh == null) return false;
        Matrix4f inverse = fillBoundary == null ? null : new Matrix4f(matrix).invert();
        Vector3f point = new Vector3f();
        // Text layers can blend without writing depth. Draw the back and contour first so
        // the colored face is composited nearest the viewer even on those layers.
        for (int pass = 0; pass < (bevel ? 4 : 3); pass++) for (Part part : mesh.parts) {
            VertexConsumer consumer = target.getBuffer(part.glyph.layer);
            WorldTextFill.Clipper clipper = fillBoundary == null ? null
                    : new WorldTextFill.Clipper(consumer, inverse, fillBoundary, rtl);
            if (clipper != null) consumer = clipper;
            Glyph g = part.glyph;
            for (WorldGlyphMesh.Face face : part.faces) {
                boolean front = face.side() == WorldGlyphMesh.Side.FRONT;
                boolean rim = pass == 3;
                if (rim ? front || face.side() == WorldGlyphMesh.Side.BACK
                        : pass != (front ? 2 : face.side() == WorldGlyphMesh.Side.BACK ? 0 : 1)) continue;
                boolean cap = !rim && (front || face.side() == WorldGlyphMesh.Side.BACK);
                float shade = switch (face.side()) {
                    case FRONT -> 1; case BACK -> .6f; case TOP -> .90f;
                    case BOTTOM -> .50f; case LEFT -> .68f; case RIGHT -> .80f;
                };
                int rgb = front || rim ? faceColor : sideColor;
                if (rim) shade = face.side() == WorldGlyphMesh.Side.TOP ? .94f : .72f;
                int r = Math.round((rgb >> 16 & 255) * shade), b = Math.round((rgb & 255) * shade);
                int green = Math.round((rgb >> 8 & 255) * shade);
                // The caller reflects local Y to make font coordinates upright. Reverse
                // the winding here so the front cap still faces the viewer after that reflection.
                for (int v = 0; v < 4; v++) {
                    int winding = 3 - v;
                    float px, py, z;
                    switch (face.side()) {
                        case FRONT, BACK -> {
                            int corner = front ? winding : 3 - winding;
                            px = corner == 1 || corner == 2 ? face.x1() : face.x0();
                            py = corner >= 2 ? face.y1() : face.y0(); z = front ? depth : 0;
                        }
                        case LEFT, RIGHT -> {
                            int corner = face.side() == WorldGlyphMesh.Side.LEFT ? winding : 3 - winding;
                            px = face.x0(); py = corner >= 2 ? face.y1() : face.y0();
                            boolean inner = corner == 1 || corner == 2;
                            if (rim && inner) px += face.side() == WorldGlyphMesh.Side.LEFT ? bevelWidth : -bevelWidth;
                            z = rim ? depth + (inner ? Math.min(.65f, depth * .2f) : 0) : inner ? depth : 0;
                        }
                        default -> {
                            int corner = face.side() == WorldGlyphMesh.Side.BOTTOM ? winding : 3 - winding;
                            px = corner >= 2 ? face.x1() : face.x0(); py = face.y0();
                            boolean inner = corner == 1 || corner == 2;
                            if (rim && inner) py += face.side() == WorldGlyphMesh.Side.TOP ? bevelWidth : -bevelWidth;
                            z = rim ? depth + (inner ? Math.min(.65f, depth * .2f) : 0) : inner ? depth : 0;
                        }
                    }
                    float x = g.x0 + (g.x1 - g.x0) * px / g.width + xOffset;
                    float y = g.y0 + (g.y1 - g.y0) * py / g.height;
                    matrix.transformPosition(x, y, z, point);
                    float u = (g.atlasX + (cap ? px : face.sampleX() + .5f)) / g.atlasWidth;
                    float vv = (g.atlasY + (cap ? py : face.sampleY() + .5f)) / g.atlasHeight;
                    consumer.addVertex(point.x, point.y, point.z).setColor(r, green, b, Math.clamp(Math.round(alpha * 255), 0, 255))
                            .setUv(u, vv).setOverlay(0).setLight(LightTexture.FULL_BRIGHT);
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

    private static List<Glyph> capture(Component text) {
        Map<RenderType, Capture> captures = new LinkedHashMap<>();
        FontCompat.drawInBatch(Minecraft.getInstance().font, text, 0, 0, 0xFFFFFFFF, false, new Matrix4f(),
                layer -> captures.computeIfAbsent(layer, ignored -> new Capture()), Font.DisplayMode.NORMAL,
                0, LightTexture.FULL_BRIGHT);
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
        float lastX, lastY;
        boolean sawFront, sawSide, sideAfterFront;
        float frontWinding;
        float[] frontX = new float[3], frontY = new float[3];
        int frontVertices;
        public VertexConsumer addVertex(float x, float y, float z) {
            lastX = x; lastY = y;
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
            if (count == 4) flush();
            if (count++ == 0) { x0 = x1 = x; y0 = y1 = y; u0 = v0 = Float.MAX_VALUE; u1 = v1 = -Float.MAX_VALUE; }
            else { x0 = Math.min(x0, x); y0 = Math.min(y0, y); x1 = Math.max(x1, x); y1 = Math.max(y1, y); }
            return this;
        }
        public VertexConsumer setUv(float u, float v) { u0 = Math.min(u0, u); v0 = Math.min(v0, v); u1 = Math.max(u1, u); v1 = Math.max(v1, v); return this; }
        public VertexConsumer setColor(int r, int g, int b, int a) {
            if (r == 255 && g == 255 && b == 255) {
                sawFront = true;
                if (frontVertices < 3) {
                    frontX[frontVertices] = lastX; frontY[frontVertices] = lastY;
                    if (++frontVertices == 3) frontWinding = (frontX[1] - frontX[0]) * (frontY[2] - frontY[0])
                            - (frontY[1] - frontY[0]) * (frontX[2] - frontX[0]);
                }
            }
            else { sawSide = true; if (sawFront) sideAfterFront = true; }
            return this;
        }
        public VertexConsumer setColor(int argb) { return this; }
        public VertexConsumer setUv1(int u, int v) { return this; }
        public VertexConsumer setUv2(int u, int v) { return this; }
        public VertexConsumer setNormal(float x, float y, float z) { return this; }
        public VertexConsumer setLineWidth(float width) { return this; }
        void flush() { if (count == 4) quads.add(new Quad(x0, y0, x1, y1, u0, v0, u1, v1)); count = 0; }
    }
    private record Quad(float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1) { }
    private record Glyph(RenderType layer, float x0, float y0, float x1, float y1, int width, int height,
                         int atlasX, int atlasY, int atlasWidth, int atlasHeight, boolean[][] mask) { }
    private record Part(Glyph glyph, List<WorldGlyphMesh.Face> faces) { }
    private record Mesh(List<Part> parts, int faces) { }
    private static final class Entry { CompletableFuture<Mesh> mesh; }
}
