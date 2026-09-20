package dev.fandroid.client.ui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.cursor.StandardCursors;

/** Minecraft 1.21.11 exposes cursor requests directly; Cursors Extended decorates these. */
public final class PlatformCursor {
    public enum Style { POINTER, GRABBING, RESIZE }

    private PlatformCursor() { }

    public static void request(DrawContext context, Style style) {
        context.setCursor(switch (style) {
            case POINTER -> StandardCursors.POINTING_HAND;
            case GRABBING, RESIZE -> StandardCursors.RESIZE_ALL;
        });
    }
}
