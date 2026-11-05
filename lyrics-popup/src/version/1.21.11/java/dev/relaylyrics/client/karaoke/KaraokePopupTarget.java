package dev.relaylyrics.client.karaoke;

import com.mojang.blaze3d.systems.RenderSystem;
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
    public static final int DENSITY = 2;
    private static Framebuffer active;
    private static BufferAllocator allocator;
    private static FogRenderer fog;
    private static GuiRenderer renderer;
    private static GuiRenderState state;
    private final Identifier texture;
    private final SimpleFramebuffer target = new SimpleFramebuffer("Lyrica karaoke", WIDTH * DENSITY, HEIGHT * DENSITY, true);

    KaraokePopupTarget(int slot) {
        texture = Identifier.of("lyrica", "karaoke/" + slot);
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

    void paint(KaraokePopup view, double x, double y) {
        var client = MinecraftClient.getInstance();
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
            view.overlay.renderWorldPopup(context, WIDTH, HEIGHT, x, y);
            RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
                    target.getColorAttachment(), 0, target.getDepthAttachment(), 1);
            active = target;
            renderer.render(fog.getFogBuffer(FogRenderer.FogType.NONE));
            renderer.incrementFrame();
        } finally {
            active = null;
            state.clear();
            RenderSystem.setProjectionMatrix(projection, projectionType);
        }
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
