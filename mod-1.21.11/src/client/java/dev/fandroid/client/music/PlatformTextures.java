package dev.fandroid.client.music;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

/** Minecraft 1.21.11 render-pipeline and GPU texture adapter. */
public final class PlatformTextures {
    private PlatformTextures() { }

    static NativeImageBackedTexture fromImage(String name, NativeImage image) {
        return new NativeImageBackedTexture(() -> name, image);
    }

    public static NativeImageBackedTexture empty(String name, int width, int height) {
        return new NativeImageBackedTexture(name, width, height, false);
    }

    public static Identifier register(String name, NativeImageBackedTexture texture) {
        Identifier id = Identifier.of("fandroid", "dynamic/" + name.replaceAll("[^a-z0-9_/.-]", "_"));
        MinecraftClient.getInstance().getTextureManager().registerTexture(id, texture);
        return id;
    }

    static void flush(DrawContext context) {
        // DrawContext owns render-state submission on 1.21.11; drawing through the GUI
        // pipeline below is already ordered and there is no public manual flush method.
    }

    public static void draw(DrawContext context, Identifier id, int x, int y, int width, int height,
                            int cropX, int cropY, int cropWidth, int cropHeight,
                            int sourceWidth, int sourceHeight, double opacity, double brightness) {
        int light = Math.clamp((int) Math.round(brightness * 255), 0, 255);
        int alpha = Math.clamp((int) Math.round(opacity * 255), 0, 255);
        int color = alpha << 24 | light << 16 | light << 8 | light;
        context.drawTexture(RenderPipelines.GUI_TEXTURED, id, x, y, cropX, cropY,
                width, height, cropWidth, cropHeight, sourceWidth, sourceHeight, color);
    }

    public static void drawTinted(DrawContext context, Identifier id, int x, int y, int width, int height,
                                  int cropX, int cropY, int cropWidth, int cropHeight,
                                  int sourceWidth, int sourceHeight, int color) {
        context.drawTexture(RenderPipelines.GUI_TEXTURED, id, x, y, cropX, cropY,
                width, height, cropWidth, cropHeight, sourceWidth, sourceHeight, color);
    }

    static int colorArgb(NativeImage image, int x, int y) {
        return image.getColorArgb(x, y);
    }
}
