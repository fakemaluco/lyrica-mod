package dev.relaylyrics.client;

import dev.relaylyrics.client.music.WorldLyricsDirector;
import dev.relaylyrics.client.music.WorldLyricsExperience;
import dev.relaylyrics.client.music.WorldLyricsSpace;
import dev.relaylyrics.client.music.WorldLyricsLights;
import dev.relaylyrics.client.music.MultiSourceLyricsClient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** Shared decisions, dependencies and restore scopes. No Minecraft widget or rendering APIs. */
public final class LyricsSettingsCatalog {
    public static final String PREFIX = "config.relaylyrics.";
    public enum Category {
        GENERAL("general"), APPEARANCE("appearance"), CUSTOM("custom"), CONNECTION("connection"), SOURCES("sources"), ADVANCED("advanced"), WORLD("world");
        public final String key;
        Category(String name) { key = PREFIX + "category." + name; }
    }
    public enum Kind { TOGGLE, CHOICE, SLIDER, ACTION, INFO, GROUP }
    public interface Actions {
        void run(String action);
        String status(String name);
        default boolean videoPopupProfile() { return false; }
        default boolean squareVideoPopupProfile() { return false; }
    }
    public static final class Entry {
        public final String key;
        public final Kind kind;
        public Supplier<String> value = () -> "";
        public List<String> options = List.of();
        public IntSupplier selected = () -> 0;
        public IntConsumer select = ignored -> { };
        public Runnable activate = () -> { };
        public DoubleSupplier number = () -> 0;
        public DoubleConsumer setNumber = ignored -> { };
        public double min, max, step;
        public boolean changesStructure;
        Entry(String name, Kind kind) { this.key = PREFIX + name; this.kind = kind; }
    }
    private final ClientPreferences p;
    private final Actions actions;
    private final Set<String> expanded = new HashSet<>();
    private final List<Entry> entries = new ArrayList<>();

    public LyricsSettingsCatalog(ClientPreferences preferences, Actions actions) {
        p = preferences;
        this.actions = actions;
    }
    public List<Category> categories() {
        return List.of(Category.values());
    }
    public void expand(String group) { expanded.add(group); }
    public boolean expanded(String group) { return expanded.contains(group); }

    /** Search can inspect folded sections without changing the user's navigation or preferences. */
    public List<Entry> searchEntries(Category category) {
        Set<String> savedExpansion = new HashSet<>(expanded);
        List<Entry> savedEntries = List.copyOf(entries);
        try {
            for (int depth = 0; depth < 8; depth++) {
                List<Entry> all = entries(category);
                boolean unfolded = false;
                for (Entry entry : all) {
                    if (entry.kind == Kind.GROUP && entry.key.startsWith(PREFIX + "group."))
                        unfolded |= expanded.add(entry.key.substring((PREFIX + "group.").length()));
                }
                if (!unfolded) return all;
            }
            return entries(category);
        } finally {
            expanded.clear(); expanded.addAll(savedExpansion);
            entries.clear(); entries.addAll(savedEntries);
        }
    }

