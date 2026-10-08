package dev.fandroid.client.ui;

import dev.fandroid.client.ClientPreferences;
import dev.fandroid.client.MusicSource;
import dev.fandroid.client.music.MusicService;
import dev.fandroid.client.music.YouTubeMusicCompanion;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.awt.Desktop;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Browser companion onboarding; connection is local and does not require Google OAuth. */
public final class YouTubeMusicConnectionScreen extends FanDroidScreenBase {
    private final Screen parent;
    private final ClientPreferences preferences;
    private final MusicService music;
    private final List<Label> labels = new ArrayList<>();
    private final List<PositionedWidget> controls = new ArrayList<>();
    private Path installedDirectory;
    private Text notice = Text.empty();
    private ButtonWidget installButton, folderButton, pairingButton, sourceButton;
    private boolean installing;
    private int left, panelWidth, bodyTop, bodyBottom, contentHeight, scroll;

    public YouTubeMusicConnectionScreen(Screen parent, ClientPreferences preferences, MusicService music) {
        super(Text.translatable("config.fandroid.youtube.setup.title"));
        this.parent = parent; this.preferences = preferences; this.music = music;
    }
    @Override protected void init() {
        clearChildren();
        labels.clear(); controls.clear();
        panelWidth = Math.min(560, Math.max(180, width - 32));
        left = (width - panelWidth) / 2;
        bodyTop = 62; bodyBottom = height - 40;
        int y = label("config.fandroid.youtube.setup.intro", 0, 0xFFBFC9D9);
        y = label("config.fandroid.youtube.setup.install", y + 10, 0xFFFFFFFF);
        int half = (panelWidth - 6) / 2;
        installButton = addButton("config.fandroid.youtube.setup.prepare", left, y, half, this::install);
        folderButton = addButton("config.fandroid.youtube.setup.copy_folder", left + half + 6, y,
                panelWidth - half - 6, () -> {
                    if (installedDirectory != null) copy(installedDirectory.toString());
                });
        y = label("config.fandroid.youtube.setup.extensions", y + 32, 0xFFFFFFFF);
        addButton("config.fandroid.youtube.setup.chrome", left, y, half, () -> copy("chrome://extensions"));
        addButton("config.fandroid.youtube.setup.edge", left + half + 6, y, panelWidth - half - 6,
                () -> copy("edge://extensions"));
        y = label("config.fandroid.youtube.setup.load", y + 32, 0xFFBFC9D9);
        y = label("config.fandroid.youtube.setup.play", y + 10, 0xFFFFFFFF);
        addButton("config.fandroid.youtube.setup.open_music", left, y, half,
                () -> browse(URI.create("https://music.youtube.com/")));
        sourceButton = addButton("config.fandroid.youtube.setup.use_source", left + half + 6, y,
                panelWidth - half - 6, () -> {
                    preferences.setMusicSource(MusicSource.YOUTUBE_MUSIC); preferences.save();
                    if (music != null) music.syncMusicSource();
                    updateButtons();
                });
        y = label("config.fandroid.youtube.setup.pairing", y + 32, 0xFFBFC9D9);
        pairingButton = addButton("config.fandroid.youtube.setup.copy_code", left, y, panelWidth, () -> {
            if (music != null && music.pairingCode() != null) copy(music.pairingCode());
        });
        y = label("config.fandroid.youtube.setup.spicy", y + 32, 0xFFBFC9D9);
        addButton("config.fandroid.spotify.title", left, y, panelWidth,
                () -> MinecraftClient.getInstance().setScreen(new SpotifyAccountScreen(this, preferences, music)));
        contentHeight = y + 88;
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), ignored -> close())
                .dimensions((width - 120) / 2, height - 28, 120, 20).build());
        updateButtons(); positionControls();
    }
    private int label(String key, int y, int color) {
        Text text = Text.translatable(key); labels.add(new Label(text, y, color));
        return y + textRenderer.wrapLines(text, panelWidth).size() * 12;
    }
    private ButtonWidget addButton(String key, int x, int y, int w, Runnable action) {
        ButtonWidget button = ButtonWidget.builder(Text.translatable(key), ignored -> action.run())
                .dimensions(x, bodyTop + y, w, 20).build();
        controls.add(new PositionedWidget(button, y)); addDrawableChild(button); return button;
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
        installButton.active = !installing;
        folderButton.active = installedDirectory != null;
        pairingButton.active = music != null && music.pairingCode() != null && !music.pairingCode().isBlank();
        sourceButton.active = preferences.getMusicSource() != MusicSource.YOUTUBE_MUSIC;
    }
    @Override public void tick() { super.tick(); updateButtons(); }
    private void install() {
        if (installing) return;
        installing = true; updateButtons();
        CompletableFuture.supplyAsync(() -> {
            try { return YouTubeMusicCompanion.installAndOpen(); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
        }).whenComplete((directory, failure) -> MinecraftClient.getInstance().execute(() -> {
            installing = false;
            if (failure == null) {
                installedDirectory = directory;
                MinecraftClient.getInstance().keyboard.setClipboard(directory.toString());
                notice = Text.translatable("config.fandroid.youtube.setup.folder_ready");
            } else notice = Text.translatable("config.fandroid.youtube.guide_failed");
            updateButtons();
        }));
    }
    private void copy(String value) {
        MinecraftClient.getInstance().keyboard.setClipboard(value);
        notice = Text.translatable("config.fandroid.youtube.setup.copied");
    }
    private void browse(URI link) {
        copy(link.toString());
        CompletableFuture.runAsync(() -> {
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
                    Desktop.getDesktop().browse(link);
            } catch (Exception ignored) { /* The copied link remains available. */ }
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
        boolean ready = music != null && music.youtubeMusicConnected();
        Text live = Text.translatable(ready ? "config.fandroid.youtube.setup.ready"
                : "config.fandroid.youtube.setup.waiting");
        context.drawText(textRenderer, live, left, 36, ready ? 0xFF73E49A : 0xFFE7D5A9, false);
        if (!notice.getString().isBlank()) context.drawText(textRenderer,
                textRenderer.trimToWidth(notice.getString(), panelWidth), left, 48, 0xFFBFC9D9, false);
        context.enableScissor(left, bodyTop, left + panelWidth, bodyBottom);
        for (Label label : labels) drawWrapped(context, label.text, bodyTop + label.y - scroll, label.color);
        if (!notice.getString().isBlank()) drawWrapped(context, notice, bodyTop + contentHeight - 48 - scroll, 0xFFBFC9D9);
        context.disableScissor();
        if (contentHeight > bodyBottom - bodyTop) {
            int available = bodyBottom - bodyTop;
            int thumb = Math.max(12, available * available / contentHeight);
            int y = bodyTop + scroll * (available - thumb) / Math.max(1, contentHeight - available);
            context.fill(left + panelWidth + 4, bodyTop, left + panelWidth + 6, bodyBottom, 0x404C586B);
            context.fill(left + panelWidth + 4, y, left + panelWidth + 6, y + thumb, 0xFF8797AD);
        }
    }
    private void drawWrapped(DrawContext context, Text text, int y, int color) {
        for (var line : textRenderer.wrapLines(text, panelWidth)) {
            context.drawText(textRenderer, line, left, y, color, false); y += 12;
        }
    }
    private record Label(Text text, int y, int color) { }
    private record PositionedWidget(ClickableWidget widget, int y) { }
    void verifyLayoutForProbe() {
        for (PositionedWidget control : controls) {
            if (control.widget.visible && (control.widget.getY() < bodyTop
                    || control.widget.getY() + control.widget.getHeight() > bodyBottom))
                throw new IllegalStateException("YouTube Music setup control outside viewport");
        }
        for (Label label : labels) {
            if (label.text.getString().startsWith("config."))
                throw new IllegalStateException("Untranslated YouTube Music setup label: " + label.text.getString());
        }
    }
    @Override public boolean shouldPause() { return false; }
    @Override public void close() { MinecraftClient.getInstance().setScreen(parent); }
}
