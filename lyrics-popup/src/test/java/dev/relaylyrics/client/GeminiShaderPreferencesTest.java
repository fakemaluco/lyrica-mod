package dev.relaylyrics.client;

import dev.relaylyrics.protocol.ProtocolCodec;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class GeminiShaderPreferencesTest {
    @Test void oldPreferencesDoNotEnableTheExperimentAndCredentialsAreNotExported() throws Exception {
        var p = ClientPreferences.decode("{\"lyricsWidth\":390}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(p.isGeminiShaderEnabled()).isFalse(); assertThat(p.getGeminiShaderModel()).isEqualTo("gemini-flash-latest");
        p.setGeminiShaderEnabled(true); p.setGeminiShaderModel("gemini-3.5-flash-lite"); p.setGeminiShaderDirection("Purple ocean");
        p.setGeminiShaderFps(23); p.setGeminiShaderResolution(256); p.setGeminiShaderBrightness(75);
        var json = ProtocolCodec.mapper().writeValueAsBytes(p); var restored = ClientPreferences.decode(json);
        assertThat(restored.isGeminiShaderEnabled()).isTrue(); assertThat(restored.getGeminiShaderDirection()).isEqualTo("Purple ocean");
        assertThat(restored.getGeminiShaderFps()).isEqualTo(23); assertThat(restored.getGeminiShaderResolution()).isEqualTo(256);
        assertThat(restored.getLyricsWidth()).isEqualTo(390);
        assertThat(new String(json, java.nio.charset.StandardCharsets.UTF_8).toLowerCase()).doesNotContain("geminiapikey", "geminisecret", "geminishaderkey");
    }
    @Test void experimentalControlsAreTwoGroupsDeepAndRestoringSettingsDisablesTheFeature() {
        var p = new ClientPreferences(); var c = new LyricsSettingsCatalog(p, new LyricsSettingsCatalog.Actions() {
            public void run(String name) { } public String status(String name) { return ""; }
        });
        var category = LyricsSettingsCatalog.Category.ADVANCED;
        assertThat(c.entries(category)).noneMatch(e -> e.key.contains("gemini"));
        c.expand("experimental"); assertThat(c.entries(category)).anyMatch(e -> e.key.endsWith("group.gemini_shader"));
        assertThat(c.entries(category)).noneMatch(e -> e.key.endsWith("gemini.enabled"));
        c.expand("gemini_shader"); var controls = c.entries(category);
        assertThat(controls).anyMatch(e -> e.key.endsWith("gemini.model"));
        assertThat(controls).anyMatch(e -> e.key.endsWith("gemini.key") && e.masked);
        assertThat(controls).anyMatch(e -> e.key.endsWith("gemini.regenerate"));
        p.setGeminiShaderEnabled(true); c.restore("all"); assertThat(p.isGeminiShaderEnabled()).isFalse();
    }
}
