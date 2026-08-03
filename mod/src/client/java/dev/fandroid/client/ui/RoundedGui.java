package dev.fandroid.client.ui;

import dev.fandroid.client.music.PlatformTextures;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Version-neutral rounded geometry and true scissor bands for textured surfaces. */
public final class RoundedGui {
    private static final int MAX_RADIUS = 16;
    private static final int BORDER_SUPERSAMPLE = 4;
    private static final int MAX_BORDER_TEXTURES = 12;
    private static final AtomicInteger BORDER_GENERATION = new AtomicInteger();
    private static final Map<BorderKey, BorderTexture> BORDER_TEXTURES =
            new LinkedHashMap<>(8, .75F, true);
    private static final Map<Integer, MaskTexture> FILL_TEXTURES =
            new LinkedHashMap<>(8, .75F, true);

    private RoundedGui() { }

    public static void clipped(DrawContext context, int x, int y, int width, int height,
                               int radius, Runnable drawer) {
        if (width <= 0 || height <= 0 || drawer == null) return;
        int r = radius(width, height, radius);
        if (r == 0) {
            band(context, x, y, x + width, y + height, drawer);
            return;
        }
        if (height - r * 2 > 0) band(context, x, y + r, x + width, y + height - r, drawer);
        for (int row = 0; row < r; row++) {
            int inset = cornerInset(r, row);
            band(context, x + inset, y + row, x + width - inset, y + row + 1, drawer);
            band(context, x + inset, y + height - row - 1,
                    x + width - inset, y + height - row, drawer);
        }
    }

    public static void fill(DrawContext context, int x, int y, int width, int height,
                            int radius, int color) {
        if (width <= 0 || height <= 0) return;
        int r = radius(width, height, radius);
        if (r == 0) {
            context.fill(x, y, x + width, y + height, color);
            return;
        }
        if (height - r * 2 > 0) context.fill(x, y + r, x + width, y + height - r, color);
        for (int row = 0; row < r; row++) {
            int inset = cornerInset(r, row);
            context.fill(x + inset, y + row, x + width - inset, y + row + 1, color);
            context.fill(x + inset, y + height - row - 1,
                    x + width - inset, y + height - row, color);
        }
    }

    /** Runtime-supersampled equivalent of a CSS rounded background. */
    public static void smoothFill(DrawContext context, int x, int y, int width, int height,
                                  int radius, int color) {
        if (width <= 0 || height <= 0 || color >>> 24 == 0) return;
        int r = radius(width, height, radius);
        if (r == 0) {
            context.fill(x, y, x + width, y + height, color);
            return;
        }
        drawMaskNineSlice(context, fillTexture(r), x, y, width, height, color);
    }

    public static void gradient(DrawContext context, int x, int y, int width, int height,
                                int radius, int topColor, int bottomColor) {
        clipped(context, x, y, width, height, radius,
                () -> context.fillGradient(x, y, x + width, y + height, topColor, bottomColor));
    }

    public static void outline(DrawContext context, int x, int y, int width, int height,
                               int radius, int color) {
        if (width <= 1 || height <= 1) return;
        int r = radius(width, height, radius);
        if (r == 0) {
            context.fill(x, y, x + width, y + 1, color);
            context.fill(x, y + height - 1, x + width, y + height, color);
            context.fill(x, y + 1, x + 1, y + height - 1, color);
            context.fill(x + width - 1, y + 1, x + width, y + height - 1, color);
            return;
        }
        for (int row = 0; row < height; row++) {
            int inset = row < r ? cornerInset(r, row)
                    : row >= height - r ? cornerInset(r, height - row - 1) : 0;
            int left = x + inset;
            int right = x + width - inset;
            if (row == 0 || row == height - 1) {
                context.fill(left, y + row, right, y + row + 1, color);
            } else if (right - left >= 2) {
                context.fill(left, y + row, left + 1, y + row + 1, color);
                context.fill(right - 1, y + row, right, y + row + 1, color);
            }
        }
    }

