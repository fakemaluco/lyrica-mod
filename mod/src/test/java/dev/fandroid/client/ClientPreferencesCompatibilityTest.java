package dev.fandroid.client;

import dev.fandroid.protocol.ProtocolCodec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClientPreferencesCompatibilityTest {
    @Test
    void obsoletePopupStyleIsIgnoredAndLegacyFontFallsBackToDefault() throws Exception {
        ClientPreferences preferences = ProtocolCodec.mapper().readValue(
                "{\"lyricsPopupStyle\":\"GLASS\",\"lyricsFont\":\"UNIFORM\"}", ClientPreferences.class);

        assertThat(preferences.getLyricsFont()).isEqualTo(LyricsFont.MINECRAFT);
        assertThat(preferences.getGoogleFontFamily()).isEmpty();
        assertThat(preferences.isLyricsTextShadow()).isTrue();
        assertThat(preferences.isLyricsBold()).isFalse();
        assertThat(preferences.getLyricsAlignment()).isEqualTo(LyricsTextAlignment.LEFT);
        assertThat(preferences.getLyricsFontWeight()).isEqualTo(LyricsFontWeight.REGULAR);
        assertThat(preferences.getLyricsEffects()).isEqualTo(LyricsEffectsMode.ENHANCED);
        assertThat(preferences.isLyricsClickSeek()).isTrue();
        assertThat(preferences.isModernMode()).isFalse();
        assertThat(preferences.getMusicSource()).isEqualTo(MusicSource.AUTOMATIC);
        assertThat(preferences.getLyricsRenderType()).isEqualTo(LyricsRenderType.NON_INTRUSIVE);
        assertThat(preferences.isPhoneDarkMode()).isFalse();
        assertThat(ProtocolCodec.mapper().writeValueAsString(preferences))
                .doesNotContain("lyricsPopupStyle")
                .contains("\"lyricsFont\":\"MINECRAFT\"");
    }

    @Test
    void darkModeIsSerializedWhenEnabled() throws Exception {
        ClientPreferences preferences = new ClientPreferences();
        preferences.setPhoneDarkMode(true);

        String json = ProtocolCodec.mapper().writeValueAsString(preferences);
        assertThat(json).contains("\"phoneDarkMode\":true");
        assertThat(ProtocolCodec.mapper().readValue(json, ClientPreferences.class).isPhoneDarkMode()).isTrue();
    }

    @Test
    void googleFontAndTextShadowAreSerialized() throws Exception {
        ClientPreferences preferences = new ClientPreferences();
        preferences.setGoogleFontFamily("  Noto   Sans JP  ");
        preferences.setLyricsTextShadow(false);
        preferences.setLyricsBold(true);

        assertThat(preferences.getGoogleFontFamily()).isEqualTo("Noto Sans JP");
        assertThat(preferences.getLyricsFont()).isEqualTo(LyricsFont.GOOGLE);
        ClientPreferences restored = ProtocolCodec.mapper().readValue(
                ProtocolCodec.mapper().writeValueAsBytes(preferences), ClientPreferences.class);
        assertThat(restored.getGoogleFontFamily()).isEqualTo("Noto Sans JP");
        assertThat(restored.isLyricsTextShadow()).isFalse();
        assertThat(restored.isLyricsBold()).isTrue();
        assertThat(restored.getLyricsFontWeight()).isEqualTo(LyricsFontWeight.BOLD);
    }

}
