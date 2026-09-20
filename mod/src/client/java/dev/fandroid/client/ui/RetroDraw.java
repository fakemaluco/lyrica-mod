package dev.fandroid.client.ui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.Text;
import net.minecraft.text.OrderedText;
import net.minecraft.util.Identifier;

public final class RetroDraw {
    /** Tight corners inspired by early portable media-player interfaces. */
    private static final int MAX_CORNER_RADIUS = 7;
    private static final Identifier UI_FONT = Identifier.ofVanilla("default");
    private static final float MINIMUM_UI_SCALE = 1.50F;
    private static boolean darkMode;
    public static final float BODY_TEXT_SCALE = 2.00F;
    public static final float SMALL_TEXT_SCALE = 1.72F;
    public static final float TITLE_TEXT_SCALE = 2.55F;
    public static final float ICON_TEXT_SCALE = 4.5F;

    private RetroDraw() {
    }

    public static void setDarkMode(boolean enabled) {
        darkMode = enabled;
    }

    public static void rounded(DrawContext context, int x, int y, int width, int height, int radius, int color) {
        if (width <= 0 || height <= 0) return;
        color = themedFill(color);
        int r = Math.min(Math.max(0, radius), Math.min(MAX_CORNER_RADIUS, Math.min(width, height) / 2));
        context.fill(x + r, y, x + width - r, y + height, color);
        context.fill(x, y + r, x + width, y + height - r, color);
        for (int i = 0; i < r; i++) {
            int inset = cornerInset(r, i);
            context.fill(x + inset, y + i, x + width - inset, y + i + 1, color);
            context.fill(x + inset, y + height - i - 1, x + width - inset, y + height - i, color);
        }
    }

    public static void circle(DrawContext context, int centerX, int centerY, int radius, int color) {
        if (radius <= 0) return;
        color = themedFill(color);
        double squaredRadius = radius * radius;
        for (int row = -radius; row <= radius; row++) {
            int halfWidth = (int) Math.floor(Math.sqrt(Math.max(0, squaredRadius - row * row)));
            context.fill(centerX - halfWidth, centerY + row,
                    centerX + halfWidth + 1, centerY + row + 1, color);
        }
    }

    public static void outlinedCircle(DrawContext context, int centerX, int centerY,
                                      int radius, int thickness, int outline, int inside) {
        circle(context, centerX, centerY, radius, outline);
        circle(context, centerX, centerY, Math.max(1, radius - thickness), inside);
    }

    public static void beveled(DrawContext context, int x, int y, int width, int height, int radius,
                               int color, int highlight, int lowlight) {
        rounded(context, x, y, width, height, radius, lowlight);
        rounded(context, x + 1, y + 1, width - 2, height - 2, Math.max(0, radius - 1), color);
        context.fill(x + 3, y + 2, x + width - 3, y + 4, themedFill(highlight));
        context.fill(x + 3, y + height - 4, x + width - 3, y + height - 2, themedFill(lowlight));
    }

    public static void outlineRounded(DrawContext context, int x, int y, int width, int height,
                                      int radius, int thickness, int color, int insideColor) {
        rounded(context, x, y, width, height, radius, color);
        rounded(context, x + thickness, y + thickness, width - thickness * 2, height - thickness * 2,
                Math.max(0, radius - thickness), insideColor);
    }

    public static void text(DrawContext context, TextRenderer renderer, String value,
                            int x, int y, int color) {
        text(context, renderer, Text.literal(value), x, y, color, BODY_TEXT_SCALE);
    }

    public static void text(DrawContext context, TextRenderer renderer, Text value,
                            int x, int y, int color) {
        text(context, renderer, value, x, y, color, BODY_TEXT_SCALE);
    }

    public static void text(DrawContext context, TextRenderer renderer, String value,
                            int x, int y, int color, float scale) {
        text(context, renderer, Text.literal(value), x, y, color, scale);
    }

    public static void text(DrawContext context, TextRenderer renderer, Text value,
                            int x, int y, int color, float scale) {
        scale = Math.max(MINIMUM_UI_SCALE, scale);
        Text styled = PlatformTextStyles.withFont(value, UI_FONT);
        GuiTransform.push(context);
        GuiTransform.translate(context, x, y);
        GuiTransform.scale(context, scale, scale);
        context.drawText(renderer, styled, 0, 0, themedText(color), true);
        GuiTransform.pop(context);
    }

