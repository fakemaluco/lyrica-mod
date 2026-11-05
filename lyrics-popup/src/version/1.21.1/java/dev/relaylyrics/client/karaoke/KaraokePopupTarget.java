package dev.relaylyrics.client.karaoke;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import static dev.relaylyrics.karaoke.KaraokeWire.*;

public final class KaraokePopupTarget implements AutoCloseable {
    public static final int DENSITY = 2;
    private static boolean painting;
    private static BufferAllocator allocator;
    private final Identifier texture;
    private SimpleFramebuffer target;

    KaraokePopupTarget(int slot) { texture = Identifier.of("lyrica", "karaoke/" + slot); }

    Identifier texture() { return texture; }
    public static boolean painting() { return painting; }

    void paint(KaraokePopup view, double x, double y) {
        if (allocator == null) allocator = new BufferAllocator(256 * 1024);
        var projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        var sorting = RenderSystem.getVertexSorting();
        var modelView = RenderSystem.getModelViewStack();
        int drawTarget = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int readTarget = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        modelView.pushMatrix().translation(0, 0, -11000);
        try {
            if (target == null) {
                target = new SimpleFramebuffer(WIDTH * DENSITY, HEIGHT * DENSITY, true, false);
                target.setClearColor(0, 0, 0, 0);
                target.setTexFilter(GL11.GL_LINEAR);
                MinecraftClient.getInstance().getTextureManager().registerTexture(texture, new AbstractTexture() {
                    @Override public int getGlId() { return target.getColorAttachment(); }
                    @Override public void load(ResourceManager resources) { }
                });
            }
            target.clear(false);
            target.beginWrite(true);
            RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0, WIDTH, HEIGHT, 0, 1000, 21000), VertexSorter.BY_Z);
            RenderSystem.applyModelViewMatrix();
            painting = true;
            var context = new DrawContext(MinecraftClient.getInstance(), VertexConsumerProvider.immediate(allocator));
            view.overlay.renderWorldPopup(context, WIDTH, HEIGHT, x, y);
            context.draw();
        } finally {
            painting = false;
            RenderSystem.disableScissor();
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(projection, sorting);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawTarget);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readTarget);
            RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        }
    }

    @Override public void close() {
        if (target == null) return;
        int drawTarget = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int readTarget = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        MinecraftClient.getInstance().getTextureManager().destroyTexture(texture);
        target.delete();
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawTarget);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readTarget);
    }
    static void clearRenderer() {
        if (allocator != null) allocator.close();
        allocator = null;
    }
}
