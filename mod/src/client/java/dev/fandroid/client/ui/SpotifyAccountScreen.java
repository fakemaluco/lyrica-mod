package dev.fandroid.client.ui;

import dev.fandroid.client.ClientPreferences;
import dev.fandroid.client.music.MusicService;
import dev.fandroid.client.music.SpotifyAccountClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

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
    private TextFieldWidget clientId;
    private ButtonWidget connectButton, disconnectButton, reopenButton;
    private URI authorizationLink;
    private Text notice = Text.empty();
    private boolean opening;
    private int left, panelWidth, bodyTop, bodyBottom, contentHeight, scroll, statusY;

    public SpotifyAccountScreen(Screen parent, ClientPreferences preferences, MusicService music) {
        super(Text.translatable("config.fandroid.spotify.title"));
        this.parent = parent;
        this.preferences = preferences;
        this.music = music;
    }

    @Override protected void init() {
        String pendingId = clientId == null ? preferences.getSpotifyClientId() : clientId.getText();
        clearChildren();
        labels.clear(); controls.clear();
        panelWidth = Math.min(560, Math.max(180, width - 32));
        left = (width - panelWidth) / 2;
        bodyTop = 62; bodyBottom = height - 40;
        int y = label("config.fandroid.spotify.setup.intro", 0, 0xFFBFC9D9);
        y = label("config.fandroid.spotify.setup.application", y + 10, 0xFFFFFFFF);
        addButton("config.fandroid.spotify.setup.dashboard", left, y, panelWidth,
                () -> browse(URI.create("https://developer.spotify.com/dashboard")));
        y = label("config.fandroid.spotify.setup.redirect", y + 32, 0xFFFFFFFF);
        addButton(SpotifyAccountClient.REDIRECT_URI, true, left, y, panelWidth, () -> {
            MinecraftClient.getInstance().keyboard.setClipboard(SpotifyAccountClient.REDIRECT_URI);
            notice = Text.translatable("config.fandroid.spotify.setup.redirect_copied");
        });
        y = label("config.fandroid.spotify.setup.client", y + 32, 0xFFFFFFFF);
        clientId = new TextFieldWidget(textRenderer, left, bodyTop + y, panelWidth, 20,
                Text.translatable("config.fandroid.spotify.client_id"));
        clientId.setMaxLength(80);
        clientId.setText(pendingId);
        clientId.setTextPredicate(value -> value.matches("[A-Za-z0-9]*"));
        addControl(clientId, y);
        int half = (panelWidth - 6) / 2;
        connectButton = addButton("config.fandroid.spotify.connect", left, y + 28, half, this::connect);
        disconnectButton = addButton("config.fandroid.spotify.disconnect", left + half + 6, y + 28,
                panelWidth - half - 6, this::disconnect);
        reopenButton = addButton("config.fandroid.spotify.setup.reopen", left, y + 54, panelWidth, () -> {
            if (authorizationLink != null) browse(authorizationLink);
        });
        y = label("config.fandroid.spotify.setup.browser_hint", y + 84, 0xFFBFC9D9);
        statusY = y + 12;
        contentHeight = statusY + 64;
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), ignored -> close())
                .dimensions((width - 120) / 2, height - 28, 120, 20).build());
        updateButtons(); positionControls();
    }

    private int label(String key, int y, int color) {
        Text text = Text.translatable(key);
        labels.add(new Label(text, y, color));
        return y + textRenderer.wrapLines(text, panelWidth).size() * 12;
    }
    private ButtonWidget addButton(String key, int x, int y, int w, Runnable action) {
        return addButton(key, false, x, y, w, action);
    }
    private ButtonWidget addButton(String value, boolean literal, int x, int y, int w, Runnable action) {
        ButtonWidget button = ButtonWidget.builder(literal ? Text.literal(value) : Text.translatable(value),
                ignored -> action.run()).dimensions(x, bodyTop + y, w, 20).build();
        addControl(button, y); return button;
    }
    private void addControl(ClickableWidget widget, int y) {
        controls.add(new PositionedWidget(widget, y)); addDrawableChild(widget);
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
                && clientId.getText().strip().matches("[A-Za-z0-9]{16,64}");
        disconnectButton.active = music != null && music.spotifyAccountConnected();
        reopenButton.active = authorizationLink != null;
    }
    @Override public void tick() { super.tick(); updateButtons(); }

    private void connect() {
        if (music == null || opening) return;
        String id = clientId.getText().strip();
        if (!id.matches("[A-Za-z0-9]{16,64}")) return;
        preferences.setSpotifyClientId(id); preferences.save();
        opening = true;
        notice = Text.translatable("config.fandroid.spotify.opening");
        music.connectSpotifyAccount(id).whenComplete((link, error) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null) return;
            client.execute(() -> {
                opening = false;
                if (error == null) {
                    authorizationLink = link;
                    client.keyboard.setClipboard(link.toString());
                    notice = Text.translatable("config.fandroid.spotify.waiting");
                } else notice = Text.translatable("config.fandroid.spotify.failed");
                updateButtons();
                scroll = Math.max(0, contentHeight - (bodyBottom - bodyTop));
                positionControls();
            });
        });
    }
    private void disconnect() {
        if (music != null) music.disconnectSpotifyAccount();
        authorizationLink = null;
        notice = Text.translatable("config.fandroid.spotify.disconnected");
        updateButtons();
    }
    private void browse(URI link) {
        MinecraftClient.getInstance().keyboard.setClipboard(link.toString());
        notice = Text.translatable("config.fandroid.spotify.setup.link_copied");
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
        if (key != GLFW.GLFW_KEY_PAGE_DOWN && key != GLFW.GLFW_KEY_PAGE_UP) return false;
        scroll += (key == GLFW.GLFW_KEY_PAGE_DOWN ? 1 : -1) * Math.max(28, bodyBottom - bodyTop - 24);
        positionControls(); return true;
    }
    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderVanillaScreen(context, mouseX, mouseY, delta);
        context.drawText(textRenderer, title, (width - textRenderer.getWidth(title)) / 2, 14, 0xFFFFFFFF, false);
        Text live = music == null ? Text.translatable("config.fandroid.offline")
                : Text.literal(music.spotifyAccountStatus());
        int color = music != null && music.spotifyAccountConnected() ? 0xFF73E49A : 0xFFE7D5A9;
        context.drawText(textRenderer, textRenderer.trimToWidth(live.getString(), panelWidth), left, 36, color, false);
        if (!notice.getString().isBlank()) context.drawText(textRenderer,
                textRenderer.trimToWidth(notice.getString(), panelWidth), left, 48, 0xFFBFC9D9, false);
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
    private int drawWrapped(DrawContext context, Text text, int y, int color) {
        for (var line : textRenderer.wrapLines(text, panelWidth)) {
            context.drawText(textRenderer, line, left, y, color, false); y += 12;
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
    private record Label(Text text, int y, int color) { }
    private record PositionedWidget(ClickableWidget widget, int y) { }
    @Override public boolean shouldPause() { return false; }
    @Override public void close() { MinecraftClient.getInstance().setScreen(parent); }
}
