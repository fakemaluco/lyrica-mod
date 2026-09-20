package dev.fandroid.client.ui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.fandroid.FanDroidMod;
import dev.fandroid.client.mixin.glow.DrawContextStateAccessor;
import dev.fandroid.client.mixin.glow.SpecialGuiElementRendererAccessor;
import net.fabricmc.fabric.api.client.rendering.v1.SpecialGuiElementRegistry;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.PostEffectProcessor;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.ScreenRect;
import net.minecraft.client.gui.render.SpecialGuiElementRenderer;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.gui.render.state.special.SpecialGuiElementRenderState;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.util.memory.ObjectAllocator;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** FrameGraph-backed active lyric glow for Minecraft 1.21.11. */
final class LyricsBloomRenderer implements AutoCloseable {
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static final AtomicInteger RESOURCE_GENERATION = new AtomicInteger();
    private static volatile boolean available;

    private final List<GlowGlyph> glyphs = new ArrayList<>();
    private final Matrix3x2f pose = new Matrix3x2f();
    private int viewportLeft;
    private int viewportTop;
    private int viewportRight;
    private int viewportBottom;
    private boolean recording;

    static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            try {
                SpecialGuiElementRegistry.register(context -> new GlowElementRenderer(context.vertexConsumers()));
                ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(
                        new SimpleSynchronousResourceReloadListener() {
                            @Override public Identifier getFabricId() {
                                return Identifier.of("fandroid", "lyrics_glow_reload");
                            }

                            @Override public void reload(ResourceManager manager) {
                                RESOURCE_GENERATION.incrementAndGet();
                            }
                        });
                available = true;
            } catch (RuntimeException failure) {
                available = false;
                FanDroidMod.LOGGER.warn("Enhanced lyrics glow registration failed: {}", failure.toString());
            }
        }
    }

    boolean begin(DrawContext context, int clipLeft, int clipTop, int clipRight, int clipBottom,
                  boolean softwareClip) {
        if (!available) return false;
        glyphs.clear();
        pose.set(context.getMatrices());
        viewportLeft = clipLeft;
        viewportTop = clipTop;
        viewportRight = clipRight;
        viewportBottom = clipBottom;
        recording = clipRight > clipLeft && clipBottom > clipTop;
        return recording;
    }

    void drawGlyph(DrawContext context, TextRenderer renderer, Text text,
                   double x, double y, double scale, int color,
                   int clipLeft, int clipTop, int clipRight, int clipBottom) {
        if (!recording || text == null || (color >>> 24) == 0
                || clipRight <= clipLeft || clipBottom <= clipTop) return;
        glyphs.add(new GlowGlyph(renderer, text, (float) x, (float) y, (float) scale, color,
                clipLeft, clipTop, clipRight, clipBottom));
    }

    boolean finish(DrawContext context, int clipLeft, int clipTop, int clipRight, int clipBottom,
                   float radius, boolean clip, boolean additive) {
        recording = false;
        if (glyphs.isEmpty()) return true;
        try {
            ScreenRect viewport = transformedRect(viewportLeft, viewportTop,
                    viewportRight, viewportBottom, pose);
            GlowElementState state = new GlowElementState(List.copyOf(glyphs),
                    viewportLeft, viewportTop, viewportRight, viewportBottom,
                    radius, new Matrix3x2f(pose), viewport, viewport);
            ((DrawContextStateAccessor) context).fandroid$getState().addSpecialElement(state);
            glyphs.clear();
            return true;
        } catch (RuntimeException failure) {
            glyphs.clear();
            return false;
        }
    }

    private static ScreenRect transformedRect(int left, int top, int right, int bottom, Matrix3x2fc pose) {
        return new ScreenRect(left, top, Math.max(0, right - left), Math.max(0, bottom - top))
                .transformEachVertex(pose);
    }

    @Override
    public void close() {
        recording = false;
        glyphs.clear();
    }

    private record GlowGlyph(TextRenderer renderer, Text text, float x, float y, float scale, int color,
                             int clipLeft, int clipTop, int clipRight, int clipBottom) { }

    private record GlowElementState(List<GlowGlyph> glyphs,
                                    int x1, int y1, int x2, int y2, float radius,
                                    Matrix3x2f pose, ScreenRect scissorArea,
                                    ScreenRect bounds) implements SpecialGuiElementRenderState {
        @Override public float scale() { return 1; }
    }

    private static final class GlowElementRenderer extends SpecialGuiElementRenderer<GlowElementState> {
        private static final int FULL_BRIGHT = 0xF000F0;
        private final Map<Identifier, PostEffectProcessor> processors = new HashMap<>();
        private int framebufferScale = 1;
        private int resourceGeneration = RESOURCE_GENERATION.get();

        private GlowElementRenderer(VertexConsumerProvider.Immediate vertexConsumers) {
            super(vertexConsumers);
        }

        @Override
        public void render(GlowElementState state, GuiRenderState guiState, int scale) {
            framebufferScale = Math.max(1, scale);
            super.render(state, guiState, scale);
            if (blurRenderedTexture(state)) super.renderElement(state, guiState);
        }

        @Override
        protected void renderElement(GlowElementState state, GuiRenderState guiState) {
            // Defer composition until the shader succeeds. Otherwise the translucent source
            // itself would look like a second unblurred copy of the active word.
        }

        @Override
        public Class<GlowElementState> getElementClass() {
            return GlowElementState.class;
        }

        @Override
        protected void render(GlowElementState state, MatrixStack matrices) {
            float centerX = (state.x2() - state.x1()) / 2.0F;
            for (GlowGlyph glyph : state.glyphs()) {
                matrices.push();
                matrices.translate(glyph.x() - state.x1() - centerX,
                        glyph.y() - state.y2(), 0);
                matrices.scale(glyph.scale(), glyph.scale(), 1);
                enableSourceScissor(state, glyph);
                try {
                    glyph.renderer().draw(glyph.text(), 0, 0, glyph.color(), false,
                            matrices.peek().getPositionMatrix(), vertexConsumers,
                            TextRenderer.TextLayerType.NORMAL, 0, FULL_BRIGHT);
                    vertexConsumers.draw();
                } finally {
                    RenderSystem.disableScissorForRenderTypeDraws();
                    matrices.pop();
                }
            }
        }

        private void enableSourceScissor(GlowElementState state, GlowGlyph glyph) {
            int left = Math.max(0, glyph.clipLeft() - state.x1()) * framebufferScale;
            int bottom = Math.max(0, state.y2() - glyph.clipBottom()) * framebufferScale;
            int width = Math.max(1, glyph.clipRight() - glyph.clipLeft()) * framebufferScale;
            int height = Math.max(1, glyph.clipBottom() - glyph.clipTop()) * framebufferScale;
            RenderSystem.enableScissorForRenderTypeDraws(left, bottom, width, height);
        }

        private boolean blurRenderedTexture(GlowElementState state) {
            try {
                if (resourceGeneration != RESOURCE_GENERATION.get()) {
                    processors.values().forEach(PostEffectProcessor::close);
                    processors.clear();
                    resourceGeneration = RESOURCE_GENERATION.get();
                }
                SpecialGuiElementRendererAccessor access = (SpecialGuiElementRendererAccessor) (Object) this;
                GpuTexture texture = access.fandroid$getTexture();
                GpuTextureView view = access.fandroid$getTextureView();
                if (texture == null || view == null) return false;
                Identifier effectId = effectId(state.radius());
                PostEffectProcessor processor = processors.computeIfAbsent(effectId, id ->
                        MinecraftClient.getInstance().getShaderLoader()
                                .loadPostEffect(id, Set.of(PostEffectProcessor.MAIN)));
                if (processor == null) return false;
                processor.render(new BorrowedFramebuffer(texture, view), ObjectAllocator.TRIVIAL);
                return true;
            } catch (RuntimeException failure) {
                // The base fill remains visible; never replace a failed shader with fake copies.
                return false;
            }
        }

        private static Identifier effectId(float radius) {
            String profile;
            if (radius < 1.55F) profile = "core_soft";
            else if (radius < 1.85F) profile = "core_normal";
            else if (radius < 2.4F) profile = "core_strong";
            else if (radius < 3.15F) profile = "outer_soft";
            else if (radius < 3.85F) profile = "outer_normal";
            else profile = "outer_strong";
            return Identifier.of("fandroid", "lyrics_glow_" + profile);
        }

        @Override
        protected String getName() {
            return "Relay lyrics glow";
        }

        @Override
        public void close() {
            processors.values().forEach(PostEffectProcessor::close);
            processors.clear();
            super.close();
        }
    }

    private static final class BorrowedFramebuffer extends Framebuffer {
        private BorrowedFramebuffer(GpuTexture texture, GpuTextureView view) {
            super("Relay lyrics glow", false);
            colorAttachment = texture;
            colorAttachmentView = view;
            textureWidth = texture.getWidth(0);
            textureHeight = texture.getHeight(0);
        }

        @Override
        public void delete() {
            colorAttachment = null;
            colorAttachmentView = null;
        }
    }
}
