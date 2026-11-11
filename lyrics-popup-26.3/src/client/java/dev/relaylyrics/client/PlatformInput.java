package dev.relaylyrics.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDLKeycode;
import org.lwjgl.sdl.SDLMouse;

public final class PlatformInput {
    public static final int CONTROL = SDLKeycode.SDL_KMOD_CTRL;
    public static final int SHIFT = SDLKeycode.SDL_KMOD_SHIFT;

    private static final Object QUEUE_SEARCH = new Object();

    public static void queueTextInput(boolean focused) {
        var input = Minecraft.getInstance().textInputManager();
        if (focused) input.startTextInput(QUEUE_SEARCH);
        else input.stopTextInput(QUEUE_SEARCH);
    }

    private PlatformInput() { }

    public static boolean controlDown() { return InputConstants.isKeyDown(InputConstants.KEY_LCONTROL) || InputConstants.isKeyDown(InputConstants.KEY_RCONTROL); }
    public static boolean shiftDown() { return InputConstants.isKeyDown(InputConstants.KEY_LSHIFT) || InputConstants.isKeyDown(InputConstants.KEY_RSHIFT); }
    public static boolean shortcut(int modifiers) { return (modifiers & (CONTROL | SDLKeycode.SDL_KMOD_GUI)) != 0; }
    public static boolean shift(int modifiers) { return (modifiers & SHIFT) != 0; }
    public static boolean leftHeld() { return (SDLMouse.nSDL_GetMouseState(0, 0) & SDLMouse.SDL_BUTTON_LMASK) != 0; }
}