    public List<Entry> entries(Category category) {
        entries.clear();
        switch (category) {
            case GENERAL -> {
                toggle("visible", p::isLyricsVisible, p::setLyricsVisible);
                choice("render_type", LyricsRenderType.values(), p::getLyricsRenderType, p::setLyricsRenderType);
                indexed("inventory_content", List.of("inventory_content.none", "inventory_content.lyrics", "inventory_content.cover", "inventory_content.both"),
                        () -> LyricsSettingsPresets.inventoryContent(p), n -> LyricsSettingsPresets.setInventoryContent(p, n));
                toggle("pinned", p::isLyricsPinned, p::setLyricsPinned);
                indexed("additional_text", List.of("additional_text.none", "additional_text.translation", "additional_text.romanization", "additional_text.both"),
                        () -> LyricsSettingsPresets.additionalText(p), n -> LyricsSettingsPresets.setAdditionalText(p, n));
                toggle("animations", p::isLyricsAnimations, p::setLyricsAnimations).changesStructure = true;
                action("adjust_layout", "adjust_layout.button", () -> actions.run("layout"));
            }
            case APPEARANCE -> {
                indexed("visual_style", List.of("visual_style.classic", "visual_style.modern"), () -> p.isModernMode() ? 1 : 0, n -> p.setModernMode(n == 1));
                action("font", "font", () -> actions.run("font")).value = () -> p.getGoogleFontFamily().isEmpty() ? "@" + PREFIX + "google_font.minecraft" : p.getGoogleFontFamily();
                percent("scale", .6, 1.8, .05, p::getLyricsTextScale, p::setLyricsTextScale);
                choice("alignment", LyricsTextAlignment.values(), p::getLyricsAlignment, p::setLyricsAlignment);
                choice("effects_preset", LyricsSettingsPresets.Effects.values(), () -> LyricsSettingsPresets.effects(p), v -> {
                    if (v == LyricsSettingsPresets.Effects.CUSTOM) { expanded.add("effects"); actions.run("custom_effects"); }
                    else LyricsSettingsPresets.applyEffects(p, v);
                });
                choice("background_preset", LyricsSettingsPresets.Background.values(), () -> LyricsSettingsPresets.background(p), v -> { LyricsSettingsPresets.applyBackground(p, v); actions.run("background_style"); }).changesStructure = true;
                if (group("motion_artwork")) {
                    toggle("motion.canvas", p::isSpotifyCanvas, value -> { p.setSpotifyCanvas(value); actions.run("motion_artwork"); });
                    toggle("motion.animated_cover", p::isAnimatedCovers, value -> { p.setAnimatedCovers(value); actions.run("motion_artwork"); });
                    toggle("motion.background", p::isCanvasBackground, value -> { p.setCanvasBackground(value); actions.run("motion_artwork"); });
                    toggle("motion.pause", p::isPauseMotionWithMusic, value -> { p.setPauseMotionWithMusic(value); actions.run("motion_artwork"); });
                    info("motion.status", () -> actions.status("motion_artwork"));
                    action("motion.retry", "motion.retry.button", () -> actions.run("motion_artwork"));
                }
                action("restore_appearance", "restore", () -> restore("appearance"));
            }
            case CONNECTION -> {
                List<MusicSource> main = List.of(MusicSource.values());
                indexed("music_app", main.stream().map(v -> "music_source." + lower(v)).toList(), () -> main.indexOf(p.getMusicSource()), n -> {
                    p.setMusicSource(main.get(n)); actions.run("source");
                }).changesStructure = true;
                action("connection.setup", "connection.setup.button", () -> actions.run("connection_setup"));
                info("connection.status", () -> actions.status("connection"));
                info("connection.method", () -> actions.status("method"));
                info("connection.playback", () -> actions.status("playback"));
                info("connection.lyrics", () -> actions.status("lyrics"));
                action("reload", "reload.button", () -> actions.run("retry"));
                if (group("connection_details")) {
                    choice("music_source", MusicSource.values(), p::getMusicSource, v -> { p.setMusicSource(v); actions.run("source"); }).changesStructure = true;
                    action("youtube.matching", "spotify.configure", () -> actions.run("spotify"));
                    info("connection.client_id", p::getSpotifyClientId);
                    info("connection.code", () -> actions.status("code"));
                    info("connection.port", () -> actions.status("port"));
                    info("connection.extension", () -> actions.status("extension"));
                    info("connection.track", () -> actions.status("track"));
                    info("connection.device", () -> actions.status("device"));
                }
            }
            case CUSTOM -> custom();
            case SOURCES -> {
                info("sources.explanation", () -> "@" + PREFIX + "sources.explanation.value");
                toggle("sources.prefer_spicy", p::isPreferSpicyLyrics, enabled -> { p.setPreferSpicyLyrics(enabled); actions.run("lyrics_sources"); });
                if (group("source_priority")) {
                    var priorityOrder = p.getLyricsSourceOrder();
                    for (String id : priorityOrder) {
                        Entry e = entry("sources.priority." + id, Kind.CHOICE);
                        e.options = java.util.stream.IntStream.rangeClosed(1, priorityOrder.size()).mapToObj(n -> Integer.toString(n)).toList();
                        e.selected = () -> priorityOrder.indexOf(id);
                        e.value = () -> "#" + (e.selected.getAsInt() + 1);
                        e.select = index -> { p.moveLyricsSource(id, index); actions.run("lyrics_sources"); };
                        e.changesStructure = true;
                    }
                    action("sources.priority.reset", "restore", () -> { p.setLyricsSourceOrder(null); actions.run("lyrics_sources"); }).changesStructure = true;
                }
                for (var source : MultiSourceLyricsClient.sources()) {
                    toggle("sources." + source.id(), () -> p.isLyricsSourceEnabled(source.id()), enabled -> {
                        p.setLyricsSourceEnabled(source.id(), enabled); actions.run("lyrics_sources");
                    });
                }
                action("sources.restore", "restore", () -> {
                    p.setLyricsSources(null); actions.run("lyrics_sources");
                });
                info("sources.diagnostics", () -> actions.status("sources"));
            }
            case ADVANCED -> advanced();
            case WORLD -> {
                toggle("world_lyrics", p::isWorldLyrics, enabled -> {
                    if (enabled) p.enableWorldLyricsWithModernDefaults(); else p.setWorldLyrics(false);
                }).changesStructure = true;
                if (p.isWorldLyrics()) {
                    choice("world_style", WorldLyricsExperience.Style.values(), p::getWorldLyricsStyle, p::setWorldLyricsStyle);
                    percent("world_size", .75, 2, .05, p::getWorldLyricsSize, p::setWorldLyricsSize);
                    choice("world_position", WorldLyricsSpace.Position.values(), p::getWorldLyricsPosition, p::setWorldLyricsPosition);
                    slider("world_luminance", 0, 15, 1, p::getWorldLyricsLuminance, v -> p.setWorldLyricsLuminance((int)Math.round(v)), "");
                    toggle("world_terrain_light", p::isWorldLyricsTerrainLight, p::setWorldLyricsTerrainLight);
                    choice("world_quality", WorldLyricsDirector.Quality.values(), p::getWorldLyricsQuality, p::setWorldLyricsQuality);
                    action("world_customize", "customize.button", () -> { expanded.add("world"); actions.run("custom_world"); });
                }
            }
        }
        return List.copyOf(entries);
    }

