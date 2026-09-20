package dev.fandroid.client.ui;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.fandroid.FanDroidMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.PostEffectProcessor;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.opengl.GL11;

/** Off-screen bloom pass used only by the enhanced lyrics renderer. */
final class LyricsBloomRenderer implements AutoCloseable {
    private static final Identifier BLUR = Identifier.of("fandroid", "shaders/post/lyrics_bloom.json");
    private static final int DOWNSAMPLE = 1;

    private SimpleFramebuffer glowTarget;
    private PostEffectProcessor blur;
    private int width;
    private int height;
    private boolean writing;

    static void register() { }

    boolean begin(DrawContext context, int clipLeft, int clipTop, int clipRight, int clipBottom,
                  boolean softwareClip) {
        MinecraftClient client = MinecraftClient.getInstance();
        try {
            context.draw();
            ensureResources(client);
            glowTarget.setClearColor(0, 0, 0, 0);
            glowTarget.clear(MinecraftClient.IS_SYSTEM_MAC);
            glowTarget.beginWrite(true);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            writing = true;
            return true;
        } catch (Exception failure) {
            recoverMainTarget(client);
            close();
            FanDroidMod.LOGGER.warn("Enhanced lyrics bloom unavailable; using compatible renderer: {}",
                    failure.toString());
            return false;
        }
    }

    boolean finish(DrawContext context, int clipLeft, int clipTop, int clipRight, int clipBottom,
                   float radius, boolean clip, boolean additive) {
        MinecraftClient client = MinecraftClient.getInstance();
        try {
            context.draw();
            glowTarget.endWrite();
            writing = false;
            // Radius is expressed in screen pixels by the shared profile. This target is
            // half-resolution, so convert it before filtering to keep both versions matched.
            blur.setUniforms("Radius", Math.clamp(radius / DOWNSAMPLE, .5F, 6.0F));
            blur.render(0);

            Framebuffer main = client.getFramebuffer();
            main.beginWrite(false);
            if (clip) context.enableScissor(clipLeft, clipTop, clipRight, clipBottom);
            try {
                glowTarget.setTexFilter(GL11.GL_LINEAR);
                RenderSystem.enableBlend();
                if (additive) {
                    // The source was already blended into a transparent framebuffer and is
                    // therefore premultiplied. SRC_ALPHA here would dim it a second time.
                    RenderSystem.blendFunc(GlStateManager.SrcFactor.ONE, GlStateManager.DstFactor.ONE);
                } else {
                    RenderSystem.defaultBlendFunc();
                }
                glowTarget.draw(main.textureWidth, main.textureHeight, false);
            } finally {
                RenderSystem.setShaderColor(1, 1, 1, 1);
                RenderSystem.defaultBlendFunc();
                if (clip) context.disableScissor();
            }
            main.beginWrite(false);
            return true;
        } catch (Exception failure) {
            recoverMainTarget(client);
            close();
            FanDroidMod.LOGGER.warn("Enhanced lyrics bloom failed; using compatible renderer: {}",
                    failure.toString());
            return false;
        }
    }

    void drawGlyph(DrawContext context, TextRenderer renderer, Text text,
                   double x, double y, double scale, int color,
                   int clipLeft, int clipTop, int clipRight, int clipBottom) {
        if (!writing || (color >>> 24) == 0 || clipRight <= clipLeft || clipBottom <= clipTop) return;
        context.enableScissor(clipLeft, clipTop, clipRight, clipBottom);
        try {
            GuiTransform.push(context);
            GuiTransform.translate(context, (float) x, (float) y);
            GuiTransform.scale(context, (float) scale, (float) scale);
            context.drawText(renderer, text, 0, 0, color, false);
            GuiTransform.pop(context);
        } finally {
            context.disableScissor();
        }
    }

    private void ensureResources(MinecraftClient client) throws Exception {
        Framebuffer main = client.getFramebuffer();
        int nextWidth = Math.max(1, main.textureWidth / DOWNSAMPLE);
        int nextHeight = Math.max(1, main.textureHeight / DOWNSAMPLE);
        if (glowTarget != null && width == nextWidth && height == nextHeight) return;
        close();
        width = nextWidth;
        height = nextHeight;
        glowTarget = new SimpleFramebuffer(width, height, false, MinecraftClient.IS_SYSTEM_MAC);
        glowTarget.setClearColor(0, 0, 0, 0);
        blur = new PostEffectProcessor(client.getTextureManager(), client.getResourceManager(), glowTarget, BLUR);
        blur.setupDimensions(width, height);
    }

    private void recoverMainTarget(MinecraftClient client) {
        try {
            if (writing && glowTarget != null) glowTarget.endWrite();
        } catch (RuntimeException ignored) { }
        writing = false;
        try {
            client.getFramebuffer().beginWrite(false);
            RenderSystem.defaultBlendFunc();
        } catch (RuntimeException ignored) { }
    }

    @Override
    public void close() {
        writing = false;
        if (blur != null) blur.close();
        if (glowTarget != null) glowTarget.delete();
        blur = null;
        glowTarget = null;
        width = 0;
        height = 0;
    }
}
