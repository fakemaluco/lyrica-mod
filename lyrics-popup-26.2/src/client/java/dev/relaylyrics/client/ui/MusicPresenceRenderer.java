package dev.relaylyrics.client.ui;

import dev.relaylyrics.client.*;
import dev.relaylyrics.client.music.CoverArtTexture;
import dev.relaylyrics.client.presence.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import java.util.UUID;

/** One lazily loaded cover, anchored to the selected player, independent of popup geometry. */
public final class MusicPresenceRenderer {
    private static CoverArtTexture artwork;
    private static UUID shown;
    private static String cover = "";
    private static long opened, lastSpaceCheck;
    private static int side = 1;
    private static boolean backFacing;
    public static boolean probeBackFacing() { return backFacing; }
    private static org.joml.Matrix4f cardInverse;
    private static double probeAnchorX = Double.NaN;
    public static int probeSide() { return side; }
    public static double probeAnchorX() { return probeAnchorX; }
    public static boolean probeReady() { return shown != null && System.nanoTime() - opened >= 1_200_000_000L; }
    private MusicPresenceRenderer() { }
    // HUD callback intentionally draws nothing; the card is submitted with world geometry.
    public static void render(GuiGraphicsExtractor context, float delta) { }
    public static void renderWorld(com.mojang.blaze3d.vertex.PoseStack matrices,
                                   MultiBufferSource buffers,
                                   net.minecraft.world.phys.Vec3 camera) {
        var client = Minecraft.getInstance();
        if (CompatibilityManager.worldShadowPass() || client.level == null || client.gui.screen() != null || client.gui.hud.isHidden() || client.options.keyPlayerList.isDown() || matrices == null || buffers == null) return;
        UUID id = MusicPresence.pinned(); var song = MusicPresence.track(id);
        if (song == null) { if (shown != null) clear(); return; }
        float delta = PresencePlatform.renderDelta();
        double[] anchor = PresencePlatform.anchor(id, delta); if (anchor == null) return;
        var rotation = client.gameRenderer.mainCamera().rotation();
        if (Boolean.getBoolean("lyrica.musicPresenceSelfTest")) {
            var point = PresenceProjection.project(anchor[0], anchor[1], anchor[2], rotation.x, rotation.y, rotation.z, rotation.w,
                    PresencePlatform.fov(delta), client.getWindow().getGuiScaledWidth(), client.getWindow().getGuiScaledHeight());
            if (point != null) probeAnchorX = point.x();
        }
        if (!id.equals(shown)) { shown = id; opened = System.nanoTime(); side = 1; lastSpaceCheck = 0; cardInverse = null; }
        double[] pose = PresencePlatform.cardPose(id, delta, side); if (pose == null) return;
        if (System.nanoTime() - lastSpaceCheck > 200_000_000L) {
            lastSpaceCheck = System.nanoTime();
            if (PresencePlatform.cardBlocked(pose)) {
                var other = PresencePlatform.cardPose(id, delta, -side);
                if (other != null && !PresencePlatform.cardBlocked(other)) { side = -side; pose = other; }
            }
        }
        boolean animated = RelayLyricsClient.preferences().isLyricsAnimations();
        double elapsed = animated ? (System.nanoTime() - opened) / 1_000_000_000.0 : 1;
        double visibility = animated ? smooth(MusicPresence.cardOpacity()) : 1;
        double opacity = visibility * smooth(elapsed / .24);
        if (opacity < .01) return;
        if (artwork == null) artwork = CoverArtTexture.forPresence();
        if (!cover.equals(song.coverUrl())) { cover = song.coverUrl(); artwork.update(cover); }
        matrices.pushPose();
        try {
            double travel = smooth(elapsed / .36) * visibility;
            // Damped spring: mass 1, stiffness 260, damping 18; settles without a hard stop.
            double frequency = Math.sqrt(260 - 81);
            double spring = elapsed >= .8 ? 1 : 1 - Math.exp(-9 * elapsed) * (Math.cos(frequency * elapsed) + 9 / frequency * Math.sin(frequency * elapsed));
            float size = .0065f * (float)((.32 + .68 * spring) * (.32 + .68 * visibility));
            double distance = .90 * side * travel;
            double cx = pose[0] - Math.cos(pose[3]) * distance, cz = pose[2] + Math.sin(pose[3]) * distance;
            matrices.translate(cx, pose[1], cz);
            backFacing = -cx * Math.sin(pose[3]) - cz * Math.cos(pose[3]) < 0;
            matrices.mulPose(new org.joml.Matrix4f().rotation(new org.joml.Quaternionf().rotationY((float)(pose[3] + (backFacing ? Math.PI : 0)))));
            float bubble = (float)(1 + .05 * Math.sin(Math.PI * Math.clamp(elapsed / .45, 0, 1)) * Math.exp(-4 * elapsed));
            matrices.scale(size * bubble, -size / bubble, size);
            var matrix = matrices.last().pose();
            cardInverse = new org.joml.Matrix4f(matrix).invert();
            var idTexture = artwork.presenceTexture();
            if (idTexture != null) {
                var vertices = buffers.getBuffer(opacity < .999 ? net.minecraft.client.renderer.rendertype.RenderTypes.entityTranslucent(idTexture) : net.minecraft.client.renderer.rendertype.RenderTypes.entityCutout(idTexture));
                double aspect = artwork.presenceAspect();
                float u0 = aspect > 1 ? (float)((1 - 1 / aspect) / 2) : 0, u1 = 1 - u0;
                float v0 = aspect < 1 ? (float)((1 - aspect) / 2) : 0, v1 = 1 - v0;
                // Fractional endpoints follow a true circular arc instead of integer steps.
                for (int step = 0; step < 192; step++) {
                    float y0 = step * .5f, y1 = (step + 1) * .5f;
                    float i0 = cornerInset(y0), i1 = cornerInset(y1);
                    float va = v0 + (v1 - v0) * y0 / 96f, vb = v0 + (v1 - v0) * y1 / 96f;
                    vertex(vertices, matrix, -48 + i1, y1, u0 + (u1-u0)*i1/96, vb, (int)Math.round(255 * opacity));
                    vertex(vertices, matrix, 48 - i1, y1, u1 - (u1-u0)*i1/96, vb, (int)Math.round(255 * opacity));
                    vertex(vertices, matrix, 48 - i0, y0, u1 - (u1-u0)*i0/96, va, (int)Math.round(255 * opacity));
                    vertex(vertices, matrix, -48 + i0, y0, u0 + (u1-u0)*i0/96, va, (int)Math.round(255 * opacity));
                }
            } else {
                var note = styled("♫"); matrices.pushPose(); matrices.translate(0, 42, 0);
                WorldTextMesh.draw(note, matrices.last().pose(), buffers, -client.font.width(note) / 2f,
                        .25f, 0xFFFFFF, 0xAAAAAA, (float)opacity, null, false);
                matrices.popPose();
            }
            var title = fit(song.title(), 120); var artist = fit(song.artist(), 120);
            matrices.pushPose();
            matrices.translate(0, 102, -.5);
            WorldTextMesh.draw(title, matrices.last().pose(), buffers, -client.font.width(title) / 2f,
                    .25f, 0xFFFFFF, 0xAAAAAA, (float)opacity, null, false);
            matrices.translate(0, 12, 0);
            WorldTextMesh.draw(artist, matrices.last().pose(), buffers, -client.font.width(artist) / 2f,
                    .25f, 0xCACACA, 0x777777, (float)opacity, null, false);
            matrices.popPose();
        } finally { matrices.popPose(); }
    }
    /** Ray intersection with the visible card in camera space, including its labels. */
    public static double hoveredCardDistance() {
        UUID id = MusicPresence.pinned();
        if (id == null || MusicPresence.track(id) == null) return Double.POSITIVE_INFINITY;
        if (cardInverse == null || !id.equals(shown)) return Double.POSITIVE_INFINITY;
        var client = Minecraft.getInstance();
        var rotation = client.gameRenderer.mainCamera().rotation();
        var origin = new org.joml.Vector3f().mulPosition(cardInverse);
        var direction = rotation.transform(new org.joml.Vector3f(0, 0, -1)).mulDirection(cardInverse);
        if (Math.abs(direction.z) < .00001) return Double.POSITIVE_INFINITY;
        float distance = -origin.z / direction.z;
        float x = origin.x + direction.x * distance, y = origin.y + direction.y * distance;
        return distance > 0 && distance <= 24 && Math.abs(x) <= 62 && y >= -3 && y <= 125
                ? distance : Double.POSITIVE_INFINITY;
    }

