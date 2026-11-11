package dev.relaylyrics.client.karaoke;

import com.google.gson.JsonObject;
import dev.relaylyrics.client.CompatibilityManager;
import dev.relaylyrics.client.PresenceWorldLayer;
import dev.relaylyrics.client.WorldLyricsRenderHook;
import dev.relaylyrics.karaoke.KaraokeNetwork;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.minecraft.client.render.Frustum;
import net.minecraft.util.math.Box;

import java.util.UUID;

import static dev.relaylyrics.karaoke.KaraokeWire.*;

public final class KaraokePlatform {
    private static final Identifier PIXEL = Identifier.of("lyrica", "textures/gui/lyrics/karaoke_pixel.png");
    private static Frustum frustum;

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
        return MinecraftClient.getInstance().getNetworkHandler() != null && ClientPlayNetworking.canSend(KaraokeNetwork.Packet.ID);
    }
    static void send(JsonObject message) {
        if (!supported()) return;
        byte[] payload = encode(message);
        if (payload.length <= MAX_BYTES) ClientPlayNetworking.send(new KaraokeNetwork.Packet(payload));
    }
    static void execute(Runnable action) { MinecraftClient.getInstance().execute(action); }
    static boolean isLocalPlayer(UUID id) {
        var player = MinecraftClient.getInstance().player;
        return player != null && id.equals(player.getUuid());
    }
    static boolean popupZoomModifierHeld() {
        var client = MinecraftClient.getInstance();
        if (client.currentScreen != null) return false;
        long window = client.getWindow().getHandle();
        return org.lwjgl.glfw.GLFW.glfwGetKey(window, org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL) == org.lwjgl.glfw.GLFW.GLFW_PRESS
                || org.lwjgl.glfw.GLFW.glfwGetKey(window, org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_CONTROL) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
    }

    static boolean buttonHeld(boolean right) {
        var options = MinecraftClient.getInstance().options;
        return (right ? options.useKey : options.attackKey).isPressed();
    }

    public static void paintPopups(boolean hiddenHudPass) {
        if (MinecraftClient.getInstance().options.hudHidden != hiddenHudPass) return;
        try {
            KaraokeSurfaces.paint();
        } finally {
            frustum = null;
        }
    }
    static void listenNotice(String key, Object... args) {
        var player = MinecraftClient.getInstance().player;
        if (player != null) player.sendMessage(net.minecraft.text.Text.translatable("music.lyrica.listen." + key, args), false);
    }
    static void clearPopups() {
        frustum = null;
        KaraokeSurfaces.clear();
    }

    static boolean inView(KaraokeClient.Room room, Panel panel) {
        if (frustum == null) return true;
        double yaw = Math.toRadians(panel.yaw());
        // Include the drag handle and cursors beyond the textured quad.
        int side = room.queue.side != 0 ? room.queue.side : room.queue.displaySide;
        int wing = KaraokeQueueView.wing(KaraokeClient.hasQueue(room), side, side == -1 ? -1 : 1);
        double halfWidth = (WIDTH / 2.0 + Math.max(8, wing)) * panel.pixelSize();
        double halfHeight = KaraokeClient.HANDLE_BOTTOM * panel.pixelHeight();
        double dx = Math.abs(Math.cos(yaw)) * halfWidth + .02;
        double dz = Math.abs(Math.sin(yaw)) * halfWidth + .02;
        return frustum.isVisible(new Box(panel.x() - dx, panel.y() - halfHeight, panel.z() - dz,
                panel.x() + dx, panel.y() + halfHeight, panel.z() + dz));
    }
    static KaraokePopup popup(KaraokeClient.Room room) {
        var surface = KaraokeSurfaces.get(room);
        return surface == null ? null : surface.popup();
    }
    static void queueTextInput(boolean focused) { }
    static void releaseKeys() { net.minecraft.client.option.KeyBinding.unpressAll(); }
    static String clipboard() { return MinecraftClient.getInstance().keyboard.getClipboard(); }
    static void clipboard(String value) { MinecraftClient.getInstance().keyboard.setClipboard(value); }
    static void openSettings() {
        var client = MinecraftClient.getInstance();
        client.setScreen(new dev.relaylyrics.client.ui.LyricsConfigScreen(client.currentScreen));
    }
    static double visibleDistance(KaraokeClient.Room room) {
        var client = MinecraftClient.getInstance();
        if (client.world == null) return Double.POSITIVE_INFINITY;
        return visibleDistance(room, client.world.getRegistryKey().getValue().toString(), WorldLyricsRenderHook.cameraPosition(client));
    }
    private static double visibleDistance(KaraokeClient.Room room, String dimension, Vec3d camera) {
        var panel = room.panel;
        if (room.dismissed || !dimension.equals(panel.dimension())) return Double.POSITIVE_INFINITY;
        return panel.distanceSquared(camera.x, camera.y, camera.z);
    }

    static KaraokeClient.Ray ray() {
        var client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null || client.currentScreen != null) return null;
        var eye = WorldLyricsRenderHook.cameraPosition(client);
        var direction = new Vector3f(0, 0, -1).rotate(client.gameRenderer.getCamera().getRotation());
        var end = eye.add(direction.x * RANGE, direction.y * RANGE, direction.z * RANGE);
        var block = client.world.raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, client.player));
        double limit = block.getType() == HitResult.Type.MISS ? RANGE : eye.distanceTo(block.getPos());
        return new KaraokeClient.Ray(client.world.getRegistryKey().getValue().toString(), eye.x, eye.y, eye.z,
                direction.x, direction.y, direction.z, limit);
    }

    public static void render(MatrixStack matrices, VertexConsumerProvider buffers, Vec3d camera, Frustum viewFrustum) {
        var client = MinecraftClient.getInstance();
        if (matrices == null || buffers == null || client.world == null || CompatibilityManager.worldShadowPass()) return;
        frustum = viewFrustum;
        String dimension = client.world.getRegistryKey().getValue().toString();
        var aim = KaraokeClient.rooms().isEmpty() ? null : ray();
        KaraokeClient.captureAim(aim);
        var draggedRoom = KaraokeClient.draggedRoom();
        var draggedPanel = draggedRoom == null ? null : KaraokeClient.draggedPanel(aim);
        long now = System.nanoTime();
        boolean animate = KaraokeSurfaces.motionEnabled();
        boolean spatialMotion = KaraokeSurfaces.spatialMotionEnabled();
        for (var room : KaraokeSurfaces.renderRooms()) {
            var closing = KaraokeSurfaces.closingSurface(room);
            var surface = closing != null ? closing : KaraokeSurfaces.get(room);
            if (surface == null || visibleDistance(room, dimension, camera) > RANGE * RANGE && closing == null) continue;
            if (closing != null && !dimension.equals(room.panel.dimension())) continue;
            double opacity = room.motion.opacity(now, animate);
            if (opacity <= 0) continue;
            var panel = room == draggedRoom && draggedPanel != null ? draggedPanel : room.panel;
            if (!inView(room, panel)) continue;
            double yaw = Math.toRadians(panel.yaw());
            boolean back = Math.sin(yaw) * (camera.x - panel.x()) + Math.cos(yaw) * (camera.z - panel.z()) < 0;
            if (back) yaw += Math.PI;
            matrices.push();
            try {
                matrices.translate(panel.x() - camera.x, panel.y() - camera.y, panel.z() - camera.z);
                matrices.multiply(new Quaternionf().rotationY((float) yaw));
                float factor = spatialMotion ? (float) room.motion.scale(opacity) : 1;
                float scale = (float) panel.pixelSize();
                matrices.scale(scale * factor, -(float) panel.pixelHeight() * factor, scale);
                drawPanel(room, matrices, buffers, back, surface, opacity, closing != null);
            } finally { matrices.pop(); }
        }
    }

    private static void drawPanel(KaraokeClient.Room room, MatrixStack matrices, VertexConsumerProvider buffers, boolean back,
                                  KaraokeSurfaces.Surface surface, double opacity, boolean closing) {
        var popup = surface.popup();
        float textureWidth = popup.textureWidth();
        float leftPixels = popup.wingPixels(popup.leftWing);
        float leftU = leftPixels / textureWidth, rightU = (leftPixels + popup.width) / textureWidth;
        var texture = surface.target().texture();
        int alpha = (int) Math.round(opacity * 255) << 24;
        quad(matrices, buffers, texture, -WIDTH / 2f, -HEIGHT / 2f, WIDTH / 2f, HEIGHT / 2f, 0,
                alpha | (back ? 0x575757 : 0xFFFFFF), true, leftU, rightU);
        if (!back) {
            drawWing(matrices, buffers, texture, -1, popup.leftWing, leftU, opacity, popup.drawerProgress);
            drawWing(matrices, buffers, texture, 1, popup.rightWing, 1 - rightU, opacity, popup.drawerProgress);
        }
        if (closing) return;
        if (room.accepted) {
            if (room.owned()) {
                drawHandle(room, matrices, buffers, back);
                if (!back) drawResizeGrip(room, matrices, buffers);
            }
            if (!back && !MinecraftClient.getInstance().options.hudHidden) {
                long now = System.nanoTime();
                room.cursors.forEach((id, cursor) -> {
                    int color = 0xFF000000 | ((id.hashCode() & 0x007F7F7F) | 0x00808080);
                    var point = cursor.at(now);
                    cross(matrices, buffers, (float) point.panelX(), (float) point.panelY(), color);
                });
            }
        }
        var hit = KaraokeClient.hit();
        if (!MinecraftClient.getInstance().options.hudHidden && hit != null && hit.room() == room
                && (!KaraokeClient.hasQueue(room) || KaraokeQueueView.tabAt(hit.x(), hit.y(), room.queue.side) == 0))
            cross(matrices, buffers, (float) (back ? -hit.x() : hit.x()), (float) hit.y(), 0xFFFFFFFF);
    }

    private static void drawWing(MatrixStack matrices, VertexConsumerProvider buffers, Identifier texture, int side,
                                 int width, float textureFraction, double opacity, double drawerProgress) {
        float reveal = width == KaraokeQueueView.DRAWER_WIDTH ? (float) drawerProgress : 1;
        if (width == 0 || reveal <= .001f) return;
        float edge = side * WIDTH / 2f;
        float outer = edge + side * width * reveal;
        int color = (int) Math.round(opacity * reveal * 255) << 24 | 0xFFFFFF;
        float u1 = side < 0 ? 0 : 1 - textureFraction * reveal;
        float u2 = side < 0 ? textureFraction * reveal : 1;
        quad(matrices, buffers, texture, Math.min(edge, outer), -HEIGHT / 2f, Math.max(edge, outer), HEIGHT / 2f,
                0, color, true, u1, u2);
    }

    private static void drawHandle(KaraokeClient.Room room, MatrixStack matrices, VertexConsumerProvider buffers, boolean back) {
        var hit = KaraokeClient.hit();
        boolean hovered = hit != null && hit.room() == room && KaraokeClient.handleAt(hit.x(), hit.y());
        int color = hovered || KaraokeClient.draggedRoom() == room ? 0xFFFFFFFF : back ? 0xFF888888 : 0xFFD2CFD9;
        int y = (KaraokeClient.HANDLE_TOP + KaraokeClient.HANDLE_BOTTOM) / 2;
        rect(matrices, buffers, -26, y - 2, 26, y + 3, 0, 0xB5151219);
        rect(matrices, buffers, -24, y - 1, 24, y + 2, .15f, color);
    }

    private static void drawResizeGrip(KaraokeClient.Room room, MatrixStack matrices, VertexConsumerProvider buffers) {
        var hit = KaraokeClient.hit();
        boolean hovered = hit != null && hit.room() == room && KaraokeClient.resizeAt(hit.x(), hit.y());
        int color = hovered ? 0xFFFFFFFF : 0xDCD2CFD9;
        int x = WIDTH / 2 - 6, y = HEIGHT / 2 - 6;
        for (int step = 0; step < 3; step++) {
            rect(matrices, buffers, x - 10 + step * 3, y - step * 3, x + 1, y - step * 3 + 2, .45f, 0xB7181521);
            rect(matrices, buffers, x - 9 + step * 3, y - step * 3, x, y - step * 3 + 1.25f, .5f, color);
        }
    }

    private static void cross(MatrixStack m, VertexConsumerProvider b, float x, float y, int color) {
        rect(m, b, x - 5, y - 1.25f, x + 5, y + 1.25f, 1, 0xD0100D15);
        rect(m, b, x - 1.25f, y - 5, x + 1.25f, y + 5, 1, 0xD0100D15);
        rect(m, b, x - 4.5f, y - .65f, x + 4.5f, y + .65f, 1.1f, color);
        rect(m, b, x - .65f, y - 4.5f, x + .65f, y + 4.5f, 1.1f, color);
    }
    private static void rect(MatrixStack m, VertexConsumerProvider b, float x1, float y1, float x2, float y2, float z, int color) {
        quad(m, b, PIXEL, x1, y1, x2, y2, z, color, false);
    }
    private static void quad(MatrixStack m, VertexConsumerProvider b, Identifier texture,
                             float x1, float y1, float x2, float y2, float z, int color, boolean flipped) {
        quad(m, b, texture, x1, y1, x2, y2, z, color, flipped, 0, 1);
    }
    private static void quad(MatrixStack m, VertexConsumerProvider b, Identifier texture,
                             float x1, float y1, float x2, float y2, float z, int color, boolean flipped,
                             float u1, float u2) {
        float top = flipped ? 1 : 0, bottom = 1 - top;
        var matrix = m.peek().getPositionMatrix();
        var vertices = b.getBuffer(PresenceWorldLayer.cover(texture, true));
        vertices.vertex(matrix, x1,y2,z).color(color).texture(u1,bottom).overlay(0xA0000).light(0xF000F0).normal(0,0,1);
        vertices.vertex(matrix, x2,y2,z).color(color).texture(u2,bottom).overlay(0xA0000).light(0xF000F0).normal(0,0,1);
        vertices.vertex(matrix, x2,y1,z).color(color).texture(u2,top).overlay(0xA0000).light(0xF000F0).normal(0,0,1);
        vertices.vertex(matrix, x1,y1,z).color(color).texture(u1,top).overlay(0xA0000).light(0xF000F0).normal(0,0,1);
    }
}
