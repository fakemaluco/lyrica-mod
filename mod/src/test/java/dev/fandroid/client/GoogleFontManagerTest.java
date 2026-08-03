package dev.fandroid.client;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleFontManagerTest {
    @Test
    void normalizesHumanReadableGoogleFontFamilyNames() {
        assertThat(GoogleFontManager.normalizeFamily("  Noto   Sans JP  ")).isEqualTo("Noto Sans JP");
        assertThat(GoogleFontManager.normalizeFamily("M PLUS 1 Code")).isEqualTo("M PLUS 1 Code");
        assertThat(GoogleFontManager.normalizeFamily("Roboto?evil=true")).isEmpty();
    }

    @Test
    void extractsOnlyTheTrueTypeSourceFromGoogleCss() {
        String css = """
                @font-face {
                  font-family: 'Roboto';
                  src: url(https://fonts.gstatic.com/s/roboto/font.ttf) format('truetype');
                }
                """;

        assertThat(GoogleFontManager.extractTtfUrl(css))
                .isEqualTo("https://fonts.gstatic.com/s/roboto/font.ttf");
        assertThat(GoogleFontManager.extractTtfUrl("src: url(font.woff2) format('woff2')")).isEmpty();
    }
}
