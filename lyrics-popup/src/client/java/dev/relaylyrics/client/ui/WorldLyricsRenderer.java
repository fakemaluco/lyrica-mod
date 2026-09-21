package dev.relaylyrics.client.ui;

import dev.relaylyrics.client.ClientPreferences;
import dev.relaylyrics.client.LyricsFont;
import dev.relaylyrics.client.LyricsFontWeight;
import dev.relaylyrics.client.music.LyricsRenderText;
import dev.relaylyrics.client.music.WorldLyricsPlan;
import dev.relaylyrics.protocol.lyrics.LyricLine;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.joml.Quaternionf;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Draws synchronized lyric phrases as local-only world geometry, never as entities or GUI. */
public final class WorldLyricsRenderer {
    private static final double FORWARD_DISTANCE = 4.35;
    private static final double ROW_SPACING = .82;
    private static final float BASE_PIXEL_SCALE = .040F;
    private static final Map<LineKey, Anchor> ANCHORS = new HashMap<>();
    private static Object activeWorld;

    private WorldLyricsRenderer() { }

    public static void render(MatrixStack matrices, VertexConsumerProvider consumers, Vec3d cameraPos) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPreferences preferences = dev.relaylyrics.client.RelayLyricsClient.preferences();
        LyricsOverlay overlay = dev.relaylyrics.client.RelayLyricsClient.overlay();
        if (!preferences.isWorldLyrics() || overlay == null || client.player == null
                || client.world == null || matrices == null || consumers == null) {
            ANCHORS.clear();
            return;
        }
        if (activeWorld != client.world) {
            ANCHORS.clear();
            activeWorld = client.world;
        }

        long now = overlay.currentPositionMillis();
        List<WorldLyricsPlan.WorldLine> lines = WorldLyricsPlan.visibleLines(overlay.document(), now);
        if (lines.isEmpty()) {
            ANCHORS.clear();
            return;
        }

