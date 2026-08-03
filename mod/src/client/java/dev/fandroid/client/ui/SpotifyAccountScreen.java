package dev.fandroid.client.ui;

import dev.fandroid.client.ClientPreferences;
import dev.fandroid.client.music.MusicService;
import dev.fandroid.client.music.SpotifyAccountClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/** Vanilla OAuth setup screen. Client secret is deliberately never requested or stored. */
public final class SpotifyAccountScreen extends Screen {
    private final Screen parent;
    private final ClientPreferences preferences;
    private final MusicService music;
    private TextFieldWidget clientId;
    private Text status = Text.empty();
    private int statusColor = 0xFFA0A0A0;

    public SpotifyAccountScreen(Screen parent, ClientPreferences preferences, MusicService music) {
        super(Text.translatable("config.fandroid.spotify.title"));
        this.parent = parent;
        this.preferences = preferences;
        this.music = music;
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(460, width - 32);
        int left = (width - panelWidth) / 2;
        int fieldY = height / 2 - 38;
        clientId = new TextFieldWidget(textRenderer, left, fieldY, panelWidth, 20,
                Text.translatable("config.fandroid.spotify.client_id"));
        clientId.setMaxLength(80);
        clientId.setText(preferences.getSpotifyClientId());
        clientId.setTextPredicate(value -> value.matches("[A-Za-z0-9]*"));
        addDrawableChild(clientId);
        int buttonWidth = (panelWidth - 8) / 3;
        int buttonY = fieldY + 30;
        addDrawableChild(ButtonWidget.builder(Text.translatable("config.fandroid.spotify.connect"),
                ignored -> connect()).dimensions(left, buttonY, buttonWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("config.fandroid.spotify.disconnect"),
                ignored -> disconnect()).dimensions(left + buttonWidth + 4, buttonY, buttonWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), ignored -> close())
                .dimensions(left + (buttonWidth + 4) * 2, buttonY, buttonWidth, 20).build());
    }

    private void connect() {
        String id = clientId.getText().strip();
        preferences.setSpotifyClientId(id);
        preferences.save();
        status = Text.translatable("config.fandroid.spotify.opening");
        statusColor = 0xFFFFD37A;
        music.connectSpotifyAccount(id).whenComplete((link, error) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null) return;
            client.execute(() -> {
                if (error == null) {
                    client.keyboard.setClipboard(link.toString());
                    status = Text.translatable("config.fandroid.spotify.waiting");
                    statusColor = 0xFF73E49A;
                } else {
                    status = Text.translatable("config.fandroid.spotify.failed");
                    statusColor = 0xFFFF6B6B;
                }
            });
        });
    }

    private void disconnect() {
        music.disconnectSpotifyAccount();
        status = Text.translatable("config.fandroid.spotify.disconnected");
        statusColor = 0xFFA0A0A0;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
        drawCentered(context, title, height / 2 - 92, 0xFFFFFFFF);
        drawCentered(context, Text.translatable("config.fandroid.spotify.hint"),
                height / 2 - 72, 0xFFB7B7B7);
        drawCentered(context, Text.literal(SpotifyAccountClient.REDIRECT_URI),
                height / 2 - 57, 0xFF8FCAD4);
        String liveStatus = music.spotifyAccountStatus();
        Text shown = status.getString().isBlank() ? Text.literal(liveStatus) : status;
        if (!shown.getString().isBlank()) drawCentered(context, shown, height / 2 + 25, statusColor);
    }

    private void drawCentered(DrawContext context, Text text, int y, int color) {
        context.drawTextWithShadow(textRenderer, text, (width - textRenderer.getWidth(text)) / 2, y, color);
    }

    @Override public boolean shouldPause() { return false; }
    @Override public void close() { MinecraftClient.getInstance().setScreen(parent); }
}
