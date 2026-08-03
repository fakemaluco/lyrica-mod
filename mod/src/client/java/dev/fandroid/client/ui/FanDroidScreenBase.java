package dev.fandroid.client.ui;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/** Maps the 1.21.1 primitive input callbacks to FanDroid's stable input surface. */
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
    public final boolean mouseClicked(double x, double y, int button) {
        return handleMouseClicked(x, y, button);
    }

    @Override
    public final boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        return handleMouseDragged(x, y, button, dx, dy);
    }

    @Override
    public final boolean mouseReleased(double x, double y, int button) {
        return handleMouseReleased(x, y, button);
    }

    @Override
    public final boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        return handleMouseScrolled(x, y, horizontal, vertical);
    }

    @Override
    public final boolean charTyped(char character, int modifiers) {
        return handleCharTyped(character, modifiers);
    }

    @Override
    public final boolean keyPressed(int key, int scanCode, int modifiers) {
        return handleKeyPressed(key, scanCode, modifiers);
    }
}