    /**
     * Runtime-generated, supersampled rounded border. Unlike the integer geometry above,
     * its alpha edge is sampled at display resolution and stays smooth while the GUI scales.
     */
    public static void smoothOutline(DrawContext context, int x, int y, int width, int height,
                                     int radius, int outerColor, int innerColor) {
        if (width <= 2 || height <= 2) return;
        int r = radius(width, height, radius);
        if (r == 0) {
            outline(context, x, y, width, height, 0, outerColor);
            return;
        }
        BorderTexture border = borderTexture(new BorderKey(r, outerColor, innerColor));
        drawNineSlice(context, border, x, y, width, height);
    }

    public static void smoothOutline(DrawContext context, int x, int y, int width, int height,
                                     int radius, int color) {
        smoothOutline(context, x, y, width, height, radius, color, color);
    }

    private static BorderTexture borderTexture(BorderKey key) {
        synchronized (BORDER_TEXTURES) {
            BorderTexture existing = BORDER_TEXTURES.get(key);
            if (existing != null) return existing;
            int cap = key.radius() + 1;
            int logicalSize = cap * 2 + 2;
            int textureSize = logicalSize * BORDER_SUPERSAMPLE;
            NativeImageBackedTexture texture = PlatformTextures.empty(
                    "Relay smooth border", textureSize, textureSize);
            Identifier id = PlatformTextures.register("relay_smooth_border_"
                    + BORDER_GENERATION.incrementAndGet(), texture);
            NativeImage image = texture.getImage();
            if (image == null) throw new IllegalStateException("smooth border texture has no image");
            renderBorder(image, textureSize, key.radius() * BORDER_SUPERSAMPLE,
                    key.outerColor(), key.innerColor());
            texture.upload();
            BorderTexture created = new BorderTexture(id, texture, textureSize, cap);
            BORDER_TEXTURES.put(key, created);
            while (BORDER_TEXTURES.size() > MAX_BORDER_TEXTURES) {
                BorderKey eldest = BORDER_TEXTURES.keySet().iterator().next();
                BorderTexture discarded = BORDER_TEXTURES.remove(eldest);
                if (discarded != null) {
                    MinecraftClient.getInstance().getTextureManager().destroyTexture(discarded.id());
                }
            }
            return created;
        }
    }

    private static MaskTexture fillTexture(int radius) {
        synchronized (FILL_TEXTURES) {
            MaskTexture existing = FILL_TEXTURES.get(radius);
            if (existing != null) return existing;
            int cap = Math.max(1, radius);
            int logicalSize = cap * 2 + 2;
            int textureSize = logicalSize * BORDER_SUPERSAMPLE;
            NativeImageBackedTexture texture = PlatformTextures.empty(
                    "Relay smooth fill", textureSize, textureSize);
            Identifier id = PlatformTextures.register("relay_smooth_fill_" + radius, texture);
            NativeImage image = texture.getImage();
            if (image == null) throw new IllegalStateException("smooth fill texture has no image");
            double half = textureSize / 2.0;
            double scaledRadius = radius * BORDER_SUPERSAMPLE;
            for (int py = 0; py < textureSize; py++) {
                for (int px = 0; px < textureSize; px++) {
                    double distance = roundedRectDistance(px + .5, py + .5,
                            half, half, half, half, scaledRadius);
                    int alpha = Math.clamp((int) Math.round(
                            Math.clamp(.5 - distance, 0, 1) * 255), 0, 255);
                    image.setColor(px, py, toAbgr(alpha << 24 | 0xFFFFFF));
                }
            }
            texture.upload();
            MaskTexture created = new MaskTexture(id, texture, textureSize, cap);
            FILL_TEXTURES.put(radius, created);
            return created;
        }
    }

