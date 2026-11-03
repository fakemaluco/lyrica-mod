package dev.relaylyrics.client;

import dev.relaylyrics.protocol.ProtocolCodec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

final class ClientPreferencesCompatibilityTest {
    @Test
    void oldBackgroundPreferencesKeepTheirModeAndArtistChoiceSurvivesReload() throws Exception {
        var p = ProtocolCodec.mapper().readValue("{}", ClientPreferences.class);
        assertThat(LyricsSettingsPresets.background(p)).isEqualTo(LyricsSettingsPresets.Background.COVER);
        LyricsSettingsPresets.applyBackground(p, LyricsSettingsPresets.Background.ARTIST);
        var loaded = ProtocolCodec.mapper().readValue(ProtocolCodec.mapper().writeValueAsBytes(p), ClientPreferences.class);
        assertThat(loaded.isLyricsArtistBackground()).isTrue();
        assertThat(LyricsSettingsPresets.background(loaded)).isEqualTo(LyricsSettingsPresets.Background.ARTIST);
        LyricsSettingsPresets.applyBackground(loaded, LyricsSettingsPresets.Background.COVER);
        assertThat(loaded.isLyricsArtistBackground()).isFalse();
    }
    @Test
    void motionArtworkDefaultsAndExplicitChoicesSurviveReload() throws Exception {
        var preferences = ProtocolCodec.mapper().readValue("{}", ClientPreferences.class);
        assertThat(preferences.isSpotifyCanvas()).isTrue();
        assertThat(preferences.isAnimatedCovers()).isTrue();
        assertThat(preferences.isCanvasBackground()).isTrue();
        assertThat(preferences.isPauseMotionWithMusic()).isTrue();
        preferences.setSpotifyCanvas(false);
        preferences.setAnimatedCovers(false);
        preferences.setCanvasBackground(true);
        preferences.setPauseMotionWithMusic(false);
        var restored = ProtocolCodec.mapper().readValue(ProtocolCodec.mapper().writeValueAsBytes(preferences), ClientPreferences.class);
        assertThat(restored.isSpotifyCanvas()).isFalse();
        assertThat(restored.isAnimatedCovers()).isFalse();
        assertThat(restored.isCanvasBackground()).isTrue();
        assertThat(restored.isPauseMotionWithMusic()).isFalse();
    }

    @Test
    void oldPreferencesEnableSourcesAndExplicitEmptyChoiceStaysEmpty() throws Exception {
        var preferences = ProtocolCodec.mapper().readValue("{}", ClientPreferences.class);
        assertThat(preferences.getLyricsSources()).contains("spicy_lyrics", "lrc_red", "lrclib", "amll_ttml_db");
        preferences.setLyricsSourceEnabled("lrc_red", false);
        var restored = ProtocolCodec.mapper().readValue(ProtocolCodec.mapper().writeValueAsBytes(preferences), ClientPreferences.class);
        assertThat(restored.isLyricsSourceEnabled("lrc_red")).isFalse();
        assertThat(restored.isLyricsSourceEnabled("lrclib")).isTrue();
        preferences.setLyricsSources(java.util.List.of());
        restored = ProtocolCodec.mapper().readValue(ProtocolCodec.mapper().writeValueAsBytes(preferences), ClientPreferences.class);
        assertThat(restored.getLyricsSources()).isEmpty();
    }

    @Test
    void sourcePriorityNormalizesUnknownDuplicatesAndSurvivesReload() throws Exception {
        var preferences = new ClientPreferences();
        var defaults = preferences.getLyricsSourceOrder();
        assertThat(defaults).startsWith("spicy_community", "amll_ttml_db", "apple_music");
        preferences.setLyricsSourceOrder(java.util.Arrays.asList("lrclib", null, "unknown", "lrclib", "apple_music"));
        preferences.moveLyricsSource("genius", 0);
        var restored = ProtocolCodec.mapper().readValue(ProtocolCodec.mapper().writeValueAsBytes(preferences), ClientPreferences.class);
        assertThat(restored.getLyricsSourceOrder()).startsWith("genius", "lrclib", "apple_music");
        assertThat(restored.getLyricsSourceOrder()).containsExactlyInAnyOrderElementsOf(defaults);
        restored.setLyricsSourceOrder(null);
        assertThat(restored.getLyricsSourceOrder()).isEqualTo(defaults);
    }

