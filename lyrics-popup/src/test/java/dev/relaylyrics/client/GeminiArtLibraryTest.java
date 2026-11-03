package dev.relaylyrics.client;
import dev.relaylyrics.client.music.*;
import dev.relaylyrics.client.ClientPreferences;
import dev.relaylyrics.protocol.ProtocolCodec;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class GeminiArtLibraryTest {
    @Test void recipesApplyEditableCombinationsAndRestoreResetsArt() {
        var p=new ClientPreferences();
        for(int i=0;i<GeminiArtLibrary.RECIPES.size();i++){
            GeminiArtLibrary.RECIPES.get(i).apply(p);assertThat(GeminiArtLibrary.recipeIndex(p)).isEqualTo(i+1);
        }
        p.setGeminiArtScale(270);assertThat(GeminiArtLibrary.recipeIndex(p)).isZero();
        var catalog=new LyricsSettingsCatalog(p,new LyricsSettingsCatalog.Actions(){public void run(String n){}public String status(String n){return "";}});
        catalog.restore("all");assertThat(p.getGeminiArtStyle()).isEqualTo("auto");assertThat(p.getGeminiArtSecondary()).isEqualTo("none");assertThat(p.isGeminiArtAnimated()).isFalse();
    }
    @Test void selectedArtCompositionReachesGenerationWithExactParameters() {
        var p=new ClientPreferences();p.setGeminiArtStyle("pencil");p.setGeminiArtSecondary("comic");
        p.setGeminiArtScale(150);p.setGeminiArtStroke(200);p.setGeminiArtGrain(40);p.setGeminiArtSeed(17);p.setGeminiArtBlend(60);
        var direction=GeminiArtLibrary.direction(p);
        assertThat(direction).contains("lyricaPencil(sceneColor,uv,depth, 1.500, 2.000, 0.400, 17.0, 0.0)","lyricaComic(sceneColor", "at weight 0.6", "not world-surface UV");
        p.setGeminiArtSeed(18);assertThat(GeminiArtLibrary.direction(p)).isNotEqualTo(direction);
        p.setGeminiArtAudio(false);assertThat(GeminiArtLibrary.direction(p)).contains("Do not use energy");
    }
    @Test void automaticStyleAlsoReceivesParametersAndChangesItsCacheIdentity() {
        var p=new ClientPreferences();String initial=GeminiArtLibrary.direction(p);
        p.setGeminiArtScale(175);p.setGeminiArtSeed(73);
        assertThat(GeminiArtLibrary.direction(p)).contains("1.750, 1.000, 0.300, 73.0, 0.0").isNotEqualTo(initial);
    }
    @Test void everySelectableStyleHasExecutableWorldCodeAndUniqueIdentity() {
        assertThat(GeminiArtLibrary.STYLES).extracting(GeminiArtLibrary.Style::id).doesNotHaveDuplicates().hasSize(18);
        for(var style:GeminiArtLibrary.STYLES) {
            var spec=new GeminiShaderSpec(style.id(),style.technique(),"vec3 musicWorld(vec3 sceneColor, vec2 uv, float depth) { return "+style.function()+"(sceneColor,uv,depth,1.,1.,.3,1.,0.); }");
            assertThat(GeminiShaderSpec.fragmentSource(spec.glsl(),true)).contains("vec3 "+style.function()+"(");
        }
    }
    @Test void artisticPreferencesSurviveExportAndOldConfigurationsHaveStableDefaults() throws Exception {
        var p=ClientPreferences.decode("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(p.getGeminiArtStyle()).isEqualTo("auto");assertThat(p.isGeminiArtAnimated()).isFalse();
        p.setGeminiArtStyle("handdrawn");p.setGeminiArtSecondary("ink");p.setGeminiArtSeed(52);p.setGeminiArtScale(999);
        p.setGeminiArtAudio(false);p.setGeminiArtAnimated(true);p.setGeminiCreativeFreedom(92);p.setGeminiSongContext(false);
        var q=ClientPreferences.decode(ProtocolCodec.mapper().writeValueAsBytes(p));
        assertThat(q.getGeminiArtStyle()).isEqualTo("handdrawn");assertThat(q.getGeminiArtSecondary()).isEqualTo("ink");
        assertThat(q.getGeminiArtSeed()).isEqualTo(52);assertThat(q.getGeminiArtScale()).isEqualTo(300);
        assertThat(q.getGeminiCreativeFreedom()).isEqualTo(92);assertThat(q.isGeminiSongContext()).isFalse();assertThat(q.isGeminiArtAudio()).isFalse();assertThat(q.isGeminiArtAnimated()).isTrue();
    }
}
