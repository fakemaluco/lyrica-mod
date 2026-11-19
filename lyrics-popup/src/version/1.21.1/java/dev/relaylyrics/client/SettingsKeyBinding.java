package dev.relaylyrics.client;

import dev.relaylyrics.client.ui.LyricsConfigScreen;
import dev.relaylyrics.client.ui.UpdateIslandScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import org.lwjgl.glfw.GLFW;

final class SettingsKeyBinding {
    private SettingsKeyBinding() { }

    static void register() {
        var settings = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.relaylyrics.settings", GLFW.GLFW_KEY_L, "key.category.relaylyrics.settings"));
        var update = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.relaylyrics.update", GLFW.GLFW_KEY_U, "key.category.relaylyrics.settings"));
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (update.wasPressed() && client.player != null && client.currentScreen == null && client.getOverlay() == null
                    && RelayLyricsClient.music() != null && RelayLyricsClient.music().support().update() != null) {
                UpdateIslandScreen.open(null);
                return;
            }
            boolean pressed = settings.wasPressed();
            if (client.player == null || client.currentScreen != null || client.getOverlay() != null) return;
            var advancements = client.options.advancementsKey;
            // This version sends the press to only one binding when two actions share a key.
            if (settings.equals(advancements)) pressed = advancements.wasPressed() || pressed;
            if (pressed) client.setScreen(new LyricsConfigScreen(null));
        });
    }
}