    @Test void spicyIsPreferredForOldConfigurationsAndTheChoiceSurvivesReload() throws Exception {
        var preferences = ProtocolCodec.mapper().readValue("{}", ClientPreferences.class);
        assertThat(preferences.isPreferSpicyLyrics()).isTrue();
        preferences.setPreferSpicyLyrics(false);
        var restored = ProtocolCodec.mapper().readValue(ProtocolCodec.mapper().writeValueAsBytes(preferences), ClientPreferences.class);
        assertThat(restored.isPreferSpicyLyrics()).isFalse();
        assertThat(restored.isLyricsSourceEnabled("spicy_lyrics")).isTrue();
    }

    @Test
    void missingStandaloneFieldsUseSafePopupDefaults() throws Exception {
        ClientPreferences preferences = ProtocolCodec.mapper().readValue("{}", ClientPreferences.class);

        assertThat(preferences.isLyricsVisible()).isTrue();
        assertThat(preferences.isLyricsPinned()).isTrue();
        assertThat(preferences.isLyricsDynamicBackground()).isTrue();
        assertThat(preferences.isLyricsBorder()).isFalse();
        assertThat(preferences.isLyricsResizeGrip()).isFalse();
        assertThat(preferences.isLyricsTextShadow()).isTrue();
        assertThat(preferences.isLyricsBold()).isFalse();
        assertThat(preferences.getGoogleFontFamily()).isEmpty();
        assertThat(preferences.getLyricsEffects()).isEqualTo(LyricsEffectsMode.ENHANCED);
        assertThat(preferences.getLyricsAlignment()).isEqualTo(LyricsTextAlignment.LEFT);
        assertThat(preferences.getLyricsFontWeight()).isEqualTo(LyricsFontWeight.REGULAR);
        assertThat(preferences.getLyricsGlowIntensity()).isEqualTo(LyricsGlowIntensity.NORMAL);
        assertThat(preferences.getLyricsTransitionSpeed()).isEqualTo(LyricsTransitionSpeed.SMOOTH);
        assertThat(preferences.isLyricsClickSeek()).isTrue();
        assertThat(preferences.isInventoryPopup()).isTrue();
        assertThat(preferences.isInventoryNowPlaying()).isTrue();
        assertThat(preferences.isModernMode()).isTrue();
        assertThat(preferences.isWorldLyrics()).isFalse();
        assertThat(preferences.getMusicSource()).isEqualTo(MusicSource.AUTOMATIC);
        assertThat(preferences.getLyricsRenderType()).isEqualTo(LyricsRenderType.NON_INTRUSIVE);
        assertThat(preferences.getLyricsWidth()).isEqualTo(210);
        assertThat(preferences.getLyricsHeight()).isEqualTo(130);
    }

    @Test
    void inventoryElementsCanBeDisabledIndependently() throws Exception {
        ClientPreferences preferences = ProtocolCodec.mapper().readValue("""
                {"inventoryPopup":false,"inventoryNowPlaying":true,"modernMode":true}
                """, ClientPreferences.class);

        assertThat(preferences.isInventoryPopup()).isFalse();
        assertThat(preferences.isInventoryNowPlaying()).isTrue();
        assertThat(preferences.isModernMode()).isTrue();

        preferences.setInventoryPopup(true);
        preferences.setInventoryNowPlaying(false);
        ClientPreferences restored = ProtocolCodec.mapper().readValue(
                ProtocolCodec.mapper().writeValueAsBytes(preferences), ClientPreferences.class);
        assertThat(restored.isInventoryPopup()).isTrue();
        assertThat(restored.isInventoryNowPlaying()).isFalse();
    }

