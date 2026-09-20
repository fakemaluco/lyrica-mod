package dev.fandroid.client;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class GoogleFontManagerTest {
    @Test
    void normalizesHumanReadableGoogleFontFamilyNames() {
        assertThat(GoogleFontManager.normalizeFamily("  Noto   Sans JP  ")).isEqualTo("Noto Sans JP");
        assertThat(GoogleFontManager.normalizeFamily("M PLUS 1 Code")).isEqualTo("M PLUS 1 Code");
        assertThat(GoogleFontManager.normalizeFamily("Roboto?evil=true")).isEmpty();
    }

    @Test
    void extractsEveryDistinctTrueTypeSubsetFromGoogleCss() {
        String css = """
                @font-face {
                  font-family: 'Roboto';
                  src: url(https://fonts.gstatic.com/s/roboto/font.ttf) format('truetype');
                }
                @font-face {
                  font-family: 'Roboto';
                  src: local('Roboto'), url('https://fonts.gstatic.com/s/roboto/cyrillic.ttf') format("truetype");
                }
                @font-face {
                  src: url(https://fonts.gstatic.com/s/roboto/font.ttf) format('truetype');
                }
                """;

        assertThat(GoogleFontManager.extractTtfUrl(css))
                .isEqualTo("https://fonts.gstatic.com/s/roboto/font.ttf");
        assertThat(GoogleFontManager.extractTtfUrls(css)).containsExactly(
                "https://fonts.gstatic.com/s/roboto/font.ttf",
                "https://fonts.gstatic.com/s/roboto/cyrillic.ttf");
        assertThat(GoogleFontManager.extractTtfUrl("src: url(font.woff2) format('woff2')")).isEmpty();
    }

    @Test
    void generatedDefinitionKeepsLogicalMetricsAndUsesHighQualityRasterization() {
        String definition = GoogleFontManager.fontDefinitionJson(List.of("google-00.ttf", "google-01.ttf"));

        assertThat(definition).contains("\"size\": 11.0");
        assertThat(definition).contains("\"oversample\": 5.0");
        assertThat(definition).containsSubsequence(
                "lyrica:google-00.ttf", "lyrica:google-01.ttf", "minecraft:default");
    }

    @Test
    void validatesDownloadSourcesAndTheirAggregateBudget() {
        URI trusted = URI.create("https://fonts.gstatic.com/s/noto/latin.ttf");
        assertThat(GoogleFontManager.approvedFontUris(List.of(trusted.toString(), trusted.toString())))
                .containsExactly(trusted);
        assertThatIllegalArgumentException().isThrownBy(() -> GoogleFontManager.approvedFontUris(
                List.of("https://example.invalid/font.ttf")));

        List<String> tooMany = IntStream.range(0, GoogleFontManager.MAX_FONT_FILES + 1)
                .mapToObj(index -> "https://fonts.gstatic.com/s/noto/" + index + ".ttf")
                .toList();
        assertThatIllegalArgumentException().isThrownBy(() -> GoogleFontManager.approvedFontUris(tooMany));
        assertThat(GoogleFontManager.withinDownloadBudget(GoogleFontManager.MAX_TOTAL_FONT_BYTES - 4, 4)).isTrue();
        assertThat(GoogleFontManager.withinDownloadBudget(GoogleFontManager.MAX_TOTAL_FONT_BYTES - 4, 5)).isFalse();
    }

    @Test
    void cacheMarkerVersionsExistingGoogleFontPacks() {
        assertThat(GoogleFontManager.cacheMarker("Roboto", 400)).isEqualTo("v2\nRoboto\n400");
        assertThat(GoogleFontManager.cacheMarker("Roboto", 400)).isNotEqualTo("Roboto\n400");
    }
}
