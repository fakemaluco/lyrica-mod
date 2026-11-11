package dev.relaylyrics.client.karaoke;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.relaylyrics.client.ui.KaraokeInvitationRenderer;
import dev.relaylyrics.client.ui.GuiTransform;
import dev.relaylyrics.client.ui.LiquidGlass;
import dev.relaylyrics.client.ui.SettingsTypography;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.fog.FogRenderer;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.util.Identifier;

import java.util.List;

import static dev.relaylyrics.karaoke.KaraokeWire.*;

public final class KaraokePopupTarget implements AutoCloseable {
    private static int density;
    private static Framebuffer active;
    private static BufferAllocator allocator;
    private static FogRenderer fog;
    private static GuiRenderer renderer;
    private static GuiRenderState state;
    private final Identifier texture;
    private final SimpleFramebuffer target = new SimpleFramebuffer("Lyrica karaoke", WIDTH * 2, HEIGHT * 2, true);

    KaraokePopupTarget(int slot) {
        texture = Identifier.of("lyrica", "karaoke/" + slot);
        registerTexture();
    }

    private void registerTexture() {
        MinecraftClient.getInstance().getTextureManager().registerTexture(texture, new AbstractTexture() {
            {
                glTexture = target.getColorAttachment();
                glTextureView = target.getColorAttachmentView();
                sampler = RenderSystem.getSamplerCache().get(FilterMode.LINEAR);
            }
            @Override public void close() { /* The framebuffer owns the attachment. */ }
        });
    }

    Identifier texture() { return texture; }
    public static Framebuffer active() { return active; }

    public static int density() { return density; }

    void paint(KaraokePopup view, double x, double y) {
        int scale = KaraokeSurfaces.textureDensity(view.textureWidth(), view.height);
        var client = MinecraftClient.getInstance();
        int width = view.textureWidth() * scale, height = view.height * scale;
        if (target.textureWidth != width || target.textureHeight != height) {
            target.resize(width, height);
            registerTexture();
        }
        if (renderer == null) {
            state = new GuiRenderState();
            fog = new FogRenderer();
            allocator = new BufferAllocator(256 * 1024);
            renderer = new GuiRenderer(state, VertexConsumerProvider.immediate(allocator),
                    client.gameRenderer.getEntityRenderCommandQueue(), client.gameRenderer.getEntityRenderDispatcher(), List.of());
        }
        var context = new DrawContext(client, state, (int) x, (int) y);
        var projection = RenderSystem.getProjectionMatrixBuffer();
        var projectionType = RenderSystem.getProjectionType();
        try {
            if (view.invitation) KaraokeInvitationRenderer.draw(context, view.invitationLayout(), x, y,
                    view.spotifySync, view.preview, view.buttons, view.motionEnabled);
            else {
                int left = view.wingPixels(view.leftWing);
                GuiTransform.push(context);
                try {
                    GuiTransform.translate(context, left, 0);
                    view.overlay.renderWorldPopup(context, view.width, view.height, x, y);
                } finally { GuiTransform.pop(context); }
                drawQueue(context, view, left, x + left, y);
            }
            RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
                    target.getColorAttachment(), 0, target.getDepthAttachment(), 1);
            density = scale;
            active = target;
            renderer.render(fog.getFogBuffer(FogRenderer.FogType.NONE));
            renderer.incrementFrame();
        } finally {
            active = null;
            state.clear();
            RenderSystem.setProjectionMatrix(projection, projectionType);
        }
    }

    private static void drawQueue(DrawContext context, KaraokePopup view, int left, double px, double py) {
        if (view.leftWing == 0) return;
        var font = MinecraftClient.getInstance().textRenderer;
        var canvas = new KaraokeQueueView.Canvas() {
            @Override public void backdrop(int x, int y, int width, int height, int radius) {
                view.overlay.renderKaraokeQueueBackdrop(context, x, y, width, height, radius);
                GuiTransform.nextLayer(context);
            }
            @Override public void glass(int x, int y, int width, int height, int radius, double engagement) {
                LiquidGlass.draw(context, x, y, width, height, radius, 1, engagement);
                GuiTransform.nextLayer(context);
            }
            @Override public void fill(int x, int y, int endX, int endY, int color) {
                context.fill(x, y, endX, endY, color);
            }
            @Override public void text(String value, int x, int y, int maxWidth, int color) {
                String fitted = KaraokeInvitationLayout.fitLabel(value, maxWidth, font::getWidth);
                context.drawText(font, fitted, x, y, color, false);
            }
            @Override public void icon(String name, double x, double y, int size, int color) {
                SettingsTypography.icon(context, name, x, y, size, color);
            }
            @Override public int width(String value) { return font.getWidth(value); }
            @Override public String label(String key) { return net.minecraft.text.Text.translatable(key).getString(); }
        };
        int hoveredTab = KaraokeQueueView.tabAt(((px - left) / view.width - .5) * dev.relaylyrics.karaoke.KaraokeWire.WIDTH,
                (py / view.height - .5) * dev.relaylyrics.karaoke.KaraokeWire.HEIGHT, view.queue.side);
        long now = System.nanoTime();
        view.queue.draw(canvas, 0, left, view.height, -1, hoveredTab, px, py, now, view.motionEnabled);
        view.queue.draw(canvas, left + view.width, view.wingPixels(view.rightWing), view.height, 1,
                hoveredTab, px, py, now, view.motionEnabled);
    }

    @Override public void close() {
        MinecraftClient.getInstance().getTextureManager().destroyTexture(texture);
        target.delete();
    }
    static void clearRenderer() {
        if (renderer == null) return;
        renderer.close();
        fog.close();
        allocator.close();
        renderer = null;
        state = null;
        fog = null;
        allocator = null;
    }
}
