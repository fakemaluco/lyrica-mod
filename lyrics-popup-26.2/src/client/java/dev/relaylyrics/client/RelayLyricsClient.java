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
    private static boolean effectsProbeArmed;
    private static int effectsProbeTicks;

    public static ClientPreferences preferences() {
        if (preferences == null) preferences = (Boolean.getBoolean("lyrica.worldExperienceSelfTest") || Boolean.getBoolean("lyrica.geminiWorldSelfTest")) ? new ClientPreferences() : ClientPreferences.load();
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
        if (!Boolean.getBoolean("lyrica.geminiWorldSelfTest") && !Boolean.getBoolean("lyrica.worldExperienceSelfTest") && !Boolean.getBoolean("lyrica.worldMeshSelfTest") && !Boolean.getBoolean("lyrica.effectsSelfTest") && !Boolean.getBoolean("lyrica.configSelfTest") && !Boolean.getBoolean("lyrica.motionArtworkSelfTest"))
            music = new MusicService(overlay, preferences());
        GoogleFontManager.restore(preferences().getGoogleFontFamily(),
                preferences().getLyricsFontWeight().cssWeight(), overlay::fontChanged);
        InventoryLyricsInteraction.register();
        MusicPresence.initialize();
        dev.relaylyrics.client.karaoke.KaraokeClient.initialize();
        WorldLyricsRenderHook.register();
        net.fabricmc.fabric.api.resource.ResourceManagerHelper.get(net.minecraft.server.packs.PackType.CLIENT_RESOURCES)
                .registerReloadListener(new net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener() {
                    public net.minecraft.resources.Identifier getFabricId() { return net.minecraft.resources.Identifier.fromNamespaceAndPath("relaylyrics", "world_mesh"); }
                    public void onResourceManagerReload(net.minecraft.server.packs.resources.ResourceManager manager) {
                        Minecraft.getInstance().execute(() -> {
                            dev.relaylyrics.client.ui.WorldTextMesh.clear();
                            overlay.fontChanged();
                            dev.relaylyrics.client.music.WorldGeminiRenderer.close();
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
            if (effectsProbeArmed && client.gui.overlay() == null && ++effectsProbeTicks >= 10) {
                effectsProbeArmed = false;
                client.options.guiScale().set(1);
                client.resizeGui();
                if (Boolean.getBoolean("lyrica.motionArtworkSelfTest")) dev.relaylyrics.client.ui.MotionArtworkProbe.start();
                else if (Boolean.getBoolean("lyrica.configSelfTest")) dev.relaylyrics.client.ui.LyricsConfigProbe.start();
                else client.gui.setScreen(new dev.relaylyrics.client.ui.LyricsEffectsProbe());
            }
            if (meshProbeArmed && client.gui.overlay() == null && ++meshProbeTicks >= 10) {
                meshProbeArmed = false;
                try {
                    dev.relaylyrics.client.ui.WorldTextMesh.verifyFontCapture();
                    LyricsConfigScreen.verifyWorldTabVisibility();
                }
                finally { client.stop(); }
            }
            dev.relaylyrics.client.ui.JadeIslandProbe.tick(client);
            dev.relaylyrics.client.ui.GeminiWorldProbe.tick();
            overlay.tick();
            if (music != null) {
                dev.relaylyrics.client.compat.JiAfkPlayback.tick(music, client.level != null);
                music.tickAudioDucking(client.level != null);
                music.tickGeminiShaders();
                if (client.player != null) {
                    String version = music.support().takeNotification();
                    if (!version.isBlank()) client.gui.hud.getChat().addClientSystemMessage(net.minecraft.network.chat.Component.translatable("config.relaylyrics.updates.notification", version));
                }
            }
            dev.relaylyrics.client.karaoke.KaraokeClient.tick();
            MusicPresence.tick();
            dev.relaylyrics.client.ui.WorldLyricsProbe.tick(client);
            dev.relaylyrics.client.ui.WorldLyricsRenderer.tickPlayerMotion(client);
            dev.relaylyrics.client.ui.WorldTextMesh.tick();
            dev.relaylyrics.client.music.WorldLyricsLights.tick(client.level, System.nanoTime(),
                    preferences().isWorldLyrics() && preferences().isLyricsVisible() && preferences().isWorldLyricsTerrainLight());
            InventoryLyricsController.tick(client);
        });
        // Keep the normal HUD pass as a fallback. Inventory screens receive a
        // dedicated non-blurred pass after their own rendering. Do not render
        // both passes in handled screens: they would advance the cover-hover
        // animation once toward closed and once toward open every frame.
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("relaylyrics", "lyrics"), (context, tickCounter) -> {
            dev.relaylyrics.client.karaoke.KaraokePlatform.paintPopups();
            var screen = Minecraft.getInstance().gui.screen();
            if (screen == null) {
                InventoryLyricsController.renderWorldHud(context);
                dev.relaylyrics.client.ui.GeminiWorldProbe.hud(context);
                JadeIsland.render(context);
            }
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            dev.relaylyrics.client.karaoke.KaraokeClient.clear();
            preferences().save();
            if (overlay != null) overlay.close();
            MusicPresence.close();
            if (music != null) music.close();
            dev.relaylyrics.client.music.WorldGeminiRenderer.close();
        });
        FanDroidMod.LOGGER.info("Relay Lyrics standalone popup initialized");
    }
}
