package dev.relaylyrics.client;

import dev.relaylyrics.client.music.WorldLyricsPresentation;
import dev.relaylyrics.protocol.ProtocolCodec;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class CinematicLyricsPreferencesTest {
    @Test void legacySettingsUseCalmLocalDirectionWithoutChangingAppearance() throws Exception {
        var p = ClientPreferences.decode("{\"worldLyrics\":true,\"worldLyricsFaceColor\":1193046,\"worldLyricsIntensity\":\"INTENSE\"}".getBytes(StandardCharsets.UTF_8));
        assertThat(p.getWorldLyricsDirection()).isEqualTo(WorldLyricsPresentation.Direction.AUTOMATIC);
        assertThat(p.getWorldLyricsPresentationIntensity()).isEqualTo(WorldLyricsPresentation.Intensity.DISCREET);
        assertThat(p.isWorldLyricsCinematic()).isFalse();
        assertThat(p.isWorldLyricsVolumetric()).isFalse();
        assertThat(p.getWorldLyricsFaceColor()).isEqualTo(0x123456);
        assertThat(p.getWorldLyricsIntensity()).isEqualTo(ClientPreferences.WorldIntensity.INTENSE);
    }

    @Test void directionPreferencesRoundTripIndependentlyFromShaderExperiment() throws Exception {
        var p = new ClientPreferences();
        p.setWorldLyricsDirection(WorldLyricsPresentation.Direction.GEMINI);
        p.setWorldLyricsPresentationIntensity(WorldLyricsPresentation.Intensity.EXPRESSIVE);
        p.setWorldLyricsCinematic(true);
        p.setWorldLyricsGeminiModel(" custom-direction-model ");
        p.setWorldLyricsGeminiDirection("quiet verses and falling chorus");
        p.setGeminiShaderModel("shader-model");
        byte[] json = ProtocolCodec.mapper().writeValueAsBytes(p);
        var restored = ClientPreferences.decode(json);
        assertThat(restored.getWorldLyricsDirection()).isEqualTo(WorldLyricsPresentation.Direction.GEMINI);
        assertThat(restored.getWorldLyricsPresentationIntensity()).isEqualTo(WorldLyricsPresentation.Intensity.EXPRESSIVE);
        assertThat(restored.isWorldLyricsCinematic()).isTrue();
        assertThat(restored.getWorldLyricsGeminiModel()).isEqualTo("custom-direction-model");
        assertThat(restored.getWorldLyricsGeminiDirection()).isEqualTo("quiet verses and falling chorus");
        assertThat(restored.getGeminiShaderModel()).isEqualTo("shader-model");
        assertThat(restored.isGeminiShaderEnabled()).isFalse();
        assertThat(new String(json, StandardCharsets.UTF_8)).doesNotContain("apiKey", "worldGeminiKey");
    }

    @Test void volumeIsIndependentFromDirectionAndAnimationAndRestoresAsAppearance() throws Exception {
        var p = new ClientPreferences();
        p.setWorldLyricsVolumetric(true);
        p.setWorldLyricsPresentationIntensity(WorldLyricsPresentation.Intensity.BALANCED);
        p.setLyricsAnimations(false);
        var restored = ClientPreferences.decode(ProtocolCodec.mapper().writeValueAsBytes(p));
        assertThat(restored.isWorldLyricsVolumetric()).isTrue();
        assertThat(restored.getWorldLyricsDirection()).isEqualTo(WorldLyricsPresentation.Direction.AUTOMATIC);
        assertThat(restored.getWorldLyricsPresentationIntensity()).isEqualTo(WorldLyricsPresentation.Intensity.BALANCED);
        assertThat(restored.isLyricsAnimations()).isFalse();
        var catalog = new LyricsSettingsCatalog(restored, new LyricsSettingsCatalog.Actions() {
            public void run(String action) { }
            public String status(String name) { return ""; }
        });
        catalog.restore("world_volume");
        assertThat(restored.isWorldLyricsVolumetric()).isFalse();
        assertThat(restored.getWorldLyricsPresentationIntensity()).isEqualTo(WorldLyricsPresentation.Intensity.BALANCED);
        assertThat(restored.isLyricsAnimations()).isFalse();
    }

    @Test void invalidTextAndNullChoicesReceiveBoundedDefaults() {
        var p = new ClientPreferences();
        p.setWorldLyricsDirection(null); p.setWorldLyricsPresentationIntensity(null);
        p.setWorldLyricsGeminiModel(null); p.setWorldLyricsGeminiDirection("x".repeat(900));
        assertThat(p.getWorldLyricsDirection()).isEqualTo(WorldLyricsPresentation.Direction.AUTOMATIC);
        assertThat(p.getWorldLyricsPresentationIntensity()).isEqualTo(WorldLyricsPresentation.Intensity.DISCREET);
        assertThat(p.getWorldLyricsGeminiModel()).isEqualTo("gemini-flash-latest");
        assertThat(p.getWorldLyricsGeminiDirection()).hasSize(500);
    }

    @Test void geminiDirectionExistsOnlyInsideExperimentsAndPreservesExistingSettings() {
        var p = new ClientPreferences(); p.setWorldLyrics(true);
        List<String> actions = new ArrayList<>();
        var catalog = new LyricsSettingsCatalog(p, new LyricsSettingsCatalog.Actions() {
            public void run(String action) { actions.add(action); }
            public String status(String name) { return name; }
        });
        assertThat(catalog.entries(LyricsSettingsCatalog.Category.WORLD)).hasSizeLessThanOrEqualTo(8)
                .noneMatch(e -> e.key.endsWith("world_gemini.key"));
        assertThat(catalog.searchEntries(LyricsSettingsCatalog.Category.WORLD))
                .noneMatch(e -> e.key.contains("world_gemini") || e.key.endsWith("world_direction"));
        assertThat(catalog.entries(LyricsSettingsCatalog.Category.ADVANCED))
                .noneMatch(e -> e.key.contains("world_gemini"));
        catalog.expand("experimental");
        assertThat(catalog.entries(LyricsSettingsCatalog.Category.ADVANCED))
                .anyMatch(e -> e.key.endsWith("group.world_gemini"))
                .noneMatch(e -> e.key.endsWith("world_gemini.key"));
        catalog.expand("world_gemini");
        var entries = catalog.entries(LyricsSettingsCatalog.Category.ADVANCED);
        assertThat(entries).anyMatch(e -> e.key.endsWith("world_gemini.key") && e.masked);
        var enable = entries.stream().filter(e -> e.key.endsWith("world_gemini.enabled")).findFirst().orElseThrow();
        enable.activate.run();
        assertThat(p.getWorldLyricsDirection()).isEqualTo(WorldLyricsPresentation.Direction.GEMINI);
        assertThat(p.isGeminiShaderEnabled()).isFalse();
        enable.activate.run();
        assertThat(p.getWorldLyricsDirection()).isEqualTo(WorldLyricsPresentation.Direction.AUTOMATIC);
        entries.stream().filter(e -> e.key.endsWith("world_gemini.regenerate")).findFirst().orElseThrow().activate.run();
        assertThat(actions).containsExactly("world_gemini_regenerate");
        p.setWorldLyricsDirection(WorldLyricsPresentation.Direction.GEMINI);
        p.setWorldLyricsPresentationIntensity(WorldLyricsPresentation.Intensity.EXPRESSIVE);
        p.setWorldLyricsGeminiModel("preserve-model");
        catalog.restore("world_experience");
        assertThat(p.getWorldLyricsDirection()).isEqualTo(WorldLyricsPresentation.Direction.AUTOMATIC);
        assertThat(p.getWorldLyricsPresentationIntensity()).isEqualTo(WorldLyricsPresentation.Intensity.DISCREET);
        assertThat(p.getWorldLyricsGeminiModel()).isEqualTo("preserve-model");
    }
}
