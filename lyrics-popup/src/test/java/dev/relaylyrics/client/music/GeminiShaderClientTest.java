package dev.relaylyrics.client.music;

import com.sun.net.httpserver.HttpServer;
import dev.relaylyrics.protocol.ProtocolCodec;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class GeminiShaderClientTest {
    static final String CODE = "vec3 musicWorld(vec3 sceneColor, vec2 uv, float depth) { return vec3(uv.x, uv.y, .2 + .1 * sin(uTime)); }";
    @Test void sendsTheKeyOnlyInTheHeaderAndParsesStructuredGlsl() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var calls = new AtomicInteger();
        server.createContext("/models/gemini-flash-latest:generateContent", exchange -> {
            try {
                calls.incrementAndGet();
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                assertThat(exchange.getRequestHeaders().getFirst("x-goog-api-key")).isEqualTo("fake_test_key_1234567890");
                assertThat(exchange.getRequestURI().toString()).doesNotContain("fake_test_key");
                assertThat(body).doesNotContain("fake_test_key");
                var json = ProtocolCodec.mapper().readTree(body);
                assertThat(json.path("generationConfig").path("responseMimeType").asText()).isEqualTo("application/json");
                assertThat(json.path("generationConfig").path("responseJsonSchema").path("required").toString()).contains("glsl");
                var parts = List.of(
                        Map.of("thought", true, "text", "not part of the final JSON"),
                        Map.of("text", new GeminiShaderSpec("Ocean", "Ondas para esta música", CODE).json()));
                var candidate = Map.of("finishReason", "STOP", "content", Map.of("parts", parts));
                byte[] answer = ProtocolCodec.mapper().writeValueAsBytes(Map.of("candidates", List.of(candidate)));
                exchange.sendResponseHeaders(200, answer.length); exchange.getResponseBody().write(answer);
            } catch (Exception error) { throw new RuntimeException(error); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            var client = new GeminiShaderClient(HttpClient.newHttpClient(), URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/models/"));
            var shader = client.generate("fake_test_key_1234567890", "models/gemini-flash-latest",
                    new GeminiShaderClient.Track("Song", "Artist", "Album", 120_000, 120), "violet waves");
            assertThat(shader.name()).isEqualTo("Ocean"); assertThat(shader.glsl()).isEqualTo(CODE); assertThat(calls).hasValue(1);
        } finally { server.stop(0); }
    }
    @Test void quotaAndAuthenticationErrorsDoNotExposeTheResponseOrCredential() throws Exception {
        for (int status : new int[]{403, 429}) {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                byte[] text = "private error details and fake_test_key_1234567890".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, text.length); exchange.getResponseBody().write(text); exchange.close();
            });
            server.start();
            try {
                var client = new GeminiShaderClient(HttpClient.newHttpClient(), URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"));
                assertThatThrownBy(() -> client.generate("fake_test_key_1234567890", "gemini-flash-latest",
                        new GeminiShaderClient.Track("Song", "Artist", "", 120_000, 0), ""))
                        .isInstanceOf(GeminiShaderClient.Failure.class).hasMessage(status == 403 ? "AUTH" : "QUOTA");
            } finally { server.stop(0); }
        }
    }
    @Test void modelIdsCannotRedirectTheCredentialToAnotherServer() {
        for (String model : new String[]{"https://other.example", "../escape", "model?key=oops", "model:other", "model/child"})
            assertThatThrownBy(() -> GeminiShaderClient.model(model)).isInstanceOf(IllegalArgumentException.class);
        assertThat(GeminiShaderClient.model("models/gemini-flash-latest")).isEqualTo("gemini-flash-latest");
    }
    @Test void rejectsUnboundedOrExternalShaderOperationsAndKeepsTheHostEntryPoint() throws Exception {
        for (String forbidden : new String[]{"while(true) {}", "for(int i=0;i<99;i++) {}", "#version 450", "uniform sampler2D s;", "gl_FragDepth=0.;", "discard;", "float a[99];"})
            assertThatThrownBy(() -> new GeminiShaderSpec("Test", "", "vec3 musicWorld(vec3 sceneColor, vec2 uv, float depth) { " + forbidden + " return vec3(0.); }"))
                    .isInstanceOf(IllegalArgumentException.class);
        assertThat(new GeminiShaderSpec("Test", "", "/* for a soft mood */ " + CODE).glsl()).contains("musicWorld");
        assertThat(GeminiShaderSpec.fragmentSource(CODE)).contains("#version 150", "void main()", "uEnergy", "uBass", "musicWorld(scene, texCoord, depth)");
    }
}
