package dev.relaylyrics.client.ui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Records glyph and mesh quads during extraction, then submits immutable geometry. */
public final class WorldBuffers implements MultiBufferSource {
    private final Map<RenderType, Recorder> layers = new LinkedHashMap<>();

    @Override public VertexConsumer getBuffer(RenderType type) {
        return layers.computeIfAbsent(type, ignored -> new Recorder());
    }

    public void submit(SubmitNodeCollector collector) {
        for (var entry : layers.entrySet()) {
            List<Vertex> vertices = List.copyOf(entry.getValue().vertices);
            if (vertices.isEmpty()) continue;
            collector.submitCustomGeometry(new PoseStack(), entry.getKey(), (pose, target) -> {
                for (Vertex vertex : vertices) {
                    target.addVertex(vertex.x, vertex.y, vertex.z)
                            .setColor(vertex.color).setUv(vertex.u, vertex.v)
                            .setOverlay(vertex.overlay).setLight(vertex.light)
                            .setNormal(vertex.nx, vertex.ny, vertex.nz);
                }
            });
        }
    }

    private static final class Recorder implements VertexConsumer {
        private final List<Vertex> vertices = new ArrayList<>();
        private Vertex current;

        @Override public VertexConsumer addVertex(float x, float y, float z) {
            current = new Vertex(x, y, z);
            vertices.add(current);
            return this;
        }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) {
            current.color = a << 24 | r << 16 | g << 8 | b;
            return this;
        }
        @Override public VertexConsumer setColor(int color) { current.color = color; return this; }
        @Override public VertexConsumer setUv(float u, float v) { current.u = u; current.v = v; return this; }
        @Override public VertexConsumer setUv1(int u, int v) { current.overlay = u | v << 16; return this; }
        @Override public VertexConsumer setUv2(int u, int v) { current.light = u | v << 16; return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) {
            current.nx = x; current.ny = y; current.nz = z;
            return this;
        }
        public VertexConsumer setUv3(float u, float v) { return this; }
        @Override public VertexConsumer setLineWidth(float width) { return this; }
    }

    private static final class Vertex {
        final float x, y, z;
        float u, v, nx, ny, nz;
        int color = -1, overlay, light = LightTexture.FULL_BRIGHT;
        Vertex(float x, float y, float z) { this.x = x; this.y = y; this.z = z; }
    }
}
