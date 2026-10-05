package dev.relaylyrics.client.ui;

import dev.relaylyrics.client.ClientPreferences;
import dev.relaylyrics.client.music.MusicService;
import dev.relaylyrics.client.music.SpotifyAccountClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Vanilla OAuth setup screen. Client secret is deliberately never requested or stored. */
public final class SpotifyAccountScreen extends FanDroidScreenBase {
    private final Screen parent;
    private final ClientPreferences preferences;
    private final MusicService music;
    private EditBox clientId;
    private Component status = Component.empty();
    private int statusColor = 0xFFA0A0A0;

    public SpotifyAccountScreen(Screen parent, ClientPreferences preferences, MusicService music) {
        super(Component.translatable("config.relaylyrics.spotify.title"));
        this.parent = parent;
        this.preferences = preferences;
        this.music = music;
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(460, width - 32);
        int left = (width - panelWidth) / 2;
        int fieldY = height / 2 - 38;
        clientId = new EditBox(font, left, fieldY, panelWidth, 20,
                Component.translatable("config.relaylyrics.spotify.client_id"));
        clientId.setMaxLength(80);
        clientId.setValue(preferences.getSpotifyClientId());
        addRenderableWidget(clientId);
        int buttonWidth = (panelWidth - 8) / 3;
        int buttonY = fieldY + 30;
        addRenderableWidget(Button.builder(Component.translatable("config.relaylyrics.spotify.connect"),
                ignored -> connect()).bounds(left, buttonY, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("config.relaylyrics.spotify.disconnect"),
                ignored -> disconnect()).bounds(left + buttonWidth + 4, buttonY, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), ignored -> onClose())
                .bounds(left + (buttonWidth + 4) * 2, buttonY, buttonWidth, 20).build());
    }

    private void connect() {
        String id = clientId.getValue().strip();
        preferences.setSpotifyClientId(id);
        preferences.save();
        status = Component.translatable("config.relaylyrics.spotify.opening");
        statusColor = 0xFFFFD37A;
        music.connectSpotifyAccount(id).whenComplete((link, error) -> {
            Minecraft client = Minecraft.getInstance();
            if (client == null) return;
            client.execute(() -> {
                if (error == null) {
                    client.keyboardHandler.setClipboard(link.toString());
                    status = Component.translatable("config.relaylyrics.spotify.waiting");
                    statusColor = 0xFF73E49A;
                } else {
                    status = Component.translatable("config.relaylyrics.spotify.failed");
                    statusColor = 0xFFFF6B6B;
                }
            });
        });
    }

    private void disconnect() {
        music.disconnectSpotifyAccount();
        status = Component.translatable("config.relaylyrics.spotify.disconnected");
        statusColor = 0xFFA0A0A0;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        renderVanillaScreen(context, mouseX, mouseY, delta);
        drawCentered(context, title, height / 2 - 92, 0xFFFFFFFF);
        drawCentered(context, Component.translatable("config.relaylyrics.spotify.hint"),
                height / 2 - 72, 0xFFB7B7B7);
        drawCentered(context, Component.literal(SpotifyAccountClient.REDIRECT_URI),
                height / 2 - 57, 0xFF8FCAD4);
        String liveStatus = music.spotifyAccountStatus();
        Component shown = status.getString().isBlank() ? Component.literal(liveStatus) : status;
        if (!shown.getString().isBlank()) drawCentered(context, shown, height / 2 + 25, statusColor);
    }

    private void drawCentered(GuiGraphicsExtractor context, Component text, int y, int color) {
        context.text(font, text, (width - font.width(text)) / 2, y, color);
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { Minecraft.getInstance().gui.setScreen(parent); }
}
