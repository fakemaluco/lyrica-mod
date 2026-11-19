package dev.relaylyrics.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.relaylyrics.client.ui.LyricsConfigScreen;
import dev.relaylyrics.client.ui.UpdateIslandScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

final class SettingsKeyBinding {
    private SettingsKeyBinding() { }

    static void register() {
        var category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("relaylyrics", "settings"));
        var settings = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.relaylyrics.settings", InputConstants.KEY_L, category));
        var update = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.relaylyrics.update", InputConstants.KEY_U, category));
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (update.consumeClick() && client.player != null && client.gui.screen() == null && client.gui.overlay() == null
                    && RelayLyricsClient.music() != null && RelayLyricsClient.music().support().update() != null) {
                UpdateIslandScreen.open(null);
                return;
            }
            if (!settings.consumeClick() || client.player == null || client.gui.screen() != null
                    || client.gui.overlay() != null) return;
            var advancements = client.options.keyAdvancements;
            if (settings.same(advancements)) while (advancements.consumeClick()) { }
            client.gui.setScreen(new LyricsConfigScreen(null));
        });
    }
}
