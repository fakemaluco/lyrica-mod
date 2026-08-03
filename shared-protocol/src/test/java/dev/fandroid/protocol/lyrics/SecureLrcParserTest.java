package dev.fandroid.protocol.lyrics;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecureLrcParserTest {
    private final SecureLrcParser parser = new SecureLrcParser();

    @Test
    void parsesClassicLineTimingMultipleTimestampsAndOffset() {
        String lrc = """
                [ar:Artist]
                [offset:+100]
                [00:01.20][00:05.000] First line
                [00:08.50] Second line
                """;
        LyricsDocument document = parser.parse(lrc.getBytes(StandardCharsets.UTF_8));

        assertThat(document.lines()).hasSize(3);
        assertThat(document.lines().getFirst().startMillis()).isEqualTo(1_300);
        assertThat(document.lines().getFirst().text()).isEqualTo("First line");
        assertThat(document.lines().getFirst().endMillis()).isEqualTo(5_100);
        assertThat(parser.hasWordTiming(document)).isFalse();
    }

    @Test
    void preservesEnhancedWordTiming() {
        String lrc = "[00:01.00]<00:01.00>Hello <00:01.60>world";
        LyricsDocument document = parser.parse(lrc.getBytes(StandardCharsets.UTF_8));

        LyricLine line = document.lines().getFirst();
        assertThat(line.text()).isEqualTo("Hello world");
        assertThat(line.words()).hasSize(2);
        assertThat(line.words().get(1).startMillis()).isEqualTo(1_600);
        assertThat(parser.hasWordTiming(document)).isTrue();
    }

    @Test
    void normalizesUnicodeSpacesAndRemovesInvisibleDirectionMarkers() {
        String lrc = "[00:01.00]And\u2005\u2066that\u2069\u2009was\u200B\u2800normal\u3164life";

        LyricLine line = parser.parse(lrc.getBytes(StandardCharsets.UTF_8)).lines().getFirst();

        assertThat(line.text()).isEqualTo("And that was normal life");
        assertThat(line.words()).singleElement()
                .extracting(TimedWord::text).isEqualTo("And that was normal life");
    }

    @Test
    void rejectsOversizedDocuments() {
        assertThatThrownBy(() -> parser.parse(new byte[2 * 1024 * 1024 + 1]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2 MiB");
    }
}
