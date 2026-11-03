package dev.fandroid.client.ui;

import dev.fandroid.client.music.PlatformTextures;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

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
    public static final Identifier RESTORE = icon("restore-lucide");
    public static final Identifier ROMANIZATION_ON = icon("romanization-on");
    public static final Identifier ROMANIZATION_OFF = icon("romanization-off");
    public static final Identifier TRANSLATION = icon("translation");
    public static final Identifier VOLUME_MUTED = icon("volume-muted");
    public static final Identifier VOLUME_SPEAKER = icon("volume-speaker");
    public static final Identifier VOLUME_LOW = icon("volume-low");
    public static final Identifier VOLUME_HIGH = icon("volume-high");

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

    public static void drawCentered(DrawContext context, Identifier icon,
                                    int centerX, int centerY, int size, int color) {
        if (size <= 0 || color >>> 24 == 0) return;
        PlatformTextures.drawTinted(context, icon, centerX - size / 2, centerY - size / 2,
                size, size, 0, 0, 192, 192, 192, 192, color);
    }
}
