package dev.relaylyrics.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

public final class PlatformInput {
    public static final int CONTROL = GLFW.GLFW_MOD_CONTROL;
    public static final int SHIFT = GLFW.GLFW_MOD_SHIFT;

    public static void queueTextInput(boolean focused) { }

    private PlatformInput() { }

    public static boolean controlDown() { return down(InputConstants.KEY_LCONTROL) || down(InputConstants.KEY_RCONTROL); }
    public static boolean shiftDown() { return down(InputConstants.KEY_LSHIFT) || down(InputConstants.KEY_RSHIFT); }
    public static boolean shortcut(int modifiers) { return (modifiers & (CONTROL | GLFW.GLFW_MOD_SUPER)) != 0; }
    public static boolean shift(int modifiers) { return (modifiers & SHIFT) != 0; }
    public static boolean leftHeld() {
        return GLFW.glfwGetMouseButton(Minecraft.getInstance().getWindow().handle(), InputConstants.MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
    }
    private static boolean down(int key) { return InputConstants.isKeyDown(Minecraft.getInstance().getWindow(), key); }
}
