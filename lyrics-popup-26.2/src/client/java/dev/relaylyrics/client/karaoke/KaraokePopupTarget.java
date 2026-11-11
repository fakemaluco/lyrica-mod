package dev.relaylyrics.client.karaoke;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.relaylyrics.client.ui.KaraokeInvitationRenderer;
import dev.relaylyrics.client.ui.GuiTransform;
import dev.relaylyrics.client.ui.LiquidGlass;
import dev.relaylyrics.client.ui.SettingsTypography;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.state.WindowRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;

import java.util.List;

import static dev.relaylyrics.karaoke.KaraokeWire.*;

public final class KaraokePopupTarget implements AutoCloseable {
    private static final Vector4f CLEAR = new Vector4f();
    private static final WindowRenderState WINDOW = new WindowRenderState();
    private static RenderTarget active;
    private static GuiRenderer renderer;
    private static GuiRenderState state;
    private final Identifier texture;
    private final TextureTarget target = new TextureTarget("Lyrica karaoke", WIDTH * 2, HEIGHT * 2, true, GpuFormat.RGBA8_UNORM);

    KaraokePopupTarget(int slot) {
        texture = Identifier.fromNamespaceAndPath("lyrica", "karaoke/" + slot);
        registerTexture();
    }

    private void registerTexture() {
        Minecraft.getInstance().getTextureManager().register(texture, new AbstractTexture() {
            {
                texture = target.getColorTexture();
                textureView = target.getColorTextureView();
                sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
            }
            @Override public void close() { /* The framebuffer owns the attachment. */ }
        });
    }

    Identifier texture() { return texture; }
    public static RenderTarget active() { return active; }
    public static WindowRenderState window() { return WINDOW; }

    void paint(KaraokePopup view, double x, double y) {
        int scale = KaraokeSurfaces.textureDensity(view.textureWidth(), view.height);
        var client = Minecraft.getInstance();
        int width = view.textureWidth() * scale, height = view.height * scale;
        if (target.width != width || target.height != height) {
            target.resize(width, height);
            registerTexture();
        }
        if (renderer == null) {
            state = new GuiRenderState();
            renderer = new GuiRenderer(state, client.gameRenderer.featureRenderDispatcher(), List.of());
        }
        var context = new GuiGraphicsExtractor(client, state, (int) x, (int) y);
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
                    target.getColorTexture(), CLEAR, target.getDepthTexture(), 0);
            WINDOW.width = width;
            WINDOW.height = height;
            WINDOW.guiScale = scale;
            active = target;
            renderer.render();
            renderer.endFrame();
        } finally {
            active = null;
            state.reset();
            RenderSystem.setProjectionMatrix(projection, projectionType);
        }
    }

    private static void drawQueue(GuiGraphicsExtractor context, KaraokePopup view, int left, double px, double py) {
        if (view.leftWing == 0) return;
        var font = Minecraft.getInstance().font;
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
                String fitted = KaraokeInvitationLayout.fitLabel(value, maxWidth, font::width);
                context.text(font, fitted, x, y, color, false);
            }
            @Override public void icon(String name, double x, double y, int size, int color) {
                SettingsTypography.icon(context, name, x, y, size, color);
            }
            @Override public int width(String value) { return font.width(value); }
            @Override public String label(String key) { return net.minecraft.network.chat.Component.translatable(key).getString(); }
        };
        int hoveredTab = KaraokeQueueView.tabAt(((px - left) / view.width - .5) * dev.relaylyrics.karaoke.KaraokeWire.WIDTH,
                (py / view.height - .5) * dev.relaylyrics.karaoke.KaraokeWire.HEIGHT, view.queue.side);
        long now = System.nanoTime();
        view.queue.draw(canvas, 0, left, view.height, -1, hoveredTab, px, py, now, view.motionEnabled);
        view.queue.draw(canvas, left + view.width, view.wingPixels(view.rightWing), view.height, 1,
                hoveredTab, px, py, now, view.motionEnabled);
    }

    @Override public void close() {
        Minecraft.getInstance().getTextureManager().release(texture);
        target.destroyBuffers();
    }
    static void clearRenderer() {
        if (renderer != null) renderer.close();
        renderer = null;
        state = null;
    }
}