    private void advanced() {
        if (group("performance")) {
            indexed("animation_fps", List.of("animation_fps.0", "animation_fps.30", "animation_fps.60"),
                    () -> p.getLyricsAnimationFrameRate() == 0 ? 0 : p.getLyricsAnimationFrameRate() == 30 ? 1 : 2,
                    n -> p.setLyricsAnimationFrameRate(n == 0 ? 0 : n == 1 ? 30 : 60));
            toggle("loading_skeleton", p::isLyricsLoadingSkeleton, p::setLyricsLoadingSkeleton);
            action("restore_performance", "restore", () -> restore("performance"));
        }
        action("defaults", "restore", () -> actions.run("restore_all"));
    }

    private void custom() {
        if (group("typography")) {
            action("font", "font", () -> actions.run("font")).value = () -> p.getGoogleFontFamily().isEmpty() ? "@" + PREFIX + "google_font.minecraft" : p.getGoogleFontFamily();
            choice("weight", LyricsFontWeight.values(), p::getLyricsFontWeight, v -> { p.setLyricsFontWeight(v); actions.run("weight"); });
            percent("scale", .6, 1.8, .05, p::getLyricsTextScale, p::setLyricsTextScale);
            choice("alignment", LyricsTextAlignment.values(), p::getLyricsAlignment, p::setLyricsAlignment);
            toggle("translation", p::isLyricsTranslation, p::setLyricsTranslation);
            toggle("romanization", p::isLyricsRomanization, p::setLyricsRomanization);
        }
        if (group("interaction")) {
            toggle("allow_gui_overlap", p::isLyricsAllowGuiOverlap, p::setLyricsAllowGuiOverlap);
            toggle("click_seek", p::isLyricsClickSeek, p::setLyricsClickSeek);
            toggle("border", p::isLyricsBorder, p::setLyricsBorder);
            toggle("resize_grip", p::isLyricsResizeGrip, p::setLyricsResizeGrip);
            integer("width", actions.squareVideoPopupProfile() ? 64 : 96, 900, 2,
                    () -> p.getPopupWidth(actions.videoPopupProfile(), actions.squareVideoPopupProfile()),
                    v -> p.setPopupWidth(actions.videoPopupProfile(), actions.squareVideoPopupProfile(), v), " px");
            integer("height", 64, 600, 2,
                    () -> p.getPopupHeight(actions.videoPopupProfile(), actions.squareVideoPopupProfile()),
                    v -> p.setPopupHeight(actions.videoPopupProfile(), actions.squareVideoPopupProfile(), v), " px");
            action("reset_layout", "reset", p::resetLayout);
        }
        if (group("effects")) {
            toggle("simple", p::isSimpleLyricsMode, p::setSimpleLyricsMode).changesStructure = true;
            if (!p.isSimpleLyricsMode()) choice("glow", LyricsGlowIntensity.values(), p::getLyricsGlowIntensity, p::setLyricsGlowIntensity);
            if (p.isLyricsAnimations()) choice("transition", LyricsTransitionSpeed.values(), p::getLyricsTransitionSpeed, p::setLyricsTransitionSpeed);
            toggle("shadow", p::isLyricsTextShadow, p::setLyricsTextShadow).changesStructure = true;
            if (p.isLyricsTextShadow()) {
                integer("shadow_opacity", 0, 100, 5, p::getLyricsShadowOpacity, p::setLyricsShadowOpacity, "%");
                integer("shadow_distance", 1, 4, 1, p::getLyricsShadowDistance, p::setLyricsShadowDistance, " px");
                indexed("shadow_softness", List.of("shadow_softness.0", "shadow_softness.1", "shadow_softness.2"), p::getLyricsShadowSoftness, p::setLyricsShadowSoftness);
            }
            action("restore_effects", "restore", () -> restore("effects"));
        }
        if (group("background")) {
            toggle("dynamic", p::isLyricsDynamicBackground, p::setLyricsDynamicBackground).changesStructure = true;
            toggle("reactive", p::isLyricsReactiveBackground, p::setLyricsReactiveBackground).changesStructure = true;
            integer("opacity", 0, 100, 5, p::getLyricsBackgroundOpacity, p::setLyricsBackgroundOpacity, "%");
            toggle("cover_shadow", p::isLyricsCoverShadow, p::setLyricsCoverShadow);
            if (p.isLyricsDynamicBackground() && !p.isLyricsArtistBackground()) {
                integer("blur", 0, 3, 1, p::getLyricsBlurStrength, p::setLyricsBlurStrength, "");
                if (p.isLyricsAnimations()) integer("speed", 0, 3, 1, p::getLyricsBackgroundSpeed, p::setLyricsBackgroundSpeed, "");
            }
            if (p.isLyricsReactiveBackground()) {
                choice("reactive_source", ClientPreferences.ReactiveSource.values(), p::getLyricsReactiveSource, p::setLyricsReactiveSource).changesStructure = true;
                if (p.getLyricsReactiveSource() != ClientPreferences.ReactiveSource.WORDS) action("reactive_output_reload", "reactive_output_reload.button", () -> actions.run("audio"));
                percent("reactive_intensity", .25, 3, .05, p::getLyricsReactiveIntensity, p::setLyricsReactiveIntensity);
                toggle("reactive_hue", p::isLyricsReactiveHue, p::setLyricsReactiveHue).changesStructure = true;
                if (p.isLyricsReactiveHue()) {
                    toggle("reactive_hue_auto", p::isLyricsReactiveHueAuto, p::setLyricsReactiveHueAuto);
                    slider("reactive_hue_amount", 0, 180, 5, p::getLyricsReactiveHueAmount, p::setLyricsReactiveHueAmount, "°");
                }
                toggle("reactive_bloom", p::isLyricsReactiveBloom, p::setLyricsReactiveBloom).changesStructure = true;
                if (p.isLyricsReactiveBloom()) slider("reactive_bloom_amount", 0, 300, 5, p::getLyricsReactiveBloomAmount, p::setLyricsReactiveBloomAmount, "%");
                toggle("reactive_spectral", p::isLyricsReactiveSpectral, p::setLyricsReactiveSpectral).changesStructure = true;
                if (p.isLyricsReactiveSpectral()) slider("reactive_spectral_amount", 0, 300, 5, p::getLyricsReactiveSpectralAmount, p::setLyricsReactiveSpectralAmount, "%");
            }
            action("restore_background", "restore", () -> restore("background"));
        }
        if (p.isWorldLyrics() && group("world")) {
            action("font", "font", () -> actions.run("font"));
            slider("world_depth", .04, .30, .01, p::getWorldLyricsDepth, p::setWorldLyricsDepth, "");
            colors("world_face_color", new int[]{0xFFFFFF, 0xE4F5FF, 0xFFF0D2, 0xDEF7E4, 0xF5E1FF}, p::getWorldLyricsFaceColor, p::setWorldLyricsFaceColor);
            colors("world_side_color", new int[]{0x56647A, 0x323A4B, 0x7A5B3C, 0x377B85, 0x805487}, p::getWorldLyricsSideColor, p::setWorldLyricsSideColor);
            percent("world_animation_strength", 0, 1.5, .1, p::getWorldLyricsAnimationIntensity, p::setWorldLyricsAnimationIntensity);
            toggle("world_light", p::isWorldLyricsWorldLight, p::setWorldLyricsWorldLight);
            info("world_light_status", () -> "@" + PREFIX + (WorldLyricsLights.available() ? "world_light_status.ready" : "world_light_status.missing"));
            action("world_restore", "restore", () -> restore("world_experience"));
        }
    }

