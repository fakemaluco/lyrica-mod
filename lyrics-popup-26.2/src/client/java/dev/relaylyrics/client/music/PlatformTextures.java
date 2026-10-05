package dev.relaylyrics.client.music;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/** Minecraft 1.21.11 render-pipeline and GPU texture adapter. */
public final class PlatformTextures {
    private PlatformTextures() { }

    static DynamicTexture fromImage(String name, NativeImage image) {
        return new SmoothDynamicTexture(() -> name, image);
    }

    public static DynamicTexture empty(String name, int width, int height) {
        return new SmoothDynamicTexture(name, width, height);
    }

    /** Physical pixels represented by one logical GUI pixel. */
    public static double guiScale() {
        return Math.max(1.0, Minecraft.getInstance().getWindow().getGuiScale());
    }

    public static boolean supportsRoundedCoverComposite() { return true; }

    public static Identifier register(String name, DynamicTexture texture) {
        Identifier id = Identifier.fromNamespaceAndPath("relaylyrics", "dynamic/" + name.replaceAll("[^a-z0-9_/.-]", "_"));
        Minecraft.getInstance().getTextureManager().register(id, texture);
        return id;
    }

    static void flush(GuiGraphicsExtractor context) {
        // DrawContext owns render-state submission on 1.21.11; drawing through the GUI
        // pipeline below is already ordered and there is no public manual flush method.
    }

    public static void draw(GuiGraphicsExtractor context, Identifier id, int x, int y, int width, int height,
                            int cropX, int cropY, int cropWidth, int cropHeight,
                            int sourceWidth, int sourceHeight, double opacity, double brightness) {
        int light = Math.clamp((int) Math.round(brightness * 255), 0, 255);
        int alpha = Math.clamp((int) Math.round(opacity * 255), 0, 255);
        int color = alpha << 24 | light << 16 | light << 8 | light;
        context.blit(RenderPipelines.GUI_TEXTURED, id, x, y, cropX, cropY,
                width, height, cropWidth, cropHeight, sourceWidth, sourceHeight, color);
    }

    public static void drawTinted(GuiGraphicsExtractor context, Identifier id, int x, int y, int width, int height,
                                  int cropX, int cropY, int cropWidth, int cropHeight,
                                  int sourceWidth, int sourceHeight, int color) {
        context.blit(RenderPipelines.GUI_TEXTURED, id, x, y, cropX, cropY,
                width, height, cropWidth, cropHeight, sourceWidth, sourceHeight, color);
    }

    static int colorArgb(NativeImage image, int x, int y) {
        return image.getPixel(x, y);
    }

    static void setColorArgb(NativeImage image, int x, int y, int argb) {
        image.setPixel(x, y, argb);
    }

    /**
     * 1.21.11 defaults every NativeImageBackedTexture to a repeating nearest sampler.
     * That is suitable for pixel atlases but tears the small rounded album composite
     * when the inventory's Modern layout scales it. Covers and generated GUI surfaces
     * need the same linear, edge-clamped sampling used by the 1.21.1 adapter.
     */
    private static final class SmoothDynamicTexture extends DynamicTexture {
        private SmoothDynamicTexture(Supplier<String> name, NativeImage image) {
            super(name, image);
            sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        }

        private SmoothDynamicTexture(String name, int width, int height) {
            super(name, width, height, false);
            sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        }
    }
}
