package dev.relaylyrics.client;

import dev.relaylyrics.protocol.ProtocolCodec;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class LanguageAndMotionPreferencesTest {
    @Test void videoPlaybackIsIndependentAndNewPreferencesRoundTrip() throws Exception {
        var p = new ClientPreferences();
        p.setLyricsAnimations(false);
        assertThat(p.isMotionVideos()).isTrue();
        p.setMotionVideos(false); p.setAutomaticTranslation(true); p.setTranslationLanguage("pt-BR");
        p.setRomanizationUnderLine(true); p.setUpdateChecks(false); p.setUpdatePrereleases(true);
        p.setTranslationProvider("Gemini"); p.setActiveTranslationModel("my-gemini-model");
        p.setTranslationProvider("OpenAi"); p.setActiveTranslationModel("my-ai-model");
        var restored = ClientPreferences.decode(ProtocolCodec.mapper().writeValueAsBytes(p));
        assertThat(restored.isMotionVideos()).isFalse();
        assertThat(restored.isAutomaticTranslation()).isTrue();
        assertThat(restored.isLyricsTranslation()).isTrue();
        assertThat(restored.getTranslationLanguage()).isEqualTo("pt");
        assertThat(restored.isRomanizationUnderLine()).isTrue();
        assertThat(restored.isUpdateChecks()).isFalse();
        assertThat(restored.isUpdatePrereleases()).isTrue();
        assertThat(restored.translationModel()).isEqualTo("my-ai-model");
        restored.setTranslationProvider("Gemini");
        assertThat(restored.translationModel()).isEqualTo("my-gemini-model");
    }
    @Test void existingPreferencesKeepReplacementModeAndDoNotEnableTranslationRequests() throws Exception {
        var old = ClientPreferences.decode("{\"lyricsRomanization\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(old.isRomanizationUnderLine()).isFalse();
        assertThat(old.isAutomaticTranslation()).isFalse();
        assertThat(new ClientPreferences().isRomanizationUnderLine()).isTrue();
    }
}
