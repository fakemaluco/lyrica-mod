package dev.relaylyrics.client.ui;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Minecraft 1.21.11 exposes cursor requests directly; Cursors Extended decorates these. */
public final class PlatformCursor {
    public enum Style { POINTER, GRABBING, RESIZE }

    private PlatformCursor() { }

    public static void request(GuiGraphicsExtractor context, Style style) {
        context.requestCursor(switch (style) {
            case POINTER -> CursorTypes.POINTING_HAND;
            case GRABBING, RESIZE -> CursorTypes.RESIZE_ALL;
        });
    }
}