    private static void renderBorder(NativeImage image, int size, double radius,
                                     int outerColor, int innerColor) {
        double half = size / 2.0;
        double borderWidth = 1.65 * BORDER_SUPERSAMPLE;
        for (int py = 0; py < size; py++) {
            for (int px = 0; px < size; px++) {
                double pointX = px + .5;
                double pointY = py + .5;
                double outerDistance = roundedRectDistance(pointX, pointY,
                        half, half, half, half, radius);
                double innerDistance = roundedRectDistance(pointX, pointY,
                        half, half, half - borderWidth, half - borderWidth,
                        Math.max(0, radius - borderWidth));
                double outerCoverage = Math.clamp(.5 - outerDistance, 0, 1);
                double innerCoverage = Math.clamp(.5 - innerDistance, 0, 1);
                double coverage = Math.clamp(outerCoverage - innerCoverage, 0, 1);
                if (coverage <= 0) {
                    image.setColor(px, py, 0);
                    continue;
                }
                double depth = Math.clamp(-outerDistance / borderWidth, 0, 1);
                int color = blendArgb(outerColor, innerColor, depth);
                int sourceAlpha = color >>> 24;
                int alpha = Math.clamp((int) Math.round(sourceAlpha * coverage), 0, 255);
                image.setColor(px, py, toAbgr(alpha << 24 | color & 0xFFFFFF));
            }
        }
    }

    private static double roundedRectDistance(double x, double y, double centerX, double centerY,
                                              double halfWidth, double halfHeight, double radius) {
        double qx = Math.abs(x - centerX) - Math.max(0, halfWidth - radius);
        double qy = Math.abs(y - centerY) - Math.max(0, halfHeight - radius);
        double outside = Math.hypot(Math.max(qx, 0), Math.max(qy, 0));
        return outside + Math.min(Math.max(qx, qy), 0) - radius;
    }

    private static int blendArgb(int first, int second, double amount) {
        double t = Math.clamp(amount, 0, 1);
        int a = mix(first >>> 24, second >>> 24, t);
        int r = mix(first >>> 16 & 255, second >>> 16 & 255, t);
        int g = mix(first >>> 8 & 255, second >>> 8 & 255, t);
        int b = mix(first & 255, second & 255, t);
        return a << 24 | r << 16 | g << 8 | b;
    }

    private static int mix(int first, int second, double amount) {
        return Math.clamp((int) Math.round(first + (second - first) * amount), 0, 255);
    }

    private static int toAbgr(int argb) {
        return argb & 0xFF00FF00 | argb >>> 16 & 0xFF | (argb & 0xFF) << 16;
    }

    private static void drawNineSlice(DrawContext context, BorderTexture border,
                                      int x, int y, int width, int height) {
        int cap = border.cap();
        if (width < cap * 2 || height < cap * 2) {
            outline(context, x, y, width, height, Math.min(cap - 1, Math.min(width, height) / 2),
                    0xFFFFFFFF);
            return;
        }
        int sampleCap = cap * BORDER_SUPERSAMPLE;
        int sampleMiddle = border.textureSize() - sampleCap * 2;
        int middleWidth = width - cap * 2;
        int middleHeight = height - cap * 2;
        drawPiece(context, border, x, y, cap, cap, 0, 0, sampleCap, sampleCap);
        drawPiece(context, border, x + cap, y, middleWidth, cap,
                sampleCap, 0, sampleMiddle, sampleCap);
        drawPiece(context, border, x + width - cap, y, cap, cap,
                sampleCap + sampleMiddle, 0, sampleCap, sampleCap);
        drawPiece(context, border, x, y + cap, cap, middleHeight,
                0, sampleCap, sampleCap, sampleMiddle);
        drawPiece(context, border, x + width - cap, y + cap, cap, middleHeight,
                sampleCap + sampleMiddle, sampleCap, sampleCap, sampleMiddle);
        drawPiece(context, border, x, y + height - cap, cap, cap,
                0, sampleCap + sampleMiddle, sampleCap, sampleCap);
        drawPiece(context, border, x + cap, y + height - cap, middleWidth, cap,
                sampleCap, sampleCap + sampleMiddle, sampleMiddle, sampleCap);
        drawPiece(context, border, x + width - cap, y + height - cap, cap, cap,
                sampleCap + sampleMiddle, sampleCap + sampleMiddle, sampleCap, sampleCap);
    }

