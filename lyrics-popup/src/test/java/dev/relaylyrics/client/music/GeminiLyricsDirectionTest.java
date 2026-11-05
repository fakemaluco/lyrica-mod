package dev.relaylyrics.client.music;

import com.sun.net.httpserver.HttpServer;
import dev.relaylyrics.client.ClientPreferences;
import dev.relaylyrics.protocol.ProtocolCodec;
import dev.relaylyrics.protocol.lyrics.*;
import dev.relaylyrics.protocol.music.PlaybackSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

class GeminiLyricsDirectionTest {
    @TempDir Path temp;

    @Test void rejectsTimingEditsUnknownIdsInvalidGroupingAndExecutableFields() throws Exception {
        var commission = GeminiLyricsClient.commission(song("A", true), lyrics());
        String json = direction(commission);
        var parsed = GeminiLyricsSpec.parse(json, commission.timeline());
        assertThat(parsed.cues()).hasSize(3);
        var cue = parsed.cues().values().iterator().next();
        assertThat(lyrics().lines()).contains(cue.source().line());
        assertThat(cue.source().line().words()).isNotEmpty();
        for (String invalid : List.of(json.replace("\"holdMillis\":1500", "\"holdMillis\":900000"),
                json.replace("\"region\":\"RIGHT\"", "\"region\":\"RUN_CODE\""),
                json.replace("\"groupEnds\":[2]", "\"groupEnds\":[3]"),
                json.replace("\"groupEnds\":[2]", "\"groupEnds\":[4294967298]"),
                json.replace("\"accents\":[]", "\"accents\":[],\"startMillis\":0"),
                json.replace(commission.timeline().cues().getFirst().id(), "unknown-id")))
            assertThatThrownBy(() -> GeminiLyricsSpec.parse(invalid, commission.timeline())).isInstanceOf(Exception.class);
    }

