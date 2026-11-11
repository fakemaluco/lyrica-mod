package dev.fandroid.client.ui;

import dev.fandroid.client.SungTextColor;
import dev.fandroid.client.music.PlatformTextures;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Version-neutral rounded geometry and true scissor bands for textured surfaces. */
public final class RoundedGui {
    private static final int MAX_RADIUS = 16;
    private static final int BORDER_SUPERSAMPLE = 8;
    private static final int MAX_BORDER_TEXTURES = 12;
    private static final AtomicInteger BORDER_GENERATION = new AtomicInteger();
    private static final Map<BorderKey, BorderTexture> BORDER_TEXTURES =
            new LinkedHashMap<>(8, .75F, true);
    private static final Map<ShapeKey, MaskTexture> FILL_TEXTURES =
            new LinkedHashMap<>(8, .75F, true);
    private static final List<List<RoundedClipBand>> CLIP_PLANS = createClipPlans();

    private static final Map<GradientKey, GradientTexture> GRADIENTS = new LinkedHashMap<>(4, .75f, true);
    private record GradientKey(int width, int height, int radius, int top, int bottom, int sidebarWidth, int sidebarColor, double density, List<SungTextColor.Stop> stops) { }
    private record GradientTexture(Identifier id, int width, int height) { }

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
        for (RoundedClipBand clipBand : roundedClipPlan(r)) {
            int inset = clipBand.inset();
            band(context, x + inset, y + clipBand.startRow(), x + width - inset,
                    y + clipBand.endRow(), drawer);
            band(context, x + inset, y + height - clipBand.endRow(), x + width - inset,
                    y + height - clipBand.startRow(), drawer);
        }
    }

    /**
     * Groups adjacent corner scanlines whose exact inset differs by at most two pixels.
     * The midpoint keeps every edge within one logical pixel while reducing a radius-16
     * clip from 33 complete callbacks to at most 11.
     */
    static List<RoundedClipBand> roundedClipPlan(int radius) {
        int safeRadius = Math.clamp(radius, 0, MAX_RADIUS);
        return CLIP_PLANS.get(safeRadius);
    }

    private static List<List<RoundedClipBand>> createClipPlans() {
        List<List<RoundedClipBand>> plans = new ArrayList<>(MAX_RADIUS + 1);
        plans.add(List.of());
        for (int radius = 1; radius <= MAX_RADIUS; radius++) {
            plans.add(buildClipPlan(radius));
        }
        return List.copyOf(plans);
    }

    private static List<RoundedClipBand> buildClipPlan(int safeRadius) {
        List<RoundedClipBand> bands = new ArrayList<>();
        int start = 0;
        while (start < safeRadius) {
            int minimum = cornerInset(safeRadius, start);
            int maximum = minimum;
            int end = start + 1;
            while (end < safeRadius) {
                int candidate = cornerInset(safeRadius, end);
                int nextMinimum = Math.min(minimum, candidate);
                int nextMaximum = Math.max(maximum, candidate);
                if (nextMaximum - nextMinimum > 2) break;
                minimum = nextMinimum;
                maximum = nextMaximum;
                end++;
            }
            bands.add(new RoundedClipBand(start, end, (minimum + maximum) / 2));
            start = end;
        }
        return List.copyOf(bands);
    }

    /** Runs the drawer clipped to a straight rectangle (integer scissor, batch-safe). */
    public static void scissor(DrawContext context, int x, int y, int width, int height,
                               Runnable drawer) {
        if (width <= 0 || height <= 0 || drawer == null) return;
        band(context, x, y, x + width, y + height, drawer);
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
        smoothFillClipped(context, x, y, width, height, radius, x + width, y, color);
    }

    /** Clips only the fill geometry, keeping the rounded ends under fractional GUI transforms. */
    public static void smoothFillPortion(DrawContext context, int x, int y, int width, int height,
                                         int radius, int amount, boolean vertical, int color) {
        if (amount <= 0) return;
        int right = x + (vertical ? width : Math.min(amount, width));
        int top = y + (vertical ? Math.max(0, height - amount) : 0);
        smoothFillClipped(context, x, y, width, height, radius, right, top, color);
    }

    private static void smoothFillClipped(DrawContext context, int x, int y, int width, int height,
                                          int radius, int right, int top, int color) {
        if (width <= 0 || height <= 0 || color >>> 24 == 0) return;
        int r = radius(width, height, radius);
        if (r == 0) {
            context.fill(x, top, right, y + height, color);
            return;
        }
        drawMaskNineSlice(context, fillTexture(r), x, y, width, height, right, top, color);
    }

    public static void gradient(DrawContext context, int x, int y, int width, int height,
                                int radius, int topColor, int bottomColor) {
        clipped(context, x, y, width, height, radius,
                () -> context.fillGradient(x, y, x + width, y + height, topColor, bottomColor));
    }

    /** Pixel-accurate alpha corners; opacity is a tint, so an entrance never rebuilds the surface. */
    public static void smoothGradient(DrawContext context, int x, int y, int width, int height,
                                      int radius, int topColor, int bottomColor, int sidebarWidth, int sidebarColor, double opacity) {
        smoothGradient(context, x, y, width, height, radius, topColor, bottomColor, sidebarWidth, sidebarColor, opacity, null);
    }

    public static void smoothHorizontalGradient(DrawContext context, int x, int y, int width, int height,
                                                int radius, SungTextColor gradient, double opacity) {
        smoothGradient(context, x, y, width, height, radius, 0, 0, 0, 0, opacity, gradient);
    }

    private static void smoothGradient(DrawContext context, int x, int y, int width, int height,
                                       int radius, int topColor, int bottomColor, int sidebarWidth, int sidebarColor,
                                       double opacity, SungTextColor horizontal) {
        if (width <= 0 || height <= 0 || opacity <= 0) return;
        double density = Math.min(PlatformTextures.guiScale(), Math.sqrt(4_000_000.0 / ((double) width * height)));
        GradientKey key = new GradientKey(width, height, radius, topColor, bottomColor, sidebarWidth, sidebarColor, density, horizontal == null ? List.of() : horizontal.stops());
        GradientTexture gradient = GRADIENTS.get(key);
        if (gradient == null) {
            int rw = Math.max(1, (int) Math.ceil(width * density)), rh = Math.max(1, (int) Math.ceil(height * density));
            NativeImageBackedTexture texture = PlatformTextures.empty("Smooth panel gradient", rw, rh);
            NativeImage image = texture.getImage();
            double r = Math.min(radius * density, Math.min(rw, rh) / 2.0);
            int[] columns = null;
            if (horizontal != null) {
                columns = new int[rw];
                for (int px = 0; px < rw; px++)
                    columns[px] = 0xFF000000 | horizontal.gradient(px / (double) Math.max(1, rw - 1));
            }
            for (int py = 0; py < rh; py++) {
                int color = blendArgb(topColor, bottomColor, py / (double) Math.max(1, rh - 1));
                for (int px = 0; px < rw; px++) {
                    int painted = columns != null ? columns[px]
                            : px < sidebarWidth * density ? sourceOver(sidebarColor, color) : color;
                    double coverage = 1;
                    if ((px < r || px >= rw - r) && (py < r || py >= rh - r)) {
                        double distance = roundedRectDistance(px + .5, py + .5, rw / 2.0, rh / 2.0, rw / 2.0, rh / 2.0, r);
                        coverage = Math.clamp(.5 - distance, 0, 1);
                    }
                    int alpha = (int) Math.round((painted >>> 24) * coverage);
                    PlatformTextures.setUiPixel(image, px, py, alpha << 24 | painted & 0xFFFFFF);
                }
            }
            PlatformTextures.upload(texture);
            Identifier id = PlatformTextures.register("settings_gradient_" + BORDER_GENERATION.incrementAndGet(), texture);
            gradient = new GradientTexture(id, rw, rh); GRADIENTS.put(key, gradient);
            while (GRADIENTS.size() > 4) {
                var first = GRADIENTS.entrySet().iterator(); var old = first.next(); first.remove();
                PlatformTextures.retireEffectMask(old.getValue().id());
            }
        }
        PlatformTextures.draw(context, gradient.id(), x, y, width, height, 0, 0, gradient.width(), gradient.height(),
                gradient.width(), gradient.height(), opacity, 1);
    }

    private static int sourceOver(int foreground, int background) {
        double a = (foreground >>> 24) / 255.0, b = (background >>> 24) / 255.0 * (1 - a), total = a + b;
        if (total == 0) return 0;
        int color = (int) Math.round(total * 255) << 24;
        for (int shift = 0; shift < 24; shift += 8) color |= (int) Math.round(((foreground >>> shift & 255) * a + (background >>> shift & 255) * b) / total) << shift;
        return color;
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
        BorderTexture border = borderTexture(new BorderKey(r, outerColor, innerColor, PlatformTextures.guiScale()));
        drawNineSlice(context, border, x, y, width, height);
    }

    public static void smoothOutline(DrawContext context, int x, int y, int width, int height,
                                     int radius, int color) {
        if (width <= 2 || height <= 2 || color >>> 24 == 0) return;
        int r = radius(width, height, radius);
        if (r == 0) { outline(context, x, y, width, height, 0, color); return; }
        BorderTexture border = borderTexture(new BorderKey(r, 0xFFFFFFFF, 0xFFFFFFFF, PlatformTextures.guiScale()));
        drawNineSliceTinted(context, border, x, y, width, height, color);
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
                    key.outerColor(), key.innerColor(), key.density());
            PlatformTextures.upload(texture);
            BorderTexture created = new BorderTexture(id, texture, textureSize, cap);
            BORDER_TEXTURES.put(key, created);
            while (BORDER_TEXTURES.size() > MAX_BORDER_TEXTURES) {
                BorderKey eldest = BORDER_TEXTURES.keySet().iterator().next();
                BorderTexture discarded = BORDER_TEXTURES.remove(eldest);
                if (discarded != null) {
                    PlatformTextures.retireEffectMask(discarded.id());
                }
            }
            return created;
        }
    }

    private static MaskTexture fillTexture(int radius) {
        synchronized (FILL_TEXTURES) {
            ShapeKey key = new ShapeKey(radius, PlatformTextures.guiScale());
            MaskTexture existing = FILL_TEXTURES.get(key);
            if (existing != null) return existing;
            int cap = Math.max(1, radius);
            int logicalSize = cap * 2 + 2;
            int textureSize = logicalSize * BORDER_SUPERSAMPLE;
            NativeImageBackedTexture texture = PlatformTextures.empty(
                    "Relay smooth fill", textureSize, textureSize);
            Identifier id = PlatformTextures.register("relay_smooth_fill_" + radius + "_" + BORDER_GENERATION.incrementAndGet(), texture);
            NativeImage image = texture.getImage();
            if (image == null) throw new IllegalStateException("smooth fill texture has no image");
            double half = textureSize / 2.0;
            double scaledRadius = radius * BORDER_SUPERSAMPLE;
            for (int py = 0; py < textureSize; py++) {
                for (int px = 0; px < textureSize; px++) {
                    double distance = roundedRectDistance(px + .5, py + .5,
                            half, half, half, half, scaledRadius);
                    int alpha = Math.clamp((int) Math.round(
                            Math.clamp(.5 - distance * key.density() / BORDER_SUPERSAMPLE, 0, 1) * 255), 0, 255);
                    image.setColor(px, py, toAbgr(alpha << 24 | 0xFFFFFF));
                }
            }
            PlatformTextures.upload(texture);
            MaskTexture created = new MaskTexture(id, texture, textureSize, cap);
            FILL_TEXTURES.put(key, created);
            while (FILL_TEXTURES.size() > 32) {
                var first = FILL_TEXTURES.entrySet().iterator(); var old = first.next(); first.remove();
                PlatformTextures.retireEffectMask(old.getValue().id());
            }
            return created;
        }
    }

    private static void renderBorder(NativeImage image, int size, double radius,
                                     int outerColor, int innerColor, double density) {
        double half = size / 2.0;
        double borderWidth = BORDER_SUPERSAMPLE;
        for (int py = 0; py < size; py++) {
            for (int px = 0; px < size; px++) {
                double pointX = px + .5;
                double pointY = py + .5;
                double outerDistance = roundedRectDistance(pointX, pointY,
                        half, half, half, half, radius);
                double innerDistance = roundedRectDistance(pointX, pointY,
                        half, half, half - borderWidth, half - borderWidth,
                        Math.max(0, radius - borderWidth));
                double outerCoverage = Math.clamp(.5 - outerDistance * density / BORDER_SUPERSAMPLE, 0, 1);
                double innerCoverage = Math.clamp(.5 - innerDistance * density / BORDER_SUPERSAMPLE, 0, 1);
                double coverage = Math.clamp(outerCoverage - innerCoverage, 0, 1);
                if (coverage <= 0) {
                    image.setColor(px, py, toAbgr(outerColor & 0xFFFFFF));
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
        int cap = Math.min(border.cap(), Math.min(width, height) / 2);
        if (cap <= 0) return;
        // Compress the corner source for narrow capsules instead of falling back
        // to the pixel-stepped outline used by the retro renderer.
        int sampleCap = border.cap() * BORDER_SUPERSAMPLE;
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

    private static void drawNineSliceTinted(DrawContext context, BorderTexture border,
                                      int x, int y, int width, int height, int color) {
        int cap = Math.min(border.cap(), Math.min(width, height) / 2);
        if (cap <= 0) return;
        // Compress the corner source for narrow capsules instead of falling back
        // to the pixel-stepped outline used by the retro renderer.
        int sampleCap = border.cap() * BORDER_SUPERSAMPLE;
        int sampleMiddle = border.textureSize() - sampleCap * 2;
        int middleWidth = width - cap * 2;
        int middleHeight = height - cap * 2;
        drawTintedPiece(context, border, x, y, cap, cap, 0, 0, sampleCap, sampleCap, color);
        drawTintedPiece(context, border, x + cap, y, middleWidth, cap,
                sampleCap, 0, sampleMiddle, sampleCap, color);
        drawTintedPiece(context, border, x + width - cap, y, cap, cap,
                sampleCap + sampleMiddle, 0, sampleCap, sampleCap, color);
        drawTintedPiece(context, border, x, y + cap, cap, middleHeight,
                0, sampleCap, sampleCap, sampleMiddle, color);
        drawTintedPiece(context, border, x + width - cap, y + cap, cap, middleHeight,
                sampleCap + sampleMiddle, sampleCap, sampleCap, sampleMiddle, color);
        drawTintedPiece(context, border, x, y + height - cap, cap, cap,
                0, sampleCap + sampleMiddle, sampleCap, sampleCap, color);
        drawTintedPiece(context, border, x + cap, y + height - cap, middleWidth, cap,
                sampleCap, sampleCap + sampleMiddle, sampleMiddle, sampleCap, color);
        drawTintedPiece(context, border, x + width - cap, y + height - cap, cap, cap,
                sampleCap + sampleMiddle, sampleCap + sampleMiddle, sampleCap, sampleCap, color);
    }

    private static void drawTintedPiece(DrawContext context, BorderTexture border,
            int x, int y, int width, int height, int sourceX, int sourceY, int sourceWidth, int sourceHeight, int color) {
        if (width <= 0 || height <= 0) return;
        PlatformTextures.drawTinted(context, border.id(), x, y, width, height, sourceX, sourceY,
                sourceWidth, sourceHeight, border.textureSize(), border.textureSize(), color);
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
                                          int x, int y, int width, int height, int right, int top, int color) {
        int cap = Math.min(mask.cap(), Math.min(width, height) / 2);
        if (cap <= 0) {
            context.fill(x, top, right, y + height, color);
            return;
        }
        int sampleCap = mask.cap() * BORDER_SUPERSAMPLE;
        int sampleMiddle = mask.textureSize() - sampleCap * 2;
        int middleWidth = width - cap * 2;
        int middleHeight = height - cap * 2;
        drawMaskPiece(context, mask, x, y, cap, cap,
                0, 0, sampleCap, sampleCap, right, top, color);
        drawMaskPiece(context, mask, x + cap, y, middleWidth, cap,
                sampleCap, 0, sampleMiddle, sampleCap, right, top, color);
        drawMaskPiece(context, mask, x + width - cap, y, cap, cap,
                sampleCap + sampleMiddle, 0, sampleCap, sampleCap, right, top, color);
        drawMaskPiece(context, mask, x, y + cap, cap, middleHeight,
                0, sampleCap, sampleCap, sampleMiddle, right, top, color);
        drawMaskPiece(context, mask, x + cap, y + cap, middleWidth, middleHeight,
                sampleCap, sampleCap, sampleMiddle, sampleMiddle, right, top, color);
        drawMaskPiece(context, mask, x + width - cap, y + cap, cap, middleHeight,
                sampleCap + sampleMiddle, sampleCap, sampleCap, sampleMiddle, right, top, color);
        drawMaskPiece(context, mask, x, y + height - cap, cap, cap,
                0, sampleCap + sampleMiddle, sampleCap, sampleCap, right, top, color);
        drawMaskPiece(context, mask, x + cap, y + height - cap, middleWidth, cap,
                sampleCap, sampleCap + sampleMiddle, sampleMiddle, sampleCap, right, top, color);
        drawMaskPiece(context, mask, x + width - cap, y + height - cap, cap, cap,
                sampleCap + sampleMiddle, sampleCap + sampleMiddle, sampleCap, sampleCap, right, top, color);
    }

    private static void drawMaskPiece(DrawContext context, MaskTexture mask,
                                      int x, int y, int width, int height,
                                      int sourceX, int sourceY, int sourceWidth, int sourceHeight,
                                      int right, int top, int color) {
        int drawnWidth = Math.min(width, right - x);
        int skippedRows = Math.max(0, top - y);
        int drawnHeight = height - skippedRows;
        if (drawnWidth <= 0 || drawnHeight <= 0) return;
        int sampledWidth = drawnWidth == width ? sourceWidth
                : Math.max(1, (int) Math.round(sourceWidth * (drawnWidth / (double) width)));
        int skippedTexels = skippedRows == 0 ? 0
                : Math.min(sourceHeight - 1, (int) Math.round(sourceHeight * (skippedRows / (double) height)));
        PlatformTextures.drawTinted(context, mask.id(), x, y + skippedRows, drawnWidth, drawnHeight,
                sourceX, sourceY + skippedTexels, sampledWidth, sourceHeight - skippedTexels,
                mask.textureSize(), mask.textureSize(), color);
    }

    private static void band(DrawContext context, int left, int top, int right, int bottom,
                             Runnable drawer) {
        if (right <= left || bottom <= top) return;
        GuiTransform.enableScissor(context, left, top, right, bottom);
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

    private record BorderKey(int radius, int outerColor, int innerColor, double density) { }
    private record ShapeKey(int radius, double density) { }

    private record BorderTexture(Identifier id, NativeImageBackedTexture texture,
                                 int textureSize, int cap) { }

    private record MaskTexture(Identifier id, NativeImageBackedTexture texture,
                               int textureSize, int cap) { }

    record RoundedClipBand(int startRow, int endRow, int inset) { }
}
