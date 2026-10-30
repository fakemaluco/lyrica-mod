package dev.fandroid.protocol.lyrics;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecureTtmlParserTest {
    private final SecureTtmlParser parser = new SecureTtmlParser();

    @Test void romanizationNestedInsideEachWordRetainsThatWordsOwnTiming() {
        var line = parser.parse("""
                <tt xmlns:ttm="http://www.w3.org/ns/ttml#metadata"><body><p begin="1s" end="4s">
                  <span begin="1s" end="2s">안녕<span ttm:role="x-roman">annyeong</span></span>
                  <span begin="3s" end="4s"> 세계<span ttm:role="x-transliteration">segye</span></span>
                </p></body></tt>
                """.getBytes(StandardCharsets.UTF_8)).lines().getFirst();
        assertThat(line.text()).isEqualTo("안녕 세계");
        assertThat(line.words()).extracting(TimedWord::romanization).containsExactly("annyeong", "segye");
        assertThat(line.words()).extracting(TimedWord::startMillis).containsExactly(1000L, 3000L);
        assertThat(line.words()).extracting(TimedWord::endMillis).containsExactly(2000L, 4000L);
    }

    @Test void timedRomanizationKeepsWordBoundariesAndDoesNotMixSimultaneousBackingVoices() {
        var doc = parser.parse("""
                <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata"
                    xmlns:itunes="http://music.apple.com/lyric-ttml-internal">
                  <head><metadata><transliterations><transliteration><text for="L1">
                    <span begin="1001ms" end="1.499s">wata</span><span begin="1.7s" end="2s">shi </span>
                    <span begin="3s" end="4s">wa</span>
                    <span ttm:role="x-bg"><span begin="1s" end="4s">koe</span></span>
                    <span ttm:role="x-bg"><span begin="1s" end="4s">ai</span></span>
                  </text></transliteration></transliterations></metadata></head>
                  <body><p begin="1s" end="4s" itunes:key="L1"><span begin="1s" end="1.5s">わた</span><span begin="1.7s" end="2s">し</span><span begin="3s" end="4s">は</span><span ttm:role="x-bg"><span begin="1s" end="4s">声</span></span><span ttm:role="x-bg"><span begin="1s" end="4s">愛</span></span></p></body>
                </tt>
                """.getBytes(StandardCharsets.UTF_8));
        var group = doc.groups().getFirst();
        assertThat(group.lead().romanization()).isEqualTo("watashi wa");
        assertThat(group.lead().words()).extracting(TimedWord::romanization).containsExactly("wata", "shi", " wa");
        assertThat(group.lead().words()).extracting(TimedWord::startMillis).containsExactly(1000L, 1700L, 3000L);
        assertThat(group.backingVocals()).extracting(LyricLine::romanization).containsExactly("koe", "ai");
        assertThat(group.backingVocals()).allSatisfy(line ->
                assertThat(line.words().getFirst().romanization()).isEqualTo(line.romanization()));
    }

    @Test void repairsOldSpicySidecarSpacingUsingItsOriginalTimedWords() {
        var line = parser.parse("""
                <tt xmlns:itunes="http://music.apple.com/lyric-ttml-internal">
                  <head><transliterations><transliteration><text for="L1"><span begin="1s" end="2s">annyeong</span><span begin="3s" end="4s">segye</span></text></transliteration></transliterations></head>
                  <body><p begin="1s" end="4s" itunes:key="L1"><span begin="1s" end="2s">안녕</span><span begin="3s" end="4s"> 세계</span></p></body>
                </tt>
                """.getBytes(StandardCharsets.UTF_8)).lines().getFirst();
        assertThat(line.romanization()).isEqualTo("annyeong segye");
        assertThat(line.words()).extracting(TimedWord::romanization).containsExactly("annyeong", " segye");
    }

    @Test void inlineTimedRomanizationDoesNotReplaceTheOriginalTextOrTimestamps() {
        var line = parser.parse("""
                <tt xmlns:ttm="http://www.w3.org/ns/ttml#metadata"><body><p begin="1s" end="4s">
                  <span begin="1s" end="2s">안녕 </span><span begin="3s" end="4s">세계</span>
                  <span ttm:role="x-roman"><span begin="1s" end="2s">annyeong </span><span begin="3s" end="4s">segye</span></span>
                </p></body></tt>
                """.getBytes(StandardCharsets.UTF_8)).lines().getFirst();
        assertThat(line.text()).isEqualTo("안녕 세계");
        assertThat(line.romanization()).isEqualTo("annyeong segye");
        assertThat(line.words()).extracting(TimedWord::romanization).containsExactly("annyeong", " segye");
        assertThat(line.words()).extracting(TimedWord::startMillis).containsExactly(1000L, 3000L);
    }

    @Test void spicyExplicitDuetSideWinsEvenWithoutAgentsAndPreservesSimultaneousSinging() {
        var doc = parser.parse("""
                <tt xmlns="http://www.w3.org/ns/ttml" xmlns:spicy="https://spicylyrics.org/ns/ttml"
                    xmlns:ttm="http://www.w3.org/ns/ttml#metadata"><body><div>
                  <p begin="1s" end="5s" spicy:oppositeAligned="true"><span begin="1s" end="5s">Right voice</span>
                    <span ttm:role="x-bg"><span begin="2s" end="4s">Backing voice</span></span></p>
                  <p begin="2s" end="6s" ttm:agent="v2" spicy:oppositeAligned="false">Left voice</p>
                </div></body></tt>
                """.getBytes(StandardCharsets.UTF_8));
        assertThat(doc.groups().getFirst().alignment()).isEqualTo(LyricAlignment.OPPOSITE);
        assertThat(doc.groups().get(1).alignment()).isEqualTo(LyricAlignment.NORMAL);
        assertThat(doc.groups().getFirst().backingVocals()).hasSize(1);
        assertThat(doc.activeGroupsAt(3_000)).hasSize(2);
    }

    @Test void appleAgentsAreInheritedAndVoice2000KeepsItsSideWhenOpeningTheSong() {
        var doc = parser.parse("""
                <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
                  <body ttm:agent="v1000"><div ttm:agent="v2000"><p begin="1s" end="4s">Right opens</p>
                    <p begin="2s" end="5s" ttm:agent="v1000">Left overrides</p></div>
                    <div><p begin="6s" end="8s">Left inherits body</p></div></body>
                </tt>
                """.getBytes(StandardCharsets.UTF_8));
        assertThat(doc.groups()).extracting(LyricVocalGroup::alignment)
                .containsExactly(LyricAlignment.OPPOSITE, LyricAlignment.NORMAL, LyricAlignment.NORMAL);
        assertThat(doc.groups().getFirst().lead().agent()).isEqualTo("v2000");
    }

    @Test void aSpicyBackgroundOnlyOpeningIsKeptAndItsTimingIsNotCancelledByTheNextVoice() {
        var doc = parser.parse("""
                <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
                  <body><div><p begin="1s" end="5s"><span ttm:role="x-bg">
                    <span begin="1s" end="2s">Backing </span><span begin="2s" end="5s">opening</span></span></p>
                    <p begin="3s" end="6s" ttm:agent="v2">Second voice</p></div></body>
                </tt>
                """.getBytes(StandardCharsets.UTF_8));
        assertThat(doc.groups()).hasSize(2);
        assertThat(doc.groups().getFirst().lead().text()).isBlank();
        assertThat(doc.groups().getFirst().backingVocals().getFirst().words()).hasSize(2);
        assertThat(doc.activeGroupsAt(4_000)).hasSize(2);
    }

    @Test
    void parsesLineAndWordTimingTranslationAndDuetAgent() {
        String ttml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
                  <body><div>
                    <p begin="00:01.200" end="00:04.000" ttm:agent="v2">
                      <span begin="00:01.200" end="00:02.100">Hello </span>
                      <span begin="00:02.100" end="00:04.000">world</span>
                      <span type="translation">Olá mundo</span>
                    </p>
                  </div></body>
                </tt>
                """;
        LyricsDocument document = parser.parse(ttml.getBytes(StandardCharsets.UTF_8));

        assertThat(document.lines()).hasSize(1);
        assertThat(document.lines().getFirst().text()).isEqualTo("Hello world");
        assertThat(document.lines().getFirst().words()).hasSize(2);
        assertThat(document.lines().getFirst().translation()).isEqualTo("Olá mundo");
        assertThat(document.lines().getFirst().agent()).isEqualTo("v2");
        assertThat(document.lineAt(2_000)).isZero();
    }

    @Test
    void rejectsXxeBeforeAnyResolverCanRun() {
        String malicious = """
                <?xml version="1.0"?>
                <!DOCTYPE foo [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
                <tt><body><p begin="0s" end="1s">&xxe;</p></body></tt>
                """;
        assertThatThrownBy(() -> parser.parse(malicious.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DTD");
    }

    @Test
    void rejectsMalformedInput() {
        assertThatThrownBy(() -> parser.parse("<tt><p>".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parsesAmllBackgroundVocalsAndSidecarText() {
        String ttml = """
                <tt xmlns="http://www.w3.org/ns/ttml"
                    xmlns:ttm="http://www.w3.org/ns/ttml#metadata"
                    xmlns:itunes="http://music.apple.com/lyric-ttml-internal">
                  <head><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
                    <translations><translation xml:lang="pt-BR" type="subtitle">
                      <text for="L1">Tradução clara</text>
                    </translation></translations>
                    <transliterations><transliteration xml:lang="ja-Latn">
                      <text for="L1">romaji line</text>
                    </transliteration></transliterations>
                  </iTunesMetadata></head>
                  <body><div><p begin="10.000" end="14.000" itunes:key="L1" ttm:agent="v2">
                    <span begin="10.000" end="11.000">Lead </span>
                    <span begin="11.000" end="12.000">voice</span>
                    <span ttm:role="x-bg" begin="12.000" end="13.500">
                      <span begin="12.000" end="13.500">(echo)</span>
                      <span ttm:role="x-translation">(eco)</span>
                    </span>
                  </p></div></body>
                </tt>
                """;

        LyricsDocument document = parser.parse(ttml.getBytes(StandardCharsets.UTF_8));

        assertThat(document.lines()).hasSize(2);
        LyricLine lead = document.lines().stream().filter(line -> !line.background()).findFirst().orElseThrow();
        LyricLine background = document.lines().stream().filter(LyricLine::background).findFirst().orElseThrow();
        assertThat(lead.text()).isEqualTo("Lead voice");
        assertThat(lead.translation()).isEqualTo("Tradução clara");
        assertThat(lead.romanization()).isEqualTo("romaji line");
        assertThat(lead.agent()).isEqualTo("v2");
        assertThat(background.text()).isEqualTo("(echo)");
        assertThat(background.translation()).isEqualTo("(eco)");
        assertThat(background.startMillis()).isEqualTo(12_000);
        assertThat(document.lineAt(12_500)).isEqualTo(document.lines().indexOf(lead));
    }

    @Test
    void acceptsDurationAndBareSecondTiming() {
        String ttml = """
                <tt><body><p begin="1.25" dur="2.5s">A line</p></body></tt>
                """;
        LyricLine line = parser.parse(ttml.getBytes(StandardCharsets.UTF_8)).lines().getFirst();
        assertThat(line.startMillis()).isEqualTo(1_250);
        assertThat(line.endMillis()).isEqualTo(3_750);
    }

    @Test
    void preservesMetadataRubyMultipleBackingVocalsAndOverlappingDuetGroups() {
        String ttml = """
                <tt xmlns="http://www.w3.org/ns/ttml"
                    xmlns:ttm="http://www.w3.org/ns/ttml#metadata"
                    xmlns:tts="http://www.w3.org/ns/ttml#styling"
                    xmlns:amll="http://www.example.com/ns/amll">
                  <head><metadata>
                    <amll:meta key="spotifyId" value="6wOYnPq1hPihRepcDClPUw"/>
                    <amll:meta key="musicName" value="Meet Me Halfway"/>
                    <amll:meta key="artists" value="The Black Eyed Peas"/>
                    <amll:meta key="album" value="The E.N.D."/>
                    <amll:meta key="songwriters" value="will.i.am, Jean Baptiste"/>
                  </metadata></head>
                  <body><div>
                    <p begin="1s" end="4s" ttm:agent="v1">
                      <span begin="1s" end="2s" tts:ruby="container"><span tts:ruby="base">To</span><span tts:ruby="text">tou</span></span>
                      <span begin="2s" end="4s">gether</span>
                      <span ttm:role="x-bg" begin="1.5s" end="3s"><span begin="1.5s" end="3s">(one)</span></span>
                      <span ttm:role="x-bg" begin="2s" end="3.5s"><span begin="2s" end="3.5s">(two)</span></span>
                    </p>
                    <p begin="2.5s" end="5s" ttm:agent="v2"><span begin="2.5s" end="5s">Duet</span></p>
                  </div></body>
                </tt>
                """;

        LyricsDocument document = parser.parse(ttml.getBytes(StandardCharsets.UTF_8));

        assertThat(document.metadata().trackKey()).isEqualTo("6wOYnPq1hPihRepcDClPUw");
        assertThat(document.metadata().title()).isEqualTo("Meet Me Halfway");
        assertThat(document.metadata().songwriters()).isEqualTo("will.i.am, Jean Baptiste");
        assertThat(document.metadata().syncMode()).isEqualTo(LyricsSyncMode.WORD);
        assertThat(document.groups()).hasSize(2);
        assertThat(document.groups().getFirst().lead().text()).isEqualTo("Together");
        assertThat(document.groups().getFirst().backingVocals()).hasSize(2);
        assertThat(document.groups().get(1).alignment()).isEqualTo(LyricAlignment.OPPOSITE);
        assertThat(document.activeGroupsAt(2_750)).hasSize(2);
    }

    @Test
    void alignsVoiceTwoRightEvenWhenItIsTheFirstPhraseAndKeepsItsBackingVocals() {
        String ttml = """
                <tt xmlns="http://www.w3.org/ns/ttml"
                    xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
                  <body><div>
                    <p begin="1s" end="4s" ttm:agent="v2">
                      <span begin="1s" end="4s">Second voice opens</span>
                      <span ttm:role="x-bg" begin="2s" end="3s">(backing)</span>
                    </p>
                    <p begin="4s" end="6s" ttm:agent="v1">Primary voice</p>
                  </div></body>
                </tt>
                """;

        LyricsDocument document = parser.parse(ttml.getBytes(StandardCharsets.UTF_8));

        assertThat(document.groups().getFirst().alignment()).isEqualTo(LyricAlignment.OPPOSITE);
        assertThat(document.groups().getFirst().backingVocals()).hasSize(1);
        assertThat(document.groups().get(1).alignment()).isEqualTo(LyricAlignment.NORMAL);
    }
}
