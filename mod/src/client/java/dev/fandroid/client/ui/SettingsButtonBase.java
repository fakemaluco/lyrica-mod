package dev.fandroid.client.ui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/** Native focus, pointer, keyboard and narration with a version-neutral custom surface. */
abstract class SettingsButtonBase extends ClickableWidget {
    private final Runnable action;
    SettingsButtonBase(int x, int y, int width, int height, Text message, Runnable action) {
        super(x, y, width, height, message); this.action = action;
    }
    protected abstract void drawSurface(DrawContext context, int mouseX, int mouseY, float delta);
    @Override public final void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
        drawSurface(context, mouseX, mouseY, delta);
    }
    @Override public void onClick(double mouseX, double mouseY) { action.run(); }
    @Override public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (!active || !visible) return false;
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER || key == GLFW.GLFW_KEY_SPACE) {
            playDownSound(MinecraftClient.getInstance().getSoundManager()); action.run(); return true;
        }
        return false;
    }
    @Override protected void appendClickableNarrations(NarrationMessageBuilder builder) { appendDefaultNarrations(builder); }
}