    private static void drawPiece(DrawContext context, BorderTexture border,
                                  int x, int y, int width, int height,
                                  int sourceX, int sourceY, int sourceWidth, int sourceHeight) {
        if (width <= 0 || height <= 0) return;
        PlatformTextures.draw(context, border.id(), x, y, width, height,
                sourceX, sourceY, sourceWidth, sourceHeight,
                border.textureSize(), border.textureSize(), 1, 1);
    }

    private static void drawMaskNineSlice(DrawContext context, MaskTexture mask,
                                          int x, int y, int width, int height, int color) {
        int cap = Math.min(mask.cap(), Math.min(width, height) / 2);
        if (cap <= 0) {
            context.fill(x, y, x + width, y + height, color);
            return;
        }
        int sampleCap = mask.cap() * BORDER_SUPERSAMPLE;
        int sampleMiddle = mask.textureSize() - sampleCap * 2;
        int middleWidth = width - cap * 2;
        int middleHeight = height - cap * 2;
        drawMaskPiece(context, mask, x, y, cap, cap,
                0, 0, sampleCap, sampleCap, color);
        drawMaskPiece(context, mask, x + cap, y, middleWidth, cap,
                sampleCap, 0, sampleMiddle, sampleCap, color);
        drawMaskPiece(context, mask, x + width - cap, y, cap, cap,
                sampleCap + sampleMiddle, 0, sampleCap, sampleCap, color);
        drawMaskPiece(context, mask, x, y + cap, cap, middleHeight,
                0, sampleCap, sampleCap, sampleMiddle, color);
        drawMaskPiece(context, mask, x + cap, y + cap, middleWidth, middleHeight,
                sampleCap, sampleCap, sampleMiddle, sampleMiddle, color);
        drawMaskPiece(context, mask, x + width - cap, y + cap, cap, middleHeight,
                sampleCap + sampleMiddle, sampleCap, sampleCap, sampleMiddle, color);
        drawMaskPiece(context, mask, x, y + height - cap, cap, cap,
                0, sampleCap + sampleMiddle, sampleCap, sampleCap, color);
        drawMaskPiece(context, mask, x + cap, y + height - cap, middleWidth, cap,
                sampleCap, sampleCap + sampleMiddle, sampleMiddle, sampleCap, color);
        drawMaskPiece(context, mask, x + width - cap, y + height - cap, cap, cap,
                sampleCap + sampleMiddle, sampleCap + sampleMiddle, sampleCap, sampleCap, color);
    }

    private static void drawMaskPiece(DrawContext context, MaskTexture mask,
                                      int x, int y, int width, int height,
                                      int sourceX, int sourceY, int sourceWidth, int sourceHeight,
                                      int color) {
        if (width <= 0 || height <= 0) return;
        PlatformTextures.drawTinted(context, mask.id(), x, y, width, height,
                sourceX, sourceY, sourceWidth, sourceHeight,
                mask.textureSize(), mask.textureSize(), color);
    }

    private static void band(DrawContext context, int left, int top, int right, int bottom,
                             Runnable drawer) {
        if (right <= left || bottom <= top) return;
        context.enableScissor(left, top, right, bottom);
        try {
            drawer.run();
        } finally {
            context.disableScissor();
        }
    }

    private static int radius(int width, int height, int requested) {
        return Math.min(Math.max(0, requested),
                Math.min(MAX_RADIUS, Math.min(width, height) / 2));
    }

    private static int cornerInset(int radius, int row) {
        double dy = radius - row - .5;
        return radius - (int) Math.floor(Math.sqrt(Math.max(0, radius * radius - dy * dy)));
    }

    private record BorderKey(int radius, int outerColor, int innerColor) { }

    private record BorderTexture(Identifier id, NativeImageBackedTexture texture,
                                 int textureSize, int cap) { }

    private record MaskTexture(Identifier id, NativeImageBackedTexture texture,
                               int textureSize, int cap) { }
}
