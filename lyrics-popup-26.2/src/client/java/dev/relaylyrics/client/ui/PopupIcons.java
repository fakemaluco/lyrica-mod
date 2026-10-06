package dev.relaylyrics.client.ui;

import dev.relaylyrics.client.music.PlatformTextures;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

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
    public static final Identifier SETTINGS = icon("settings");
    public static final Identifier VOLUME_MUTED = icon("volume-muted");
    public static final Identifier VOLUME_SPEAKER = icon("volume-speaker");
    public static final Identifier VOLUME_LOW = icon("volume-low");
    public static final Identifier VOLUME_HIGH = icon("volume-high");

    private PopupIcons() { }

    private static Identifier icon(String name) {
        return Identifier.fromNamespaceAndPath("lyrica", "textures/gui/lyrics/icons/" + name + ".png");
    }

    public static Identifier volume(double level) {
        if (level <= .001) return VOLUME_MUTED;
        if (level < .25) return VOLUME_SPEAKER;
        if (level < .65) return VOLUME_LOW;
        return VOLUME_HIGH;
    }

    public static void drawCentered(GuiGraphicsExtractor context, Identifier icon,
                                    int centerX, int centerY, int size, int color) {
        if (size <= 0 || color >>> 24 == 0) return;
        PlatformTextures.drawTinted(context, icon, centerX - size / 2, centerY - size / 2,
                size, size, 0, 0, 192, 192, 192, 192, color);
    }
}
