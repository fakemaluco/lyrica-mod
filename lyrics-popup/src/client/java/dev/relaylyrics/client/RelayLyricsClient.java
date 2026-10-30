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
    private static boolean meshProbeArmed;
    private static int meshProbeTicks;
    private static boolean effectsProbeArmed;
    private static int effectsProbeTicks;

    public static ClientPreferences preferences() {
        if (preferences == null) preferences = Boolean.getBoolean("lyrica.worldExperienceSelfTest") ? new ClientPreferences() : ClientPreferences.load();
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
        if (!Boolean.getBoolean("lyrica.worldExperienceSelfTest") && !Boolean.getBoolean("lyrica.worldMeshSelfTest") && !Boolean.getBoolean("lyrica.effectsSelfTest") && !Boolean.getBoolean("lyrica.configSelfTest") && !Boolean.getBoolean("lyrica.motionArtworkSelfTest"))
            music = new MusicService(overlay, preferences());
        GoogleFontManager.restore(preferences().getGoogleFontFamily(),
                preferences().getLyricsFontWeight().cssWeight(), overlay::fontChanged);
        InventoryLyricsInteraction.register();
        WorldLyricsRenderHook.register();
        net.fabricmc.fabric.api.resource.ResourceManagerHelper.get(net.minecraft.resource.ResourceType.CLIENT_RESOURCES)
                .registerReloadListener(new net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener() {
                    public net.minecraft.util.Identifier getFabricId() { return net.minecraft.util.Identifier.of("relaylyrics", "world_mesh"); }
                    public void reload(net.minecraft.resource.ResourceManager manager) {
                        MinecraftClient.getInstance().execute(() -> {
                            dev.relaylyrics.client.ui.WorldTextMesh.clear();
                            overlay.fontChanged();
                        });
                    }
                });
        if (Boolean.getBoolean("lyrica.worldMeshSelfTest"))
            ClientLifecycleEvents.CLIENT_STARTED.register(client -> meshProbeArmed = true);
        if (Boolean.getBoolean("lyrica.effectsSelfTest"))
            ClientLifecycleEvents.CLIENT_STARTED.register(client -> effectsProbeArmed = true);
        if (Boolean.getBoolean("lyrica.configSelfTest"))
            ClientLifecycleEvents.CLIENT_STARTED.register(client -> effectsProbeArmed = true);
        if (Boolean.getBoolean("lyrica.motionArtworkSelfTest"))
            ClientLifecycleEvents.CLIENT_STARTED.register(client -> effectsProbeArmed = true);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (effectsProbeArmed && client.getOverlay() == null && ++effectsProbeTicks >= 10) {
                effectsProbeArmed = false;
                client.options.getGuiScale().setValue(1);
                client.onResolutionChanged();
                if (Boolean.getBoolean("lyrica.motionArtworkSelfTest")) dev.relaylyrics.client.ui.MotionArtworkProbe.start();
                else if (Boolean.getBoolean("lyrica.configSelfTest")) dev.relaylyrics.client.ui.LyricsConfigProbe.start();
                else client.setScreen(new dev.relaylyrics.client.ui.LyricsEffectsProbe());
            }
            if (meshProbeArmed && client.getOverlay() == null && ++meshProbeTicks >= 10) {
                meshProbeArmed = false;
                try {
                    dev.relaylyrics.client.ui.WorldTextMesh.verifyFontCapture();
                    LyricsConfigScreen.verifyWorldTabVisibility();
                }
                finally { client.scheduleStop(); }
            }
            overlay.tick();
            dev.relaylyrics.client.ui.WorldLyricsProbe.tick(client);
            dev.relaylyrics.client.ui.WorldLyricsRenderer.tickPlayerMotion(client);
            dev.relaylyrics.client.ui.WorldTextMesh.tick();
            dev.relaylyrics.client.music.WorldLyricsLights.tick(client.world, System.nanoTime(),
                    preferences().isWorldLyrics() && preferences().isLyricsVisible() && preferences().isWorldLyricsTerrainLight());
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
