package dev.relaylyrics.client.ui;

import net.minecraft.client.render.VertexConsumer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class WorldTextFillTest {
    @Test void clipsInLocalSpaceAndInterpolatesUvsOnARotatedWall() {
        Matrix4f matrix = new Matrix4f().translate(10, 3, -6).rotateY(.8F).rotateZ(.05F).scale(.055F);
        Capture target = new Capture();
        var clipper = new WorldTextFill.Clipper(target, new Matrix4f(matrix).invert(), 4, false);
        quad(clipper, matrix); clipper.flush();
        assertThat(target.vertices).hasSize(8);
        int boundaryVertices = 0;
        for (var v : target.vertices) {
            var local = new Matrix4f(matrix).invert().transformPosition(new Vector3f(v.x, v.y, v.z));
            assertThat(v.u).isCloseTo(local.x / 10, within(.0001F));
            if (Math.abs(local.x - 4) < .001) boundaryVertices++;
            if (local.x < 3.99) assertThat(v.alpha).isEqualTo(255);
            if (local.x > 4.01) assertThat(v.alpha).isEqualTo(117);
        }
        assertThat(boundaryVertices).isEqualTo(4);
    }
    @Test void rightToLeftReversesTheFilledSideWithoutMirroringTheGlyph() {
        Capture target = new Capture();
        var clipper = new WorldTextFill.Clipper(target, new Matrix4f(), 7, true);
        quad(clipper, new Matrix4f()); clipper.flush();
        assertThat(target.vertices).hasSize(8);
        for (var v : target.vertices) {
            if (v.x < 7) assertThat(v.alpha).isEqualTo(117);
            if (v.x > 7) assertThat(v.alpha).isEqualTo(255);
            assertThat(v.u).isCloseTo(v.x / 10, within(.00001F));
        }
    }
    @Test void quadsOutsideBoundaryAreEmittedOnceAndFinalQuadIsFlushed() {
        Capture target = new Capture();
        var clipper = new WorldTextFill.Clipper(target, new Matrix4f(), 20, false);
        quad(clipper, new Matrix4f()); quad(clipper, new Matrix4f()); clipper.flush(); clipper.flush();
        assertThat(target.vertices).hasSize(8);
        assertThat(target.vertices).allMatch(v -> v.alpha == 255);
    }
    private static void quad(VertexConsumer target, Matrix4f transform) {
        for (float[] corner : new float[][]{{0,0},{0,8},{10,8},{10,0}}) {
            var v = transform.transformPosition(new Vector3f(corner[0], corner[1], 0));
            target.vertex(v.x, v.y, v.z).color(255,255,255,255).texture(corner[0]/10, corner[1]/8).light(240,240);
        }
    }
    private static class Vertex { float x,y,z,u; int alpha; }
    private static class Capture implements VertexConsumer {
        final List<Vertex> vertices = new ArrayList<>(); Vertex current;
        public VertexConsumer vertex(float x,float y,float z) {
            current = new Vertex(); current.x=x; current.y=y; current.z=z; vertices.add(current); return this;
        }
        public VertexConsumer color(int r,int g,int b,int a) { current.alpha=a; return this; }
        public VertexConsumer color(int color) { current.alpha=color>>>24; return this; }
        public VertexConsumer texture(float u,float v) { current.u=u; return this; }
        public VertexConsumer overlay(int u,int v) { return this; }
        public VertexConsumer light(int u,int v) { return this; }
        public VertexConsumer normal(float x,float y,float z) { return this; }
        public VertexConsumer lineWidth(float width) { return this; }
    }
}
