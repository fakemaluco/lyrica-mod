package dev.relaylyrics.client;

import dev.relaylyrics.client.music.WorldLyricsDirector;
import dev.relaylyrics.client.music.WorldLyricsExperience;
import dev.relaylyrics.client.music.WorldLyricsPresentation;
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
    public enum Kind { TOGGLE, CHOICE, SLIDER, ACTION, INFO, GROUP, TEXT }
    public interface Actions {
        void run(String action);
        String status(String name);
        default void input(String name, String value) { }
        default dev.relaylyrics.client.music.PopupSizeProfile popupSizeProfile() {
            return videoPopupProfile() ? squareVideoPopupProfile() ? dev.relaylyrics.client.music.PopupSizeProfile.CANVAS_ONLY
                    : dev.relaylyrics.client.music.PopupSizeProfile.CANVAS_WITH_LYRICS : dev.relaylyrics.client.music.PopupSizeProfile.COVER_WITH_LYRICS;
        }
        default boolean videoPopupProfile() { return false; }
        default boolean squareVideoPopupProfile() { return false; }
    }
    public static final class Entry {
        public final String key;
        public final Kind kind;
        public String section = "";
        public Supplier<String> value = () -> "";
        public List<String> options = List.of();
        public IntSupplier selected = () -> 0;
        public IntConsumer select = ignored -> { };
        public Runnable activate = () -> { };
        public DoubleSupplier number = () -> 0;
        public DoubleConsumer setNumber = ignored -> { };
        public double min, max, step;
        public boolean changesStructure, masked;
        public Consumer<String> setText = ignored -> { };
        Entry(String name, Kind kind) { this.key = PREFIX + name; this.kind = kind; }
        /** Keep a native control alive while refreshing captured preference values and callbacks. */
        public void refreshFrom(Entry next) {
            if (!key.equals(next.key) || kind != next.kind) throw new IllegalArgumentException("Setting identity changed");
            section = next.section; value = next.value; options = next.options; selected = next.selected;
            select = next.select; activate = next.activate; number = next.number; setNumber = next.setNumber;
            min = next.min; max = next.max; step = next.step; changesStructure = next.changesStructure;
            masked = next.masked; setText = next.setText;
        }
    }
    private final ClientPreferences p;
    private final Actions actions;
    private String section = "";
    private final Set<String> expanded = new HashSet<>();
    private final List<Entry> entries = new ArrayList<>();

    public LyricsSettingsCatalog(ClientPreferences preferences, Actions actions) {
        p = preferences;
        this.actions = actions;
    }
    private Entry input(String name, Supplier<String> value, Consumer<String> change, boolean masked) {
        Entry entry = entry(name, Kind.TEXT); entry.value = value; entry.setText = change; entry.masked = masked;
        return entry;
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
        entries.clear(); section = lower(category);
        switch (category) {
            case GENERAL -> {
                toggle("visible", p::isLyricsVisible, p::setLyricsVisible);
                choice("render_type", LyricsRenderType.values(), p::getLyricsRenderType, p::setLyricsRenderType);
                indexed("inventory_content", List.of("inventory_content.none", "inventory_content.lyrics", "inventory_content.cover", "inventory_content.both"),
                        () -> LyricsSettingsPresets.inventoryContent(p), n -> LyricsSettingsPresets.setInventoryContent(p, n));
                toggle("pinned", p::isLyricsPinned, p::setLyricsPinned);
                toggle("popup_edge_docking", p::isPopupEdgeDocking, p::setPopupEdgeDocking);
                indexed("additional_text", List.of("additional_text.none", "additional_text.translation", "additional_text.romanization", "additional_text.both"),
                        () -> LyricsSettingsPresets.additionalText(p), n -> LyricsSettingsPresets.setAdditionalText(p, n));
                toggle("animations", p::isLyricsAnimations, p::setLyricsAnimations).changesStructure = true;
                integer("lyrics_delay", -5_000, 5_000, 25, p::getLyricsDelayMillis, p::setLyricsDelayMillis, " ms");
                action("lyrics_delay_reset", "reset", () -> p.setLyricsDelayMillis(0));
                action("adjust_layout", "adjust_layout.button", () -> actions.run("layout"));
            }
            case APPEARANCE -> {
                section = "style";
                indexed("visual_style", List.of("visual_style.classic", "visual_style.modern"), () -> p.isModernMode() ? 1 : 0, n -> p.setModernMode(n == 1));
                action("font", "font", () -> actions.run("font")).value = () -> p.getGoogleFontFamily().isEmpty() ? "@" + PREFIX + "google_font.minecraft" : p.getGoogleFontFamily();
                percent("scale", .6, 1.8, .05, p::getLyricsTextScale, p::setLyricsTextScale);
                choice("alignment", LyricsTextAlignment.values(), p::getLyricsAlignment, p::setLyricsAlignment);
                section = "appearance_effects";
                choice("effects_preset", LyricsSettingsPresets.Effects.values(), () -> LyricsSettingsPresets.effects(p), v -> {
                    if (v == LyricsSettingsPresets.Effects.CUSTOM) { expanded.add("effects"); actions.run("custom_effects"); }
                    else LyricsSettingsPresets.applyEffects(p, v);
                });
                section = "appearance_background";
                choice("background_preset", LyricsSettingsPresets.Background.values(), () -> LyricsSettingsPresets.background(p), v -> { LyricsSettingsPresets.applyBackground(p, v); actions.run("background_style"); }).changesStructure = true;
                if (group("motion_artwork")) {
                    toggle("motion.videos", p::isMotionVideos, value -> { p.setMotionVideos(value); actions.run("motion_artwork"); });
                    toggle("motion.canvas", p::isSpotifyCanvas, value -> { p.setSpotifyCanvas(value); actions.run("motion_artwork"); });
                    toggle("motion.animated_cover", p::isAnimatedCovers, value -> { p.setAnimatedCovers(value); actions.run("motion_artwork"); });
                    toggle("motion.background", p::isCanvasBackground, value -> { p.setCanvasBackground(value); actions.run("motion_artwork"); });
                    toggle("motion.pause", p::isPauseMotionWithMusic, value -> { p.setPauseMotionWithMusic(value); actions.run("motion_artwork"); });
                    info("motion.status", () -> actions.status("motion_artwork"));
                    action("motion.retry", "motion.retry.button", () -> actions.run("motion_artwork"));
                    if (group("island")) {
                        toggle("island.lyrics", p::isIslandLyrics, p::setIslandLyrics);
                        toggle("island.kawarp", p::isIslandKawarpBackground, p::setIslandKawarpBackground);
                        toggle("island.jade", p::isJadeIsland, p::setJadeIsland);
                        toggle("island.video", p::isIslandVideoCover, value -> { p.setIslandVideoCover(value); actions.run("motion_artwork"); });
                    }
                }
                section = "appearance_tools";
                action("restore_appearance", "restore", () -> restore("appearance"));
            }
            case CONNECTION -> {
                List<MusicSource> main = List.of(MusicSource.values());
                indexed("music_app", main.stream().map(v -> "music_source." + lower(v)).toList(), () -> main.indexOf(p.getMusicSource()), n -> {
                    p.setMusicSource(main.get(n)); actions.run("source");
                }).changesStructure = true;
                action("connection.auto", "connection.auto.button", () -> actions.run("connection_auto"));
                if (group("spotify_account")) {
                    info("spotify.account_status", () -> actions.status("spotify_status"));
                    info("spotify.account_intro", () -> "@" + PREFIX + "spotify.account_intro.value");
                    input("spotify.client_input", p::getSpotifyClientId, p::setSpotifyClientId, false);
                    action("spotify.connect", "spotify.connect", () -> actions.run("spotify_connect"));
                    action("spotify.disconnect", "spotify.disconnect", () -> actions.run("spotify_disconnect"));
                    action("spotify.setup.reopen", "spotify.setup.reopen", () -> actions.run("spotify_reopen"));
                    if (group("spotify_setup")) {
                        info("spotify.setup_step", () -> "@" + PREFIX + "spotify.setup.application");
                        action("spotify.setup.dashboard", "spotify.dashboard.button", () -> actions.run("spotify_dashboard"));
                        action("spotify.setup.redirect", "spotify.copy_redirect", () -> actions.run("spotify_redirect"));
                    }
                }
                if (group("youtube_account")) {
                    info("youtube.account_status", () -> actions.status("youtube_status"));
                    info("youtube.account_intro", () -> "@" + PREFIX + "youtube.account_intro.value");
                    action("youtube.setup.prepare", "youtube.setup.prepare", () -> actions.run("youtube_install"));
                    action("youtube.setup.copy_folder", "youtube.setup.copy_folder", () -> actions.run("youtube_folder"));
                    action("youtube.setup.chrome", "youtube.setup.chrome", () -> actions.run("youtube_chrome"));
                    action("youtube.setup.edge", "youtube.setup.edge", () -> actions.run("youtube_edge"));
                    info("youtube.load_instructions", () -> "@" + PREFIX + "youtube.setup.load");
                    action("youtube.setup.open_music", "youtube.setup.open_music", () -> actions.run("youtube_open"));
                    action("youtube.setup.copy_code", "youtube.setup.copy_code", () -> actions.run("youtube_pair"));
                }
                section = "connection_status";
                info("connection.status", () -> actions.status("connection"));
                info("connection.playback", () -> actions.status("playback"));
                if (group("music_presence")) {
                    toggle("music_presence.enabled", p::isMusicPresenceEnabled, p::setMusicPresenceEnabled);
                    toggle("music_presence.sharing", p::isMusicPresenceSharing, p::setMusicPresenceSharing);
                    info("music_presence.status", () -> actions.status("music_presence"));
                }
                if (group("connection_details")) {
                    info("connection.method", () -> actions.status("method"));
                    info("connection.lyrics", () -> actions.status("lyrics"));
                    action("reload", "reload.button", () -> actions.run("retry"));
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
                if (group("spicy_account")) {
                    info("spicy.key_status", () -> actions.status("spicy_status"));
                    input("spicy.key_input", () -> actions.status("spicy_key_input"), value -> actions.input("spicy_key", value), true);
                    action("spicy.key_apply", "spicy.key_apply.button", () -> actions.run("spicy_apply"));
                    action("spicy.key_get", "spicy.key_get.button", () -> actions.run("spicy_get"));
                    action("spicy.key_remove", "spicy.key_remove.button", () -> actions.run("spicy_remove"));
                }
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
                section = "source_providers";
                for (var source : MultiSourceLyricsClient.sources()) {
                    toggle("sources." + source.id(), () -> p.isLyricsSourceEnabled(source.id()), enabled -> {
                        p.setLyricsSourceEnabled(source.id(), enabled); actions.run("lyrics_sources");
                    });
                }
                action("sources.restore", "restore", () -> {
                    p.setLyricsSources(null); actions.run("lyrics_sources");
                });
                section = "source_diagnostics";
                info("sources.diagnostics", () -> actions.status("sources"));
            }
            case ADVANCED -> advanced();
            case WORLD -> {
                toggle("world_lyrics", p::isWorldLyrics, enabled -> {
                    if (enabled) p.enableWorldLyricsWithModernDefaults(); else p.setWorldLyrics(false);
                }).changesStructure = true;
                if (p.isWorldLyrics()) {
                    choice("world_presentation_intensity", WorldLyricsPresentation.Intensity.values(), p::getWorldLyricsPresentationIntensity, p::setWorldLyricsPresentationIntensity);
                    toggle("world_reposition", p::isWorldLyricsReposition, p::setWorldLyricsReposition);
                    toggle("world_cinematic", p::isWorldLyricsCinematic, p::setWorldLyricsCinematic);
                    choice("world_style", WorldLyricsExperience.Style.values(), p::getWorldLyricsStyle, p::setWorldLyricsStyle);
                    percent("world_size", .75, 2, .05, p::getWorldLyricsSize, p::setWorldLyricsSize);
                    action("world_customize", "customize.button", () -> { expanded.add("world"); actions.run("custom_world"); });
                }
            }
        }
        return List.copyOf(entries);
    }

    private void advanced() {
        if (CompatibilityManager.jiAfkInstalled())
            toggle("ji_afk_resume_music", p::isJiAfkResumeMusic, p::setJiAfkResumeMusic);
        if (dev.relaylyrics.client.music.AudioDucking.supported() && group("audio_ducking")) {
            toggle("audio_ducking.enabled", p::isAudioDucking, p::setAudioDucking).changesStructure = true;
            if (p.isAudioDucking()) {
                choice("audio_ducking.trigger", ClientPreferences.DuckingTrigger.values(), p::getDuckingTrigger, p::setDuckingTrigger);
                choice("audio_ducking.target", ClientPreferences.DuckingTarget.values(),
                        p::getDuckingTarget, p::setDuckingTarget).changesStructure = true;
                if (p.getDuckingTarget() == ClientPreferences.DuckingTarget.APPLICATION)
                    input("audio_ducking.application", p::getDuckingApplication, p::setDuckingApplication, false);
                percent("audio_ducking.volume", 0, 1, .05, p::getDuckingVolume, p::setDuckingVolume);
            }
        }
        if (group("updates")) {
            toggle("updates.automatic", p::isUpdateChecks, p::setUpdateChecks);
            toggle("updates.prereleases", p::isUpdatePrereleases, p::setUpdatePrereleases);
            info("updates.status", () -> actions.status("updates"));
            action("updates.check", "updates.check.button", () -> actions.run("updates_check"));
            action("updates.open", "updates.open.button", () -> actions.run("updates_open"));
        }
        if (group("support")) {
            info("support.privacy", () -> "@" + PREFIX + "support.privacy.value");
            action("support.copy", "support.copy.button", () -> actions.run("diagnostics_copy"));
            action("support.report", "support.report.button", () -> actions.run("bug_report"));
        }

        if (group("performance")) {
            indexed("animation_fps", List.of("animation_fps.0", "animation_fps.30", "animation_fps.60"),
                    () -> p.getLyricsAnimationFrameRate() == 0 ? 0 : p.getLyricsAnimationFrameRate() == 30 ? 1 : 2,
                    n -> p.setLyricsAnimationFrameRate(n == 0 ? 0 : n == 1 ? 30 : 60));
            toggle("loading_skeleton", p::isLyricsLoadingSkeleton, p::setLyricsLoadingSkeleton);
            action("restore_performance", "restore", () -> restore("performance"));
        }
        if (group("experimental")) {
            if (group("world_gemini")) {
                info("world_gemini.privacy", () -> "@" + PREFIX + "world_gemini.privacy.value");
                toggle("world_gemini.enabled", () -> p.getWorldLyricsDirection() == WorldLyricsPresentation.Direction.GEMINI,
                        enabled -> p.setWorldLyricsDirection(enabled ? WorldLyricsPresentation.Direction.GEMINI : WorldLyricsPresentation.Direction.AUTOMATIC));
                input("world_gemini.key", () -> actions.status("world_gemini_key_input"), value -> actions.input("world_gemini_key", value), true);
                info("world_gemini.key_status", () -> actions.status("world_gemini_key_status"));
                action("world_gemini.key_save", "gemini.key_save.button", () -> actions.run("world_gemini_key_save"));
                action("world_gemini.key_remove", "gemini.key_remove.button", () -> actions.run("world_gemini_key_remove"));
                toggle("world_gemini.song_font", p::isWorldLyricsGeminiFont, p::setWorldLyricsGeminiFont);
                input("world_gemini.model", p::getWorldLyricsGeminiModel, p::setWorldLyricsGeminiModel, false);
                input("world_gemini.direction", p::getWorldLyricsGeminiDirection, p::setWorldLyricsGeminiDirection, false);
                info("world_gemini.status", () -> actions.status("world_gemini_status"));
                action("world_gemini.regenerate", "world_gemini.regenerate.button", () -> actions.run("world_gemini_regenerate"));
            }
            if (group("gemini_shader")) {
                info("gemini.privacy", () -> "@" + PREFIX + "gemini.privacy.value");
                toggle("gemini.enabled", p::isGeminiShaderEnabled, p::setGeminiShaderEnabled);
                input("gemini.key", () -> actions.status("gemini_key_input"), value -> actions.input("gemini_key", value), true);
                info("gemini.key_status", () -> actions.status("gemini_key_status"));
                action("gemini.key_save", "gemini.key_save.button", () -> actions.run("gemini_key_save"));
                action("gemini.key_remove", "gemini.key_remove.button", () -> actions.run("gemini_key_remove"));
                input("gemini.model", p::getGeminiShaderModel, p::setGeminiShaderModel, false);
                toggle("gemini.song_context",p::isGeminiSongContext,p::setGeminiSongContext);
                integer("gemini.creative_freedom",0,100,5,p::getGeminiCreativeFreedom,p::setGeminiCreativeFreedom,"%");
                var recipes=dev.relaylyrics.client.music.GeminiArtLibrary.RECIPES;
                var recipeLabels=new java.util.ArrayList<String>();recipeLabels.add("gemini.recipe.custom");
                recipes.forEach(v->recipeLabels.add("gemini.recipe."+v.id()));
                indexed("gemini.art_recipe",recipeLabels,()->dev.relaylyrics.client.music.GeminiArtLibrary.recipeIndex(p),i->{if(i>0)recipes.get(i-1).apply(p);});
                var primary=dev.relaylyrics.client.music.GeminiArtLibrary.ids(false);
                var secondary=dev.relaylyrics.client.music.GeminiArtLibrary.ids(true);
                indexed("gemini.art_style", primary.stream().map(v->"gemini.art."+v).toList(),()->Math.max(0,primary.indexOf(p.getGeminiArtStyle())),i->p.setGeminiArtStyle(primary.get(i)));
                indexed("gemini.art_secondary", secondary.stream().map(v->"gemini.art."+v).toList(),()->Math.max(0,secondary.indexOf(p.getGeminiArtSecondary())),i->p.setGeminiArtSecondary(secondary.get(i)));
                integer("gemini.art_blend",0,100,5,p::getGeminiArtBlend,p::setGeminiArtBlend,"%");
                integer("gemini.art_scale",25,300,5,p::getGeminiArtScale,p::setGeminiArtScale,"%");
                integer("gemini.art_stroke",25,300,5,p::getGeminiArtStroke,p::setGeminiArtStroke,"%");
                integer("gemini.art_grain",0,100,5,p::getGeminiArtGrain,p::setGeminiArtGrain,"%");
                integer("gemini.art_seed",0,999,1,p::getGeminiArtSeed,p::setGeminiArtSeed,"");
                toggle("gemini.art_animated",p::isGeminiArtAnimated,p::setGeminiArtAnimated);
                toggle("gemini.art_audio",p::isGeminiArtAudio,p::setGeminiArtAudio);
                input("gemini.direction", p::getGeminiShaderDirection, p::setGeminiShaderDirection, false);
                toggle("gemini.automatic", p::isGeminiShaderAutomatic, p::setGeminiShaderAutomatic);
                integer("gemini.world_strength", 0, 100, 5, p::getGeminiWorldStrength, p::setGeminiWorldStrength, "%");
                info("gemini.status", () -> actions.status("gemini_status"));
                info("gemini.design", () -> actions.status("gemini_design"));
                action("gemini.regenerate", "gemini.regenerate.button", () -> actions.run("gemini_regenerate"));
                action("gemini.clear_cache", "gemini.clear_cache.button", () -> actions.run("gemini_clear_cache"));
            }
        }
        section = "advanced_tools";
        action("defaults", "restore", () -> actions.run("restore_all"));
    }

    private void custom() {
        if (group("languages")) {
            toggle("romanization", p::isLyricsRomanization, p::setLyricsRomanization);
            indexed("romanization.mode", List.of("romanization.mode.replace", "romanization.mode.under"),
                    () -> p.isRomanizationUnderLine() ? 1 : 0, n -> p.setRomanizationUnderLine(n == 1));
            toggle("translation", p::isLyricsTranslation, p::setLyricsTranslation);
            var languageCodes = new java.util.ArrayList<>(com.tx24.spicyplayer.translation.TranslationLanguages.INSTANCE.getCommon().keySet());
            if (!languageCodes.contains(p.getTranslationLanguage())) languageCodes.add(p.getTranslationLanguage());
            Entry language = entry("translation.language", Kind.CHOICE);
            language.options = languageCodes.stream().map(code -> java.util.Locale.forLanguageTag(code).getDisplayLanguage(java.util.Locale.getDefault())).toList();
            language.selected = () -> languageCodes.indexOf(p.getTranslationLanguage());
            language.select = index -> p.setTranslationLanguage(languageCodes.get(index));
            language.value = () -> language.options.get(language.selected.getAsInt());
            info("translation.privacy", () -> "@" + PREFIX + "translation.privacy.value");
            toggle("translation.automatic", p::isAutomaticTranslation, p::setAutomaticTranslation);
            action("translation.now", "translation.now.button", () -> actions.run("translation_now"));
            info("translation.status", () -> actions.status("translation"));
            if (group("translation_provider")) {
                var providers = List.of("Google", "DeepL", "Gemini", "OpenAi");
                indexed("translation.provider", providers.stream().map(v -> "translation.provider." + v).toList(),
                        () -> providers.indexOf(p.getTranslationProvider()), n -> { p.setTranslationProvider(providers.get(n)); actions.run("translation_provider"); }).changesStructure = true;
                if (!p.getTranslationProvider().equals("Google")) {
                    input("translation.key", () -> actions.status("translation_key_input"), v -> actions.input("translation_key", v), true);
                    info("translation.key_status", () -> actions.status("translation_key_status"));
                    action("translation.key_save", "translation.key_save.button", () -> actions.run("translation_key_save"));
                    action("translation.key_remove", "translation.key_remove.button", () -> actions.run("translation_key_remove"));
                }
                if (List.of("Gemini", "OpenAi").contains(p.getTranslationProvider())) {
                    input("translation.model", p::translationModel, p::setActiveTranslationModel, false);
                }
                if (p.getTranslationProvider().equals("OpenAi")) input("translation.address", p::getTranslationAddress, p::setTranslationAddress, false);
            }
        }

        if (group("typography")) {
            action("font", "font", () -> actions.run("font")).value = () -> p.getGoogleFontFamily().isEmpty() ? "@" + PREFIX + "google_font.minecraft" : p.getGoogleFontFamily();
            choice("weight", LyricsFontWeight.values(), p::getLyricsFontWeight, v -> { p.setLyricsFontWeight(v); actions.run("weight"); });
            percent("scale", .6, 1.8, .05, p::getLyricsTextScale, p::setLyricsTextScale);
            choice("alignment", LyricsTextAlignment.values(), p::getLyricsAlignment, p::setLyricsAlignment);

        }
        if (group("interaction")) {
            toggle("allow_gui_overlap", p::isLyricsAllowGuiOverlap, p::setLyricsAllowGuiOverlap);
            toggle("click_seek", p::isLyricsClickSeek, p::setLyricsClickSeek);
            toggle("border", p::isLyricsBorder, p::setLyricsBorder);
            toggle("resize_grip", p::isLyricsResizeGrip, p::setLyricsResizeGrip);
            info("popup_profile", () -> "@" + PREFIX + "popup_profile." + lower(actions.popupSizeProfile()));
            integer("width", actions.squareVideoPopupProfile() ? 64 : 96, 900, 2,
                    () -> p.getPopupWidth(actions.popupSizeProfile()),
                    v -> p.setPopupWidth(actions.popupSizeProfile(), v), " px");
            integer("height", 64, 600, 2,
                    () -> p.getPopupHeight(actions.popupSizeProfile()),
                    v -> p.setPopupHeight(actions.popupSizeProfile(), v), " px");
            action("popup_restore", "popup_restore.button", () -> p.setPopupDocked(false));
            action("reset_layout", "reset", p::resetLayout);
        }
        if (group("effects")) {
            choice("line_animation", ClientPreferences.LineLyricsAnimation.values(), p::getLineLyricsAnimation, p::setLineLyricsAnimation);
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
            choice("world_position", WorldLyricsSpace.Position.values(), p::getWorldLyricsPosition, p::setWorldLyricsPosition);
            slider("world_luminance", 0, 15, 1, p::getWorldLyricsLuminance, v -> p.setWorldLyricsLuminance((int)Math.round(v)), "");
            toggle("world_terrain_light", p::isWorldLyricsTerrainLight, p::setWorldLyricsTerrainLight);
            choice("world_quality", WorldLyricsDirector.Quality.values(), p::getWorldLyricsQuality, p::setWorldLyricsQuality);
            action("font", "font", () -> actions.run("font"));
            toggle("world_volumetric", p::isWorldLyricsVolumetric, p::setWorldLyricsVolumetric);
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
                p.setMotionVideos(d.isMotionVideos());
                p.setModernMode(d.isModernMode()); p.setGoogleFontFamily(""); p.setLyricsFontWeight(d.getLyricsFontWeight());
                p.setLyricsTextScale(d.getLyricsTextScale()); p.setLyricsAlignment(d.getLyricsAlignment());
                p.setIslandKawarpBackground(d.isIslandKawarpBackground()); p.setIslandVideoCover(d.isIslandVideoCover()); p.setJadeIsland(d.isJadeIsland());
                p.setIslandLyrics(d.isIslandLyrics());
                restore("effects"); restore("background"); restore("motion_artwork"); actions.run("font_changed");
            }
            case "effects" -> {
                LyricsSettingsPresets.applyEffects(p, LyricsSettingsPresets.Effects.SPICY);
                p.setLineLyricsAnimation(d.getLineLyricsAnimation());
            }
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
                p.setWorldLyricsVolumetric(d.isWorldLyricsVolumetric());
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
                p.setWorldLyricsDirection(d.getWorldLyricsDirection()); p.setWorldLyricsPresentationIntensity(d.getWorldLyricsPresentationIntensity());
                p.setWorldLyricsStyle(d.getWorldLyricsStyle()); p.setWorldLyricsAccent(d.getWorldLyricsAccent());
                p.setWorldLyricsTerrainLight(d.isWorldLyricsTerrainLight()); p.setWorldLyricsLuminance(d.getWorldLyricsLuminance()); p.setWorldLyricsPosition(d.getWorldLyricsPosition());
                p.setWorldLyricsSize(d.getWorldLyricsSize()); p.setWorldLyricsSurfacePreference(d.getWorldLyricsSurfacePreference());
                p.setWorldLyricsQuality(d.getWorldLyricsQuality()); p.setWorldLyricsCinematic(d.isWorldLyricsCinematic());
            }
            case "all" -> {
                p.setAudioDucking(d.isAudioDucking());
                p.setDuckingTrigger(d.getDuckingTrigger()); p.setDuckingTarget(d.getDuckingTarget());
                p.setDuckingApplication(d.getDuckingApplication()); p.setDuckingVolume(d.getDuckingVolume());
                restore("world_experience");
                restore("appearance"); restore("performance"); restore("world_position"); restore("world_volume"); restore("world_animation");
                p.setGeminiCreativeFreedom(85); p.setGeminiSongContext(true); p.setGeminiArtStyle("auto"); p.setGeminiArtSecondary("none"); p.setGeminiArtScale(100); p.setGeminiArtStroke(100); p.setGeminiArtGrain(30); p.setGeminiArtSeed(1); p.setGeminiArtBlend(35); p.setGeminiArtAnimated(false); p.setGeminiArtAudio(true);
                p.setGeminiWorldStrength(100); p.setGeminiShaderEnabled(false); p.setGeminiShaderAutomatic(d.isGeminiShaderAutomatic());
                p.setGeminiShaderModel(d.getGeminiShaderModel()); p.setGeminiShaderDirection("");
                p.setGeminiShaderResolution(d.getGeminiShaderResolution()); p.setGeminiShaderFps(d.getGeminiShaderFps());
                p.setGeminiShaderBrightness(d.getGeminiShaderBrightness());
                p.setLyricsDelayMillis(d.getLyricsDelayMillis());
                p.resetLayout(); p.setLyricsVisible(d.isLyricsVisible()); p.setLyricsPinned(d.isLyricsPinned());
                p.setLyricsRenderType(d.getLyricsRenderType()); p.setInventoryPopup(d.isInventoryPopup()); p.setInventoryNowPlaying(d.isInventoryNowPlaying());
                p.setAutomaticTranslation(d.isAutomaticTranslation()); p.setTranslationLanguage(d.getTranslationLanguage());
                p.setTranslationGeminiModel(""); p.setTranslationProvider(d.getTranslationProvider()); p.setTranslationAddress(d.getTranslationAddress()); p.setTranslationModel(d.getTranslationModel());
                p.setRomanizationUnderLine(d.isRomanizationUnderLine()); p.setUpdateChecks(d.isUpdateChecks()); p.setUpdatePrereleases(d.isUpdatePrereleases());
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
        section = name;
        Entry e = entry("group." + name, Kind.GROUP);
        e.value = () -> expanded.contains(name) ? "@" + PREFIX + "collapse" : "@" + PREFIX + "expand";
        e.activate = () -> { if (!expanded.remove(name)) expanded.add(name); };
        e.changesStructure = true;
        return expanded.contains(name);
    }
    private Entry entry(String name, Kind kind) { Entry e = new Entry(name, kind); e.section = section; entries.add(e); return e; }
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
