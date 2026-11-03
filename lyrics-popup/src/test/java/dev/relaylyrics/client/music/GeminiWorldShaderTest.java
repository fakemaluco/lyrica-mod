package dev.relaylyrics.client.music;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class GeminiWorldShaderTest {
    @Test void oldPopupContractIsRejectedAndWorldShaderReceivesRealSceneAndDepth() {
        assertThatThrownBy(()->new GeminiShaderSpec("Old","","vec3 musicShader(vec2 uv) { return vec3(uv,0.); }"))
                .isInstanceOf(IllegalArgumentException.class);
        String code="vec3 musicWorld(vec3 sceneColor, vec2 uv, float depth) { return vec3(dot(sceneColor,vec3(.2126,.7152,.0722))); }";
        var spec=new GeminiShaderSpec("Monochrome","World colors",code);
        String wrapped=GeminiShaderSpec.fragmentSource(spec.glsl(),true);
        assertThat(wrapped).contains("texture(SceneSampler, texCoord)","texture(DepthSampler, texCoord)","musicWorld(scene, texCoord, depth)","#version 330");
        assertThat(wrapped).contains("1.0 - rawDepth", "MusicBands.w");
    }
    @Test void worldUniformLayoutNormalizesReversedDepthAndPreservesNativeResolution() {
        var spec=new GeminiShaderSpec("Test","","vec3 musicWorld(vec3 color, vec2 uv, float depth) { return color; }");
        var effect=new GeminiShaderSession.WorldEffect("id",spec,12,12,.4f,.3f,.2f,.5f,.7f,.8f,.6f,.9f,1);
        var modern=effect.uniforms(1920,1080,true).asFloatBuffer();
        assertThat(modern.remaining()).isEqualTo(16);assertThat(modern.get(0)).isEqualTo(12);
        assertThat(modern.get(7)).isEqualTo(1);assertThat(modern.get(11)).isEqualTo(1);
        assertThat(modern.get(12)).isEqualTo(1920);assertThat(modern.get(13)).isEqualTo(1080);
        assertThat(effect.uniforms(1280,720,false).asFloatBuffer().get(11)).isZero();
    }
}
