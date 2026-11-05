package dev.relaylyrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

final class StandaloneMetadataTest {
    private static final String[] OPTIONAL_COMPATIBILITY_MODS = {
            "modmenu", "controlify", "minecraft-cursor", "cursors_extended",
            "mousetweaks", "emi", "roughlyenoughitems", "iris", "sodium",
            "immediatelyfast", "xaerominimap", "appleskin", "lambdynlights"
    };
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void exposesKaraokeAndOptionalClientEntrypoints() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/fabric.mod.json")) {
            assertThat(stream).isNotNull();
            JsonNode metadata = mapper.readTree(stream);
            assertThat(metadata.path("id").asText()).isEqualTo("lyrica");
            assertThat(metadata.path("environment").asText()).isEqualTo("*");
            assertThat(metadata.path("depends").path("minecraft").asText())
                    .isIn("1.21.1", "1.21.11", "26.2");
            assertThat(metadata.path("entrypoints").has("client")).isTrue();
            assertThat(metadata.path("entrypoints").has("modmenu")).isTrue();
            assertThat(metadata.path("entrypoints").path("main").get(0).asText())
                    .isEqualTo("dev.relaylyrics.karaoke.KaraokeNetwork");
            assertThat(metadata.path("mixins").get(0).path("environment").asText()).isEqualTo("client");
            assertThat(metadata.path("entrypoints").has("voicechat")).isFalse();
            assertThat(metadata.path("entrypoints").path("lambdynlights:initializer").get(0).path("adapter").asText()).isEqualTo("lyrica_optional_lighting");
            assertThat(metadata.path("languageAdapters").has("lyrica_optional_lighting")).isTrue();
        }
    }

    @Test
    void declaresEveryCompatibilityAsOptional() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/fabric.mod.json")) {
            assertThat(stream).isNotNull();
            JsonNode metadata = mapper.readTree(stream);
            JsonNode suggestions = metadata.path("suggests");
            assertThat(suggestions.isObject()).isTrue();
            assertThat(OPTIONAL_COMPATIBILITY_MODS).allSatisfy(modId ->
                    assertThat(suggestions.has(modId)).as(modId).isTrue());
            assertThat(metadata.path("depends").propertyStream().map(entry -> entry.getKey()).toList())
                    .doesNotContain(OPTIONAL_COMPATIBILITY_MODS);
        }
    }

    @Test
    void englishAndPortugueseHaveTheSameKeys() throws Exception {
        JsonNode english = read("/assets/relaylyrics/lang/en_us.json");
        JsonNode portuguese = read("/assets/relaylyrics/lang/pt_br.json");
        assertThat(english.propertyStream().map(entry -> entry.getKey()).toList())
                .containsExactlyInAnyOrderElementsOf(
                        portuguese.propertyStream().map(entry -> entry.getKey()).toList());
    }

    private JsonNode read(String path) throws Exception {
        try (InputStream stream = getClass().getResourceAsStream(path)) {
            assertThat(stream).isNotNull();
            return mapper.readTree(stream);
        }
    }
}