    @Test void commissionIncludesOriginalSyllableTimesAndNeverAllowsSplitWordGroups() throws Exception {
        var line = new LyricLine(0, 3000, "Hello world", List.of(new TimedWord(0, 500, "Hel"),
                new TimedWord(500, 1000, "lo"), new TimedWord(1000, 3000, " world")), "v1", false, null, null);
        var input = GeminiLyricsClient.commission(song("A", true), new LyricsDocument(List.of(line)));
        var source = ProtocolCodec.mapper().readTree(input.json()).path("lines").get(0);
        assertThat(source.path("tokens").get(1).path("startMillis").asLong()).isEqualTo(500);
        assertThat(source.path("agent").asText()).isEqualTo("v1");
        String json = direction(input).replace("\"groupEnds\":[3]", "\"groupEnds\":[1,3]");
        assertThatThrownBy(() -> GeminiLyricsSpec.parse(json, input.timeline())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void modelKeepsTimedWordsTogetherAndCanIsolateTheLastWord() throws Exception {
        var line = new LyricLine(0, 3000, "Hello love", List.of(new TimedWord(0, 500, "Hel"),
                new TimedWord(500, 1000, "lo", true), new TimedWord(1000, 3000, " love")), "v1", false, null, null);
        var input = GeminiLyricsClient.commission(song("A", true), new LyricsDocument(List.of(line)), true);
        String id = input.timeline().cues().getFirst().id();
        String wire = """
                {"songColor":"#ff8fa3","songFont":"OSWALD","cues":[{"id":"%s","composition":"PROGRESSIVE",
                "region":"CENTER","entrance":"FADE","volumetric":false,"holdMillis":1500,
                "accentToken":2,"accentEntrance":"POP","accentCenter":true}]}
                """.formatted(id);
        var spec = GeminiLyricsSpec.fromModel(wire, input.timeline());
        var cue = spec.cues().get(id);
        var tokens = input.timeline().tokens(cue);
        assertThat(cue.groupEnds()).containsExactly(2, 3);
        assertThat(cue.look().color()).isEqualTo(0xff8fa3);
        assertThat(cue.look().font()).isEqualTo(WorldLyricsPresentation.SongFont.OSWALD);
        assertThat(cue.accentAt(2).entrance()).isEqualTo(WorldLyricsPresentation.Entrance.POP);
        var groups = WorldLyricsStage.groups(cue, tokens, WorldLyricsPresentation.Intensity.DISCREET);
        assertThat(groups.getFirst().region()).isNotEqualTo(WorldLyricsPresentation.Region.CENTER);
        assertThat(groups.getLast().region()).isEqualTo(WorldLyricsPresentation.Region.CENTER);
    }

    @Test void onlyGeneratesWhenEnabledAndActivatesAtNextPhraseWithSeekReconstruction() throws Exception {
        var p = new ClientPreferences(); p.setWorldLyrics(true); var time = new AtomicLong(); var calls = new AtomicInteger();
        try (var session = new GeminiLyricsSession(p, temp, (key, model, input, prompt) -> {
            calls.incrementAndGet(); return direction(input);
        }, time::get)) {
            session.saveKey("fixture_key_1234567890"); session.tick(song("A", true), lyrics(), 1000);
            assertThat(session.status()).isEqualTo("disabled"); assertThat(calls).hasValue(0);
            p.setWorldLyricsDirection(WorldLyricsPresentation.Direction.GEMINI);
            var doc = lyrics(); session.tick(song("A", true), doc, 1000);
            time.set(2_000_000_000L); session.tick(song("A", true), doc, 2000); finish(session);
            assertThat(session.status()).isEqualTo("ready"); assertThat(calls).hasValue(1);
            assertThat(session.overrides().values()).allMatch(cue -> cue.source().line().startMillis() > 2000);
            for (int i = 0; i < 10; i++) session.tick(song("A", true), doc, 2500);
            assertThat(calls).hasValue(1);
            session.tick(song("A", true), doc, 0);
            assertThat(session.overrides()).hasSize(3);
            p.setWorldLyricsDirection(WorldLyricsPresentation.Direction.AUTOMATIC);
            assertThat(session.overrides()).isEmpty(); session.tick(song("A", true), doc, 0);
            assertThat(session.status()).isEqualTo("disabled");
        }
    }

    @Test void lateResponseFromOldTrackIsDiscardedAndMalformedReplyDoesNotRetryEachTick() throws Exception {
        var p = enabled(); var time = new AtomicLong(); var began = new CountDownLatch(1); var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        try (var session = new GeminiLyricsSession(p, temp, (key, model, input, prompt) -> {
            calls.incrementAndGet(); began.countDown();
            while (release.getCount() != 0) { try { release.await(1, TimeUnit.SECONDS); } catch (InterruptedException ignored) { } }
            return direction(input);
        }, time::get)) {
            session.saveKey("fixture_key_1234567890"); var doc = lyrics(); session.tick(song("A", true), doc, 0);
            time.set(2_000_000_000L); session.tick(song("A", true), doc, 1000);
            assertThat(began.await(2, TimeUnit.SECONDS)).isTrue();
            session.tick(song("B", true), doc, 0); release.countDown();
            time.set(3_000_000_000L); session.tick(song("B", true), doc, 1000); finish(session);
            assertThat(session.overrides()).isEmpty(); assertThat(calls).hasValue(1);
        } finally { release.countDown(); }
        var badCalls = new AtomicInteger(); time.set(30_000_000_000L);
        try (var session = new GeminiLyricsSession(p, temp.resolve("invalid"), (key, model, input, prompt) -> {
            badCalls.incrementAndGet(); return "{\"cues\":[{\"code\":\"anything\"}]}";
        }, time::get)) {
            session.saveKey("fixture_key_1234567890"); var doc = lyrics(); session.tick(song("A", true), doc, 0);
            time.addAndGet(2_000_000_000L); session.tick(song("A", true), doc, 1000); finish(session);
            assertThat(session.status()).isEqualTo("response"); assertThat(session.overrides()).isEmpty();
            for (int i = 0; i < 10; i++) session.tick(song("A", true), doc, 2000);
            assertThat(badCalls).hasValue(1);
        }
    }

    @Test void cacheAvoidsNewRequestButChangesInSourceTimingOrDirectionInvalidateIt() throws Exception {
        var p = enabled(); var time = new AtomicLong(); var doc = lyrics(); var calls = new AtomicInteger();
        String oldKey;
        try (var session = new GeminiLyricsSession(p, temp, (key, model, input, prompt) -> direction(input), time::get)) {
            session.saveKey("fixture_key_1234567890"); session.tick(song("A", true), doc, 0);
            time.set(2_000_000_000L); session.tick(song("A", true), doc, 1000); finish(session); oldKey = session.cacheKey();
        }
        try (var session = new GeminiLyricsSession(p, temp, (key, model, input, prompt) -> { calls.incrementAndGet(); return direction(input); }, time::get)) {
            session.tick(song("A", true), doc, 0); time.addAndGet(2_000_000_000L); session.tick(song("A", true), doc, 1000); finish(session);
            assertThat(session.status()).isEqualTo("cached"); assertThat(calls).hasValue(0);
            p.setWorldLyricsGeminiDirection("soft falling words"); session.tick(song("A", true), doc, 1000);
            assertThat(session.cacheKey()).isNotEqualTo(oldKey);
            var line = doc.lines().getFirst(); var changed = new LyricsDocument(List.of(new LyricLine(line.startMillis() + 10,
                    line.endMillis(), line.text(), line.words(), line.agent(), line.background(), null, null)));
            String promptKey = session.cacheKey(); session.tick(song("A", true), changed, 1000);
            assertThat(session.cacheKey()).isNotEqualTo(promptKey);
            session.removeKey(); assertThat(session.keyConfigured()).isFalse(); assertThat(session.overrides()).isEmpty();
        }
        assertThat(Files.exists(temp.resolve("api-key.txt"))).isFalse();
    }

    @Test void translationRefreshRebindsOriginalReferencesWithoutSpendingAnotherRequest() throws Exception {
        var p = enabled(); var time = new AtomicLong(); var calls = new AtomicInteger();
        try (var session = new GeminiLyricsSession(p, temp, (key, model, input, prompt) -> {
            calls.incrementAndGet(); return direction(input);
        }, time::get)) {
            session.saveKey("fixture_key_1234567890"); var source = lyrics(); session.tick(song("A", true), source, 0);
            time.set(2_000_000_000L); session.tick(song("A", true), source, 1000); finish(session);
            String cache = session.cacheKey();
            var translated = new LyricsDocument(source.lines().stream().map(line -> new LyricLine(line.startMillis(),
                    line.endMillis(), line.text(), line.words(), line.agent(), line.background(), "Olá mundo", null)).toList());
            session.tick(song("A", true), translated, 1000);
            assertThat(session.cacheKey()).isEqualTo(cache); assertThat(calls).hasValue(1);
            assertThat(session.overrides()).hasSize(2);
            assertThat(session.overrides().values()).allMatch(cue -> "Olá mundo".equals(cue.source().line().translation()));
            assertThat(session.pending()).isNull();
        }
    }

    @Test void clientUsesStructuredOutputAndKeepsCredentialOutOfUrlAndPayload() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var requests = new AtomicInteger();
        var input = GeminiLyricsClient.commission(song("A", true), lyrics());
        server.createContext("/models/gemini-flash-latest:generateContent", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.createContext("/models/gemini-3.5-flash-lite:generateContent", exchange -> {
            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                assertThat(exchange.getRequestHeaders().getFirst("x-goog-api-key")).isEqualTo("fixture_key_1234567890");
                assertThat(body).doesNotContain("fixture_key_1234567890");
                var json = ProtocolCodec.mapper().readTree(body);
                assertThat(json.path("generationConfig").path("responseJsonSchema").path("required").toString()).contains("cues");
                assertThat(body).contains("startMillis", "endMillis", "Hello world"); requests.incrementAndGet();
                var cues = input.timeline().cues().stream().map(cue -> Map.of(
                        "id", cue.id(), "composition", "FALLING_WORDS", "region", "RIGHT",
                        "entrance", "FALL", "volumetric", false, "holdMillis", 1500, "accentToken", 1)).toList();
                var flat = ProtocolCodec.mapper().createArrayNode();
                cues.forEach(cue -> flat.add(ProtocolCodec.mapper().valueToTree(cue)));
                flat.addObject().put("id", "unknown");
                String modelReply = ProtocolCodec.mapper().createObjectNode().set("cues", flat).toString();
                byte[] bytes = ProtocolCodec.mapper().writeValueAsBytes(Map.of("candidates", List.of(Map.of("finishReason", "STOP",
                        "content", Map.of("parts", List.of(Map.of("thought", true, "text", "private reasoning"), Map.of("text", modelReply)))))));
                exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
            } catch (Exception error) { throw new RuntimeException(error); } finally { exchange.close(); }
        });
        server.start();
        try {
            var client = new GeminiLyricsClient(HttpClient.newHttpClient(), URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/models/"));
            var reply = client.generate("fixture_key_1234567890", "gemini-flash-latest", input, "gentle");
            var parsed = GeminiLyricsSpec.parse(reply, input.timeline());
            assertThat(parsed.cues()).hasSize(3);
            parsed.cues().values().forEach(cue -> {
                assertThat(cue.groupEnds()).containsExactly(1, 2);
                assertThat(cue.accents()).extracting(WorldLyricsPresentation.Accent::tokenIndex).containsExactly(1);
            });
            assertThat(requests).hasValue(2);
        } finally { server.stop(0); }
    }

    private static ClientPreferences enabled() {
        var p = new ClientPreferences(); p.setWorldLyrics(true); p.setWorldLyricsDirection(WorldLyricsPresentation.Direction.GEMINI); return p;
    }
    private static PlaybackSnapshot song(String title, boolean playing) { return GeminiShaderSessionTest.song(title, playing); }
    private static LyricsDocument lyrics() {
        var lines = new ArrayList<LyricLine>();
        for (int i = 0; i < 3; i++) { long start = i * 10_000L;
            lines.add(new LyricLine(start, start + 5000, "Hello world", List.of(new TimedWord(start, start + 2000, "Hello"),
                    new TimedWord(start + 2000, start + 5000, " world")), "v1", false, null, null)); }
        return new LyricsDocument(lines);
    }
    private static String direction(GeminiLyricsClient.Commission input) throws Exception {
        var cues = new ArrayList<Map<String, Object>>();
        for (var cue : input.timeline().cues()) cues.add(Map.of("id", cue.id(), "composition", "FALLING_WORDS", "region", "RIGHT",
                "entrance", "FALL", "volumetric", false, "holdMillis", 1500, "accents", List.of(),
                "groupEnds", List.of(WorldLyricsPlan.tokens(cue.source().line(), false).size())));
        return ProtocolCodec.mapper().writeValueAsString(Map.of("cues", cues));
    }
    private static void finish(GeminiLyricsSession session) throws Exception {
        var pending = session.pending(); if (pending != null) pending.get(5, TimeUnit.SECONDS);
    }
}
