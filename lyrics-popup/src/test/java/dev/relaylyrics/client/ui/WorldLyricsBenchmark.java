package dev.relaylyrics.client.ui;

import dev.relaylyrics.client.music.WorldLyricsPlan;
import dev.relaylyrics.protocol.lyrics.LyricLine;
import dev.relaylyrics.protocol.lyrics.TimedWord;
import net.minecraft.client.render.VertexConsumer;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.util.Arrays;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

/** Optional CPU probe, not a GPU/FPS benchmark. No machine-dependent pass threshold. */
@EnabledIfEnvironmentVariable(named = "LYRICA_WORLD_BENCHMARK", matches = "true")
class WorldLyricsBenchmark {
    @Test void threeConcurrentLines() {
        var line = new LyricLine(0, 4000, "held", List.of(new TimedWord(0, 4000, "held")), "v1", false, null, null);
        var token = WorldLyricsPlan.tokens(line, false).getFirst();
        var reaction = new WorldLyricsPlan.PlayerReaction(1, .5, 1, 1);
        var sink = new Sink(); var matrix = new Matrix4f();
        long[] samples = new long[2000];
        double checksum = 0;
        for (int frame = -1000; frame < samples.length; frame++) {
            long start = System.nanoTime();
            for (int row = 0; row < 3; row++) {
                for (int glyph = 0; glyph < 80; glyph++) {
                    var motion = WorldLyricsPlan.motion(line, token, 1000 + Math.floorMod(frame, 2000), glyph / 79.0,
                            true, true, 1.4, 6, reaction, true);
                    checksum += motion.y() + motion.scale();
                }
                var clipper = new WorldTextFill.Clipper(sink, matrix, 4, false);
                clipper.vertex(0,0,0).color(-1).texture(0,0).light(240,240);
                clipper.vertex(0,8,0).color(-1).texture(0,1).light(240,240);
                clipper.vertex(10,8,0).color(-1).texture(1,1).light(240,240);
                clipper.vertex(10,0,0).color(-1).texture(1,0).light(240,240);
                clipper.flush();
            }
            if (frame >= 0) samples[frame] = System.nanoTime() - start;
        }
        Arrays.sort(samples);
        System.out.printf(java.util.Locale.ROOT,
                "World lyrics CPU probe: 240 motion evaluations + 3 clipped quads/frame; median %.3f ms, p95 %.3f ms%n",
                samples[samples.length / 2] / 1e6, samples[(int)(samples.length * .95)] / 1e6);
        assertThat(checksum).isPositive();
        assertThat(sink.vertices).isEqualTo(3000L * 3 * 8);
    }
    private static class Sink implements VertexConsumer {
        long vertices;
        public VertexConsumer vertex(float x,float y,float z) { vertices++; return this; }
        public VertexConsumer color(int r,int g,int b,int a) { return this; }
        public VertexConsumer color(int color) { return this; }
        public VertexConsumer texture(float u,float v) { return this; }
        public VertexConsumer overlay(int u,int v) { return this; }
        public VertexConsumer light(int u,int v) { return this; }
        public VertexConsumer normal(float x,float y,float z) { return this; }
        public VertexConsumer lineWidth(float width) { return this; }
    }
}
