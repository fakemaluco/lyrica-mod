package dev.relaylyrics.client.ui;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;

/** Splits font quads at a local-space fill boundary, preserving UVs, depth and shadows. */
final class WorldTextFill {
    private WorldTextFill() { }

    static void draw(Font renderer, Component text, float x, int color, boolean shadow,
                     Matrix4f matrix, MultiBufferSource target, float boundary, boolean rtl) {
        Matrix4f inverse = new Matrix4f(matrix).invert();
        // Flush before requesting another layer: Immediate providers may invalidate their previous buffer.
        Clipper[] active = new Clipper[1];
        MultiBufferSource provider = layer -> {
            if (active[0] != null) active[0].flush();
            active[0] = new Clipper(target.getBuffer(layer), inverse, boundary, rtl);
            return active[0];
        };
        FontCompat.drawInBatch(renderer, text, x, 0, color, shadow, matrix, provider,
                Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
        if (active[0] != null) active[0].flush();
    }

    static final class Clipper implements VertexConsumer {
        private final VertexConsumer target;
        private final Matrix4f inverse;
        private final float boundary;
        private final boolean rtl;
        private final Vertex[] quad = {new Vertex(), new Vertex(), new Vertex(), new Vertex()};
        private int count;
        private Vertex current;
        Clipper(VertexConsumer target, Matrix4f inverse, float boundary, boolean rtl) {
            this.target = target; this.inverse = inverse; this.boundary = boundary; this.rtl = rtl;
        }
        public VertexConsumer addVertex(float x, float y, float z) {
            if (count == 4) flush();
            current = quad[count++];
            current.x = x; current.y = y; current.z = z;
            current.local = inverse.m00() * x + inverse.m10() * y + inverse.m20() * z + inverse.m30();
            return this;
        }
        public VertexConsumer setColor(int r, int g, int b, int a) {
            current.r = r; current.g = g; current.b = b; current.a = a; return this;
        }
        public VertexConsumer setColor(int argb) {
            return setColor(argb >> 16 & 255, argb >> 8 & 255, argb & 255, argb >>> 24);
        }
        public VertexConsumer setUv(float u, float v) { current.u = u; current.v = v; return this; }
        public VertexConsumer setUv1(int u, int v) { current.overlay = u | v << 16; return this; }
        public VertexConsumer setUv2(int u, int v) { current.light = u | v << 16; return this; }
        public VertexConsumer setNormal(float x, float y, float z) { return this; }
        // Required by 1.21.11; harmless additional method on 1.21.1.
        public VertexConsumer setUv3(float u, float v) { return this; }
        public VertexConsumer setLineWidth(float width) { return this; }

        void flush() {
            if (count == 4) {
                int left = 0;
                for (Vertex vertex : quad) if (vertex.local <= boundary) left++;
                if (left == 0 || left == 4) {
                    float alpha = ((left == 4) != rtl) ? 1 : .46F;
                    for (Vertex vertex : quad) emit(vertex, alpha);
                } else {
                    half(true, rtl ? .46F : 1);
                    half(false, rtl ? 1 : .46F);
                }
            }
            count = 0;
        }
        private void half(boolean left, float alpha) {
            Vertex[] polygon = new Vertex[6];
            int size = 0;
            Vertex previous = quad[3];
            boolean previousInside = left ? previous.local <= boundary : previous.local >= boundary;
            for (Vertex vertex : quad) {
                boolean inside = left ? vertex.local <= boundary : vertex.local >= boundary;
                if (inside != previousInside) {
                    float t = (boundary - previous.local) / (vertex.local - previous.local);
                    polygon[size++] = Vertex.mix(previous, vertex, t);
                }
                if (inside) polygon[size++] = vertex;
                previous = vertex; previousInside = inside;
            }
            if (size == 4) { for (int i = 0; i < size; i++) emit(polygon[i], alpha); }
            else for (int i = 1; i + 1 < size; i++) {
                // Font render layers consume quads; a duplicated vertex encodes a triangle.
                emit(polygon[0], alpha); emit(polygon[i], alpha);
                emit(polygon[i + 1], alpha); emit(polygon[i + 1], alpha);
            }
        }
        private void emit(Vertex v, float alpha) {
            target.addVertex(v.x, v.y, v.z).setColor(Math.round(v.r), Math.round(v.g), Math.round(v.b), Math.round(v.a * alpha))
                    .setUv(v.u, v.v).setOverlay(v.overlay).setLight(v.light);
        }
    }
    private static final class Vertex {
        float x, y, z, local, u, v, r, g, b, a;
        int light, overlay;
        static Vertex mix(Vertex a, Vertex b, float t) {
            Vertex v = new Vertex();
            v.x = a.x + (b.x - a.x) * t; v.y = a.y + (b.y - a.y) * t; v.z = a.z + (b.z - a.z) * t;
            v.local = a.local + (b.local - a.local) * t;
            v.u = a.u + (b.u - a.u) * t; v.v = a.v + (b.v - a.v) * t;
            v.r = a.r + (b.r - a.r) * t; v.g = a.g + (b.g - a.g) * t;
            v.b = a.b + (b.b - a.b) * t; v.a = a.a + (b.a - a.a) * t;
            v.light = a.light; v.overlay = a.overlay;
            return v;
        }
    }
}