    public static void text(DrawContext context, TextRenderer renderer, OrderedText value,
                            int x, int y, int color, float scale) {
        scale = Math.max(MINIMUM_UI_SCALE, scale);
        GuiTransform.push(context);
        GuiTransform.translate(context, x, y);
        GuiTransform.scale(context, scale, scale);
        context.drawText(renderer, value, 0, 0, themedText(color), true);
        GuiTransform.pop(context);
    }

    public static void centeredText(DrawContext context, TextRenderer renderer, String value,
                                    int centerX, int y, int color) {
        centeredText(context, renderer, Text.literal(value), centerX, y, color, BODY_TEXT_SCALE);
    }

    public static void centeredText(DrawContext context, TextRenderer renderer, Text value,
                                    int centerX, int y, int color) {
        centeredText(context, renderer, value, centerX, y, color, BODY_TEXT_SCALE);
    }

    public static void centeredText(DrawContext context, TextRenderer renderer, String value,
                                    int centerX, int y, int color, float scale) {
        centeredText(context, renderer, Text.literal(value), centerX, y, color, scale);
    }

    public static void centeredText(DrawContext context, TextRenderer renderer, Text value,
                                    int centerX, int y, int color, float scale) {
        scale = Math.max(MINIMUM_UI_SCALE, scale);
        int width = Math.round(renderer.getWidth(PlatformTextStyles.withFont(value, UI_FONT)) * scale);
        text(context, renderer, value, centerX - width / 2, y, color, scale);
    }

    public static Text uiText(String value) {
        return PlatformTextStyles.withFont(Text.literal(value), UI_FONT);
    }

    public static void boldText(DrawContext context, TextRenderer renderer, String value,
                                int x, int y, int color, float scale) {
        scale = Math.max(MINIMUM_UI_SCALE, scale);
        Text styled = PlatformTextStyles.withFontBold(Text.literal(value), UI_FONT);
        GuiTransform.push(context);
        GuiTransform.translate(context, x, y);
        GuiTransform.scale(context, scale, scale);
        context.drawText(renderer, styled, 0, 0, themedText(color), true);
        GuiTransform.pop(context);
    }

    public static void regularText(DrawContext context, TextRenderer renderer, String value,
                                   int x, int y, int color, float scale) {
        scale = Math.max(MINIMUM_UI_SCALE, scale);
        Text styled = PlatformTextStyles.withFont(value, UI_FONT);
        GuiTransform.push(context);
        GuiTransform.translate(context, x, y);
        GuiTransform.scale(context, scale, scale);
        context.drawText(renderer, styled, 0, 0, themedText(color), true);
        GuiTransform.pop(context);
    }

    public static String fit(TextRenderer renderer, String value, int logicalWidth) {
        if (value == null) return "";
        return renderer.trimToWidth(value, Math.max(0, (int) (logicalWidth / BODY_TEXT_SCALE)));
    }

    private static int cornerInset(int radius, int row) {
        double dy = radius - row - .5;
        return radius - (int) Math.floor(Math.sqrt(Math.max(0, radius * radius - dy * dy)));
    }

    private static int themedText(int color) {
        if (!darkMode) return color;
        return switch (color) {
            case 0xFF1F292D, 0xFF302E2B, 0xFF34312D, 0xFF2E2D2A, 0xFF3A3732 -> 0xFFE9F1F1;
            case 0xFF68757A, 0xFF777066, 0xFF999085, 0xFF5D5851, 0xFF8B8276 -> 0xFFAAB9BD;
            default -> color;
        };
    }

    private static int themedFill(int color) {
        if (!darkMode) return color;
        return switch (color) {
            case 0xFFF4F9F9, 0xFFF3FAFA, 0xFFE8EFEF, 0xFFE5EEEE -> 0xFF1C272B;
            case 0xFFD9E5E7, 0xFFD4E3E5, 0xFFD4DFE0, 0xFFD2D5D6, 0xFFD1D1D6,
                 0xFFBECBCD, 0xFFC3D3D6 -> 0xFF2A383D;
            case 0xFFFFFFFF -> 0xFF63767C;
            case 0xAAE0ECEE, 0xAADBE7E9, 0xAACFE5D6, 0xAADFE4E5 -> 0xAA25353A;
            default -> color;
        };
    }
}
