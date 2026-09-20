package dev.fandroid.client;

import dev.fandroid.FanDroidMod;
import dev.fandroid.client.ui.FanDroidScreen;
import dev.fandroid.client.ui.LyricsOverlay;
import dev.fandroid.client.ui.CallHudOverlay;
import dev.fandroid.client.ui.NotificationHudOverlay;
import dev.fandroid.client.music.MusicService;
import dev.fandroid.network.CallSignalPayload;
import dev.fandroid.network.CapabilitiesPayload;
import dev.fandroid.network.ServerDirectoryPayload;
import dev.fandroid.network.ServerEnvelopePayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.option.KeyBinding;

public final class FanDroidClient implements ClientModInitializer {
    private static final ClientRuntime RUNTIME = new ClientRuntime();
    private static final LyricsOverlay LYRICS = new LyricsOverlay(RUNTIME.preferences());
    private static final MusicService MUSIC = new MusicService(LYRICS, RUNTIME.preferences());

    public static ClientRuntime runtime() {
        return RUNTIME;
    }

    public static LyricsOverlay lyrics() {
        return LYRICS;
    }

    public static MusicService music() {
        return MUSIC;
    }

    @Override
    public void onInitializeClient() {
        LyricsOverlay.initializeRenderingBackend();
        CompatibilityManager.initialize();
        KeyBinding openPhone = PlatformKeyBindings.registerOpenPhone();
        GoogleFontManager.restore(RUNTIME.preferences().getGoogleFontFamily(),
                RUNTIME.preferences().getLyricsFontWeight().cssWeight(), LYRICS::fontChanged);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            RUNTIME.initializeForSession(client);
            while (openPhone.wasPressed()) {
                if (client.currentScreen == null) {
                    if (RUNTIME.incomingCall() != null) RUNTIME.answerCall();
                    client.setScreen(new FanDroidScreen(RUNTIME, LYRICS));
                } else if (client.currentScreen instanceof FanDroidScreen) {
                    client.setScreen(null);
                }
            }
            RUNTIME.tick();
            LYRICS.tick();
        });
        HudRenderCallback.EVENT.register((drawContext, tickCounter) -> {
            LYRICS.renderHud(drawContext);
            NotificationHudOverlay.render(drawContext, RUNTIME);
            CallHudOverlay.render(drawContext, RUNTIME);
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> RUNTIME.connected());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> RUNTIME.disconnected());
        ClientPlayNetworking.registerGlobalReceiver(CapabilitiesPayload.ID, (payload, context) ->
                RUNTIME.setServerCapabilities(payload.flags()));
        ClientPlayNetworking.registerGlobalReceiver(ServerDirectoryPayload.ID, (payload, context) ->
                RUNTIME.updateDirectory(payload));
        ClientPlayNetworking.registerGlobalReceiver(ServerEnvelopePayload.ID, (payload, context) ->
                RUNTIME.receiveServerEnvelope(payload));
        ClientPlayNetworking.registerGlobalReceiver(CallSignalPayload.ID, (payload, context) ->
                RUNTIME.receiveCallSignal(payload));

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            LYRICS.close();
            MUSIC.close();
            RUNTIME.close();
        });
        FanDroidMod.LOGGER.info("FanDroid client initialized");
    }
}
