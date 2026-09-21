package dev.relaylyrics.client.ui;

import dev.relaylyrics.client.ClientPreferences;
import dev.relaylyrics.client.GoogleFontManager;
import dev.relaylyrics.client.LyricsFontWeight;
import dev.relaylyrics.client.MusicSource;
import dev.relaylyrics.client.RelayLyricsClient;
import dev.relaylyrics.client.music.MusicService;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Vanilla-styled category and option rows used by the Mod Menu entrypoint. */
public final class LyricsConfigScreen extends FanDroidScreenBase {
    private final Screen parent;
    private final ClientPreferences preferences;
    private final List<Row> rows = new ArrayList<>();
    private Category category = Category.GENERAL;
    private int contentLeft;
    private int controlLeft;
    private int settingsBottom;

    public LyricsConfigScreen(Screen parent) {
        super(Text.translatable("screen.relaylyrics.config"));
        this.parent = parent;
        this.preferences = RelayLyricsClient.preferences();
    }

    @Override
    protected void init() {
        rebuild();
    }

    private void rebuild() {
        clearChildren();
        rows.clear();

        int contentWidth = Math.min(780, Math.max(290, width - 28));
        contentLeft = (width - contentWidth) / 2;
        int gap = 4;
        int tabCount = Category.values().length;
        int tabWidth = (contentWidth - gap * (tabCount - 1)) / tabCount;
        int tabY = 28;
        for (Category candidate : Category.values()) {
            int index = candidate.ordinal();
            ButtonWidget tab = ButtonWidget.builder(Text.translatable(candidate.key), ignored -> {
                category = candidate;
                rebuild();
            }).dimensions(contentLeft + index * (tabWidth + gap), tabY, tabWidth, 20).build();
            tab.active = candidate != category;
            addDrawableChild(tab);
        }

        int controlWidth = Math.clamp(contentWidth / 3, 132, 220);
        controlLeft = contentLeft + contentWidth - controlWidth;
        int y = 64;
        switch (category) {
            case GENERAL -> {
                y = addToggle(y, "config.relaylyrics.visible", preferences::isLyricsVisible,
                        () -> preferences.setLyricsVisible(true), () -> preferences.setLyricsVisible(false));
                y = addToggle(y, "config.relaylyrics.pinned", preferences::isLyricsPinned,
                        () -> preferences.setLyricsPinned(true), () -> preferences.setLyricsPinned(false));
                y = addToggle(y, "config.relaylyrics.allow_gui_overlap",
                        preferences::isLyricsAllowGuiOverlap,
                        () -> preferences.setLyricsAllowGuiOverlap(true),
                        () -> preferences.setLyricsAllowGuiOverlap(false));
                y = addRow(y, Text.translatable("config.relaylyrics.render_type"),
                        () -> enumValue("config.relaylyrics.render_type.",
                                preferences.getLyricsRenderType().name()),
                        () -> changeRenderType(preferences.getLyricsRenderType().next()),
                        () -> changeRenderType(preferences.getLyricsRenderType().previous()));
                y = addToggle(y, "config.relaylyrics.inventory_popup", preferences::isInventoryPopup,
                        () -> preferences.setInventoryPopup(true), () -> preferences.setInventoryPopup(false));
                y = addToggle(y, "config.relaylyrics.inventory_now_playing",
                        preferences::isInventoryNowPlaying,
                        () -> preferences.setInventoryNowPlaying(true),
                        () -> preferences.setInventoryNowPlaying(false));
                y = addToggle(y, "config.relaylyrics.animations", preferences::isLyricsAnimations,
                        () -> preferences.setLyricsAnimations(true), () -> preferences.setLyricsAnimations(false));
                y = addRow(y, Text.translatable("config.relaylyrics.inventory_editor"),
                        () -> Text.translatable(preferences.isInventoryPopup()
                                ? "config.relaylyrics.inventory_editor.value" : "options.off"), null, null);
                y = addRow(y, Text.translatable("config.relaylyrics.reset_layout"),
                        () -> Text.translatable("config.relaylyrics.reset"), preferences::resetLayout, null);
                addRow(y, Text.translatable("config.relaylyrics.defaults"),
                        () -> Text.translatable("config.relaylyrics.restore"), this::restoreDefaults, null);
            }
            case APPEARANCE -> {
                y = addToggle(y, "config.relaylyrics.modern", preferences::isModernMode,
                        () -> preferences.setModernMode(true), () -> preferences.setModernMode(false));
                y = addToggle(y, "config.relaylyrics.border", preferences::isLyricsBorder,
                        () -> preferences.setLyricsBorder(true), () -> preferences.setLyricsBorder(false));
                y = addToggle(y, "config.relaylyrics.dynamic", preferences::isLyricsDynamicBackground,
                        () -> preferences.setLyricsDynamicBackground(true),
                        () -> preferences.setLyricsDynamicBackground(false));
                y = addToggle(y, "config.relaylyrics.cover_shadow", preferences::isLyricsCoverShadow,
                        () -> preferences.setLyricsCoverShadow(true),
                        () -> preferences.setLyricsCoverShadow(false));
                y = addRow(y, Text.translatable("config.relaylyrics.width"),
                        () -> Text.literal(preferences.getLyricsWidth() + " px"),
                        () -> preferences.setLyricsWidth(preferences.getLyricsWidth() + 20),
                        () -> preferences.setLyricsWidth(preferences.getLyricsWidth() - 20));
                y = addRow(y, Text.translatable("config.relaylyrics.height"),
                        () -> Text.literal(preferences.getLyricsHeight() + " px"),
                        () -> preferences.setLyricsHeight(preferences.getLyricsHeight() + 20),
                        () -> preferences.setLyricsHeight(preferences.getLyricsHeight() - 20));
                y = addRow(y, Text.translatable("config.relaylyrics.scale"),
                        () -> Text.literal(Math.round(preferences.getLyricsTextScale() * 100) + "%"),
                        () -> preferences.setLyricsTextScale(preferences.getLyricsTextScale() + .1),
                        () -> preferences.setLyricsTextScale(preferences.getLyricsTextScale() - .1));
                y = addRow(y, Text.translatable("config.relaylyrics.opacity"),
                        () -> Text.literal(preferences.getLyricsBackgroundOpacity() + "%"),
                        () -> preferences.setLyricsBackgroundOpacity(preferences.getLyricsBackgroundOpacity() + 10),
                        () -> preferences.setLyricsBackgroundOpacity(preferences.getLyricsBackgroundOpacity() - 10));
                y = addRow(y, Text.translatable("config.relaylyrics.blur"),
                        () -> Text.literal(Integer.toString(preferences.getLyricsBlurStrength())),
                        () -> preferences.setLyricsBlurStrength(preferences.getLyricsBlurStrength() + 1),
                        () -> preferences.setLyricsBlurStrength(preferences.getLyricsBlurStrength() - 1));
                addRow(y, Text.translatable("config.relaylyrics.speed"),
                        () -> Text.literal(Integer.toString(preferences.getLyricsBackgroundSpeed() + 1)),
                        () -> preferences.setLyricsBackgroundSpeed(preferences.getLyricsBackgroundSpeed() + 1),
                        () -> preferences.setLyricsBackgroundSpeed(preferences.getLyricsBackgroundSpeed() - 1));
            }
            case LYRICS -> {
                y = addToggle(y, "config.relaylyrics.translation", preferences::isLyricsTranslation,
                        () -> preferences.setLyricsTranslation(true), () -> preferences.setLyricsTranslation(false));
                y = addToggle(y, "config.relaylyrics.romanization", preferences::isLyricsRomanization,
                        () -> preferences.setLyricsRomanization(true), () -> preferences.setLyricsRomanization(false));
                y = addToggle(y, "config.relaylyrics.simple", preferences::isSimpleLyricsMode,
                        () -> preferences.setSimpleLyricsMode(true),
                        () -> preferences.setSimpleLyricsMode(false));
                y = addRow(y, Text.translatable("config.relaylyrics.glow"),
                        () -> enumValue("config.relaylyrics.glow.",
                                preferences.getLyricsGlowIntensity().name()),
                        () -> preferences.setLyricsGlowIntensity(preferences.getLyricsGlowIntensity().next()),
                        () -> preferences.setLyricsGlowIntensity(preferences.getLyricsGlowIntensity().previous()));
                y = addRow(y, Text.translatable("config.relaylyrics.transition"),
                        () -> enumValue("config.relaylyrics.transition.",
                                preferences.getLyricsTransitionSpeed().name()),
                        () -> preferences.setLyricsTransitionSpeed(preferences.getLyricsTransitionSpeed().next()),
                        () -> preferences.setLyricsTransitionSpeed(preferences.getLyricsTransitionSpeed().previous()));
                y = addToggle(y, "config.relaylyrics.click_seek", preferences::isLyricsClickSeek,
                        () -> preferences.setLyricsClickSeek(true),
                        () -> preferences.setLyricsClickSeek(false));
                addRow(y, Text.translatable("config.relaylyrics.reload"),
                        () -> Text.translatable("config.relaylyrics.reload.button"),
                        () -> { if (RelayLyricsClient.music() != null) RelayLyricsClient.music().retryLyrics(); }, null);
            }
            case TYPOGRAPHY -> {
                y = addRow(y, Text.translatable("config.relaylyrics.alignment"),
                        () -> enumValue("config.relaylyrics.alignment.",
                                preferences.getLyricsAlignment().name()),
                        () -> preferences.setLyricsAlignment(preferences.getLyricsAlignment().next()),
                        () -> preferences.setLyricsAlignment(preferences.getLyricsAlignment().previous()));
                y = addRow(y, Text.translatable("config.relaylyrics.weight"),
                        () -> enumValue("config.relaylyrics.weight.",
                                preferences.getLyricsFontWeight().name()),
                        () -> changeWeight(preferences.getLyricsFontWeight().next()),
                        () -> changeWeight(preferences.getLyricsFontWeight().previous()));
                y = addRow(y, Text.translatable("config.relaylyrics.font"),
                        () -> preferences.getGoogleFontFamily().isEmpty()
                                ? Text.translatable("config.relaylyrics.google_font.minecraft")
                                : Text.literal(preferences.getGoogleFontFamily()),
                        () -> MinecraftClient.getInstance().setScreen(
                                new GoogleFontScreen(this, preferences,
                                        RelayLyricsClient.overlay() == null ? null
                                                : RelayLyricsClient.overlay()::fontChanged)), null);
                y = addToggle(y, "config.relaylyrics.shadow", preferences::isLyricsTextShadow,
                        () -> preferences.setLyricsTextShadow(true), () -> preferences.setLyricsTextShadow(false));
                y = addRow(y, Text.translatable("config.relaylyrics.shadow_opacity"),
                        () -> Text.literal(preferences.getLyricsShadowOpacity() + "%"),
                        () -> preferences.setLyricsShadowOpacity(preferences.getLyricsShadowOpacity() + 10),
                        () -> preferences.setLyricsShadowOpacity(preferences.getLyricsShadowOpacity() - 10));
                y = addRow(y, Text.translatable("config.relaylyrics.shadow_distance"),
                        () -> Text.literal(preferences.getLyricsShadowDistance() + " px"),
                        () -> preferences.setLyricsShadowDistance(preferences.getLyricsShadowDistance() + 1),
                        () -> preferences.setLyricsShadowDistance(preferences.getLyricsShadowDistance() - 1));
                addRow(y, Text.translatable("config.relaylyrics.shadow_softness"),
                        () -> enumValue("config.relaylyrics.shadow_softness.",
                                Integer.toString(preferences.getLyricsShadowSoftness())),
                        () -> preferences.setLyricsShadowSoftness(
                                Math.min(2, preferences.getLyricsShadowSoftness() + 1)),
                        () -> preferences.setLyricsShadowSoftness(
                                Math.max(0, preferences.getLyricsShadowSoftness() - 1)));
            }
            case CONNECTION -> {
                MusicService music = RelayLyricsClient.music();
                y = addRow(y, Text.translatable("config.relaylyrics.music_source"),
                        () -> enumValue("config.relaylyrics.music_source.",
                                preferences.getMusicSource().name()),
                        () -> changeMusicSource(preferences.getMusicSource().next()),
                        () -> changeMusicSource(preferences.getMusicSource().previous()));
                y = addDynamicRow(y, Text.translatable("config.relaylyrics.spotify.account"),
                        () -> Text.translatable(music != null && music.spotifyAccountConnected()
                                ? "config.relaylyrics.connected" : "config.relaylyrics.spotify.configure"),
                        () -> {
                            if (music != null) MinecraftClient.getInstance().setScreen(
                                    new SpotifyAccountScreen(this, preferences, music));
                        }, null);
                y = addDynamicRow(y, Text.translatable("config.relaylyrics.connection.status"),
                        this::connectionText, null, null);
                y = addDynamicRow(y, Text.translatable("config.relaylyrics.connection.extension"),
                        () -> valueOrDash(music == null ? null : music.spicetifyExtensionVersion()), null, null);
                y = addDynamicRow(y, Text.translatable("config.relaylyrics.connection.code"),
                        () -> valueOrDash(music == null ? null : music.pairingCode()), null, null);
                y = addDynamicRow(y, Text.translatable("config.relaylyrics.connection.port"),
                        () -> music == null || music.pairingPort() <= 0 ? Text.literal("—")
                                : Text.literal(Integer.toString(music.pairingPort())), null, null);
                addDynamicRow(y, Text.translatable("config.relaylyrics.connection.lyrics"),
                        () -> valueOrDash(music == null ? null : music.lyricsStatusDetail()), null, null);
            }
            case EXPERIMENTAL -> {
                y = addToggle(y, "config.relaylyrics.world_lyrics", preferences::isWorldLyrics,
                        () -> preferences.setWorldLyrics(true),
                        () -> preferences.setWorldLyrics(false));
                y = addRow(y, Text.translatable("config.relaylyrics.reactive_source"),
                        () -> enumValue("config.relaylyrics.reactive_source.",
                                preferences.getLyricsReactiveSource().name()),
                        () -> preferences.setLyricsReactiveSource(
                                preferences.getLyricsReactiveSource().next()),
                        () -> preferences.setLyricsReactiveSource(
                                preferences.getLyricsReactiveSource().previous()));
                y = addRow(y, Text.translatable("config.relaylyrics.reactive_output_reload"),
                        () -> Text.translatable("config.relaylyrics.reactive_output_reload.button"),
                        () -> {
                            MusicService music = RelayLyricsClient.music();
                            if (music != null) music.reloadReactiveAudioOutput();
                        }, null);
                y = addRow(y, Text.translatable("config.relaylyrics.reactive_intensity"),
                        () -> Text.literal(Math.round(
                                preferences.getLyricsReactiveIntensity() * 100) + "%"),
                        () -> preferences.setLyricsReactiveIntensity(
                                preferences.getLyricsReactiveIntensity() + .25),
                        () -> preferences.setLyricsReactiveIntensity(
                                preferences.getLyricsReactiveIntensity() - .25));
                y = addToggle(y, "config.relaylyrics.reactive_hue", preferences::isLyricsReactiveHue,
                        () -> preferences.setLyricsReactiveHue(true),
                        () -> preferences.setLyricsReactiveHue(false));
                y = addToggle(y, "config.relaylyrics.reactive_hue_auto",
                        preferences::isLyricsReactiveHueAuto,
                        () -> preferences.setLyricsReactiveHueAuto(true),
                        () -> preferences.setLyricsReactiveHueAuto(false));
                y = addRow(y, Text.translatable("config.relaylyrics.reactive_hue_amount"),
                        () -> Text.literal(Math.round(
                                preferences.getLyricsReactiveHueAmount()) + "\u00B0"),
                        () -> preferences.setLyricsReactiveHueAmount(
                                preferences.getLyricsReactiveHueAmount() + 10),
                        () -> preferences.setLyricsReactiveHueAmount(
                                preferences.getLyricsReactiveHueAmount() - 10));
                y = addToggle(y, "config.relaylyrics.reactive_bloom",
                        preferences::isLyricsReactiveBloom,
                        () -> preferences.setLyricsReactiveBloom(true),
                        () -> preferences.setLyricsReactiveBloom(false));
                y = addRow(y, Text.translatable("config.relaylyrics.reactive_bloom_amount"),
                        () -> Text.literal(Math.round(
                                preferences.getLyricsReactiveBloomAmount()) + "%"),
                        () -> preferences.setLyricsReactiveBloomAmount(
                                preferences.getLyricsReactiveBloomAmount() + 25),
                        () -> preferences.setLyricsReactiveBloomAmount(
                                preferences.getLyricsReactiveBloomAmount() - 25));
                y = addToggle(y, "config.relaylyrics.reactive_spectral",
                        preferences::isLyricsReactiveSpectral,
                        () -> preferences.setLyricsReactiveSpectral(true),
                        () -> preferences.setLyricsReactiveSpectral(false));
                y = addRow(y, Text.translatable("config.relaylyrics.reactive_spectral_amount"),
                        () -> Text.literal(Math.round(
                                preferences.getLyricsReactiveSpectralAmount()) + "%"),
                        () -> preferences.setLyricsReactiveSpectralAmount(
                                preferences.getLyricsReactiveSpectralAmount() + 25),
                        () -> preferences.setLyricsReactiveSpectralAmount(
                                preferences.getLyricsReactiveSpectralAmount() - 25));
                y = addToggle(y, "config.relaylyrics.reactive", preferences::isLyricsReactiveBackground,
                        () -> preferences.setLyricsReactiveBackground(true),
                        () -> preferences.setLyricsReactiveBackground(false));
                MusicService music = RelayLyricsClient.music();
                addDynamicRow(y, Text.translatable("config.relaylyrics.volume"),
                        () -> music == null
                                ? Text.literal("—")
                                : Text.literal(Math.round(music.snapshot().volume() * 100) + "%"),
                        null, null);
            }
        }
        settingsBottom = 58;
        for (Row row : rows) {
            settingsBottom = Math.max(settingsBottom, row.button.getY() + row.button.getHeight());
        }

        int doneWidth = Math.min(200, contentWidth);
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), ignored -> close())
                .dimensions((width - doneWidth) / 2, height - 28, doneWidth, 20).build());
    }

    private int addToggle(int y, String labelKey, Supplier<Boolean> enabled,
                          Runnable increase, Runnable decrease) {
        return addRow(y, Text.translatable(labelKey),
                () -> Text.translatable(enabled.get() ? "options.on" : "options.off"), increase, decrease);
    }

    private int addRow(int y, Text label, Supplier<Text> value, Runnable increase, Runnable decrease) {
        return addRow(y, label, value, increase, decrease, false);
    }

    private int addDynamicRow(int y, Text label, Supplier<Text> value,
                              Runnable increase, Runnable decrease) {
        return addRow(y, label, value, increase, decrease, true);
    }

    private int addRow(int y, Text label, Supplier<Text> value, Runnable increase,
                       Runnable decrease, boolean dynamic) {
        Text initialValue = value.get();
        ButtonWidget control = ButtonWidget.builder(initialValue, ignored -> {
            apply(increase);
        }).dimensions(controlLeft, y, width - contentLeft - controlLeft, 20).build();
        control.active = increase != null || decrease != null;
        int available = Math.max(20, controlLeft - contentLeft - 16);
        Text fittedLabel = Text.literal(textRenderer.trimToWidth(label.getString(), available));
        rows.add(new Row(fittedLabel, control, value, decrease, dynamic, initialValue));
        addDrawableChild(control);
        return y + 25;
    }

    /** Called by the version adapter because mouse callback signatures differ between Minecraft versions. */
    public boolean handleSecondaryClick(double mouseX, double mouseY, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_RIGHT) return false;
        for (Row row : rows) {
            ButtonWidget control = row.button;
            if (row.decrease != null && mouseX >= control.getX() && mouseX < control.getX() + control.getWidth()
                    && mouseY >= control.getY() && mouseY < control.getY() + control.getHeight()) {
                apply(row.decrease);
                return true;
            }
        }
        return false;
    }

    private void apply(Runnable action) {
        if (action == null) return;
        action.run();
        preferences.save();
        refreshValues();
    }

    private static Text enumValue(String prefix, String value) {
        return Text.translatable(prefix + value.toLowerCase());
    }

    private void changeWeight(LyricsFontWeight weight) {
        preferences.setLyricsFontWeight(weight);
        String family = preferences.getGoogleFontFamily();
        if (family.isEmpty()) {
            if (RelayLyricsClient.overlay() != null) RelayLyricsClient.overlay().fontChanged();
            return;
        }
        GoogleFontManager.install(family, weight.cssWeight()).thenAccept(result -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (result.success() && client != null && RelayLyricsClient.overlay() != null) {
                client.execute(RelayLyricsClient.overlay()::fontChanged);
            }
        });
    }

    private void changeMusicSource(MusicSource source) {
        preferences.setMusicSource(source);
        if (RelayLyricsClient.music() != null) RelayLyricsClient.music().syncMusicSource();
    }

    private void changeRenderType(dev.relaylyrics.client.LyricsRenderType type) {
        preferences.setLyricsRenderType(type);
        preferences.save();
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null) client.execute(() -> client.setScreen(new LyricsConfigScreen(parent)));
    }

    private void refreshValues() {
        for (Row row : rows) row.refresh();
    }

    private void refreshDynamicValues() {
        for (Row row : rows) {
            if (row.dynamic) row.refresh();
        }
    }

    @Override
    public void tick() {
        refreshDynamicValues();
    }

    private void restoreDefaults() {
        preferences.setLyricsVisible(true);
        preferences.setLyricsPinned(true);
        preferences.setLyricsAllowGuiOverlap(false);
        preferences.setLyricsDynamicBackground(true);
        preferences.setLyricsTranslation(true);
        preferences.setLyricsRomanization(true);
        preferences.setLyricsAnimations(true);
        preferences.setLyricsBorder(true);
        preferences.setInventoryPopup(true);
        preferences.setInventoryNowPlaying(true);
        preferences.setModernMode(false);
        preferences.setWorldLyrics(false);
        preferences.setMusicSource(MusicSource.AUTOMATIC);
        preferences.setLyricsRenderType(dev.relaylyrics.client.LyricsRenderType.NON_INTRUSIVE);
        preferences.setLyricsTextShadow(true);
        preferences.setLyricsCoverShadow(true);
        preferences.setLyricsFontWeight(LyricsFontWeight.REGULAR);
        preferences.setSimpleLyricsMode(false);
        preferences.setLyricsAlignment(dev.relaylyrics.client.LyricsTextAlignment.LEFT);
        preferences.setLyricsGlowIntensity(dev.relaylyrics.client.LyricsGlowIntensity.NORMAL);
        preferences.setLyricsTransitionSpeed(dev.relaylyrics.client.LyricsTransitionSpeed.SMOOTH);
        preferences.setLyricsClickSeek(true);
        preferences.setLyricsShadowOpacity(72);
        preferences.setLyricsShadowDistance(1);
        preferences.setLyricsShadowSoftness(1);
        preferences.setGoogleFontFamily("");
        preferences.setLyricsTextScale(1.0);
        preferences.setLyricsBackgroundOpacity(100);
        preferences.setLyricsBlurStrength(2);
        preferences.setLyricsReactiveBackground(false);
        preferences.setLyricsReactiveSource(dev.relaylyrics.client.ClientPreferences.ReactiveSource.WORDS);
        preferences.setLyricsReactiveIntensity(1.0);
        preferences.setLyricsReactiveHue(true);
        preferences.setLyricsReactiveHueAmount(80);
        preferences.setLyricsReactiveHueAuto(false);
        preferences.setLyricsReactiveBloom(true);
        preferences.setLyricsReactiveBloomAmount(100);
        preferences.setLyricsReactiveSpectral(false);
        preferences.setLyricsReactiveSpectralAmount(100);
        preferences.setLyricsBackgroundSpeed(1);
        preferences.resetLayout();
        if (RelayLyricsClient.overlay() != null) RelayLyricsClient.overlay().fontChanged();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderVanillaScreen(context, mouseX, mouseY, delta);
        drawCentered(context, title, 10, 0xFFFFFFFF);
        for (Row row : rows) {
            context.drawTextWithShadow(textRenderer, row.label, contentLeft + 6,
                    row.button.getY() + 6, 0xFFE0E0E0);
        }
        renderPopupPreview(context);
    }

    private void renderPopupPreview(DrawContext context) {
        if (preferences.getLyricsRenderType() == dev.relaylyrics.client.LyricsRenderType.ALWAYS) return;
        if (RelayLyricsClient.overlay() == null || !RelayLyricsClient.overlay().isHudVisible()) return;
        int bottom = height - 36;
        int top = settingsBottom + 8;
        int availableHeight = bottom - top;
        if (availableHeight < 64) return;
        int previewHeight = Math.min(180, availableHeight);
        double aspect = preferences.getLyricsWidth() / (double) preferences.getLyricsHeight();
        int previewWidth = Math.min(Math.min(420, width - 32),
                Math.max(100, (int) Math.round(previewHeight * aspect)));
        previewHeight = Math.min(previewHeight, Math.max(60, (int) Math.round(previewWidth / aspect)));
        int x = (width - previewWidth) / 2;
        int y = availableHeight >= previewHeight
                ? top + (availableHeight - previewHeight) / 2 : bottom - previewHeight;
        RelayLyricsClient.overlay().renderPreview(context, x, Math.max(52, y), previewWidth, previewHeight);
    }

    private Text connectionText() {
        MusicService music = RelayLyricsClient.music();
        if (RelayLyricsClient.disabledByRelay()) return Text.translatable("config.relaylyrics.managed_by_relay");
        if (music != null && music.spicetifyConnected()) return Text.translatable("config.relaylyrics.connected");
        if (music != null && music.bridgeAlive()) return Text.translatable("config.relaylyrics.waiting");
        return Text.translatable("config.relaylyrics.offline");
    }

    private static Text valueOrDash(String value) {
        return value == null || value.isBlank() ? Text.literal("—") : Text.literal(value);
    }

    private void drawCentered(DrawContext context, Text text, int y, int color) {
        context.drawTextWithShadow(textRenderer, text, (width - textRenderer.getWidth(text)) / 2, y, color);
    }

    @Override
    public boolean shouldPause() { return false; }

    @Override
    public void close() {
        preferences.save();
        MinecraftClient.getInstance().setScreen(parent);
    }

    @Override
    public void removed() {
        preferences.save();
        super.removed();
    }

    private enum Category {
        GENERAL("config.relaylyrics.category.general"),
        APPEARANCE("config.relaylyrics.category.appearance"),
        LYRICS("config.relaylyrics.category.lyrics"),
        TYPOGRAPHY("config.relaylyrics.category.typography"),
        CONNECTION("config.relaylyrics.category.connection"),
        EXPERIMENTAL("config.relaylyrics.category.experimental");

        private final String key;
        Category(String key) { this.key = key; }
    }

    private static final class Row {
        private final Text label;
        private final ButtonWidget button;
        private final Supplier<Text> value;
        private final Runnable decrease;
        private final boolean dynamic;
        private Text displayedValue;

        private Row(Text label, ButtonWidget button, Supplier<Text> value, Runnable decrease,
                    boolean dynamic, Text displayedValue) {
            this.label = label;
            this.button = button;
            this.value = value;
            this.decrease = decrease;
            this.dynamic = dynamic;
            this.displayedValue = displayedValue;
        }

        private void refresh() {
            Text next = value.get();
            if (next.equals(displayedValue)) return;
            displayedValue = next;
            button.setMessage(next);
        }
    }
}
