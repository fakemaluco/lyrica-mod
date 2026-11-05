package dev.relaylyrics.client.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Maps the 1.21.11 typed input records to FanDroid's stable input surface. */
abstract class FanDroidScreenBase extends Screen {
    protected FanDroidScreenBase(Component title) {
        super(title);
    }

    /** 26.2 extracts the background and widgets in the screen render pass. */
    protected final void renderVanillaScreen(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);
    }

    /** Blur the scene already drawn behind the settings plate using the game's menu blur. */
    protected final void blurSettingsBackdrop(GuiGraphicsExtractor context, float delta) {
        extractBlurredBackground(context);
    }

    protected double inputScale() { return 1; }
    protected double pointerX(double x) { return x / inputScale(); }
    protected double pointerY(double y) { return y / inputScale(); }

    protected boolean handleMouseClicked(double x, double y, int button) { return false; }
    protected boolean handleMouseDragged(double x, double y, int button, double dx, double dy) { return false; }
    protected boolean handleMouseReleased(double x, double y, int button) { return false; }
    protected boolean handleMouseScrolled(double x, double y, double horizontal, double vertical) { return false; }
    protected boolean handleCharTyped(char character, int modifiers) { return false; }
    protected boolean handleKeyPressed(int key, int scanCode, int modifiers) { return false; }

    @Override
    public final boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        click = new MouseButtonEvent(pointerX(click.x()), pointerY(click.y()), click.buttonInfo());
        return handleMouseClicked(click.x(), click.y(), click.button())
                || super.mouseClicked(click, doubled);
    }

    @Override
    public final boolean mouseDragged(MouseButtonEvent click, double dx, double dy) {
        click = new MouseButtonEvent(pointerX(click.x()), pointerY(click.y()), click.buttonInfo()); dx /= inputScale(); dy /= inputScale();
        return handleMouseDragged(click.x(), click.y(), click.button(), dx, dy)
                || super.mouseDragged(click, dx, dy);
    }

    @Override
    public final boolean mouseReleased(MouseButtonEvent click) {
        click = new MouseButtonEvent(pointerX(click.x()), pointerY(click.y()), click.buttonInfo());
        return handleMouseReleased(click.x(), click.y(), click.button())
                || super.mouseReleased(click);
    }

    @Override
    public final boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        x = pointerX(x); y = pointerY(y);
        return handleMouseScrolled(x, y, horizontal, vertical)
                || super.mouseScrolled(x, y, horizontal, vertical);
    }

    @Override
    public final boolean charTyped(CharacterEvent input) {
        String value = input.codepointAsString();
        if (value == null || value.isEmpty()) return false;
        boolean handled = false;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (Character.isBmpCodePoint(codePoint)) {
                handled |= handleCharTyped((char) codePoint, 0);
            }
            offset += Character.charCount(codePoint);
        }
        return handled || super.charTyped(input);
    }

    @Override
    public final boolean keyPressed(KeyEvent input) {
        return handleKeyPressed(input.key(), input.scancode(), input.modifiers())
                || super.keyPressed(input);
    }
}