    public void restore(String scope) {
        ClientPreferences d = new ClientPreferences();
        switch (scope) {
            case "appearance" -> {
                p.setModernMode(d.isModernMode()); p.setGoogleFontFamily(""); p.setLyricsFontWeight(d.getLyricsFontWeight());
                p.setLyricsTextScale(d.getLyricsTextScale()); p.setLyricsAlignment(d.getLyricsAlignment());
                restore("effects"); restore("background"); restore("motion_artwork"); actions.run("font_changed");
            }
            case "effects" -> LyricsSettingsPresets.applyEffects(p, LyricsSettingsPresets.Effects.SPICY);
            case "background" -> {
                p.setLyricsArtistBackground(d.isLyricsArtistBackground()); p.setLyricsDynamicBackground(d.isLyricsDynamicBackground()); p.setLyricsReactiveBackground(d.isLyricsReactiveBackground());
                p.setLyricsBackgroundOpacity(d.getLyricsBackgroundOpacity()); p.setLyricsBlurStrength(d.getLyricsBlurStrength());
                p.setLyricsBackgroundSpeed(d.getLyricsBackgroundSpeed()); p.setLyricsCoverShadow(d.isLyricsCoverShadow());
                p.setLyricsReactiveSource(d.getLyricsReactiveSource()); p.setLyricsReactiveIntensity(d.getLyricsReactiveIntensity());
                p.setLyricsReactiveHue(d.isLyricsReactiveHue()); p.setLyricsReactiveHueAuto(d.isLyricsReactiveHueAuto());
                p.setLyricsReactiveHueAmount(d.getLyricsReactiveHueAmount()); p.setLyricsReactiveBloom(d.isLyricsReactiveBloom());
                p.setLyricsReactiveBloomAmount(d.getLyricsReactiveBloomAmount()); p.setLyricsReactiveSpectral(d.isLyricsReactiveSpectral());
                p.setLyricsReactiveSpectralAmount(d.getLyricsReactiveSpectralAmount());
            }
            case "motion_artwork" -> {
                p.setSpotifyCanvas(d.isSpotifyCanvas()); p.setAnimatedCovers(d.isAnimatedCovers());
                p.setCanvasBackground(d.isCanvasBackground()); p.setPauseMotionWithMusic(d.isPauseMotionWithMusic());
                actions.run("motion_artwork");
            }
            case "performance" -> { p.setLyricsAnimationFrameRate(d.getLyricsAnimationFrameRate()); p.setLyricsLoadingSkeleton(d.isLyricsLoadingSkeleton()); }
            case "world_position" -> {
                p.setWorldLyricsSurfacePreference(d.getWorldLyricsSurfacePreference()); p.setWorldLyricsAutoWalls(d.isWorldLyricsAutoWalls());
                p.setWorldLyricsCinematic(d.isWorldLyricsCinematic()); p.setWorldLyricsPlayerReactions(d.isWorldLyricsPlayerReactions());
                p.setWorldLyricsMaximumDistance(d.getWorldLyricsMaximumDistance()); p.setWorldLyricsMinimumDistance(d.getWorldLyricsMinimumDistance());
                p.setWorldLyricsDistanceSensitivity(d.getWorldLyricsDistanceSensitivity()); p.setWorldLyricsArcRadius(d.getWorldLyricsArcRadius());
                p.setWorldLyricsCenterGap(d.getWorldLyricsCenterGap()); p.setWorldLyricsVariation(d.getWorldLyricsVariation());
                for (var preset : WorldLyricsDirector.Preset.values()) p.setWorldLyricsPresetEnabled(preset, true);
            }
            case "world_volume" -> {
                p.setWorldLyricsMaterial(d.getWorldLyricsMaterial()); p.setWorldLyricsDepth(d.getWorldLyricsDepth());
                p.setWorldLyricsBevel(d.isWorldLyricsBevel()); p.setWorldLyricsBevelWidth(d.getWorldLyricsBevelWidth());
                p.setWorldLyricsContactShadow(d.isWorldLyricsContactShadow()); p.setWorldLyricsWorldLight(d.isWorldLyricsWorldLight());
                p.setWorldLyricsSideColor(d.getWorldLyricsSideColor()); p.setWorldLyricsFaceColor(d.getWorldLyricsFaceColor());
            }
            case "world_animation" -> {
                p.setWorldLyricsEntranceOverride(null); p.setWorldLyricsAnimationIntensity(d.getWorldLyricsAnimationIntensity());
                p.setWorldLyricsAnimationSpeed(d.getWorldLyricsAnimationSpeed()); p.setWorldLyricsTrails(d.isWorldLyricsTrails());
            }
            case "world_experience" -> {
                restore("world_volume"); restore("world_animation");
                p.setWorldLyricsStyle(d.getWorldLyricsStyle()); p.setWorldLyricsAccent(d.getWorldLyricsAccent());
                p.setWorldLyricsTerrainLight(d.isWorldLyricsTerrainLight()); p.setWorldLyricsLuminance(d.getWorldLyricsLuminance()); p.setWorldLyricsPosition(d.getWorldLyricsPosition());
                p.setWorldLyricsSize(d.getWorldLyricsSize()); p.setWorldLyricsSurfacePreference(d.getWorldLyricsSurfacePreference());
                p.setWorldLyricsQuality(d.getWorldLyricsQuality()); p.setWorldLyricsCinematic(d.isWorldLyricsCinematic());
            }
            case "all" -> {
                restore("world_experience");
                restore("appearance"); restore("performance"); restore("world_position"); restore("world_volume"); restore("world_animation");
                p.resetLayout(); p.setLyricsVisible(d.isLyricsVisible()); p.setLyricsPinned(d.isLyricsPinned());
                p.setLyricsRenderType(d.getLyricsRenderType()); p.setInventoryPopup(d.isInventoryPopup()); p.setInventoryNowPlaying(d.isInventoryNowPlaying());
                p.setLyricsTranslation(d.isLyricsTranslation()); p.setLyricsRomanization(d.isLyricsRomanization()); p.setLyricsAnimations(d.isLyricsAnimations());
                p.setLyricsAllowGuiOverlap(d.isLyricsAllowGuiOverlap()); p.setLyricsClickSeek(d.isLyricsClickSeek());
                p.setLyricsBorder(d.isLyricsBorder()); p.setLyricsResizeGrip(d.isLyricsResizeGrip()); p.setWorldLyrics(false);
                p.setWorldLyricsAutomatic(true); p.setWorldLyricsPreset(d.getWorldLyricsPreset()); p.setWorldLyricsIntensity(d.getWorldLyricsIntensity());
                p.setWorldLyricsSize(d.getWorldLyricsSize()); p.setWorldLyricsQuality(d.getWorldLyricsQuality());
            }
            default -> throw new IllegalArgumentException("Unknown restore scope: " + scope);
        }
    }

