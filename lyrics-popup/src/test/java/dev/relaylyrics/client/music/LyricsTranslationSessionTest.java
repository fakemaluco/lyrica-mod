package dev.relaylyrics.client.music;

import dev.relaylyrics.client.ClientPreferences;
import dev.relaylyrics.protocol.lyrics.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class LyricsTranslationSessionTest {
    @org.junit.jupiter.api.io.TempDir Path directory;
    private static LyricsDocument song(String text) {
        return new LyricsDocument(List.of(new LyricLine(100, 200, text, List.of(), "a", false, null, "pronunciation")));
    }
    @Test void defaultIsLocalOnlyAndManualTranslationKeepsOriginalTimingsAndRomanization() {
        var p = new ClientPreferences();
        var responses = new ArrayList<CompletableFuture<List<String>>>();
        try (var session = new LyricsTranslationSession(Path.of("unused"), p, (a,b,c,d,e,f) -> {
            var future = new CompletableFuture<List<String>>(); responses.add(future); return future;
        })) {
            var original = song("hello");
            assertThat(session.tick(original)).isSameAs(original);
            assertThat(responses).isEmpty();
            session.request(); session.tick(original);
            responses.getFirst().complete(List.of("olá"));
            var result = session.tick(original);
            assertThat(result.lines().getFirst().translation()).isEqualTo("olá");
            assertThat(result.lines().getFirst().startMillis()).isEqualTo(100);
            assertThat(result.lines().getFirst().romanization()).isEqualTo("pronunciation");
            p.setLyricsTranslation(false);
            assertThat(session.tick(result)).isSameAs(original);
        }
    }
    @Test void songAndLanguageChangesCancelOlderRequests() {
        var p = new ClientPreferences(); p.setAutomaticTranslation(true);
        var responses = new ArrayList<CompletableFuture<List<String>>>();
        try (var session = new LyricsTranslationSession(Path.of("unused"), p, (a,b,c,d,e,f) -> {
            var future = new CompletableFuture<List<String>>(); responses.add(future); return future;
        })) {
            var first = song("first"); var second = song("second");
            session.tick(first); session.tick(second);
            assertThat(responses.get(0).isCancelled()).isTrue();
            p.setTranslationLanguage("es"); session.tick(second);
            assertThat(responses.get(1).isCancelled()).isTrue();
            responses.get(2).complete(List.of("segundo"));
            var shown = session.tick(second);
            assertThat(shown.lines().getFirst().text()).isEqualTo("second");
            assertThat(shown.lines().getFirst().translation()).isEqualTo("segundo");
        }
    }

    @Test void failuresKeepTheOriginalAndKeysAreSeparateFromPreferences() throws Exception {
        var p = new ClientPreferences(); p.setAutomaticTranslation(true);
        try (var session = new LyricsTranslationSession(directory, p, (a,b,c,d,e,f) ->
                CompletableFuture.failedFuture(new IllegalArgumentException("private provider response")))) {
            var original = song("hello");
            assertThat(session.tick(original)).isSameAs(original);
            assertThat(session.status()).isEqualTo("failed");
            p.setTranslationProvider("Gemini");
            assertThat(session.saveKey("private-api-key")).isTrue();
            assertThat(session.keyConfigured()).isTrue();
            assertThat(session.saveKey(" ")).isFalse();
            p.setTranslationProvider("DeepL");
            assertThat(session.keyConfigured()).isFalse();
            p.setTranslationProvider("Gemini");
            assertThat(session.keyConfigured()).isTrue();
            assertThat(dev.relaylyrics.protocol.ProtocolCodec.mapper().writeValueAsString(p)).doesNotContain("private-api-key");
            assertThat(session.removeKey()).isTrue();
            assertThat(session.keyConfigured()).isFalse();
        }
    }
}
