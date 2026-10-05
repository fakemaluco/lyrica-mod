package dev.relaylyrics.client;

import dev.relaylyrics.FanDroidMod;
import dev.relaylyrics.client.music.MusicService;
import dev.relaylyrics.client.ui.LyricsConfigScreen;
import dev.relaylyrics.client.ui.LyricsOverlay;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

public final class RelayLyricsClient implements ClientModInitializer {
    private static ClientPreferences preferences;
    private static LyricsOverlay overlay;
    private static MusicService music;
    private static boolean disabledByRelay;
    private static boolean meshProbeArmed;
    private static int meshProbeTicks;

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
        if (!Boolean.getBoolean("lyrica.worldMeshSelfTest")) music = new MusicService(overlay, preferences());
        GoogleFontManager.restore(preferences().getGoogleFontFamily(),
                preferences().getLyricsFontWeight().cssWeight(), overlay::fontChanged);
        InventoryLyricsInteraction.register();
        WorldLyricsRenderHook.register();
        net.fabricmc.fabric.api.resource.ResourceManagerHelper.get(net.minecraft.server.packs.PackType.CLIENT_RESOURCES)
                .registerReloadListener(new net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener() {
                    public net.minecraft.resources.Identifier getFabricId() { return net.minecraft.resources.Identifier.fromNamespaceAndPath("relaylyrics", "world_mesh"); }
                    public void onResourceManagerReload(net.minecraft.server.packs.resources.ResourceManager manager) {
                        Minecraft.getInstance().execute(() -> {
                            dev.relaylyrics.client.ui.WorldTextMesh.clear();
                            overlay.fontChanged();
                        });
                    }
                });
        if (Boolean.getBoolean("lyrica.worldMeshSelfTest"))
            ClientLifecycleEvents.CLIENT_STARTED.register(client -> meshProbeArmed = true);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (meshProbeArmed && client.gui.overlay() == null && ++meshProbeTicks >= 10) {
                meshProbeArmed = false;
                try {
                    dev.relaylyrics.client.ui.WorldTextMesh.verifyFontCapture();
                    LyricsConfigScreen.verifyWorldTabVisibility();
                }
                finally { client.stop(); }
            }
            overlay.tick();
            dev.relaylyrics.client.ui.WorldLyricsRenderer.tickPlayerMotion(client);
            dev.relaylyrics.client.ui.WorldTextMesh.tick();
            InventoryLyricsController.tick(client);
        });
        // Keep the normal HUD pass as a fallback. Inventory screens receive a
        // dedicated non-blurred pass after their own rendering. Do not render
        // both passes in handled screens: they would advance the cover-hover
        // animation once toward closed and once toward open every frame.
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("relaylyrics", "lyrics"), (context, tickCounter) -> {
            var screen = Minecraft.getInstance().gui.screen();
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
