package dev.relaylyrics.client;

import dev.relaylyrics.client.ui.LyricsConfigScreen;
import dev.relaylyrics.client.ui.UpdateIslandScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

final class SettingsKeyBinding {
    private SettingsKeyBinding() { }

    static void register() {
        var category = KeyBinding.Category.create(Identifier.of("relaylyrics", "settings"));
        var settings = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.relaylyrics.settings", GLFW.GLFW_KEY_L, category));
        var update = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.relaylyrics.update", GLFW.GLFW_KEY_U, category));
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (update.wasPressed() && client.player != null && client.currentScreen == null && client.getOverlay() == null
                    && RelayLyricsClient.music() != null && RelayLyricsClient.music().support().update() != null) {
                UpdateIslandScreen.open(null);
                return;
            }
            if (!settings.wasPressed() || client.player == null || client.currentScreen != null
                    || client.getOverlay() != null) return;
            var advancements = client.options.advancementsKey;
            if (settings.equals(advancements)) while (advancements.wasPressed()) { }
            client.setScreen(new LyricsConfigScreen(null));
        });
    }
}
