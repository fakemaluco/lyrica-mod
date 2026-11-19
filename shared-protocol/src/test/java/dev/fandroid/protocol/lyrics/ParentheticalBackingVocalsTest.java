package dev.fandroid.protocol.lyrics;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ParentheticalBackingVocalsTest {
    @Test void separatesPhrasesAndRetainsWordAndSyllableTiming() {
        var words = List.of(new TimedWord(0, 310, "Eu"), new TimedWord(310, 430, " (vo", false, "(vo"),
                new TimedWord(430, 700, "zes)", true, "zes)"), new TimedWord(700, 1000, " canto"),
                new TimedWord(1000, 1290, " (sim)"));
        var original = new LyricLine(0, 1400, "Eu (vozes) canto (sim)", words, "v1", false,
                "I (voices) sing (yes)", "Eu (vozes) canto (sim)");
        var document = new LyricsDocument(List.of(original));
        var display = ParentheticalBackingVocals.infer(document);
        var group = display.groups().getFirst();

        assertThat(group.lead().text()).isEqualTo("Eu canto");
        assertThat(group.lead().translation()).isEqualTo("I sing");
        assertThat(group.lead().words()).extracting(TimedWord::startMillis).containsExactly(0L, 700L);
        assertThat(group.backingVocals()).extracting(LyricLine::text).containsExactly("vozes", "sim");
        assertThat(group.backingVocals()).allMatch(LyricLine::background);
        var first = group.backingVocals().getFirst();
        assertThat(first.words()).containsExactly(new TimedWord(310, 430, "vo", false, "vo"),
                new TimedWord(430, 700, "zes", true, "zes"));
        assertThat(first.romanization()).isEqualTo("vozes");
        assertThat(first.translation()).isEqualTo("voices");
        assertThat(first.startMillis()).isEqualTo(310);
        assertThat(first.endMillis()).isEqualTo(700);
        assertThat(display.metadata()).isSameAs(document.metadata());
        assertThat(document.lines()).containsExactly(original);
    }

    @Test void keepsTimingWhenTheProviderOmitsParenthesesFromTimedWords() {
        var words = List.of(new TimedWord(100, 350, "Down"), new TimedWord(350, 840, "low"));
        var display = ParentheticalBackingVocals.infer(new LyricsDocument(List.of(
                new LyricLine(100, 900, "Down (low)", words, "v1", false, null, null))));
        assertThat(display.groups().getFirst().backingVocals().getFirst().words()).containsExactly(words.getLast());
    }

    @Test void fullyParentheticalLinesRetainTheirTimingAndUnmatchedParenthesesStayLiteral() {
        var backing = line(4000, "(hey (oh)) (yeah)", false);
        var literal = line(5000, "Still (singing", false);
        var display = ParentheticalBackingVocals.infer(new LyricsDocument(List.of(backing, literal)));
        assertThat(display.groups().getFirst().lead().background()).isTrue();
        assertThat(display.lines()).extracting(LyricLine::text).containsExactly("hey (oh)", "yeah", literal.text());
        assertThat(display.lines().getFirst().startMillis()).isEqualTo(4000);
        assertThat(display.lines().getFirst().endMillis()).isEqualTo(5000);
        assertThat(display.groups().get(1).lead()).isSameAs(literal);
    }

    @Test void providerBackingVocalsTakePrecedenceForTheWholeDocument() {
        var document = new LyricsDocument(List.of(line(0, "Lead (literal)", false),
                line(100, "Already marked", true), line(1000, "More (literal)", false)));
        assertThat(ParentheticalBackingVocals.infer(document)).isSameAs(document);
    }

    private static LyricLine line(long start, String text, boolean backing) {
        return new LyricLine(start, start + 1000, text, List.of(), "v1", backing, null, null);
    }
}
