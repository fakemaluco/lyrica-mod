package dev.relaylyrics.client.karaoke;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.relaylyrics.client.ui.KaraokeInvitationRenderer;
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
        int scale = KaraokeSurfaces.textureDensity(view.width, view.height);
        var client = Minecraft.getInstance();
        int width = view.width * scale, height = view.height * scale;
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
            else view.overlay.renderWorldPopup(context, view.width, view.height, x, y);
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