        Set<LineKey> visible = new HashSet<>();
        for (int index = 0; index < lines.size(); index++) {
            WorldLyricsPlan.WorldLine planned = lines.get(index);
            LyricLine line = planned.line();
            LineKey key = new LineKey(line.startMillis(), line.endMillis(), line.text(), line.agent(),
                    planned.backing());
            visible.add(key);
            double row = (index - (lines.size() - 1) / 2.0) * ROW_SPACING;
            Anchor anchor = ANCHORS.computeIfAbsent(key, ignored -> createAnchor(client, cameraPos, row));
            renderLine(matrices, consumers, cameraPos, anchor, planned, now, preferences);
        }
        ANCHORS.keySet().removeIf(key -> !visible.contains(key));
    }

    private static void renderLine(MatrixStack matrices, VertexConsumerProvider consumers, Vec3d cameraPos,
                                   Anchor anchor,
                                   WorldLyricsPlan.WorldLine planned, long now,
                                   ClientPreferences preferences) {
        MinecraftClient client = MinecraftClient.getInstance();
        TextRenderer renderer = client.textRenderer;
        LyricLine line = planned.line();
        boolean romanized = preferences.isLyricsRomanization() && LyricsRenderText.hasRomanization(line);
        List<WorldLyricsPlan.Token> tokens = WorldLyricsPlan.tokens(line, romanized);
        if (tokens.isEmpty()) return;

        int spaceWidth = renderer.getWidth(styled(" ", preferences));
        int totalWidth = 0;
        for (WorldLyricsPlan.Token token : tokens) {
            if (token.startsWord() && totalWidth > 0) totalWidth += spaceWidth;
            totalWidth += renderer.getWidth(styled(token.text(), preferences));
        }
        float fit = Math.min(1F, 320F / Math.max(1, totalWidth));
        float pixelScale = BASE_PIXEL_SCALE * fit * (planned.backing() ? .78F : 1F);
        double visibility = WorldLyricsPlan.lineVisibility(line, now) * (planned.backing() ? .68 : 1);
        if (visibility <= .01) return;
        double cursor = -totalWidth * pixelScale / 2.0;

        for (WorldLyricsPlan.Token token : tokens) {
            if (token.startsWord() && cursor > -totalWidth * pixelScale / 2.0) {
                cursor += spaceWidth * pixelScale;
            }
            Text text = styled(token.text(), preferences);
            int width = renderer.getWidth(text);
            double emphasis = preferences.isLyricsAnimations() ? token.emphasis(now) : 0;
            double towardViewer = emphasis * .24;
            double elevation = emphasis * .20;
            float tokenScale = pixelScale * (float) (1 + emphasis * .09);
            double tokenCenter = cursor + width * pixelScale / 2.0;
            int alpha = (int) Math.round(255 * visibility * tokenOpacity(token, now));
            int color = Math.clamp(alpha, 8, 255) << 24 | 0xFFFFFF;

            double x = anchor.x + anchor.rightX * tokenCenter
                    - anchor.forwardX * towardViewer;
            double z = anchor.z + anchor.rightZ * tokenCenter
                    - anchor.forwardZ * towardViewer;
            matrices.push();
            double baselineY = anchor.y + renderer.fontHeight * tokenScale + elevation;
            matrices.translate(x - cameraPos.x, baselineY - cameraPos.y,
                    z - cameraPos.z);
            matrices.multiply(new Quaternionf()
                    .rotateY((float) Math.toRadians(180 - anchor.yaw)));
            matrices.scale(tokenScale, -tokenScale, tokenScale);
            renderer.draw(text, -width / 2.0F, 0, color, preferences.isLyricsTextShadow(),
                    matrices.peek().getPositionMatrix(), consumers,
                    TextRenderer.TextLayerType.NORMAL, 0, LightmapTextureManager.MAX_LIGHT_COORDINATE);
            matrices.pop();
            cursor += width * pixelScale;
        }
    }

    private static double tokenOpacity(WorldLyricsPlan.Token token, long now) {
        if (token.activeAt(now)) return 1;
        if (token.sungAt(now)) return .78;
        return .34;
    }

    private static Text styled(String value, ClientPreferences preferences) {
        LyricsFont font = preferences.getLyricsFont();
        LyricsFontWeight weight = preferences.getLyricsFontWeight();
        Text text = Text.literal(value);
        return weight.syntheticBold(font)
                ? PlatformTextStyles.withFontBold(text, font.id())
                : PlatformTextStyles.withFont(text, font.id());
    }

    private static Anchor createAnchor(MinecraftClient client, Vec3d cameraPos, double row) {
        float yaw = client.gameRenderer.getCamera().getYaw();
        double radians = Math.toRadians(yaw);
        double forwardX = -Math.sin(radians);
        double forwardZ = Math.cos(radians);
        double rightX = -forwardZ;
        double rightZ = forwardX;
        double x = cameraPos.x + forwardX * (FORWARD_DISTANCE + row);
        double z = cameraPos.z + forwardZ * (FORWARD_DISTANCE + row);
        double y = findGround(client, x, z, cameraPos.y);
        return new Anchor(x, y, z, yaw, forwardX, forwardZ, rightX, rightZ);
    }

    private static double findGround(MinecraftClient client, double x, double z, double cameraY) {
        Vec3d start = new Vec3d(x, cameraY + 3, z);
        Vec3d end = new Vec3d(x, cameraY - 10, z);
        HitResult hit = client.world.raycast(new RaycastContext(start, end,
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, client.player));
        return hit.getType() == HitResult.Type.BLOCK ? hit.getPos().y + .04 : client.player.getY() + .04;
    }

    private record LineKey(long start, long end, String text, String agent, boolean backing) { }
    private record Anchor(double x, double y, double z, float yaw,
                          double forwardX, double forwardZ, double rightX, double rightZ) { }
}
