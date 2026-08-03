package dev.fandroid.client;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

/** Minecraft 1.21.11 key binding construction. */
final class PlatformKeyBindings {
    private static final KeyBinding.Category CATEGORY = KeyBinding.Category.create(
            Identifier.of("fandroid", "general"));

    private PlatformKeyBindings() { }

    static KeyBinding registerOpenPhone() {
        return KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.fandroid.open_phone", GLFW.GLFW_KEY_LEFT_ALT, CATEGORY));
    }
}