    @Test
    void invalidPersistedValuesAreClampedWhenRead() throws Exception {
        ClientPreferences preferences = ProtocolCodec.mapper().readValue("""
                {"lyricsTextScale":99,"lyricsWidth":10,"lyricsHeight":9999,
                 "lyricsBackgroundOpacity":150,"lyricsBlurStrength":-4}
                """, ClientPreferences.class);

        assertThat(preferences.getLyricsTextScale()).isEqualTo(1.8);
        assertThat(preferences.getLyricsWidth()).isEqualTo(96);
        assertThat(preferences.getLyricsHeight()).isEqualTo(600);
        assertThat(preferences.getLyricsBackgroundOpacity()).isEqualTo(100);
        assertThat(preferences.getLyricsBlurStrength()).isZero();
    }

    @Test
    void customGoogleFontSelectsGeneratedFontResource() throws Exception {
        ClientPreferences preferences = ProtocolCodec.mapper().readValue(
                "{\"googleFontFamily\":\"Inter\",\"lyricsTextShadow\":false,\"lyricsBold\":true}",
                ClientPreferences.class);

        assertThat(preferences.getLyricsFont()).isEqualTo(LyricsFont.GOOGLE);
        assertThat(preferences.getGoogleFontFamily()).isEqualTo("Inter");
        assertThat(preferences.isLyricsTextShadow()).isFalse();
        assertThat(preferences.isLyricsBold()).isTrue();
        assertThat(preferences.getLyricsFontWeight()).isEqualTo(LyricsFontWeight.BOLD);
    }

    @Test
    void resizeGripChoicePersistsWithoutChangingLegacyDefaults() throws Exception {
        ClientPreferences preferences = ProtocolCodec.mapper().readValue("{}", ClientPreferences.class);
        assertThat(preferences.isLyricsResizeGrip()).isFalse();
        preferences.setLyricsResizeGrip(true);
        ClientPreferences restored = ProtocolCodec.mapper().readValue(
                ProtocolCodec.mapper().writeValueAsBytes(preferences), ClientPreferences.class);
        assertThat(restored.isLyricsResizeGrip()).isTrue();
    }

    @Test
    void persistsTheExperimentalWorldLyricsToggle() throws Exception {
        ClientPreferences preferences = ProtocolCodec.mapper().readValue(
                "{\"worldLyrics\":true}", ClientPreferences.class);

        assertThat(preferences.isWorldLyrics()).isTrue();
        ClientPreferences restored = ProtocolCodec.mapper().readValue(
                ProtocolCodec.mapper().writeValueAsBytes(preferences), ClientPreferences.class);
        assertThat(restored.isWorldLyrics()).isTrue();
    }
    @Test void islandDefaultsAndExplicitOptOutSurviveReload() throws Exception {
        var preferences=ProtocolCodec.mapper().readValue("{}",ClientPreferences.class);
        assertThat(preferences.isIslandKawarpBackground()).isTrue(); assertThat(preferences.isIslandVideoCover()).isTrue();
        assertThat(preferences.isPopupDocked()).isTrue(); assertThat(preferences.getLyricsTextScale()).isEqualTo(.6);
        preferences.setIslandKawarpBackground(false); preferences.setIslandVideoCover(false); preferences.setPopupDocked(false); preferences.setLyricsTextScale(1.2);
        var restored=ProtocolCodec.mapper().readValue(ProtocolCodec.mapper().writeValueAsBytes(preferences),ClientPreferences.class);
        assertThat(restored.isIslandKawarpBackground()).isFalse(); assertThat(restored.isIslandVideoCover()).isFalse();
        assertThat(restored.isPopupDocked()).isFalse(); assertThat(restored.getLyricsTextScale()).isEqualTo(1.2);
        restored.resetLayout(); assertThat(restored.isPopupDocked()).isTrue();
    }
}
