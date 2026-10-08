package dev.relaylyrics.client;

import dev.relaylyrics.protocol.ProtocolCodec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

final class ClientPreferencesCompatibilityTest {
    @Test
    void missingStandaloneFieldsUseSafePopupDefaults() throws Exception {
        ClientPreferences preferences = ProtocolCodec.mapper().readValue("{}", ClientPreferences.class);

        assertThat(preferences.isLyricsVisible()).isTrue();
        assertThat(preferences.isLyricsPinned()).isTrue();
        assertThat(preferences.isLyricsDynamicBackground()).isTrue();
        assertThat(preferences.isLyricsBorder()).isTrue();
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
        assertThat(preferences.isModernMode()).isFalse();
        assertThat(preferences.isWorldLyrics()).isFalse();
        assertThat(preferences.getMusicSource()).isEqualTo(MusicSource.AUTOMATIC);
        assertThat(preferences.getLyricsRenderType()).isEqualTo(LyricsRenderType.NON_INTRUSIVE);
        assertThat(preferences.getLyricsWidth()).isEqualTo(420);
        assertThat(preferences.getLyricsHeight()).isEqualTo(260);
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
        assertThat(preferences.getLyricsWidth()).isEqualTo(160);
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
}
