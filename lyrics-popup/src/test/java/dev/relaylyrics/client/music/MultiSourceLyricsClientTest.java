package dev.relaylyrics.client.music;

import dev.lyrica.engine.DesktopLyricsEngine;
import dev.relaylyrics.protocol.lyrics.LyricsSource;
import dev.relaylyrics.protocol.lyrics.LyricsSyncMode;
import dev.relaylyrics.protocol.music.PlaybackSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MultiSourceLyricsClientTest {
    @TempDir Path cache;

    @Test void spicyRomanizationStaysWordSyncedFromJsonThroughThePopupRenderer() {
        var body = com.google.gson.JsonParser.parseString("""
                {"Content":[{"Lead":{"Syllables":[
                  {"Text":"こん","TransliteratedText":"konn","StartTime":1,"EndTime":1.2,"IsPartOfWord":true},
                  {"Text":"にちは","TransliteratedText":"ichiwa","StartTime":1.3,"EndTime":1.9,"IsPartOfWord":false},
                  {"Text":"世界","TransliteratedText":"sekai","StartTime":2.7,"EndTime":4,"IsPartOfWord":false}]},
                  "Background":[
                    {"Syllables":[{"Text":"声","TransliteratedText":"koe","StartTime":1,"EndTime":4}]},
                    {"Syllables":[{"Text":"愛","TransliteratedText":"ai","StartTime":1,"EndTime":4}]}]}]}
                """).getAsJsonObject();
        var xml = com.tx24.spicyplayer.network.data.providers.SpicyLyricsTtmlConverter.INSTANCE.convert(body, body.getAsJsonArray("Content"));
        try (var client = new MultiSourceLyricsClient(cache)) {
            var document = client.convert(payload(xml, null, null, "WORD_SYNCED"), PlaybackSnapshot.unavailable()).document().orElseThrow();
            var group = document.groups().getFirst();
            var words = LyricsRenderText.visualWords(group.lead(), true);
            assertThat(words).extracting(LyricsRenderText.VisualWord::text).containsExactly("konnichiwa", "sekai");
            assertThat(words.getFirst().fragments()).extracting(fragment -> fragment.timing().startMillis())
                    .containsExactly(1_000L, 1_300L);
            assertThat(words.getLast().startMillis()).isEqualTo(2_700);
            assertThat(words.getLast().endMillis()).isEqualTo(4_000);
            assertThat(group.backingVocals()).extracting(line -> LyricsRenderText.visualWords(line, true).getFirst().text())
                    .containsExactly("koe", "ai");
        }
    }

    private DesktopLyricsEngine.Result payload(String ttml, String lrc, String plain, String quality) {
        return new DesktopLyricsEngine.Result(ttml, lrc, plain, "lrc.red", quality, "lrc.red: HIT", false,
                List.of("Writer"), "syncer", "", "uploader", "", "lrc_red");
    }

    @Test void remoteTtmlPreservesWordTimingAndAttribution() {
        String ttml = """
                <tt xmlns="http://www.w3.org/ns/ttml"><body><div>
                  <p begin="1s" end="4s"><span begin="1s" end="2s">Hello </span><span begin="2s" end="4s">world</span></p>
                </div></body></tt>
                """;
        try (MultiSourceLyricsClient client = new MultiSourceLyricsClient(cache)) {
            var result = client.convert(payload(ttml, null, null, "WORD_SYNCED"), PlaybackSnapshot.unavailable());
            var document = result.document().orElseThrow();
            assertThat(document.lines().getFirst().words()).hasSize(2);
            assertThat(document.lines().getFirst().words().getLast().startMillis()).isEqualTo(2000);
            assertThat(document.metadata().source()).isEqualTo(LyricsSource.REMOTE);
            assertThat(document.metadata().provider()).isEqualTo("lrc.red");
            assertThat(document.metadata().makerUsername()).isEqualTo("syncer");
            assertThat(document.metadata().songwriters()).isEqualTo("Writer");
        }
    }

    @Test void enhancedLrcNeverLosesItsWordTimestamps() {
        try (MultiSourceLyricsClient client = new MultiSourceLyricsClient(cache)) {
            var result = client.convert(payload(null, "[00:01.00]<00:01.00>Hello <00:02.50>world\n[00:04.00]Next", null,
                    "WORD_SYNCED"), PlaybackSnapshot.unavailable());
            var document = result.document().orElseThrow();
            assertThat(document.lines().getFirst().words().getLast().startMillis()).isEqualTo(2500);
            assertThat(document.metadata().syncMode()).isEqualTo(LyricsSyncMode.WORD);
        }
    }

    @Test void plainLyricsRemainStaticWithoutInventingKaraokeTiming() {
        try (MultiSourceLyricsClient client = new MultiSourceLyricsClient(cache)) {
            var result = client.convert(payload(null, null, "First line\nSecond line", "PLAIN"), PlaybackSnapshot.unavailable());
            var document = result.document().orElseThrow();
            assertThat(document.metadata().syncMode()).isEqualTo(LyricsSyncMode.STATIC);
            assertThat(document.lines()).allSatisfy(line -> {
                assertThat(line.startMillis()).isZero();
                assertThat(line.words()).isEmpty();
            });
        }
    }

    @Test void remoteXmlCannotReadExternalFilesAndCanFallbackToLrc() {
        String unsafe = "<!DOCTYPE tt [<!ENTITY local SYSTEM 'file:///does-not-exist'>]><tt><body><p>&local;</p></body></tt>";
        try (MultiSourceLyricsClient client = new MultiSourceLyricsClient(cache)) {
            var result = client.convert(payload(unsafe, "[00:01.00]Safe fallback", null, "LINE_SYNCED"), PlaybackSnapshot.unavailable());
            assertThat(result.document().orElseThrow().lines().getFirst().text()).isEqualTo("Safe fallback");
            assertThat(result.diagnostics()).contains("TTML rejected");
        }
    }

    @Test void registryContainsEveryBuiltInNetworkAdapter() {
        assertThat(MultiSourceLyricsClient.sources()).hasSize(14);
        assertThat(MultiSourceLyricsClient.defaultSources()).contains("lrc_red", "lrclib", "unison", "qq_music",
                "kugou", "kuwo", "netease", "genius", "youtube_transcript", "rmm_revival", "spicy_unison", "lrcmux", "amll_ttml_db", "spicy_lyrics");
    }

    @Test void spicyJsonThroughTheMobileConverterKeepsDuetSidesAndBackingTiming() {
        var body = com.google.gson.JsonParser.parseString("""
                {"Content":[{"OppositeAligned":true,"Lead":{"Syllables":[
                  {"Text":"Right","StartTime":1,"EndTime":3}]},"Background":[{"Syllables":[
                  {"Text":"Backing","StartTime":2,"EndTime":4}]}]},
                  {"OppositeAligned":false,"Lead":{"Syllables":[{"Text":"Left","StartTime":2,"EndTime":5}]}}]}
                """).getAsJsonObject();
        var xml = com.tx24.spicyplayer.network.data.providers.SpicyLyricsTtmlConverter.INSTANCE.convert(body, body.getAsJsonArray("Content"));
        try (var client = new MultiSourceLyricsClient(cache)) {
            var document = client.convert(payload(xml, null, null, "WORD_SYNCED"), PlaybackSnapshot.unavailable()).document().orElseThrow();
            assertThat(document.groups()).extracting(dev.relaylyrics.protocol.lyrics.LyricVocalGroup::alignment)
                    .containsExactly(dev.relaylyrics.protocol.lyrics.LyricAlignment.OPPOSITE, dev.relaylyrics.protocol.lyrics.LyricAlignment.NORMAL);
            assertThat(document.activeGroupsAt(2500)).hasSize(2);
            assertThat(document.groups().getFirst().backingVocals().getFirst().endMillis()).isEqualTo(4000);
        }
    }
}
