package dev.relaylyrics.client.ui;

import dev.relaylyrics.client.ClientPreferences;
import dev.relaylyrics.client.GoogleFontManager;
import dev.relaylyrics.client.LyricsFontWeight;
import dev.relaylyrics.client.MusicSource;
import dev.relaylyrics.client.RelayLyricsClient;
import dev.relaylyrics.client.music.MusicService;
import dev.relaylyrics.client.music.WorldLyricsDirector;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

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
        super(Component.translatable("screen.relaylyrics.config"));
        this.parent = parent;
        this.preferences = RelayLyricsClient.preferences();
    }

    @Override
    protected void init() {
        rebuild();
    }

    private void rebuild() {
        clearWidgets();
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
            Button tab = Button.builder(Component.translatable(candidate.key), ignored -> {
                category = candidate;
                scrollRows = 0;
                rebuild();
            }).bounds(contentLeft + index * (tabWidth + gap), tabY, tabWidth, 20).build();
            tab.active = candidate != category;
            addRenderableWidget(tab);
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
                y = addRow(y, Component.translatable("config.relaylyrics.render_type"),
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
                y = addRow(y, Component.translatable("config.relaylyrics.inventory_editor"),
                        () -> Component.translatable(preferences.isInventoryPopup()
                                ? "config.relaylyrics.inventory_editor.value" : "options.off"), null, null);
                y = addRow(y, Component.translatable("config.relaylyrics.reset_layout"),
                        () -> Component.translatable("config.relaylyrics.reset"), preferences::resetLayout, null);
                addRow(y, Component.translatable("config.relaylyrics.defaults"),
                        () -> Component.translatable("config.relaylyrics.restore"), this::restoreDefaults, null);
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
                y = addRow(y, Component.translatable("config.relaylyrics.width"),
                        () -> Component.literal(preferences.getLyricsWidth() + " px"),
                        () -> preferences.setLyricsWidth(preferences.getLyricsWidth() + 20),
                        () -> preferences.setLyricsWidth(preferences.getLyricsWidth() - 20));
                y = addRow(y, Component.translatable("config.relaylyrics.height"),
                        () -> Component.literal(preferences.getLyricsHeight() + " px"),
                        () -> preferences.setLyricsHeight(preferences.getLyricsHeight() + 20),
                        () -> preferences.setLyricsHeight(preferences.getLyricsHeight() - 20));
                y = addRow(y, Component.translatable("config.relaylyrics.scale"),
                        () -> Component.literal(Math.round(preferences.getLyricsTextScale() * 100) + "%"),
                        () -> preferences.setLyricsTextScale(preferences.getLyricsTextScale() + .1),
                        () -> preferences.setLyricsTextScale(preferences.getLyricsTextScale() - .1));
                y = addRow(y, Component.translatable("config.relaylyrics.opacity"),
                        () -> Component.literal(preferences.getLyricsBackgroundOpacity() + "%"),
                        () -> preferences.setLyricsBackgroundOpacity(preferences.getLyricsBackgroundOpacity() + 10),
                        () -> preferences.setLyricsBackgroundOpacity(preferences.getLyricsBackgroundOpacity() - 10));
                y = addRow(y, Component.translatable("config.relaylyrics.blur"),
                        () -> Component.literal(Integer.toString(preferences.getLyricsBlurStrength())),
                        () -> preferences.setLyricsBlurStrength(preferences.getLyricsBlurStrength() + 1),
                        () -> preferences.setLyricsBlurStrength(preferences.getLyricsBlurStrength() - 1));
                addRow(y, Component.translatable("config.relaylyrics.speed"),
                        () -> Component.literal(Integer.toString(preferences.getLyricsBackgroundSpeed() + 1)),
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
                y = addRow(y, Component.translatable("config.relaylyrics.glow"),
                        () -> enumValue("config.relaylyrics.glow.",
                                preferences.getLyricsGlowIntensity().name()),
                        () -> preferences.setLyricsGlowIntensity(preferences.getLyricsGlowIntensity().next()),
                        () -> preferences.setLyricsGlowIntensity(preferences.getLyricsGlowIntensity().previous()));
                y = addRow(y, Component.translatable("config.relaylyrics.transition"),
                        () -> enumValue("config.relaylyrics.transition.",
                                preferences.getLyricsTransitionSpeed().name()),
                        () -> preferences.setLyricsTransitionSpeed(preferences.getLyricsTransitionSpeed().next()),
                        () -> preferences.setLyricsTransitionSpeed(preferences.getLyricsTransitionSpeed().previous()));
                y = addToggle(y, "config.relaylyrics.click_seek", preferences::isLyricsClickSeek,
                        () -> preferences.setLyricsClickSeek(true),
                        () -> preferences.setLyricsClickSeek(false));
                addRow(y, Component.translatable("config.relaylyrics.reload"),
                        () -> Component.translatable("config.relaylyrics.reload.button"),
                        () -> { if (RelayLyricsClient.music() != null) RelayLyricsClient.music().retryLyrics(); }, null);
            }
            case TYPOGRAPHY -> {
                y = addRow(y, Component.translatable("config.relaylyrics.alignment"),
                        () -> enumValue("config.relaylyrics.alignment.",
                                preferences.getLyricsAlignment().name()),
                        () -> preferences.setLyricsAlignment(preferences.getLyricsAlignment().next()),
                        () -> preferences.setLyricsAlignment(preferences.getLyricsAlignment().previous()));
                y = addRow(y, Component.translatable("config.relaylyrics.weight"),
                        () -> enumValue("config.relaylyrics.weight.",
                                preferences.getLyricsFontWeight().name()),
                        () -> changeWeight(preferences.getLyricsFontWeight().next()),
                        () -> changeWeight(preferences.getLyricsFontWeight().previous()));
                y = addRow(y, Component.translatable("config.relaylyrics.font"),
                        () -> preferences.getGoogleFontFamily().isEmpty()
                                ? Component.translatable("config.relaylyrics.google_font.minecraft")
                                : Component.literal(preferences.getGoogleFontFamily()),
                        () -> Minecraft.getInstance().gui.setScreen(
                                new GoogleFontScreen(this, preferences,
                                        RelayLyricsClient.overlay() == null ? null
                                                : RelayLyricsClient.overlay()::fontChanged)), null);
                y = addToggle(y, "config.relaylyrics.shadow", preferences::isLyricsTextShadow,
                        () -> preferences.setLyricsTextShadow(true), () -> preferences.setLyricsTextShadow(false));
                y = addRow(y, Component.translatable("config.relaylyrics.shadow_opacity"),
                        () -> Component.literal(preferences.getLyricsShadowOpacity() + "%"),
                        () -> preferences.setLyricsShadowOpacity(preferences.getLyricsShadowOpacity() + 10),
                        () -> preferences.setLyricsShadowOpacity(preferences.getLyricsShadowOpacity() - 10));
                y = addRow(y, Component.translatable("config.relaylyrics.shadow_distance"),
                        () -> Component.literal(preferences.getLyricsShadowDistance() + " px"),
                        () -> preferences.setLyricsShadowDistance(preferences.getLyricsShadowDistance() + 1),
                        () -> preferences.setLyricsShadowDistance(preferences.getLyricsShadowDistance() - 1));
                addRow(y, Component.translatable("config.relaylyrics.shadow_softness"),
                        () -> enumValue("config.relaylyrics.shadow_softness.",
                                Integer.toString(preferences.getLyricsShadowSoftness())),
                        () -> preferences.setLyricsShadowSoftness(
                                Math.min(2, preferences.getLyricsShadowSoftness() + 1)),
                        () -> preferences.setLyricsShadowSoftness(
                                Math.max(0, preferences.getLyricsShadowSoftness() - 1)));
            }
            case CONNECTION -> {
                MusicService music = RelayLyricsClient.music();
                y = addRow(y, Component.translatable("config.relaylyrics.music_source"),
                        () -> enumValue("config.relaylyrics.music_source.",
                                preferences.getMusicSource().name()),
                        () -> changeMusicSource(preferences.getMusicSource().next()),
                        () -> changeMusicSource(preferences.getMusicSource().previous()));
                y = addDynamicRow(y, Component.translatable("config.relaylyrics.spotify.account"),
                        () -> Component.translatable(music != null && music.spotifyAccountConnected()
                                ? "config.relaylyrics.connected" : "config.relaylyrics.spotify.configure"),
                        () -> {
                            if (music != null) Minecraft.getInstance().gui.setScreen(
                                    new SpotifyAccountScreen(this, preferences, music));
                        }, null);
                y = addDynamicRow(y, Component.translatable("config.relaylyrics.connection.status"),
                        this::connectionText, null, null);
                y = addDynamicRow(y, Component.translatable("config.relaylyrics.connection.extension"),
                        () -> valueOrDash(music == null ? null : music.spicetifyExtensionVersion()), null, null);
                y = addDynamicRow(y, Component.translatable("config.relaylyrics.connection.code"),
                        () -> valueOrDash(music == null ? null : music.pairingCode()), null, null);
                y = addDynamicRow(y, Component.translatable("config.relaylyrics.connection.port"),
                        () -> music == null || music.pairingPort() <= 0 ? Component.literal("—")
                                : Component.literal(Integer.toString(music.pairingPort())), null, null);
                addDynamicRow(y, Component.translatable("config.relaylyrics.connection.lyrics"),
                        () -> valueOrDash(music == null ? null : music.lyricsStatusDetail()), null, null);
            }
            case WORLD -> {
                y = addWorldSection(y, "config.relaylyrics.world_section.style");
                y = addRow(y, Component.translatable("config.relaylyrics.world_size"),
                        () -> Component.literal(Math.round(preferences.getWorldLyricsSize() * 100) + "%"),
                        () -> preferences.setWorldLyricsSize(preferences.getWorldLyricsSize() + .1),
                        () -> preferences.setWorldLyricsSize(preferences.getWorldLyricsSize() - .1));
                y = addToggle(y, "config.relaylyrics.world_auto_walls", preferences::isWorldLyricsAutoWalls,
                        () -> preferences.setWorldLyricsAutoWalls(true), () -> preferences.setWorldLyricsAutoWalls(false));
                y = addToggle(y, "config.relaylyrics.world_cinematic", preferences::isWorldLyricsCinematic,
                        () -> preferences.setWorldLyricsCinematic(true), () -> preferences.setWorldLyricsCinematic(false));
                y = addToggle(y, "config.relaylyrics.world_automatic", preferences::isWorldLyricsAutomatic,
                        () -> preferences.setWorldLyricsAutomatic(true), () -> preferences.setWorldLyricsAutomatic(false));
                y = addRow(y, Component.translatable("config.relaylyrics.world_preset"),
                        () -> enumValue("config.relaylyrics.world_preset.", preferences.getWorldLyricsPreset().name()),
                        () -> preferences.setWorldLyricsPreset(preferences.getWorldLyricsPreset().next()),
                        () -> preferences.setWorldLyricsPreset(preferences.getWorldLyricsPreset().previous()));
                y = addRow(y, Component.translatable("config.relaylyrics.world_variation"),
                        () -> enumValue("config.relaylyrics.world_variation.", preferences.getWorldLyricsVariation().name()),
                        () -> preferences.setWorldLyricsVariation(preferences.getWorldLyricsVariation().next()),
                        () -> preferences.setWorldLyricsVariation(preferences.getWorldLyricsVariation().previous()));
                for (WorldLyricsDirector.Preset preset : WorldLyricsDirector.Preset.values()) {
                    y = addToggle(y, "config.relaylyrics.world_include." + preset.name().toLowerCase(),
                            () -> preferences.isWorldLyricsPresetEnabled(preset),
                            () -> preferences.setWorldLyricsPresetEnabled(preset, true),
                            () -> preferences.setWorldLyricsPresetEnabled(preset, false));
                }
                y = addRow(y, Component.translatable("config.relaylyrics.world_intensity"),
                        () -> enumValue("config.relaylyrics.world_intensity.", preferences.getWorldLyricsIntensity().name()),
                        () -> preferences.setWorldLyricsIntensity(preferences.getWorldLyricsIntensity().next()),
                        () -> preferences.setWorldLyricsIntensity(preferences.getWorldLyricsIntensity().previous()));
                y = addToggle(y, "config.relaylyrics.world_player_reactions", preferences::isWorldLyricsPlayerReactions,
                        () -> preferences.setWorldLyricsPlayerReactions(true),
                        () -> preferences.setWorldLyricsPlayerReactions(false));
                y = addWorldSection(y, "config.relaylyrics.world_section.position");
                y = addRow(y, Component.translatable("config.relaylyrics.world_surface_preference"),
                        () -> enumValue("config.relaylyrics.world_surface_preference.", preferences.getWorldLyricsSurfacePreference().name()),
                        () -> preferences.setWorldLyricsSurfacePreference(preferences.getWorldLyricsSurfacePreference().next()),
                        () -> preferences.setWorldLyricsSurfacePreference(preferences.getWorldLyricsSurfacePreference().previous()));
                y = addRow(y, Component.translatable("config.relaylyrics.world_distance_min"),
                        () -> Component.literal(String.format(java.util.Locale.ROOT, "%.1f", preferences.getWorldLyricsMinimumDistance())),
                        () -> preferences.setWorldLyricsMinimumDistance(preferences.getWorldLyricsMinimumDistance() + .5),
                        () -> preferences.setWorldLyricsMinimumDistance(preferences.getWorldLyricsMinimumDistance() - .5));
                y = addRow(y, Component.translatable("config.relaylyrics.world_distance_max"),
                        () -> Component.literal(String.format(java.util.Locale.ROOT, "%.1f", preferences.getWorldLyricsMaximumDistance())),
                        () -> preferences.setWorldLyricsMaximumDistance(preferences.getWorldLyricsMaximumDistance() + 8),
                        () -> preferences.setWorldLyricsMaximumDistance(preferences.getWorldLyricsMaximumDistance() - 8));
                y = addRow(y, Component.translatable("config.relaylyrics.world_distance_sensitivity"),
                        () -> Component.literal(Math.round(preferences.getWorldLyricsDistanceSensitivity() * 100) + "%"),
                        () -> preferences.setWorldLyricsDistanceSensitivity(preferences.getWorldLyricsDistanceSensitivity() + .1),
                        () -> preferences.setWorldLyricsDistanceSensitivity(preferences.getWorldLyricsDistanceSensitivity() - .1));
                y = addRow(y, Component.translatable("config.relaylyrics.world_arc_radius"),
                        () -> Component.literal(Math.round(preferences.getWorldLyricsArcRadius() * 100) + "%"),
                        () -> preferences.setWorldLyricsArcRadius(preferences.getWorldLyricsArcRadius() + .1),
                        () -> preferences.setWorldLyricsArcRadius(preferences.getWorldLyricsArcRadius() - .1));
                y = addRow(y, Component.translatable("config.relaylyrics.world_center_gap"),
                        () -> Component.literal(Math.round(preferences.getWorldLyricsCenterGap() * 100) + "%"),
                        () -> preferences.setWorldLyricsCenterGap(preferences.getWorldLyricsCenterGap() + .08),
                        () -> preferences.setWorldLyricsCenterGap(preferences.getWorldLyricsCenterGap() - .08));
                y = addWorldSection(y, "config.relaylyrics.world_section.volume");
                y = addRow(y, Component.translatable("config.relaylyrics.world_material"),
                        () -> enumValue("config.relaylyrics.world_material.", preferences.getWorldLyricsMaterial().name()),
                        () -> preferences.setWorldLyricsMaterial(preferences.getWorldLyricsMaterial().next()),
                        () -> preferences.setWorldLyricsMaterial(preferences.getWorldLyricsMaterial().previous()));
                y = addToggle(y, "config.relaylyrics.world_bevel", preferences::isWorldLyricsBevel,
                        () -> preferences.setWorldLyricsBevel(true), () -> preferences.setWorldLyricsBevel(false));
                y = addRow(y, Component.translatable("config.relaylyrics.world_bevel_width"),
                        () -> Component.literal(Math.round(preferences.getWorldLyricsBevelWidth() * 100) + "%"),
                        () -> preferences.setWorldLyricsBevelWidth(preferences.getWorldLyricsBevelWidth() + .1),
                        () -> preferences.setWorldLyricsBevelWidth(preferences.getWorldLyricsBevelWidth() - .1));
                y = addToggle(y, "config.relaylyrics.world_contact_shadow", preferences::isWorldLyricsContactShadow,
                        () -> preferences.setWorldLyricsContactShadow(true), () -> preferences.setWorldLyricsContactShadow(false));
                y = addToggle(y, "config.relaylyrics.world_light", preferences::isWorldLyricsWorldLight,
                        () -> preferences.setWorldLyricsWorldLight(true), () -> preferences.setWorldLyricsWorldLight(false));
                y = addRow(y, Component.translatable("config.relaylyrics.world_depth"),
                        () -> Component.literal(Math.round(preferences.getWorldLyricsDepth() * 100) + "%"),
                        () -> preferences.setWorldLyricsDepth(preferences.getWorldLyricsDepth() + .025),
                        () -> preferences.setWorldLyricsDepth(preferences.getWorldLyricsDepth() - .025));
                y = addRow(y, Component.translatable("config.relaylyrics.world_face_color"),
                        () -> Component.literal(String.format("#%06X", preferences.getWorldLyricsFaceColor())),
                        () -> preferences.setWorldLyricsFaceColor(nextFaceColor(preferences.getWorldLyricsFaceColor(), 1)),
                        () -> preferences.setWorldLyricsFaceColor(nextFaceColor(preferences.getWorldLyricsFaceColor(), -1)));
                y = addRow(y, Component.translatable("config.relaylyrics.world_side_color"),
                        () -> Component.literal(String.format("#%06X", preferences.getWorldLyricsSideColor())),
                        () -> preferences.setWorldLyricsSideColor(nextSideColor(preferences.getWorldLyricsSideColor(), 1)),
                        () -> preferences.setWorldLyricsSideColor(nextSideColor(preferences.getWorldLyricsSideColor(), -1)));
                y = addWorldSection(y, "config.relaylyrics.world_section.animations");
                y = addRow(y, Component.translatable("config.relaylyrics.world_entrance"),
                        () -> preferences.getWorldLyricsEntranceOverride() == null
                                ? Component.translatable("config.relaylyrics.world_entrance.auto")
                                : enumValue("config.relaylyrics.world_entrance.", preferences.getWorldLyricsEntranceOverride().name()),
                        () -> preferences.setWorldLyricsEntranceOverride(nextEntrance(preferences.getWorldLyricsEntranceOverride(), 1)),
                        () -> preferences.setWorldLyricsEntranceOverride(nextEntrance(preferences.getWorldLyricsEntranceOverride(), -1)));
                y = addRow(y, Component.translatable("config.relaylyrics.world_animation_strength"),
                        () -> Component.literal(Math.round(preferences.getWorldLyricsAnimationIntensity() * 100) + "%"),
                        () -> preferences.setWorldLyricsAnimationIntensity(preferences.getWorldLyricsAnimationIntensity() + .1),
                        () -> preferences.setWorldLyricsAnimationIntensity(preferences.getWorldLyricsAnimationIntensity() - .1));
                y = addRow(y, Component.translatable("config.relaylyrics.world_animation_speed"),
                        () -> Component.literal(Math.round(preferences.getWorldLyricsAnimationSpeed() * 100) + "%"),
                        () -> preferences.setWorldLyricsAnimationSpeed(preferences.getWorldLyricsAnimationSpeed() + .1),
                        () -> preferences.setWorldLyricsAnimationSpeed(preferences.getWorldLyricsAnimationSpeed() - .1));
                y = addToggle(y, "config.relaylyrics.world_trails", preferences::isWorldLyricsTrails,
                        () -> preferences.setWorldLyricsTrails(true), () -> preferences.setWorldLyricsTrails(false));
                y = addWorldSection(y, "config.relaylyrics.world_section.quality");
                y = addRow(y, Component.translatable("config.relaylyrics.world_quality"),
                        () -> enumValue("config.relaylyrics.world_quality.", preferences.getWorldLyricsQuality().name()),
                        () -> preferences.setWorldLyricsQuality(preferences.getWorldLyricsQuality().next()),
                        () -> preferences.setWorldLyricsQuality(preferences.getWorldLyricsQuality().previous()));
                y = addRow(y, Component.translatable("config.relaylyrics.world_preview"),
                        () -> Component.translatable("config.relaylyrics.world_preview.button"),
                        WorldLyricsRenderer::previewCurrentPhrase, null);
                y = addRow(y, Component.translatable("config.relaylyrics.world_reset_position"),
                        () -> Component.translatable("config.relaylyrics.restore"), () -> {
                            preferences.setWorldLyricsMaximumDistance(192);
                            preferences.setWorldLyricsMinimumDistance(6);
                            preferences.setWorldLyricsDistanceSensitivity(1);
                            preferences.setWorldLyricsArcRadius(.65);
                            preferences.setWorldLyricsCenterGap(.32);
                            preferences.setWorldLyricsSurfacePreference(WorldLyricsDirector.SurfacePreference.CONTEXT);
                        }, null);
                y = addRow(y, Component.translatable("config.relaylyrics.world_reset_volume"),
                        () -> Component.translatable("config.relaylyrics.restore"), () -> {
                            preferences.setWorldLyricsDepth(.10);
                            preferences.setWorldLyricsSideColor(0x56647A);
                            preferences.setWorldLyricsFaceColor(0xFFFFFF);
                            preferences.setWorldLyricsQuality(WorldLyricsDirector.Quality.MEDIUM);
                            preferences.setWorldLyricsMaterial(WorldLyricsDirector.Material.AUTO);
                            preferences.setWorldLyricsBevel(true);
                            preferences.setWorldLyricsBevelWidth(.45);
                            preferences.setWorldLyricsContactShadow(true);
                            preferences.setWorldLyricsWorldLight(true);
                        }, null);
                y = addRow(y, Component.translatable("config.relaylyrics.world_reset_animation"),
                        () -> Component.translatable("config.relaylyrics.restore"), () -> {
                            preferences.setWorldLyricsAnimationIntensity(1);
                            preferences.setWorldLyricsAnimationSpeed(1);
                            preferences.setWorldLyricsTrails(false);
                            preferences.setWorldLyricsEntranceOverride(null);
                        }, null);
            }
            case EXPERIMENTAL -> {
                y = addToggle(y, "config.relaylyrics.world_lyrics", preferences::isWorldLyrics,
                        preferences::enableWorldLyricsWithModernDefaults, () -> preferences.setWorldLyrics(false));
                y = addRow(y, Component.translatable("config.relaylyrics.reactive_source"),
                        () -> enumValue("config.relaylyrics.reactive_source.",
                                preferences.getLyricsReactiveSource().name()),
                        () -> preferences.setLyricsReactiveSource(
                                preferences.getLyricsReactiveSource().next()),
                        () -> preferences.setLyricsReactiveSource(
                                preferences.getLyricsReactiveSource().previous()));
                y = addRow(y, Component.translatable("config.relaylyrics.reactive_output_reload"),
                        () -> Component.translatable("config.relaylyrics.reactive_output_reload.button"),
                        () -> {
                            MusicService music = RelayLyricsClient.music();
                            if (music != null) music.reloadReactiveAudioOutput();
                        }, null);
                y = addRow(y, Component.translatable("config.relaylyrics.reactive_intensity"),
                        () -> Component.literal(Math.round(
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
                y = addRow(y, Component.translatable("config.relaylyrics.reactive_hue_amount"),
                        () -> Component.literal(Math.round(
                                preferences.getLyricsReactiveHueAmount()) + "\u00B0"),
                        () -> preferences.setLyricsReactiveHueAmount(
                                preferences.getLyricsReactiveHueAmount() + 10),
                        () -> preferences.setLyricsReactiveHueAmount(
                                preferences.getLyricsReactiveHueAmount() - 10));
                y = addToggle(y, "config.relaylyrics.reactive_bloom",
                        preferences::isLyricsReactiveBloom,
                        () -> preferences.setLyricsReactiveBloom(true),
                        () -> preferences.setLyricsReactiveBloom(false));
                y = addRow(y, Component.translatable("config.relaylyrics.reactive_bloom_amount"),
                        () -> Component.literal(Math.round(
                                preferences.getLyricsReactiveBloomAmount()) + "%"),
                        () -> preferences.setLyricsReactiveBloomAmount(
                                preferences.getLyricsReactiveBloomAmount() + 25),
                        () -> preferences.setLyricsReactiveBloomAmount(
                                preferences.getLyricsReactiveBloomAmount() - 25));
                y = addToggle(y, "config.relaylyrics.reactive_spectral",
                        preferences::isLyricsReactiveSpectral,
                        () -> preferences.setLyricsReactiveSpectral(true),
                        () -> preferences.setLyricsReactiveSpectral(false));
                y = addRow(y, Component.translatable("config.relaylyrics.reactive_spectral_amount"),
                        () -> Component.literal(Math.round(
                                preferences.getLyricsReactiveSpectralAmount()) + "%"),
                        () -> preferences.setLyricsReactiveSpectralAmount(
                                preferences.getLyricsReactiveSpectralAmount() + 25),
                        () -> preferences.setLyricsReactiveSpectralAmount(
                                preferences.getLyricsReactiveSpectralAmount() - 25));
                y = addToggle(y, "config.relaylyrics.reactive", preferences::isLyricsReactiveBackground,
                        () -> preferences.setLyricsReactiveBackground(true),
                        () -> preferences.setLyricsReactiveBackground(false));
                MusicService music = RelayLyricsClient.music();
                addDynamicRow(y, Component.translatable("config.relaylyrics.volume"),
                        () -> music == null
                                ? Component.literal("—")
                                : Component.literal(Math.round(music.snapshot().volume() * 100) + "%"),
                        null, null);
            }
        }
        positionRows();

        int doneWidth = Math.min(200, contentWidth);
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), ignored -> onClose())
                .bounds((width - doneWidth) / 2, height - 28, doneWidth, 20).build());
    }

    private int visibleRowCount() { return Math.max(1, (height - 99) / 25); }

    private void positionRows() {
        int visibleCount = visibleRowCount();
        scrollRows = Math.clamp(scrollRows, 0, Math.max(0, rows.size() - visibleCount));
        settingsBottom = 58;
        for (int i = 0; i < rows.size(); i++) {
            Button button = rows.get(i).button;
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
        return addRow(y, Component.translatable(labelKey),
                () -> Component.translatable(enabled.get() ? "options.on" : "options.off"), increase, decrease);
    }

    /** Integration probe used only by -Dlyrica.worldMeshSelfTest=true in the development client. */
    public static void verifyWorldTabVisibility() {
        Minecraft client = Minecraft.getInstance();
        Screen previous = client.gui.screen();
        ClientPreferences preferences = RelayLyricsClient.preferences();
        boolean enabled = preferences.isWorldLyrics();
        try {
            preferences.setWorldLyrics(false);
            LyricsConfigScreen screen = new LyricsConfigScreen(previous);
            client.gui.setScreen(screen);
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
            client.gui.setScreen(previous);
        }
    }

    private void expectWorldTab(boolean expected) {
        String title = Component.translatable(Category.WORLD.key).getString();
        boolean present = children().stream().anyMatch(child -> child instanceof Button button
                && button.getY() == 28 && button.getMessage().getString().equals(title));
        if (present != expected) throw new IllegalStateException("Unexpected world configuration tab visibility");
    }

    private void expectWorldToggle() {
        String label = Component.translatable("config.relaylyrics.world_lyrics").getString();
        if (rows.stream().noneMatch(row -> row.label.getString().equals(label)))
            throw new IllegalStateException("World lyrics toggle missing from Experimental settings");
    }

    private int addWorldSection(int y, String key) {
        return addRow(y, Component.translatable(key), () -> Component.literal(""), null, null);
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

    private static WorldLyricsDirector.Entrance nextEntrance(WorldLyricsDirector.Entrance current, int direction) {
        var values = WorldLyricsDirector.Entrance.values();
        int index = current == null ? 0 : current.ordinal() + 1;
        int next = Math.floorMod(index + direction, values.length + 1);
        return next == 0 ? null : values[next - 1];
    }

    private int addRow(int y, Component label, Supplier<Component> value, Runnable increase, Runnable decrease) {
        return addRow(y, label, value, increase, decrease, false);
    }

    private int addDynamicRow(int y, Component label, Supplier<Component> value,
                              Runnable increase, Runnable decrease) {
        return addRow(y, label, value, increase, decrease, true);
    }

    private int addRow(int y, Component label, Supplier<Component> value, Runnable increase,
                       Runnable decrease, boolean dynamic) {
        Component initialValue = value.get();
        Button control = Button.builder(initialValue, ignored -> {
            apply(increase);
        }).bounds(controlLeft, y, width - contentLeft - controlLeft, 20).build();
        if (label.getContents() instanceof TranslatableContents translatable
                && !I18n.get(translatable.getKey() + ".desc").equals(translatable.getKey() + ".desc"))
            control.setTooltip(Tooltip.create(Component.translatable(translatable.getKey() + ".desc")));
        control.active = increase != null || decrease != null;
        int available = Math.max(20, controlLeft - contentLeft - 16);
        Component fittedLabel = Component.literal(font.plainSubstrByWidth(label.getString(), available));
        rows.add(new Row(fittedLabel, control, value, decrease, dynamic, initialValue));
        addRenderableWidget(control);
        return y + 25;
    }

    /** Called by the version adapter because mouse callback signatures differ between Minecraft versions. */
    public boolean handleSecondaryClick(double mouseX, double mouseY, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_RIGHT) return false;
        for (Row row : rows) {
            Button control = row.button;
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

    private static Component enumValue(String prefix, String value) {
        return Component.translatable(prefix + value.toLowerCase());
    }

    private void changeWeight(LyricsFontWeight weight) {
        preferences.setLyricsFontWeight(weight);
        String family = preferences.getGoogleFontFamily();
        if (family.isEmpty()) {
            if (RelayLyricsClient.overlay() != null) RelayLyricsClient.overlay().fontChanged();
            return;
        }
        GoogleFontManager.install(family, weight.cssWeight()).thenAccept(result -> {
            Minecraft client = Minecraft.getInstance();
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
        Minecraft client = Minecraft.getInstance();
        if (client != null) client.execute(() -> client.gui.setScreen(new LyricsConfigScreen(parent)));
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
        preferences.setWorldLyricsPreset(WorldLyricsDirector.Preset.SIGN);
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
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        renderVanillaScreen(context, mouseX, mouseY, delta);
        drawCentered(context, title, 10, 0xFFFFFFFF);
        for (Row row : rows) {
            if (!row.button.visible) continue;
            context.text(font, row.label, contentLeft + 6,
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

    private void renderPopupPreview(GuiGraphicsExtractor context) {
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

    private Component connectionText() {
        MusicService music = RelayLyricsClient.music();
        if (RelayLyricsClient.disabledByRelay()) return Component.translatable("config.relaylyrics.managed_by_relay");
        if (music != null && music.spicetifyConnected()) return Component.translatable("config.relaylyrics.connected");
        if (music != null && music.bridgeAlive()) return Component.translatable("config.relaylyrics.waiting");
        return Component.translatable("config.relaylyrics.offline");
    }

    private static Component valueOrDash(String value) {
        return value == null || value.isBlank() ? Component.literal("—") : Component.literal(value);
    }

    private void drawCentered(GuiGraphicsExtractor context, Component text, int y, int color) {
        context.text(font, text, (width - font.width(text)) / 2, y, color);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void onClose() {
        preferences.save();
        Minecraft.getInstance().gui.setScreen(parent);
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
        private final Component label;
        private final Button button;
        private final Supplier<Component> value;
        private final Runnable decrease;
        private final boolean dynamic;
        private Component displayedValue;

        private Row(Component label, Button button, Supplier<Component> value, Runnable decrease,
                    boolean dynamic, Component displayedValue) {
            this.label = label;
            this.button = button;
            this.value = value;
            this.decrease = decrease;
            this.dynamic = dynamic;
            this.displayedValue = displayedValue;
        }

        private void refresh() {
            Component next = value.get();
            if (next.equals(displayedValue)) return;
            displayedValue = next;
            button.setMessage(next);
        }
    }
}
