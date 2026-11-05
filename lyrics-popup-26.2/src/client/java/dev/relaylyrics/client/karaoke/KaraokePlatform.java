package dev.relaylyrics.client.karaoke;

import com.google.gson.JsonObject;
import dev.relaylyrics.client.CompatibilityManager;
import dev.relaylyrics.client.WorldLyricsRenderHook;
import dev.relaylyrics.client.ui.FontCompat;
import net.minecraft.client.gui.Font;
import dev.relaylyrics.karaoke.KaraokeNetwork;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import dev.relaylyrics.client.ui.WorldBuffers;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.UUID;

import static dev.relaylyrics.karaoke.KaraokeWire.*;

public final class KaraokePlatform {
    private static final Identifier PIXEL = Identifier.fromNamespaceAndPath("lyrica", "textures/gui/lyrics/karaoke_pixel.png");
    private KaraokePlatform() { }

    static void register() {
        ClientPlayNetworking.registerGlobalReceiver(KaraokeNetwork.Packet.ID, (packet, context) -> {
            try {
                JsonObject json = decode(packet.data());
                if (json != null) KaraokeClient.receive(json);
            } catch (RuntimeException ignored) { }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> KaraokeClient.clear());
    }
    static boolean supported() {
        return Minecraft.getInstance().getConnection() != null && ClientPlayNetworking.canSend(KaraokeNetwork.Packet.ID);
    }
    static void send(JsonObject message) {
        if (!supported()) return;
        byte[] payload = encode(message);
        if (payload.length <= MAX_BYTES) ClientPlayNetworking.send(new KaraokeNetwork.Packet(payload));
    }
    static void execute(Runnable action) { Minecraft.getInstance().execute(action); }
    static boolean isLocalPlayer(UUID id) {
        var player = Minecraft.getInstance().player;
        return player != null && id.equals(player.getUUID());
    }
    static boolean buttonHeld(boolean right) {
        var options = Minecraft.getInstance().options;
        return (right ? options.keyUse : options.keyAttack).isDown();
    }

    public static void paintPopups() { KaraokeSurfaces.paint(); }
    static void clearPopups() { KaraokeSurfaces.clear(); }
    static KaraokePopup popup(KaraokeClient.Room room) {
        var surface = KaraokeSurfaces.get(room);
        return surface == null ? null : surface.popup();
    }
    static void openSettings() {
        var client = Minecraft.getInstance();
        client.gui.setScreen(new dev.relaylyrics.client.ui.LyricsConfigScreen(client.gui.screen()));
    }
    static double visibleDistance(KaraokeClient.Room room) {
        var client = Minecraft.getInstance();
        if (client.level == null || client.gui.hud.isHidden()) return Double.POSITIVE_INFINITY;
        return visibleDistance(room, client.level.dimension().identifier().toString(), WorldLyricsRenderHook.cameraPosition(client));
    }
    private static double visibleDistance(KaraokeClient.Room room, String dimension, Vec3 camera) {
        var panel = room.panel;
        if (room.dismissed || !dimension.equals(panel.dimension())) return Double.POSITIVE_INFINITY;
        return panel.distanceSquared(camera.x, camera.y, camera.z);
    }

    static KaraokeClient.Ray ray() {
        var client = Minecraft.getInstance();
        if (client.player == null || client.level == null || client.gui.screen() != null || client.gui.hud.isHidden()) return null;
        var eye = WorldLyricsRenderHook.cameraPosition(client);
        var direction = new Vector3f(0, 0, -1).rotate(client.gameRenderer.mainCamera().rotation());
        var end = eye.add(direction.x * RANGE, direction.y * RANGE, direction.z * RANGE);
        var block = client.level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, client.player));
        double limit = block.getType() == HitResult.Type.MISS ? RANGE : eye.distanceTo(block.getLocation());
        return new KaraokeClient.Ray(client.level.dimension().identifier().toString(), eye.x, eye.y, eye.z,
                direction.x, direction.y, direction.z, limit);
    }

    public static void render(PoseStack matrices, WorldBuffers buffers, Vec3 camera) {
        var client = Minecraft.getInstance();
        if (matrices == null || buffers == null || client.level == null || client.gui.hud.isHidden() || CompatibilityManager.worldShadowPass()) return;
        String dimension = client.level.dimension().identifier().toString();
        var aim = KaraokeClient.rooms().isEmpty() ? null : ray();
        KaraokeClient.captureAim(aim);
        var draggedRoom = KaraokeClient.draggedRoom();
        var draggedPanel = draggedRoom == null ? null : KaraokeClient.draggedPanel(aim);
        for (var room : KaraokeClient.rooms()) {
            if (visibleDistance(room, dimension, camera) > RANGE * RANGE) continue;
            var panel = room == draggedRoom && draggedPanel != null ? draggedPanel : room.panel;
            double yaw = Math.toRadians(panel.yaw());
            boolean back = Math.sin(yaw) * (camera.x - panel.x()) + Math.cos(yaw) * (camera.z - panel.z()) < 0;
            if (back) yaw += Math.PI;
            matrices.pushPose();
            try {
                matrices.translate(panel.x() - camera.x, panel.y() - camera.y, panel.z() - camera.z);
                matrices.mulPose(new Quaternionf().rotationY((float) yaw));
                float scale = (float) panel.pixelSize();
                matrices.scale(scale, -(float) panel.pixelHeight(), scale);
                drawPanel(room, matrices, buffers, back);
            } finally { matrices.popPose(); }
        }
    }

    private static void drawPanel(KaraokeClient.Room room, PoseStack matrices, WorldBuffers buffers, boolean back) {
        var surface = KaraokeSurfaces.get(room);
        if (room.accepted) {
            if (surface == null) return;
            quad(matrices, buffers, surface.target().texture(), -WIDTH / 2f, -HEIGHT / 2f,
                    WIDTH / 2f, HEIGHT / 2f, 0, back ? 0xFF575757 : 0xFFFFFFFF, true);
            if (room.owned()) {
                drawHandle(room, matrices, buffers, back);
                if (!back) drawResizeGrip(room, matrices, buffers);
            }
            if (!back) {
                long now = System.nanoTime();
                room.cursors.forEach((id, cursor) -> {
                    int color = 0xFF000000 | ((id.hashCode() & 0x007F7F7F) | 0x00808080);
                    var point = cursor.at(now);
                    cross(matrices, buffers, (float) point.panelX(), (float) point.panelY(), color);
                });
            }
        } else {
            rect(matrices, buffers, -WIDTH / 2f, -HEIGHT / 2f, WIDTH / 2f, HEIGHT / 2f, 0, back ? 0xEA100D15 : 0xE5161022);
            if (back) return;
            rect(matrices, buffers, -WIDTH / 2f, -HEIGHT / 2f, WIDTH / 2f, -67, .05f, 0xFC38234E);
            label(matrices, buffers, "Karaoke · " + room.name, -87, 0, 0xEAD7FF, WIDTH - 22);
            label(matrices, buffers, localized("karaoke.lyrica.invite"), -35, 0, 0xFFFFFF, WIDTH - 22);
            var music = dev.relaylyrics.client.RelayLyricsClient.music();
            if (music != null && music.spotifyAccountConnected())
                label(matrices, buffers, localized("karaoke.lyrica.spotify_consent"), -17, 0, 0xB9A6CC, WIDTH - 22);
            button(matrices, buffers, -125, 24, -18, 63, "✕", 0xFF633247);
            button(matrices, buffers, 18, 24, 125, 63, "✓", 0xFF285A50);
        }
        var hit = KaraokeClient.hit();
        if (hit != null && hit.room() == room)
            cross(matrices, buffers, (float) (back ? -hit.x() : hit.x()), (float) hit.y(), 0xFFFFFFFF);
    }

    private static void drawHandle(KaraokeClient.Room room, PoseStack matrices, WorldBuffers buffers, boolean back) {
        var hit = KaraokeClient.hit();
        boolean hovered = hit != null && hit.room() == room && KaraokeClient.handleAt(hit.x(), hit.y());
        int color = hovered || KaraokeClient.draggedRoom() == room ? 0xFFFFFFFF : back ? 0xFF888888 : 0xFFD2CFD9;
        int y = (KaraokeClient.HANDLE_TOP + KaraokeClient.HANDLE_BOTTOM) / 2;
        rect(matrices, buffers, -26, y - 2, 26, y + 3, 0, 0xB5151219);
        rect(matrices, buffers, -24, y - 1, 24, y + 2, .15f, color);
    }

    private static void drawResizeGrip(KaraokeClient.Room room, PoseStack matrices, WorldBuffers buffers) {
        var hit = KaraokeClient.hit();
        boolean hovered = hit != null && hit.room() == room && KaraokeClient.resizeAt(hit.x(), hit.y());
        int color = hovered ? 0xFFFFFFFF : 0xDCD2CFD9;
        int x = WIDTH / 2 - 6, y = HEIGHT / 2 - 6;
        for (int step = 0; step < 3; step++) {
            rect(matrices, buffers, x - 10 + step * 3, y - step * 3, x + 1, y - step * 3 + 2, .45f, 0xB7181521);
            rect(matrices, buffers, x - 9 + step * 3, y - step * 3, x, y - step * 3 + 1.25f, .5f, color);
        }
    }

    private static void button(PoseStack m, WorldBuffers b, int x1, int y1, int x2, int y2, String value, int color) {
        rect(m, b, x1, y1, x2, y2, .1f, color);
        label(m, b, value, (y1 + y2) / 2 - 4, (x1 + x2) / 2, 0xFFFFFF, x2 - x1);
    }
    private static String localized(String key) { return Component.translatable(key).getString(); }
    private static void label(PoseStack m, WorldBuffers b, String value, float y, float x, int color, int width) {
        var font = Minecraft.getInstance().font;
        String fitted = font.plainSubstrByWidth(value, width);
        word(m, b, fitted, x - font.width(fitted) / 2f, y, color);
    }
    private static void word(PoseStack m, WorldBuffers b, String value, float x, float y, int color) {
        if (value.isEmpty()) return;
        m.pushPose();
        try {
            m.translate(0, y, 1.25);
            FontCompat.drawInBatch(Minecraft.getInstance().font, Component.literal(value), x, 0, 0xFF000000 | color,
                    false, m.last().pose(), b, Font.DisplayMode.NORMAL, 0, 0xF000F0);
        } finally { m.popPose(); }
    }
    private static void cross(PoseStack m, WorldBuffers b, float x, float y, int color) {
        rect(m, b, x - 5, y - 1.25f, x + 5, y + 1.25f, 1, 0xD0100D15);
        rect(m, b, x - 1.25f, y - 5, x + 1.25f, y + 5, 1, 0xD0100D15);
        rect(m, b, x - 4.5f, y - .65f, x + 4.5f, y + .65f, 1.1f, color);
        rect(m, b, x - .65f, y - 4.5f, x + .65f, y + 4.5f, 1.1f, color);
    }
    private static void rect(PoseStack m, WorldBuffers b, float x1, float y1, float x2, float y2, float z, int color) {
        quad(m, b, PIXEL, x1, y1, x2, y2, z, color, false);
    }
    private static void quad(PoseStack m, WorldBuffers b, Identifier texture,
                             float x1, float y1, float x2, float y2, float z, int color, boolean flipped) {
        float top = flipped ? 1 : 0, bottom = 1 - top;
        var matrix = m.last().pose();
        var vertices = b.getBuffer(net.minecraft.client.renderer.rendertype.RenderTypes.entityTranslucent(texture));
        vertices.addVertex(matrix, x1,y2,z).setColor(color).setUv(0,bottom).setOverlay(0xA0000).setLight(0xF000F0).setNormal(0,0,1);
        vertices.addVertex(matrix, x2,y2,z).setColor(color).setUv(1,bottom).setOverlay(0xA0000).setLight(0xF000F0).setNormal(0,0,1);
        vertices.addVertex(matrix, x2,y1,z).setColor(color).setUv(1,top).setOverlay(0xA0000).setLight(0xF000F0).setNormal(0,0,1);
        vertices.addVertex(matrix, x1,y1,z).setColor(color).setUv(0,top).setOverlay(0xA0000).setLight(0xF000F0).setNormal(0,0,1);
    }
}