    private boolean group(String name) {
        Entry e = entry("group." + name, Kind.GROUP);
        e.value = () -> expanded.contains(name) ? "@" + PREFIX + "collapse" : "@" + PREFIX + "expand";
        e.activate = () -> { if (!expanded.remove(name)) expanded.add(name); };
        e.changesStructure = true;
        return expanded.contains(name);
    }
    private Entry entry(String name, Kind kind) { Entry e = new Entry(name, kind); entries.add(e); return e; }
    private Entry toggle(String name, BooleanSupplier getter, Consumer<Boolean> setter) {
        Entry e = entry(name, Kind.TOGGLE);
        e.value = () -> "@options." + (getter.getAsBoolean() ? "on" : "off");
        e.activate = () -> setter.accept(!getter.getAsBoolean());
        return e;
    }
    private Entry indexed(String name, List<String> keys, IntSupplier getter, IntConsumer setter) {
        Entry e = entry(name, Kind.CHOICE);
        e.options = keys.stream().map(v -> "@" + PREFIX + v).toList(); e.selected = getter; e.select = setter;
        e.value = () -> e.options.get(Math.clamp(getter.getAsInt(), 0, e.options.size() - 1));
        return e;
    }
    private <T extends Enum<T>> Entry choice(String name, T[] values, Supplier<T> getter, Consumer<T> setter) {
        return indexed(name, Arrays.stream(values).map(v -> name + "." + lower(v)).toList(), () -> getter.get().ordinal(), n -> setter.accept(values[n]));
    }
    private Entry slider(String name, double min, double max, double step, DoubleSupplier getter, DoubleConsumer setter, String suffix) {
        Entry e = entry(name, Kind.SLIDER); e.min = min; e.max = max; e.step = step; e.number = getter; e.setNumber = setter;
        e.value = () -> (step >= 1 ? Long.toString(Math.round(getter.getAsDouble())) : String.format(Locale.ROOT, "%.2f", getter.getAsDouble())) + suffix;
        return e;
    }
    private void percent(String name, double min, double max, double step, DoubleSupplier getter, DoubleConsumer setter) {
        slider(name, min, max, step, getter, setter, "").value = () -> Math.round(getter.getAsDouble() * 100) + "%";
    }
    private void integer(String name, int min, int max, int step, IntSupplier getter, IntConsumer setter, String suffix) {
        slider(name, min, max, step, () -> getter.getAsInt(), v -> setter.accept((int) Math.round(v)), suffix);
    }
    private Entry action(String name, String button, Runnable runnable) {
        Entry e = entry(name, Kind.ACTION); e.activate = runnable; e.value = () -> "@" + PREFIX + button; e.changesStructure = name.contains("restore") || name.contains("reset"); return e;
    }
    private void info(String name, Supplier<String> value) { entry(name, Kind.INFO).value = value; }
    private void colors(String name, int[] colors, IntSupplier getter, IntConsumer setter) {
        List<String> values = new ArrayList<>(); for (int color : colors) values.add(String.format(Locale.ROOT, "#%06X", color));
        int found = -1; for (int i = 0; i < colors.length; i++) if (colors[i] == getter.getAsInt()) found = i;
        int current = found;
        if (found < 0) values.add(String.format(Locale.ROOT, "#%06X", getter.getAsInt()));
        Entry e = entry(name, Kind.CHOICE); e.options = List.copyOf(values); e.selected = () -> current < 0 ? colors.length : current;
        e.select = n -> { if (n < colors.length) setter.accept(colors[n]); };
        e.value = () -> String.format(Locale.ROOT, "#%06X", getter.getAsInt());
    }
    private static String lower(Enum<?> value) { return value.name().toLowerCase(Locale.ROOT); }
}
