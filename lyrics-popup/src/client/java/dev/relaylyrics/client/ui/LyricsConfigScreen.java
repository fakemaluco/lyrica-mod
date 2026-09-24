package dev.relaylyrics.client.ui;

import dev.relaylyrics.client.ClientPreferences;
import dev.relaylyrics.client.GoogleFontManager;
import dev.relaylyrics.client.LyricsFontWeight;
import dev.relaylyrics.client.MusicSource;
import dev.relaylyrics.client.RelayLyricsClient;
import dev.relaylyrics.client.music.MusicService;
import dev.relaylyrics.client.music.WorldLyricsDirector;
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
    private int scrollRows;

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
        if (category == Category.WORLD && !preferences.isWorldLyrics()) category = Category.GENERAL;
        List<Category> categories = java.util.Arrays.stream(Category.values())
                .filter(value -> value != Category.WORLD || preferences.isWorldLyrics()).toList();
        int tabCount = categories.size();
        int tabWidth = (contentWidth - gap * (tabCount - 1)) / tabCount;
        int tabY = 28;
        for (Category candidate : categories) {
            int index = categories.indexOf(candidate);
            ButtonWidget tab = ButtonWidget.builder(Text.translatable(candidate.key), ignored -> {
                category = candidate;
                scrollRows = 0;
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
            case WORLD -> {
                y = addWorldSection(y, "config.relaylyrics.world_section.style");
                y = addRow(y, Text.translatable("config.relaylyrics.world_size"),
                        () -> Text.literal(Math.round(preferences.getWorldLyricsSize() * 100) + "%"),
                        () -> preferences.setWorldLyricsSize(preferences.getWorldLyricsSize() + .1),
                        () -> preferences.setWorldLyricsSize(preferences.getWorldLyricsSize() - .1));
                y = addToggle(y, "config.relaylyrics.world_auto_walls", preferences::isWorldLyricsAutoWalls,
                        () -> preferences.setWorldLyricsAutoWalls(true), () -> preferences.setWorldLyricsAutoWalls(false));
                y = addToggle(y, "config.relaylyrics.world_cinematic", preferences::isWorldLyricsCinematic,
                        () -> preferences.setWorldLyricsCinematic(true), () -> preferences.setWorldLyricsCinematic(false));
                y = addToggle(y, "config.relaylyrics.world_automatic", preferences::isWorldLyricsAutomatic,
                        () -> preferences.setWorldLyricsAutomatic(true), () -> preferences.setWorldLyricsAutomatic(false));
                y = addRow(y, Text.translatable("config.relaylyrics.world_preset"),
                        () -> enumValue("config.relaylyrics.world_preset.", preferences.getWorldLyricsPreset().name()),
                        () -> preferences.setWorldLyricsPreset(preferences.getWorldLyricsPreset().next()),
                        () -> preferences.setWorldLyricsPreset(preferences.getWorldLyricsPreset().previous()));
                y = addRow(y, Text.translatable("config.relaylyrics.world_variation"),
                        () -> enumValue("config.relaylyrics.world_variation.", preferences.getWorldLyricsVariation().name()),
                        () -> preferences.setWorldLyricsVariation(preferences.getWorldLyricsVariation().next()),
                        () -> preferences.setWorldLyricsVariation(preferences.getWorldLyricsVariation().previous()));
                for (WorldLyricsDirector.Preset preset : WorldLyricsDirector.Preset.values()) {
                    y = addToggle(y, "config.relaylyrics.world_include." + preset.name().toLowerCase(),
                            () -> preferences.isWorldLyricsPresetEnabled(preset),
                            () -> preferences.setWorldLyricsPresetEnabled(preset, true),
                            () -> preferences.setWorldLyricsPresetEnabled(preset, false));
                }
                y = addRow(y, Text.translatable("config.relaylyrics.world_intensity"),
                        () -> enumValue("config.relaylyrics.world_intensity.", preferences.getWorldLyricsIntensity().name()),
                        () -> preferences.setWorldLyricsIntensity(preferences.getWorldLyricsIntensity().next()),
                        () -> preferences.setWorldLyricsIntensity(preferences.getWorldLyricsIntensity().previous()));
                y = addToggle(y, "config.relaylyrics.world_player_reactions", preferences::isWorldLyricsPlayerReactions,
                        () -> preferences.setWorldLyricsPlayerReactions(true),
                        () -> preferences.setWorldLyricsPlayerReactions(false));
                y = addWorldSection(y, "config.relaylyrics.world_section.position");
                y = addRow(y, Text.translatable("config.relaylyrics.world_distance_min"),
                        () -> Text.literal(String.format(java.util.Locale.ROOT, "%.1f", preferences.getWorldLyricsMinimumDistance())),
                        () -> preferences.setWorldLyricsMinimumDistance(preferences.getWorldLyricsMinimumDistance() + .5),
                        () -> preferences.setWorldLyricsMinimumDistance(preferences.getWorldLyricsMinimumDistance() - .5));
                y = addRow(y, Text.translatable("config.relaylyrics.world_distance_max"),
                        () -> Text.literal(String.format(java.util.Locale.ROOT, "%.1f", preferences.getWorldLyricsMaximumDistance())),
                        () -> preferences.setWorldLyricsMaximumDistance(preferences.getWorldLyricsMaximumDistance() + 8),
                        () -> preferences.setWorldLyricsMaximumDistance(preferences.getWorldLyricsMaximumDistance() - 8));
                y = addRow(y, Text.translatable("config.relaylyrics.world_distance_sensitivity"),
                        () -> Text.literal(Math.round(preferences.getWorldLyricsDistanceSensitivity() * 100) + "%"),
                        () -> preferences.setWorldLyricsDistanceSensitivity(preferences.getWorldLyricsDistanceSensitivity() + .1),
                        () -> preferences.setWorldLyricsDistanceSensitivity(preferences.getWorldLyricsDistanceSensitivity() - .1));
                y = addRow(y, Text.translatable("config.relaylyrics.world_arc_radius"),
                        () -> Text.literal(Math.round(preferences.getWorldLyricsArcRadius() * 100) + "%"),
                        () -> preferences.setWorldLyricsArcRadius(preferences.getWorldLyricsArcRadius() + .1),
                        () -> preferences.setWorldLyricsArcRadius(preferences.getWorldLyricsArcRadius() - .1));
                y = addRow(y, Text.translatable("config.relaylyrics.world_center_gap"),
                        () -> Text.literal(Math.round(preferences.getWorldLyricsCenterGap() * 100) + "%"),
                        () -> preferences.setWorldLyricsCenterGap(preferences.getWorldLyricsCenterGap() + .08),
                        () -> preferences.setWorldLyricsCenterGap(preferences.getWorldLyricsCenterGap() - .08));
                y = addWorldSection(y, "config.relaylyrics.world_section.volume");
                y = addRow(y, Text.translatable("config.relaylyrics.world_depth"),
                        () -> Text.literal(Math.round(preferences.getWorldLyricsDepth() * 100) + "%"),
                        () -> preferences.setWorldLyricsDepth(preferences.getWorldLyricsDepth() + .025),
                        () -> preferences.setWorldLyricsDepth(preferences.getWorldLyricsDepth() - .025));
                y = addRow(y, Text.translatable("config.relaylyrics.world_face_color"),
                        () -> Text.literal(String.format("#%06X", preferences.getWorldLyricsFaceColor())),
                        () -> preferences.setWorldLyricsFaceColor(nextFaceColor(preferences.getWorldLyricsFaceColor(), 1)),
                        () -> preferences.setWorldLyricsFaceColor(nextFaceColor(preferences.getWorldLyricsFaceColor(), -1)));
                y = addRow(y, Text.translatable("config.relaylyrics.world_side_color"),
                        () -> Text.literal(String.format("#%06X", preferences.getWorldLyricsSideColor())),
                        () -> preferences.setWorldLyricsSideColor(nextSideColor(preferences.getWorldLyricsSideColor(), 1)),
                        () -> preferences.setWorldLyricsSideColor(nextSideColor(preferences.getWorldLyricsSideColor(), -1)));
                y = addWorldSection(y, "config.relaylyrics.world_section.animations");
                y = addRow(y, Text.translatable("config.relaylyrics.world_animation_strength"),
                        () -> Text.literal(Math.round(preferences.getWorldLyricsAnimationIntensity() * 100) + "%"),
                        () -> preferences.setWorldLyricsAnimationIntensity(preferences.getWorldLyricsAnimationIntensity() + .1),
                        () -> preferences.setWorldLyricsAnimationIntensity(preferences.getWorldLyricsAnimationIntensity() - .1));
                y = addRow(y, Text.translatable("config.relaylyrics.world_animation_speed"),
                        () -> Text.literal(Math.round(preferences.getWorldLyricsAnimationSpeed() * 100) + "%"),
                        () -> preferences.setWorldLyricsAnimationSpeed(preferences.getWorldLyricsAnimationSpeed() + .1),
                        () -> preferences.setWorldLyricsAnimationSpeed(preferences.getWorldLyricsAnimationSpeed() - .1));
                y = addToggle(y, "config.relaylyrics.world_trails", preferences::isWorldLyricsTrails,
                        () -> preferences.setWorldLyricsTrails(true), () -> preferences.setWorldLyricsTrails(false));
                y = addWorldSection(y, "config.relaylyrics.world_section.quality");
                y = addRow(y, Text.translatable("config.relaylyrics.world_quality"),
                        () -> enumValue("config.relaylyrics.world_quality.", preferences.getWorldLyricsQuality().name()),
                        () -> preferences.setWorldLyricsQuality(preferences.getWorldLyricsQuality().next()),
                        () -> preferences.setWorldLyricsQuality(preferences.getWorldLyricsQuality().previous()));
                y = addRow(y, Text.translatable("config.relaylyrics.world_preview"),
                        () -> Text.translatable("config.relaylyrics.world_preview.button"),
                        WorldLyricsRenderer::previewCurrentPhrase, null);
                y = addRow(y, Text.translatable("config.relaylyrics.world_reset_position"),
                        () -> Text.translatable("config.relaylyrics.restore"), () -> {
                            preferences.setWorldLyricsMaximumDistance(192);
                            preferences.setWorldLyricsMinimumDistance(6);
                            preferences.setWorldLyricsDistanceSensitivity(1);
                            preferences.setWorldLyricsArcRadius(.65);
                            preferences.setWorldLyricsCenterGap(.32);
                        }, null);
                y = addRow(y, Text.translatable("config.relaylyrics.world_reset_volume"),
                        () -> Text.translatable("config.relaylyrics.restore"), () -> {
                            preferences.setWorldLyricsDepth(.10);
                            preferences.setWorldLyricsSideColor(0x56647A);
                            preferences.setWorldLyricsFaceColor(0xFFFFFF);
                            preferences.setWorldLyricsQuality(WorldLyricsDirector.Quality.MEDIUM);
                        }, null);
                y = addRow(y, Text.translatable("config.relaylyrics.world_reset_animation"),
                        () -> Text.translatable("config.relaylyrics.restore"), () -> {
                            preferences.setWorldLyricsAnimationIntensity(1);
                            preferences.setWorldLyricsAnimationSpeed(1);
                            preferences.setWorldLyricsTrails(false);
                        }, null);
            }
            case EXPERIMENTAL -> {
                y = addToggle(y, "config.relaylyrics.world_lyrics", preferences::isWorldLyrics,
                        preferences::enableWorldLyricsWithModernDefaults, () -> preferences.setWorldLyrics(false));
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
        positionRows();

        int doneWidth = Math.min(200, contentWidth);
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), ignored -> close())
                .dimensions((width - doneWidth) / 2, height - 28, doneWidth, 20).build());
    }

    private int visibleRowCount() { return Math.max(1, (height - 99) / 25); }

    private void positionRows() {
        int visibleCount = visibleRowCount();
        scrollRows = Math.clamp(scrollRows, 0, Math.max(0, rows.size() - visibleCount));
        settingsBottom = 58;
        for (int i = 0; i < rows.size(); i++) {
            ButtonWidget button = rows.get(i).button;
            button.visible = i >= scrollRows && i < scrollRows + visibleCount;
            button.setY(64 + (i - scrollRows) * 25);
            if (button.visible) settingsBottom = Math.max(settingsBottom, button.getY() + button.getHeight());
        }
    }

    @Override
    protected boolean handleMouseScrolled(double x, double y, double horizontal, double vertical) {
        if (y < 54 || y >= height - 34 || vertical == 0 || rows.size() <= visibleRowCount()) return false;
        scrollRows += vertical > 0 ? -2 : 2;
        positionRows();
        setFocused(null);
        return true;
    }

    @Override
    protected boolean handleKeyPressed(int key, int scanCode, int modifiers) {
        if (key != GLFW.GLFW_KEY_PAGE_UP && key != GLFW.GLFW_KEY_PAGE_DOWN) return false;
        scrollRows += key == GLFW.GLFW_KEY_PAGE_UP ? -visibleRowCount() : visibleRowCount();
        positionRows();
        setFocused(null);
        return true;
    }

    private int addToggle(int y, String labelKey, Supplier<Boolean> enabled,
                          Runnable increase, Runnable decrease) {
        return addRow(y, Text.translatable(labelKey),
                () -> Text.translatable(enabled.get() ? "options.on" : "options.off"), increase, decrease);
    }

    /** Integration probe used only by -Dlyrica.worldMeshSelfTest=true in the development client. */
    public static void verifyWorldTabVisibility() {
        MinecraftClient client = MinecraftClient.getInstance();
        Screen previous = client.currentScreen;
        ClientPreferences preferences = RelayLyricsClient.preferences();
        boolean enabled = preferences.isWorldLyrics();
        try {
            preferences.setWorldLyrics(false);
            LyricsConfigScreen screen = new LyricsConfigScreen(previous);
            client.setScreen(screen);
            screen.expectWorldTab(false);
            screen.category = Category.EXPERIMENTAL;
            screen.rebuild();
            screen.expectWorldToggle();
            screen.apply(preferences::enableWorldLyricsWithModernDefaults);
            screen.expectWorldTab(true);
            screen.apply(() -> preferences.setWorldLyrics(false));
            screen.expectWorldTab(false);
            dev.relaylyrics.FanDroidMod.LOGGER.info("World configuration tab self-test passed: hidden / shown / hidden");
        } finally {
            preferences.setWorldLyrics(enabled);
            client.setScreen(previous);
        }
    }

    private void expectWorldTab(boolean expected) {
        String title = Text.translatable(Category.WORLD.key).getString();
        boolean present = children().stream().anyMatch(child -> child instanceof ButtonWidget button
                && button.getY() == 28 && button.getMessage().getString().equals(title));
        if (present != expected) throw new IllegalStateException("Unexpected world configuration tab visibility");
    }

    private void expectWorldToggle() {
        String label = Text.translatable("config.relaylyrics.world_lyrics").getString();
        if (rows.stream().noneMatch(row -> row.label.getString().equals(label)))
            throw new IllegalStateException("World lyrics toggle missing from Experimental settings");
    }

    private int addWorldSection(int y, String key) {
        return addRow(y, Text.translatable(key), () -> Text.literal(""), null, null);
    }

    private static int nextSideColor(int color, int direction) {
        int[] palette = {0x56647A, 0x2F6B8A, 0x7C3F78, 0x8A512F, 0x326B52, 0x222222};
        int current = 0;
        for (int i = 0; i < palette.length; i++) if (palette[i] == color) current = i;
        return palette[Math.floorMod(current + direction, palette.length)];
    }

    private static int nextFaceColor(int color, int direction) {
        int[] palette = {0xFFFFFF, 0xD7E8FF, 0xFFE4C7, 0xFFC9E5, 0xD3FFD9, 0xFFF3A8};
        int current = 0;
        for (int i = 0; i < palette.length; i++) if (palette[i] == color) current = i;
        return palette[Math.floorMod(current + direction, palette.length)];
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
            if (control.visible && row.decrease != null && mouseX >= control.getX() && mouseX < control.getX() + control.getWidth()
                    && mouseY >= control.getY() && mouseY < control.getY() + control.getHeight()) {
                apply(row.decrease);
                return true;
            }
        }
        return false;
    }

    private void apply(Runnable action) {
        if (action == null) return;
        boolean hadWorldTab = preferences.isWorldLyrics();
        action.run();
        preferences.save();
        if (hadWorldTab != preferences.isWorldLyrics()) { scrollRows = 0; rebuild(); return; }
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
        preferences.setWorldLyricsIntensity(ClientPreferences.WorldIntensity.NORMAL);
        preferences.setWorldLyricsPlayerReactions(true);
        preferences.setWorldLyricsSize(1.2);
        preferences.setWorldLyricsAutoWalls(true);
        preferences.setWorldLyricsCinematic(true);
        preferences.setWorldLyricsAutomatic(true);
        preferences.setWorldLyricsPreset(WorldLyricsDirector.Preset.CINEMA);
        preferences.setWorldLyricsVariation(WorldLyricsDirector.Variation.MEDIUM);
        preferences.setWorldLyricsQuality(WorldLyricsDirector.Quality.MEDIUM);
        for (var preset : WorldLyricsDirector.Preset.values()) preferences.setWorldLyricsPresetEnabled(preset, true);
        preferences.setWorldLyricsMaximumDistance(192);
        preferences.setWorldLyricsMinimumDistance(6);
        preferences.setWorldLyricsDistanceSensitivity(1);
        preferences.setWorldLyricsArcRadius(.65);
        preferences.setWorldLyricsCenterGap(.32);
        preferences.setWorldLyricsDepth(.10);
        preferences.setWorldLyricsAnimationIntensity(1);
        preferences.setWorldLyricsAnimationSpeed(1);
        preferences.setWorldLyricsTrails(false);
        preferences.setWorldLyricsSideColor(0x56647A);
        preferences.setWorldLyricsFaceColor(0xFFFFFF);
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
            if (!row.button.visible) continue;
            context.drawTextWithShadow(textRenderer, row.label, contentLeft + 6,
                    row.button.getY() + 6, 0xFFE0E0E0);
        }
        if (rows.size() > visibleRowCount()) {
            int trackHeight = Math.max(20, visibleRowCount() * 25 - 5);
            int thumbHeight = Math.max(8, trackHeight * visibleRowCount() / rows.size());
            int thumbY = 64 + (trackHeight - thumbHeight) * scrollRows / (rows.size() - visibleRowCount());
            int x = width - contentLeft + 3;
            context.fill(x, 64, x + 3, 64 + trackHeight, 0x55333333);
            context.fill(x, thumbY, x + 3, thumbY + thumbHeight, 0xCCB0B0B0);
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
        EXPERIMENTAL("config.relaylyrics.category.experimental"),
        WORLD("config.relaylyrics.category.world");

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
