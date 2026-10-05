package dev.relaylyrics.client.ui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorldBuffersTest {
    @Test void replaysCompletedVerticesWhenTheLevelRendererCollectsGeometry() {
        WorldBuffers buffers = new WorldBuffers();
        buffers.getBuffer(null).addVertex(1.5f, 2, -3).setColor(230, 240, 255, 128)
                .setUv(.25f, .75f).setLight(0xF000F0);

        List<float[]> emitted = new ArrayList<>();
        SubmitNodeCollector collector = (SubmitNodeCollector) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{SubmitNodeCollector.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("submitCustomGeometry")) {
                        var renderer = (SubmitNodeCollector.CustomGeometryRenderer) args[2];
                        renderer.render(new PoseStack().last(), new Capture(emitted));
                    }
                    return null;
                });

        buffers.submit(collector);
        assertThat(emitted).hasSize(1);
        assertThat(emitted.getFirst()).containsExactly(1.5f, 2f, -3f, .25f, .75f, 128f);
    }

    private static final class Capture implements VertexConsumer {
        private final List<float[]> emitted;
        private float[] current;
        Capture(List<float[]> emitted) { this.emitted = emitted; }
        @Override public VertexConsumer addVertex(float x, float y, float z) {
            current = new float[]{x, y, z, 0, 0, 0};
            emitted.add(current);
            return this;
        }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) {
            current[5] = a;
            return this;
        }
        @Override public VertexConsumer setColor(int color) { current[5] = color >>> 24; return this; }
        @Override public VertexConsumer setUv(float u, float v) {
            current[3] = u; current[4] = v;
            return this;
        }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
        @Override public VertexConsumer setLineWidth(float width) { return this; }
    }
}
