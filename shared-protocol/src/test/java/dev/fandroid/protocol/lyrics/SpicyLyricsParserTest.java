package dev.fandroid.protocol.lyrics;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpicyLyricsParserTest {
    private final SpicyLyricsParser parser = new SpicyLyricsParser();

    @Test
    void readsSyllablesDuetsBackgroundAndRomanization() {
        String json = """
                {"Type":"Syllable","source":"spl","SongWriters":["Alice Writer","Bob Composer"],
                  "TTMLUploadMetadata":{"Maker":{"username":"Arashii","avatar":"https://cdn.discordapp.com/maker.png"},
                  "Uploader":{"username":"Uploader","avatar":"https://spicylyrics.org/uploader.png"}},
                  "Content":[{"OppositeAligned":true,
                  "Lead":{"StartTime":1.0,"EndTime":3.0,"Syllables":[
                    {"Text":"Hel","TransliteratedText":"Her","StartTime":1.0,"EndTime":1.4,"IsPartOfWord":true},
                    {"Text":"lo","TransliteratedText":"ro","StartTime":1.4,"EndTime":1.8},
                    {"Text":"world","StartTime":2.0,"EndTime":3.0}]},
                  "Background":[{"StartTime":2.1,"EndTime":2.8,"Syllables":[
                    {"Text":"hey","StartTime":2.1,"EndTime":2.8}]}]}]}
                """;

        var parsed = parser.parse(json.getBytes(StandardCharsets.UTF_8));

        assertThat(parsed.wordSynced()).isTrue();
        assertThat(parsed.document().lines()).hasSize(2);
        LyricLine lead = parsed.document().lines().stream().filter(line -> !line.background()).findFirst().orElseThrow();
        assertThat(lead.text()).isEqualTo("Hello world");
        assertThat(lead.words()).extracting(TimedWord::text).containsExactly("Hel", "lo", " world");
        assertThat(lead.words()).extracting(TimedWord::joinPrevious).containsExactly(false, true, false);
        assertThat(lead.words()).extracting(TimedWord::romanization)
                .containsExactly("Her", "ro", null);
        assertThat(lead.romanization()).isEqualTo("Herro world");
        assertThat(parsed.document().metadata().songwriters()).isEqualTo("Alice Writer, Bob Composer");
        assertThat(parsed.document().metadata().provider()).isEqualTo("Spicy Lyrics");
        assertThat(parsed.document().metadata().communityProvided()).isTrue();
        assertThat(parsed.document().metadata().makerUsername()).isEqualTo("Arashii");
        assertThat(parsed.document().metadata().uploaderUsername()).isEqualTo("Uploader");
        assertThat(lead.agent()).isEqualTo("v2");
        assertThat(parsed.document().lines()).anyMatch(LyricLine::background);
        assertThat(parsed.document().groups()).singleElement().satisfies(group -> {
            assertThat(group.alignment()).isEqualTo(LyricAlignment.OPPOSITE);
            assertThat(group.backingVocals()).hasSize(1);
        });
        assertThat(parsed.document().activeGroupsAt(2_300)).hasSize(1);
    }

    @Test
    void readsLineSyncedLyrics() {
        String json = """
                {"Type":"Line","Content":[
                  {"Text":"First line","StartTime":0.5,"EndTime":2.0},
                  {"Text":"Second line","StartTime":2.0,"EndTime":4.0,"OppositeAligned":true}]}
                """;
        var parsed = parser.parse(json.getBytes(StandardCharsets.UTF_8));
        assertThat(parsed.wordSynced()).isFalse();
        assertThat(parsed.document().lines()).extracting(LyricLine::startMillis).containsExactly(500L, 2_000L);
        assertThat(parsed.document().lines().get(1).agent()).isEqualTo("v2");
    }

    @Test
    void preservesMultipleWriterShapesFromNestedMetadata() {
        String json = """
                {"Type":"Line","Metadata":{"Writers":[
                  {"name":"Alice Writer"},{"displayName":"Bob Composer"},"Carol Producer"]},
                  "Content":[{"Text":"One line","StartTime":0.5,"EndTime":2.0}]}
                """;

        var parsed = parser.parse(json.getBytes(StandardCharsets.UTF_8));

        assertThat(parsed.document().metadata().songwriters())
                .isEqualTo("Alice Writer, Bob Composer, Carol Producer");
    }

    @Test
    void repairsMissingPartOfWordFlagsFromTheCompleteLine() {
        String json = """
                {"Type":"Syllable","Content":[{
                  "Lead":{"Text":"heavy soul","StartTime":1.0,"EndTime":3.0,"Syllables":[
                    {"Text":"hea","StartTime":1.0,"EndTime":1.4},
                    {"Text":"vy","StartTime":1.4,"EndTime":1.8},
                    {"Text":"soul","StartTime":2.0,"EndTime":3.0}]}}]}
                """;

        LyricLine line = parser.parse(json.getBytes(StandardCharsets.UTF_8))
                .document().lines().getFirst();

        assertThat(line.text()).isEqualTo("heavy soul");
        assertThat(line.words()).extracting(TimedWord::text)
                .containsExactly("hea", "vy", " soul");
        assertThat(line.words()).extracting(TimedWord::joinPrevious)
                .containsExactly(false, true, false);
    }

    @Test
    void treatsPartOfWordAsContinuationIntoTheNextSyllable() {
        String json = """
                {"Type":"Syllable","Content":[{
                  "Lead":{"StartTime":1.0,"EndTime":2.0,"Syllables":[
                    {"Text":"an","StartTime":1.0,"EndTime":1.1},
                    {"Text":"in","StartTime":1.1,"EndTime":1.3,"IsPartOfWord":true},
                    {"Text":"fes","StartTime":1.3,"EndTime":1.6,"IsPartOfWord":true},
                    {"Text":"tation","StartTime":1.6,"EndTime":2.0}]}}]}
                """;

        LyricLine line = parser.parse(json.getBytes(StandardCharsets.UTF_8))
                .document().lines().getFirst();

        assertThat(line.text()).isEqualTo("an infestation");
        assertThat(line.words()).extracting(TimedWord::text)
                .containsExactly("an", " in", "fes", "tation");
        assertThat(line.words()).extracting(TimedWord::joinPrevious)
                .containsExactly(false, false, true, true);
    }

    @Test
    void rejectsStaticAndOversizedPayloads() {
        assertThatThrownBy(() -> parser.parse("{\"Type\":\"Static\",\"Content\":[{\"Text\":\"x\"}]}"
                .getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse(new byte[2 * 1024 * 1024 + 1]))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
