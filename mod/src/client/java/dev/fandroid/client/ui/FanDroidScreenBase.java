package dev.fandroid.client.ui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/** Maps the 1.21.1 primitive input callbacks to FanDroid's stable input surface. */
abstract class FanDroidScreenBase extends Screen {
    protected FanDroidScreenBase(Text title) {
        super(title);
    }

    /** 1.21.1 does not render the screen background from {@link Screen#render}. */
    protected final void renderVanillaScreen(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
    }

    /** Blur the scene already drawn behind the settings plate using the game's menu blur. */
    protected final void blurSettingsBackdrop(DrawContext context, float delta) {
        if (client.options.getMenuBackgroundBlurrinessValue() < 1) return;
        context.draw();
        // Legacy post passes may reuse their cached blend state after the GUI changes it.
        RenderSystem.disableBlend();
        applyBlur(delta);
        RenderSystem.defaultBlendFunc();
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
    public final boolean mouseClicked(double x, double y, int button) {
        x = pointerX(x); y = pointerY(y);
        return handleMouseClicked(x, y, button) || super.mouseClicked(x, y, button);
    }

    @Override
    public final boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        x = pointerX(x); y = pointerY(y); dx /= inputScale(); dy /= inputScale();
        return handleMouseDragged(x, y, button, dx, dy)
                || super.mouseDragged(x, y, button, dx, dy);
    }

    @Override
    public final boolean mouseReleased(double x, double y, int button) {
        x = pointerX(x); y = pointerY(y);
        return handleMouseReleased(x, y, button) || super.mouseReleased(x, y, button);
    }

    @Override
    public final boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        x = pointerX(x); y = pointerY(y);
        return handleMouseScrolled(x, y, horizontal, vertical)
                || super.mouseScrolled(x, y, horizontal, vertical);
    }

    @Override
    public final boolean charTyped(char character, int modifiers) {
        return handleCharTyped(character, modifiers) || super.charTyped(character, modifiers);
    }

    @Override
    public final boolean keyPressed(int key, int scanCode, int modifiers) {
        return handleKeyPressed(key, scanCode, modifiers)
                || super.keyPressed(key, scanCode, modifiers);
    }
}
