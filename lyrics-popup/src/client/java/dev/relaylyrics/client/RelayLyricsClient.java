package dev.relaylyrics.client;

import dev.relaylyrics.FanDroidMod;
import dev.relaylyrics.client.music.MusicService;
import dev.relaylyrics.client.ui.LyricsConfigScreen;
import dev.relaylyrics.client.ui.LyricsOverlay;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

public final class RelayLyricsClient implements ClientModInitializer {
    private static ClientPreferences preferences;
    private static LyricsOverlay overlay;
    private static MusicService music;
    private static boolean disabledByRelay;

    public static ClientPreferences preferences() {
        if (preferences == null) preferences = ClientPreferences.load();
        return preferences;
    }

    public static LyricsOverlay overlay() { return overlay; }
    public static MusicService music() { return music; }
    public static boolean disabledByRelay() { return disabledByRelay; }

    @Override
    public void onInitializeClient() {
        if (FabricLoader.getInstance().isModLoaded("fandroid")) {
            disabledByRelay = true;
            FanDroidMod.LOGGER.warn("Relay is installed, so its built-in lyrics popup will be used instead");
            return;
        }

        LyricsOverlay.initializeRenderingBackend();
        CompatibilityManager.initialize();

        overlay = new LyricsOverlay(preferences());
        music = new MusicService(overlay, preferences());
        GoogleFontManager.restore(preferences().getGoogleFontFamily(),
                preferences().getLyricsFontWeight().cssWeight(), overlay::fontChanged);
        InventoryLyricsInteraction.register();
        WorldLyricsRenderHook.register();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            overlay.tick();
            InventoryLyricsController.tick(client);
        });
        // Keep the normal HUD pass as a fallback. Inventory screens receive a
        // dedicated non-blurred pass after their own rendering. Do not render
        // both passes in handled screens: they would advance the cover-hover
        // animation once toward closed and once toward open every frame.
        HudRenderCallback.EVENT.register((context, tickCounter) -> {
            var screen = MinecraftClient.getInstance().currentScreen;
            if (screen == null) {
                InventoryLyricsController.renderWorldHud(context);
            }
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            preferences().save();
            if (overlay != null) overlay.close();
            if (music != null) music.close();
        });
        FanDroidMod.LOGGER.info("Relay Lyrics standalone popup initialized");
    }
}
