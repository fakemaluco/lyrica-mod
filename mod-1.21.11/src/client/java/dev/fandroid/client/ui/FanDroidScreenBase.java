package dev.fandroid.client.ui;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;

/** Maps the 1.21.11 typed input records to FanDroid's stable input surface. */
abstract class FanDroidScreenBase extends Screen {
    protected FanDroidScreenBase(Text title) {
        super(title);
    }

    /** 1.21.11 renders and blurs the background from {@link Screen#render}. */
    protected final void renderVanillaScreen(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
    }

    protected boolean handleMouseClicked(double x, double y, int button) { return false; }
    protected boolean handleMouseDragged(double x, double y, int button, double dx, double dy) { return false; }
    protected boolean handleMouseReleased(double x, double y, int button) { return false; }
    protected boolean handleMouseScrolled(double x, double y, double horizontal, double vertical) { return false; }
    protected boolean handleCharTyped(char character, int modifiers) { return false; }
    protected boolean handleKeyPressed(int key, int scanCode, int modifiers) { return false; }

    @Override
    public final boolean mouseClicked(Click click, boolean doubled) {
        return handleMouseClicked(click.x(), click.y(), click.button())
                || super.mouseClicked(click, doubled);
    }

    @Override
    public final boolean mouseDragged(Click click, double dx, double dy) {
        return handleMouseDragged(click.x(), click.y(), click.button(), dx, dy)
                || super.mouseDragged(click, dx, dy);
    }

    @Override
    public final boolean mouseReleased(Click click) {
        return handleMouseReleased(click.x(), click.y(), click.button())
                || super.mouseReleased(click);
    }

    @Override
    public final boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        return handleMouseScrolled(x, y, horizontal, vertical)
                || super.mouseScrolled(x, y, horizontal, vertical);
    }

    @Override
    public final boolean charTyped(CharInput input) {
        String value = input.asString();
        if (value == null || value.isEmpty()) return false;
        boolean handled = false;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (Character.isBmpCodePoint(codePoint)) {
                handled |= handleCharTyped((char) codePoint, input.modifiers());
            }
            offset += Character.charCount(codePoint);
        }
        return handled || super.charTyped(input);
    }

    @Override
    public final boolean keyPressed(KeyInput input) {
        return handleKeyPressed(input.key(), input.scancode(), input.modifiers())
                || super.keyPressed(input);
    }
}
