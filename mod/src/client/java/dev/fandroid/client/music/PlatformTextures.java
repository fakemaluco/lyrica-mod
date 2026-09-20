package dev.fandroid.client.music;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

/** Minecraft 1.21.1 dynamic-texture and GUI drawing adapter. */
public final class PlatformTextures {
    private PlatformTextures() { }

    static NativeImageBackedTexture fromImage(String name, NativeImage image) {
        return new NativeImageBackedTexture(image);
    }

    public static NativeImageBackedTexture empty(String name, int width, int height) {
        NativeImageBackedTexture texture = new NativeImageBackedTexture(width, height, false);
        texture.setFilter(true, false);
        return texture;
    }

    /** Physical pixels represented by one logical GUI pixel. */
    public static double guiScale() {
        return Math.max(1.0, MinecraftClient.getInstance().getWindow().getScaleFactor());
    }

    public static Identifier register(String name, NativeImageBackedTexture texture) {
        return MinecraftClient.getInstance().getTextureManager().registerDynamicTexture(name, texture);
    }

    static void flush(DrawContext context) {
        context.draw();
    }

    public static void draw(DrawContext context, Identifier id, int x, int y, int width, int height,
                            int cropX, int cropY, int cropWidth, int cropHeight,
                            int sourceWidth, int sourceHeight, double opacity, double brightness) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        float light = (float) Math.clamp(brightness, 0, 1);
        try {
            RenderSystem.setShaderColor(light, light, light, (float) Math.clamp(opacity, 0, 1));
            context.drawTexture(id, x, y, width, height, cropX, cropY,
                    cropWidth, cropHeight, sourceWidth, sourceHeight);
        } finally {
            // Iris/Sodium and ImmediatelyFast all assume GUI code returns neutral tint/blend state.
            RenderSystem.setShaderColor(1, 1, 1, 1);
            RenderSystem.defaultBlendFunc();
        }
    }

    public static void drawTinted(DrawContext context, Identifier id, int x, int y, int width, int height,
                                  int cropX, int cropY, int cropWidth, int cropHeight,
                                  int sourceWidth, int sourceHeight, int color) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        float alpha = (color >>> 24) / 255F;
        float red = (color >>> 16 & 255) / 255F;
        float green = (color >>> 8 & 255) / 255F;
        float blue = (color & 255) / 255F;
        try {
            RenderSystem.setShaderColor(red, green, blue, alpha);
            context.drawTexture(id, x, y, width, height, cropX, cropY,
                    cropWidth, cropHeight, sourceWidth, sourceHeight);
        } finally {
            RenderSystem.setShaderColor(1, 1, 1, 1);
            RenderSystem.defaultBlendFunc();
        }
    }

    static int colorArgb(NativeImage image, int x, int y) {
        int a = Byte.toUnsignedInt(image.getOpacity(x, y));
        int r = Byte.toUnsignedInt(image.getRed(x, y));
        int g = Byte.toUnsignedInt(image.getGreen(x, y));
        int b = Byte.toUnsignedInt(image.getBlue(x, y));
        return a << 24 | r << 16 | g << 8 | b;
    }
}
