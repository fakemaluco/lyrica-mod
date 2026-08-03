package dev.fandroid.client.ui;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;

/** Maps the 1.21.11 typed input records to FanDroid's stable input surface. */
abstract class FanDroidScreenBase extends Screen {
    protected FanDroidScreenBase(Text title) {
        super(title);
    }

    protected boolean handleMouseClicked(double x, double y, int button) { return false; }
    protected boolean handleMouseDragged(double x, double y, int button, double dx, double dy) { return false; }
    protected boolean handleMouseReleased(double x, double y, int button) { return false; }
    protected boolean handleMouseScrolled(double x, double y, double horizontal, double vertical) { return false; }
    protected boolean handleCharTyped(char character, int modifiers) { return false; }
    protected boolean handleKeyPressed(int key, int scanCode, int modifiers) { return false; }

    @Override
    public final boolean mouseClicked(Click click, boolean doubled) {
        return handleMouseClicked(click.x(), click.y(), click.button());
    }

    @Override
    public final boolean mouseDragged(Click click, double dx, double dy) {
        return handleMouseDragged(click.x(), click.y(), click.button(), dx, dy);
    }

    @Override
    public final boolean mouseReleased(Click click) {
        return handleMouseReleased(click.x(), click.y(), click.button());
    }

    @Override
    public final boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        return handleMouseScrolled(x, y, horizontal, vertical);
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
        return handled;
    }

    @Override
    public final boolean keyPressed(KeyInput input) {
        return handleKeyPressed(input.key(), input.scancode(), input.modifiers());
    }
}
