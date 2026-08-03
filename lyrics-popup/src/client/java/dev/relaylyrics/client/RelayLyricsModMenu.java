package dev.relaylyrics.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.relaylyrics.client.ui.LyricsConfigScreen;

public final class RelayLyricsModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return LyricsConfigScreen::new;
    }
}
