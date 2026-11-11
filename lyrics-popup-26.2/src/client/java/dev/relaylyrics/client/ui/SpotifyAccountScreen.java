package dev.relaylyrics.client.ui;

import dev.relaylyrics.client.ClientPreferences;
import dev.relaylyrics.client.music.MusicService;
import dev.relaylyrics.client.music.SpotifyAccountClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import com.mojang.blaze3d.platform.InputConstants;

import java.awt.Desktop;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Guided desktop OAuth setup. Credentials never change the selected playback source. */
public final class SpotifyAccountScreen extends FanDroidScreenBase {
    private final Screen parent;
    private final ClientPreferences preferences;
    private final MusicService music;
    private final List<Label> labels = new ArrayList<>();
    private final List<PositionedWidget> controls = new ArrayList<>();
    private EditBox clientId;
    private Button connectButton, disconnectButton, reopenButton;
    private URI authorizationLink;
    private Component notice = Component.empty();
    private boolean opening;
    private int left, panelWidth, bodyTop, bodyBottom, contentHeight, scroll, statusY;

    public SpotifyAccountScreen(Screen parent, ClientPreferences preferences, MusicService music) {
        super(Component.translatable("config.relaylyrics.spotify.title"));
        this.parent = parent;
        this.preferences = preferences;
        this.music = music;
    }

