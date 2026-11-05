package dev.fandroid.client.ui;

import dev.fandroid.client.music.PlatformTextures;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

import java.util.List;

/** Rasterized exports of the bundled Spicy Lyrics control icons. */
public final class PopupIcons {
    public static final Identifier HEART = icon("heart");
    public static final Identifier HEART_OUTLINE = icon("heart-outline");
    public static final Identifier PREVIOUS = icon("previous");
    public static final Identifier PLAY = icon("play");
    public static final Identifier PAUSE = icon("pause");
    public static final Identifier NEXT = icon("next");
    public static final Identifier SHUFFLE = icon("shuffle");
    public static final Identifier REPEAT = icon("repeat");
    public static final Identifier CLOSE = icon("close");
    public static final Identifier SETTINGS = icon("settings-lucide");
    public static final Identifier WORLD_LYRICS = icon("world-lyrics");
    public static final Identifier LYRICS = icon("lyrics");
    public static final Identifier RESTORE = icon("restore-lucide");
    public static final Identifier ROMANIZATION_ON = icon("romanization-on");
    public static final Identifier ROMANIZATION_OFF = icon("romanization-off");
    public static final Identifier TRANSLATION = icon("translation");
    public static final Identifier VOLUME_MUTED = icon("volume-muted");
    public static final Identifier VOLUME_SPEAKER = icon("volume-speaker");
    public static final Identifier VOLUME_LOW = icon("volume-low");
    public static final Identifier VOLUME_HIGH = icon("volume-high");

    private static final Identifier ATLAS = icon("popup-controls");
    private static final List<Identifier> ATLAS_ICONS = List.of(
            HEART, HEART_OUTLINE, PREVIOUS, PLAY, PAUSE, NEXT, SHUFFLE,
            REPEAT, CLOSE, SETTINGS, WORLD_LYRICS, LYRICS, RESTORE,
            ROMANIZATION_ON, ROMANIZATION_OFF, TRANSLATION, VOLUME_MUTED,
            VOLUME_SPEAKER, VOLUME_LOW, VOLUME_HIGH);
    private static final int ATLAS_COLUMNS = 7;
    private static final int ATLAS_GUTTER = 2;
    private static final int ATLAS_WIDTH = 476;
    private static final int ATLAS_HEIGHT = 372;

    private PopupIcons() { }

    private static Identifier icon(String name) {
        return Identifier.of("lyrica", "textures/gui/lyrics/icons/" + name + ".png");
    }

    public static Identifier volume(double level) {
        if (level <= .001) return VOLUME_MUTED;
        if (level < .25) return VOLUME_SPEAKER;
        if (level < .65) return VOLUME_LOW;
        return VOLUME_HIGH;
    }

    static void drawPreciselyCentered(DrawContext context, Identifier icon,
                                      double centerX, double centerY, int size, int color) {
        float left = (float) (centerX - size / 2.0);
        float top = (float) (centerY - size / 2.0);
        GuiTransform.push(context);
        try {
            GuiTransform.translate(context, left, top);
            drawAt(context, icon, 0, 0, size, color);
        } finally {
            GuiTransform.pop(context);
        }
    }

    public static void drawCentered(DrawContext context, Identifier icon,
                                    int centerX, int centerY, int size, int color) {
        drawAt(context, icon, centerX - size / 2, centerY - size / 2, size, color);
    }

    private static void drawAt(DrawContext context, Identifier icon,
                               int left, int top, int size, int color) {
        if (size <= 0 || color >>> 24 == 0) return;
        int index = ATLAS_ICONS.indexOf(icon);
        double pixels = size * PlatformTextures.guiScale();
        if (index < 0 || pixels > 64) {
            PlatformTextures.drawTinted(context, icon, left, top,
                    size, size, 0, 0, 192, 192, 192, 192, color);
            return;
        }

        // Each level was filtered offline, so even tiny icons keep their thin strokes.
        int source = pixels <= 16 ? 16 : pixels <= 32 ? 32 : 64;
        int levelTop = switch (source) {
            case 16 -> 0;
            case 32 -> 60;
            default -> 168;
        };
        int stride = source + ATLAS_GUTTER * 2;
        int u = index % ATLAS_COLUMNS * stride + ATLAS_GUTTER;
        int v = levelTop + index / ATLAS_COLUMNS * stride + ATLAS_GUTTER;
        PlatformTextures.drawTinted(context, ATLAS, left, top,
                size, size, u, v, source, source, ATLAS_WIDTH, ATLAS_HEIGHT, color);
    }
}
