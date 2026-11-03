package dev.fandroid.client.music;

import dev.fandroid.protocol.ProtocolCodec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** The small fragment-function contract accepted from Gemini and from the local cache. */
public record GeminiShaderSpec(String name, String description, String glsl) {
    public static final int CONTRACT = 4;
    private static final Pattern FORBIDDEN = Pattern.compile("\\b(for|while|do|discard|uniform|buffer|layout|attribute|varying|precision|main|gl_\\w*|sampler\\w*|image\\w*|atomic\\w*|texture\\w*)\\b|[#\\[\\]]");
    private static final Pattern ENTRY = Pattern.compile("\\bvec3\\s+musicWorld\\s*\\(\\s*vec3\\s+\\w+\\s*,\\s*vec2\\s+\\w+\\s*,\\s*float\\s+\\w+\\s*\\)");
    public GeminiShaderSpec {
        name = clean(name, 80); description = clean(description, 280);
        glsl = glsl == null ? "" : glsl.strip();
        if (glsl.length() < 20 || glsl.length() > 12_000) throw new IllegalArgumentException("SHADER_CONTRACT");
        String code = glsl.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\r\\n]*", "");
        if (!ENTRY.matcher(code).find() || FORBIDDEN.matcher(code).find()
                || code.chars().anyMatch(c -> c > 126 || c < 32 && c != '\n' && c != '\r' && c != '\t'))
            throw new IllegalArgumentException("SHADER_CONTRACT");
        if (code.chars().filter(c -> c == '{').count() > 32) throw new IllegalArgumentException("SHADER_CONTRACT");
    }
    public static GeminiShaderSpec parse(String json) throws Exception {
        if (json == null || json.length() > 32_768) throw new IllegalArgumentException("SHADER_RESPONSE");
        var root = ProtocolCodec.mapper().readTree(json);
        return new GeminiShaderSpec(root.path("name").asText(""), root.path("description").asText(""), root.path("glsl").asText(""));
    }
    public String json() throws Exception { return ProtocolCodec.mapper().writeValueAsString(this); }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private static String clean(String value, int max) {
        String result = value == null ? "" : value.replaceAll("[\\p{Cntrl}]", " ").strip();
        return result.substring(0, Math.min(max, result.length()));
    }
    public static String vertexSource(boolean modern) {
        return (modern ? "#version 330\n" : "#version 150\n") + """
                out vec2 texCoord;
                void main() {
                    vec2 uv = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                    gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
                    texCoord = uv;
                }
                """;
    }
    public static String fragmentSource(String glsl) { return fragmentSource(glsl, false); }
    public static String fragmentSource(String glsl, boolean modern) {
        return (modern ? "#version 330\n" : "#version 150\n") + """
                uniform sampler2D SceneSampler;
                uniform sampler2D DepthSampler;
                layout(std140) uniform WorldMusic {
                    vec4 MusicTime;
                    vec4 MusicBands;
                    vec4 MusicAccent;
                    vec4 MusicResolution;
                };
                #define uTime MusicTime.x
                #define uTrackTime MusicTime.y
                #define uEnergy MusicTime.z
                #define uBeat MusicTime.w
                #define uBass MusicBands.x
                #define uMid MusicBands.y
                #define uTreble MusicBands.z
                #define uAccent MusicAccent.xyz
                #define uResolution MusicResolution.xy
                in vec2 texCoord;
                out vec4 fragColor;
                """ + GeminiArtLibrary.functions() + glsl + """

                void main() {
                    vec3 scene = texture(SceneSampler, texCoord).rgb;
                    float rawDepth = texture(DepthSampler, texCoord).r;
                    float depth = mix(rawDepth, 1.0 - rawDepth, MusicAccent.w);
                    vec3 changed = clamp(musicWorld(scene, texCoord, depth), 0.0, 1.0);
                    fragColor = vec4(mix(scene, changed, clamp(MusicBands.w, 0.0, 1.0)), 1.0);
                }
                """;
    }
}
