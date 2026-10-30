package dev.relaylyrics.client.music;

import dev.relaylyrics.protocol.lyrics.LyricAlignment;
import dev.relaylyrics.protocol.lyrics.LyricLine;
import dev.relaylyrics.protocol.lyrics.LyricVocalGroup;
import dev.relaylyrics.protocol.lyrics.LyricsDocument;
import dev.relaylyrics.protocol.lyrics.LyricsMetadata;
import dev.relaylyrics.protocol.lyrics.TimedWord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorldLyricsPlanTest {
    @Test
    void untimedLyricsDoNotBecomeOverlappingWorldPhrases() {
        var metadata = new LyricsMetadata(dev.relaylyrics.protocol.lyrics.LyricsSource.UNKNOWN,
                dev.relaylyrics.protocol.lyrics.LyricsSyncMode.STATIC, "", "", "", "");
        LyricsDocument untimed = new LyricsDocument(List.of(
                new LyricVocalGroup(0, Long.MAX_VALUE, line(0, Long.MAX_VALUE, "plain lyrics"),
                        List.of(), LyricAlignment.NORMAL)), metadata);
        assertThat(WorldLyricsPlan.visibleLines(untimed, 1_000)).isEmpty();
    }

    @Test
    void keepsThePhraseTogetherButExposesTimedSyllablesAsEmphasisTokens() {
        LyricLine line = new LyricLine(1_000, 3_600, "안녕하세요 world", List.of(
                new TimedWord(1_000, 1_450, "안"),
                new TimedWord(1_450, 2_650, "녕하", true),
                new TimedWord(2_650, 3_050, "세요", true),
                new TimedWord(3_050, 3_600, " world")), "v1", false, null, null);

        List<WorldLyricsPlan.Token> tokens = WorldLyricsPlan.tokens(line, false);

        assertThat(tokens).extracting(WorldLyricsPlan.Token::text)
                .containsExactly("안", "녕하", "세요", "world");
        assertThat(tokens).extracting(WorldLyricsPlan.Token::startsWord)
                .containsExactly(true, false, false, true);
        assertThat(tokens.get(0).emphasis(1_200)).isGreaterThan(0);
        assertThat(tokens.get(1).emphasis(2_000)).isGreaterThan(.9);
        assertThat(tokens.get(1).emphasis(2_700)).isZero();
    }

    @Test
    void fadesInBeforeTheLineAndKeepsItsShortExitTail() {
        LyricLine line = line(1_000, 2_000, "cinematic words");
        LyricsDocument document = document(line);

        assertThat(WorldLyricsPlan.visibleLines(document, 819)).isEmpty();
        assertThat(WorldLyricsPlan.visibleLines(document, 820)).hasSize(1);
        assertThat(WorldLyricsPlan.lineVisibility(line, 820)).isZero();
        assertThat(WorldLyricsPlan.lineVisibility(line, 1_000)).isBetween(0.0, 1.0);
        assertThat(WorldLyricsPlan.lineVisibility(line, 1_400)).isEqualTo(1);
        assertThat(WorldLyricsPlan.visibleLines(document, 2_450)).hasSize(1);
        assertThat(WorldLyricsPlan.visibleLines(document, 2_451)).isEmpty();
    }

    @Test
    void preservesOverlappingAndDuetLines() {
        LyricLine first = line(1_000, 2_500, "first voice");
        LyricLine second = new LyricLine(1_500, 2_900, "second voice", List.of(),
                "v2", false, null, null);
        LyricsDocument document = new LyricsDocument(List.of(
                new LyricVocalGroup(1_000, 2_500, first, List.of(), LyricAlignment.NORMAL),
                new LyricVocalGroup(1_500, 2_900, second, List.of(), LyricAlignment.OPPOSITE)),
                LyricsMetadata.unknown());

        assertThat(WorldLyricsPlan.visibleLines(document, 2_000))
                .extracting(plan -> plan.line().text())
                .containsExactly("first voice", "second voice");
    }

    private static LyricLine line(long start, long end, String text) {
        return new LyricLine(start, end, text, List.of(), "v1", false, null, null);
    }

    private static LyricsDocument document(LyricLine line) {
        return new LyricsDocument(List.of(new LyricVocalGroup(line.startMillis(), line.endMillis(),
                line, List.of(), LyricAlignment.NORMAL)), LyricsMetadata.unknown());
    }
}
