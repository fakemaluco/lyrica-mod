package dev.relaylyrics.client;

import dev.relaylyrics.client.LyricsSettingsCatalog.Category;
import dev.relaylyrics.client.music.WorldLyricsDirector;
import dev.relaylyrics.protocol.ProtocolCodec;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class LyricsSettingsCatalogTest {
    private static LyricsSettingsCatalog catalog(ClientPreferences p) {
        return new LyricsSettingsCatalog(p, new LyricsSettingsCatalog.Actions() {
            public void run(String action) { }
            public String status(String name) { return name; }
        });
    }
    private static List<String> keys(LyricsSettingsCatalog catalog, Category category) {
        return catalog.entries(category).stream().map(e -> e.key.substring(LyricsSettingsCatalog.PREFIX.length())).toList();
    }
    @Test void organizedCustomCategoryAndSmallMainPagesWithOptionalWorld() {
        var p = new ClientPreferences(); var c = catalog(p);
        assertThat(c.categories()).containsExactly(Category.GENERAL, Category.APPEARANCE, Category.CUSTOM, Category.CONNECTION, Category.ADVANCED);
        for (var category : c.categories()) assertThat(c.entries(category)).hasSizeLessThanOrEqualTo(8);
        p.setWorldLyrics(true);
        assertThat(c.categories()).endsWith(Category.WORLD);
        for (var category : c.categories()) assertThat(c.entries(category)).hasSizeLessThanOrEqualTo(8);
        assertThat(keys(c, Category.ADVANCED)).doesNotContain("world_lyrics");
        c.expand("experimental"); assertThat(keys(c, Category.ADVANCED)).contains("world_lyrics");
        assertThat(keys(c, Category.GENERAL)).doesNotContain("world_lyrics");
    }
    @Test void openingEveryCategoryDoesNotMigrateOrOverwriteCustomValues() throws Exception {
        var p = ClientPreferences.decode("""
                {"worldLyricsPositionVersion":5,"worldLyrics":true,"worldLyricsAutomatic":false,"worldLyricsPreset":"MURAL",
                 "lyricsShadowDistance":3,"lyricsShadowOpacity":18,"lyricsGlowIntensity":"STRONG","lyricsTextScale":1.35,
                 "musicSource":"WINDOWS_MEDIA","spotifyClientId":"keep-client","googleFontFamily":"Inter"}
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String before = ProtocolCodec.mapper().writeValueAsString(p);
        var c = catalog(p);
        for (var category : c.categories()) c.entries(category);
        for (String group : List.of("effects", "background", "world", "experimental", "performance", "interaction", "connection_details")) c.expand(group);
        for (var category : c.categories()) c.entries(category);
        assertThat(LyricsSettingsPresets.effects(p)).isEqualTo(LyricsSettingsPresets.Effects.CUSTOM);
        assertThat(ProtocolCodec.mapper().writeValueAsString(p)).isEqualTo(before);
    }
    @Test void presetsTouchOnlyDocumentedEffectFields() {
        var p = new ClientPreferences();
        p.setGoogleFontFamily("Inter"); p.setLyricsTextScale(1.3); p.setLyricsAnimations(false);
        p.setLyricsAnimationFrameRate(30); p.setLyricsAlignment(LyricsTextAlignment.CENTER);
        p.setLyricsX(123); p.setLyricsY(87); p.setMusicSource(MusicSource.YOUTUBE_MUSIC); p.setLyricsTranslation(false);
        for (var preset : List.of(LyricsSettingsPresets.Effects.NONE, LyricsSettingsPresets.Effects.SOFT, LyricsSettingsPresets.Effects.SPICY)) {
            LyricsSettingsPresets.applyEffects(p, preset);
            assertThat(LyricsSettingsPresets.effects(p)).isEqualTo(preset);
            assertThat(p.isLyricsAnimations()).isFalse(); assertThat(p.getLyricsAnimationFrameRate()).isEqualTo(30);
            assertThat(p.getGoogleFontFamily()).isEqualTo("Inter"); assertThat(p.getLyricsTextScale()).isEqualTo(1.3);
            assertThat(p.getLyricsAlignment()).isEqualTo(LyricsTextAlignment.CENTER);
            assertThat(p.getLyricsX()).isEqualTo(123); assertThat(p.getLyricsY()).isEqualTo(87);
            assertThat(p.getMusicSource()).isEqualTo(MusicSource.YOUTUBE_MUSIC); assertThat(p.isLyricsTranslation()).isFalse();
        }
    }
    @Test void allAdditionalAndInventoryCombinationsRoundTrip() throws Exception {
        for (int value = 0; value < 4; value++) {
            var p = new ClientPreferences();
            LyricsSettingsPresets.setAdditionalText(p, value); LyricsSettingsPresets.setInventoryContent(p, value);
            var restored = ProtocolCodec.mapper().readValue(ProtocolCodec.mapper().writeValueAsBytes(p), ClientPreferences.class);
            assertThat(LyricsSettingsPresets.additionalText(restored)).isEqualTo(value);
            assertThat(LyricsSettingsPresets.inventoryContent(restored)).isEqualTo(value);
        }
    }
    @Test void disablingEffectsHidesControlsAndKeepsTheirValues() {
        var p = new ClientPreferences(); var c = catalog(p);
        c.expand("effects"); c.expand("background"); c.expand("world");
        p.setLyricsShadowDistance(3); p.setLyricsReactiveHueAmount(135); p.setWorldLyricsAutomatic(false);
        p.setWorldLyricsPreset(WorldLyricsDirector.Preset.MONOLITH);
        p.setLyricsTextShadow(false); LyricsSettingsPresets.applyBackground(p, LyricsSettingsPresets.Background.SIMPLE);
        assertThat(keys(c, Category.CUSTOM)).doesNotContain("shadow_distance", "reactive_hue_amount", "world_depth");
        p.setLyricsTextShadow(true); LyricsSettingsPresets.applyBackground(p, LyricsSettingsPresets.Background.REACTIVE); p.setWorldLyrics(true);
        assertThat(keys(c, Category.CUSTOM)).contains("shadow_distance", "reactive_hue_amount", "world_depth");
        assertThat(p.getLyricsShadowDistance()).isEqualTo(3); assertThat(p.getLyricsReactiveHueAmount()).isEqualTo(135);
        p.setWorldLyricsAutomatic(true); assertThat(keys(c, Category.WORLD)).doesNotContain("world_preset");
        p.setWorldLyricsAutomatic(false); assertThat(keys(c, Category.WORLD)).contains("world_preset");
        assertThat(p.getWorldLyricsPreset()).isEqualTo(WorldLyricsDirector.Preset.MONOLITH);
    }
    @Test void manualConnectionMethodsStaySelectableAndRecognized() {
        var p = new ClientPreferences(); var c = catalog(p);
        for (var source : MusicSource.values()) {
            p.setMusicSource(source);
            var entry = c.entries(Category.CONNECTION).get(0);
            assertThat(entry.value.get()).isEqualTo("@config.relaylyrics.music_source." + source.name().toLowerCase(java.util.Locale.ROOT));
            assertThat(entry.options).hasSize(MusicSource.values().length);
            c.expand("connection_details");
            var methods = c.entries(Category.CONNECTION).stream().filter(e -> e.key.endsWith(".music_source")).findFirst().orElseThrow();
            assertThat(methods.options).hasSize(MusicSource.values().length);
        }
    }
    @Test void detailedSettingsStayInCustomizeAndNoPreviewActionsRemain() {
        var p = new ClientPreferences(); var c = catalog(p);
        for (String group : List.of("effects", "background", "world", "typography", "interaction")) c.expand(group);
        p.setWorldLyrics(true);
        assertThat(keys(c, Category.CUSTOM)).contains("font", "weight", "scale", "translation", "romanization", "glow", "shadow", "width", "height", "dynamic", "reactive", "world_depth");
        assertThat(keys(c, Category.ADVANCED)).doesNotContain("shadow", "glow", "world_depth", "font", "width");
        for (var category : c.categories()) assertThat(keys(c, category)).noneMatch(key -> key.contains("preview"));
    }
    @Test void restoresRespectScopeAndRetainCredentialsAndMusicSource() {
        var p = new ClientPreferences(); var c = catalog(p);
        p.setWorldLyricsQuality(WorldLyricsDirector.Quality.HIGH); p.setWorldLyricsMaximumDistance(230); p.setWorldLyricsAnimationSpeed(1.7);
        c.restore("world_volume");
        assertThat(p.getWorldLyricsQuality()).isEqualTo(WorldLyricsDirector.Quality.HIGH);
        assertThat(p.getWorldLyricsMaximumDistance()).isEqualTo(230); assertThat(p.getWorldLyricsAnimationSpeed()).isEqualTo(1.7);
        p.setMusicSource(MusicSource.SPICETIFY_BRIDGE); p.setSpotifyClientId("keep-me"); p.setLyricsX(133);
        c.restore("appearance"); assertThat(p.getLyricsX()).isEqualTo(133);
        c.restore("all");
        assertThat(p.getSpotifyClientId()).isEqualTo("keep-me"); assertThat(p.getMusicSource()).isEqualTo(MusicSource.SPICETIFY_BRIDGE);
        assertThat(p.getLyricsX()).isEqualTo(24); assertThat(p.isWorldLyrics()).isFalse();
    }
    @Test void restoringDistanceWorksEvenWhenPreviousMaximumWasBelowDefaultMinimum() {
        var p = new ClientPreferences(); var c = catalog(p);
        p.setWorldLyricsMinimumDistance(3); p.setWorldLyricsMaximumDistance(3);
        c.restore("world_position");
        assertThat(p.getWorldLyricsMinimumDistance()).isEqualTo(6);
        assertThat(p.getWorldLyricsMaximumDistance()).isEqualTo(192);
    }
    @Test void allCatalogLabelsAndChoicesExistInBothLanguages() throws Exception {
        for (String language : List.of("en_us", "pt_br")) {
            try (var stream = getClass().getResourceAsStream("/assets/relaylyrics/lang/" + language + ".json")) {
                var translations = ProtocolCodec.mapper().readTree(stream);
                var p = new ClientPreferences(); var c = catalog(p);
                p.setWorldLyrics(true); LyricsSettingsPresets.applyBackground(p, LyricsSettingsPresets.Background.REACTIVE);
                for (String group : List.of("effects", "background", "world", "typography", "experimental", "performance", "interaction", "connection_details")) c.expand(group);
                for (boolean automatic : List.of(true, false)) {
                    p.setWorldLyricsAutomatic(automatic);
                    for (var category : c.categories()) for (var entry : c.entries(category)) {
                        assertThat(translations.has(entry.key)).as(language + ": " + entry.key).isTrue();
                        for (String option : entry.options) if (option.startsWith("@config."))
                            assertThat(translations.has(option.substring(1))).as(language + ": " + option).isTrue();
                    }
                }
            }
        }
    }
}
