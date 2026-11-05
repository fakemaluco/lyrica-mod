package dev.relaylyrics.client.ui;

import dev.relaylyrics.client.music.PopupAlignment;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

/** Immediate, one-pixel alignment feedback; no animation between exact positions. */
public final class PopupAlignmentGuides {
    public static void draw(DrawContext context, TextRenderer font, PopupAlignment.Result result, int width, int height) {
        if (result == null || font == null) return;
        GuiTransform.nextLayer(context);
        var popup = result.bounds();
        var vertical = result.vertical(); var horizontal = result.horizontal();
        if (vertical != null) {
            var target = vertical.target();
            int x = (int) Math.round(vertical.coordinate());
            context.fill(x, Math.max(0, Math.min(popup.y(), target.y()) - 8), x + 1,
                    Math.min(height, Math.max(popup.bottom(), target.bottom()) + 8), 0xCCB5A0FF);
            if (!vertical.key().startsWith("screen:")) outline(context, target, width, height);
        }
        if (horizontal != null) {
            var target = horizontal.target();
            int y = (int) Math.round(horizontal.coordinate());
            context.fill(Math.max(0, Math.min(popup.x(), target.x()) - 8), y,
                    Math.min(width, Math.max(popup.right(), target.right()) + 8), y + 1, 0xCCB5A0FF);
            if (!horizontal.key().startsWith("screen:") && (vertical == null || !target.equals(vertical.target())))
                outline(context, target, width, height);
        }
    }
    private static void outline(DrawContext context, PopupAlignment.Rect r, int width, int height) {
        int left = Math.clamp(r.x(), 0, width), top = Math.clamp(r.y(), 0, height);
        int right = Math.clamp(r.right(), 0, width), bottom = Math.clamp(r.bottom(), 0, height);
        if (right <= left || bottom <= top) return;
        context.fill(left, top, right, top + 1, 0x9978C9E8);
        context.fill(left, bottom - 1, right, bottom, 0x9978C9E8);
        context.fill(left, top, left + 1, bottom, 0x9978C9E8);
        context.fill(right - 1, top, right, bottom, 0x9978C9E8);
    }
    private PopupAlignmentGuides() { }
}
