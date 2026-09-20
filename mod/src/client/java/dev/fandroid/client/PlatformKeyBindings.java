package dev.fandroid.client;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import org.lwjgl.glfw.GLFW;

/** Minecraft 1.21.1 key binding construction. */
final class PlatformKeyBindings {
    private PlatformKeyBindings() { }

    static KeyBinding registerOpenPhone() {
        return KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.fandroid.open_phone", GLFW.GLFW_KEY_LEFT_ALT, "category.fandroid"));
    }
}
