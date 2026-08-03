package dev.fandroid.client.ui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

/** Shared Relay visual tokens and small reusable primitives. */
public final class RelayTheme {
    public static final int ICE = 0xFFEAF4F5;
    public static final int ICE_DARK = 0xFFC4D6DA;
    public static final int SILVER = 0xFFC7CDD0;
    public static final int SILVER_LIGHT = 0xFFF3F5F5;
    public static final int SILVER_DARK = 0xFF747D82;
    public static final int GRAPHITE = 0xFF20262A;
    public static final int GRAPHITE_SOFT = 0xFF3D464B;
    public static final int TEXT = 0xFF1F292D;
    public static final int TEXT_MUTED = 0xFF68757A;
    public static final int CYAN = 0xFF45BFD2;
    public static final int CYAN_DARK = 0xFF227D91;
    public static final int GREEN = 0xFF5F9277;
    public static final int RED = 0xFFA65D62;
    public static final int VIOLET = 0xFF756A9D;
    public static final int SCREEN_X = 42;
    public static final int SCREEN_Y = 76;
    public static final int SCREEN_WIDTH = 684;
    public static final int SCREEN_HEIGHT = 868;

    private RelayTheme() { }

    public static void chromePanel(DrawContext context, int x, int y, int width, int height, int inside) {
        chromePanel(context, x, y, width, height, inside, false);
    }

    public static void chromePanel(DrawContext context, int x, int y, int width, int height,
                                   int inside, boolean dark) {
        RetroDraw.outlineRounded(context, x, y, width, height, 3, 2,
                dark ? 0xFF53666C : SILVER_DARK, inside);
    }

    public static void glassPanel(DrawContext context, int x, int y, int width, int height, int tint) {
        RetroDraw.outlineRounded(context, x, y, width, height, 7, 2, 0xAAFFFFFF, tint);
        context.fill(x + 8, y + 6, x + width - 8, y + 9, 0x66FFFFFF);
    }

    public static void appIcon(DrawContext context, Identifier texture, int x, int y, int size) {
        PlatformUiTextures.draw(context, texture, x, y, size, size);
    }
}