    @Override protected void init() {
        String pendingId = clientId == null ? preferences.getSpotifyClientId() : clientId.getValue();
        clearWidgets();
        labels.clear(); controls.clear();
        panelWidth = Math.min(560, Math.max(180, width - 32));
        left = (width - panelWidth) / 2;
        bodyTop = 62; bodyBottom = height - 40;
        int y = label("config.relaylyrics.spotify.setup.intro", 0, 0xFFBFC9D9);
        y = label("config.relaylyrics.spotify.setup.application", y + 10, 0xFFFFFFFF);
        addButton("config.relaylyrics.spotify.setup.dashboard", left, y, panelWidth,
                () -> browse(URI.create("https://developer.spotify.com/dashboard")));
        y = label("config.relaylyrics.spotify.setup.redirect", y + 32, 0xFFFFFFFF);
        addButton(SpotifyAccountClient.REDIRECT_URI, true, left, y, panelWidth, () -> {
            Minecraft.getInstance().keyboardHandler.setClipboard(SpotifyAccountClient.REDIRECT_URI);
            notice = Component.translatable("config.relaylyrics.spotify.setup.redirect_copied");
        });
        y = label("config.relaylyrics.spotify.setup.client", y + 32, 0xFFFFFFFF);
        clientId = new EditBox(font, left, bodyTop + y, panelWidth, 20,
                Component.translatable("config.relaylyrics.spotify.client_id"));
        clientId.setMaxLength(80);
        clientId.setValue(pendingId);
        addControl(clientId, y);
        int half = (panelWidth - 6) / 2;
        connectButton = addButton("config.relaylyrics.spotify.connect", left, y + 28, half, this::connect);
        disconnectButton = addButton("config.relaylyrics.spotify.disconnect", left + half + 6, y + 28,
                panelWidth - half - 6, this::disconnect);
        reopenButton = addButton("config.relaylyrics.spotify.setup.reopen", left, y + 54, panelWidth, () -> {
            if (authorizationLink != null) browse(authorizationLink);
        });
        y = label("config.relaylyrics.spotify.setup.browser_hint", y + 84, 0xFFBFC9D9);
        statusY = y + 12;
        contentHeight = statusY + 64;
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), ignored -> onClose())
                .bounds((width - 120) / 2, height - 28, 120, 20).build());
        updateButtons(); positionControls();
    }

    private int label(String key, int y, int color) {
        Component text = Component.translatable(key);
        labels.add(new Label(text, y, color));
        return y + font.split(text, panelWidth).size() * 12;
    }
    private Button addButton(String key, int x, int y, int w, Runnable action) {
        return addButton(key, false, x, y, w, action);
    }
    private Button addButton(String value, boolean literal, int x, int y, int w, Runnable action) {
        Button button = Button.builder(literal ? Component.literal(value) : Component.translatable(value),
                ignored -> action.run()).bounds(x, bodyTop + y, w, 20).build();
        addControl(button, y); return button;
    }
    private void addControl(AbstractWidget widget, int y) {
        controls.add(new PositionedWidget(widget, y)); addRenderableWidget(widget);
    }
    private void positionControls() {
        scroll = Math.clamp(scroll, 0, Math.max(0, contentHeight - (bodyBottom - bodyTop)));
        for (PositionedWidget control : controls) {
            int y = bodyTop + control.y - scroll;
            control.widget.setY(y);
            control.widget.visible = y >= bodyTop && y + control.widget.getHeight() <= bodyBottom;
        }
    }
    private void updateButtons() {
        connectButton.active = music != null && !opening
                && clientId.getValue().strip().matches("[A-Za-z0-9]{16,64}");
        disconnectButton.active = music != null && music.spotifyAccountConnected();
        reopenButton.active = authorizationLink != null;
    }
    @Override public void tick() { super.tick(); updateButtons(); }

    private void connect() {
        if (music == null || opening) return;
        String id = clientId.getValue().strip();
        if (!id.matches("[A-Za-z0-9]{16,64}")) return;
        preferences.setSpotifyClientId(id); preferences.save();
        opening = true;
        notice = Component.translatable("config.relaylyrics.spotify.opening");
        music.connectSpotifyAccount(id).whenComplete((link, error) -> {
            Minecraft client = Minecraft.getInstance();
            if (client == null) return;
            client.execute(() -> {
                opening = false;
                if (error == null) {
                    authorizationLink = link;
                    client.keyboardHandler.setClipboard(link.toString());
                    notice = Component.translatable("config.relaylyrics.spotify.waiting");
                } else notice = Component.translatable("config.relaylyrics.spotify.failed");
                updateButtons();
                scroll = Math.max(0, contentHeight - (bodyBottom - bodyTop));
                positionControls();
            });
        });
    }
    private void disconnect() {
        if (music != null) music.disconnectSpotifyAccount();
        authorizationLink = null;
        notice = Component.translatable("config.relaylyrics.spotify.disconnected");
        updateButtons();
    }
    private void browse(URI link) {
        Minecraft.getInstance().keyboardHandler.setClipboard(link.toString());
        notice = Component.translatable("config.relaylyrics.spotify.setup.link_copied");
        CompletableFuture.runAsync(() -> {
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
                    Desktop.getDesktop().browse(link);
            } catch (Exception ignored) { /* The clipboard remains usable in headless environments. */ }
        });
    }
    @Override protected boolean handleMouseScrolled(double x, double y, double h, double v) {
        if (y < bodyTop || y > bodyBottom) return false;
        scroll -= (int) Math.round(v * 28); positionControls(); return true;
    }
    @Override protected boolean handleKeyPressed(int key, int scanCode, int modifiers) {
        if (key != InputConstants.KEY_PAGEDOWN && key != InputConstants.KEY_PAGEUP) return false;
        scroll += (key == InputConstants.KEY_PAGEDOWN ? 1 : -1) * Math.max(28, bodyBottom - bodyTop - 24);
        positionControls(); return true;
    }
    @Override public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        renderVanillaScreen(context, mouseX, mouseY, delta);
        context.text(font, title, (width - font.width(title)) / 2, 14, 0xFFFFFFFF);
        Component live = music == null ? Component.translatable("config.relaylyrics.offline")
                : Component.literal(music.spotifyAccountStatus());
        int color = music != null && music.spotifyAccountConnected() ? 0xFF73E49A : 0xFFE7D5A9;
        context.text(font, font.plainSubstrByWidth(live.getString(), panelWidth), left, 36, color);
        if (!notice.getString().isBlank()) context.text(font,
                font.plainSubstrByWidth(notice.getString(), panelWidth), left, 48, 0xFFBFC9D9);
        context.enableScissor(left, bodyTop, left + panelWidth, bodyBottom);
        for (Label label : labels) drawWrapped(context, label.text, bodyTop + label.y - scroll, label.color);
        int noticeY = drawWrapped(context, live, bodyTop + statusY - scroll, color);
        if (!notice.getString().isBlank()) drawWrapped(context, notice, noticeY + 6, 0xFFBFC9D9);
        context.disableScissor();
        if (contentHeight > bodyBottom - bodyTop) {
            int available = bodyBottom - bodyTop;
            int thumb = Math.max(12, available * available / contentHeight);
            int y = bodyTop + scroll * (available - thumb) / Math.max(1, contentHeight - available);
            context.fill(left + panelWidth + 4, bodyTop, left + panelWidth + 6, bodyBottom, 0x404C586B);
            context.fill(left + panelWidth + 4, y, left + panelWidth + 6, y + thumb, 0xFF8797AD);
        }
    }
    private int drawWrapped(GuiGraphicsExtractor context, Component text, int y, int color) {
        for (var line : font.split(text, panelWidth)) {
            context.text(font, line, left, y, color); y += 12;
        }
        return y;
    }
    void verifyLayoutForProbe() {
        for (PositionedWidget control : controls) {
            if (control.widget.visible && (control.widget.getY() < bodyTop
                    || control.widget.getY() + control.widget.getHeight() > bodyBottom))
                throw new IllegalStateException("Spotify setup control outside viewport");
        }
        for (Label label : labels) {
            if (label.text.getString().startsWith("config."))
                throw new IllegalStateException("Untranslated Spotify setup label: " + label.text.getString());
        }
    }
    private record Label(Component text, int y, int color) { }
    private record PositionedWidget(AbstractWidget widget, int y) { }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { Minecraft.getInstance().gui.setScreen(parent); }
}