    private static double smooth(double value) { double t = Math.clamp(value, 0, 1); return t * t * (3 - 2 * t); }
    private static float cornerInset(float y) {
        float radius = 15, dy = y < radius ? radius-y : y > 96-radius ? y-(96-radius) : 0;
        return dy == 0 ? 0 : radius - (float)Math.sqrt(Math.max(0, radius*radius-dy*dy));
    }
    private static void vertex(com.mojang.blaze3d.vertex.VertexConsumer v, org.joml.Matrix4f m, float x, float y, float u, float vv, int a) {
        v.addVertex(m,x,y,0).setColor(255,255,255,a).setUv(u,vv).setOverlay(0x000A0000).setLight(0xF000F0).setNormal(0,0,1);
    }
    private static Component styled(String value) { return PlatformTextStyles.withFont(value, RelayLyricsClient.preferences().getLyricsFont().id()); }
    private static Component fit(String value, int width) {
        var renderer = Minecraft.getInstance().font;
        if (renderer.width(styled(value)) <= width) return styled(value);
        int end = value.length();
        while (end > 0 && renderer.width(styled(value.substring(0, end) + "…")) > width) end = value.offsetByCodePoints(end, -1);
        return styled(value.substring(0, end) + "…");
    }
    private static int alpha(int color, double opacity) { return (int)Math.round((color >>> 24) * opacity) << 24 | color & 0xFFFFFF; }
    public static void clear() { if (artwork != null) artwork.close(); artwork = null; cover = ""; shown = null; cardInverse = null; }
}
