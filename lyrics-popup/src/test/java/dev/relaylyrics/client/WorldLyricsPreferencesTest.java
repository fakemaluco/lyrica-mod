package dev.relaylyrics.client;

import dev.relaylyrics.protocol.ProtocolCodec;
import dev.relaylyrics.client.music.WorldLyricsDirector;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class WorldLyricsPreferencesTest {
    @Test void phraseGradientDoesNotRestartOnTheNextWord() throws Exception {
        var preferences = ClientPreferences.decode("""
                {"sungTextColor":{"mode":"GRADIENT","solid":16711680,"stops":[{"position":0,"rgb":16711680},{"position":1000,"rgb":255}]}}
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(preferences.getSungTextColor().scope()).isEqualTo(SungTextColor.Scope.PHRASE);
        int firstWord = preferences.sungGlyphColor(0xFFFFFFFF, 1, 0, 5, 20, false, 0, 44);
        int secondWord = preferences.sungGlyphColor(0xFFFFFFFF, 1, 0, 5, 20, false, 24, 44);
        assertThat(secondWord & 255).isGreaterThan(firstWord & 255);
        assertThat(preferences.sungGlyphColor(0xFFFFFFFF, .25, 0, 5, 20, false, 24, 44)).isEqualTo(secondWord);
        var color = preferences.getSungTextColor();
        preferences.setSungTextColor(new SungTextColor(color.mode(), color.solid(), color.stops(), SungTextColor.Scope.WORD));
        var restored = ClientPreferences.decode(ProtocolCodec.mapper().writeValueAsBytes(preferences));
        assertThat(restored.getSungTextColor().scope()).isEqualTo(SungTextColor.Scope.WORD);
    }

    @Test void sungGlyphKeepsItsTintAfterTheSweepMovesOn() {
        var preferences = new ClientPreferences();
        preferences.setSungTextColor(testGradient());
        int early = preferences.sungGlyphColor(0xB0FFFFFF, .25, 0, 5, 20, false);
        assertThat(preferences.sungGlyphColor(0xB0FFFFFF, .15, 0, 5, 20, false)).isNotEqualTo(early);
        assertThat(preferences.sungGlyphColor(0xB0FFFFFF, 1, 0, 5, 20, false)).isEqualTo(early);
        assertThat(preferences.sungGlyphColor(0xB0FFFFFF, 1, 15, 5, 20, true)).isEqualTo(early);
        assertThat(preferences.sungGlyphColor(0xB0FFFFFF, 1, 15, 5, 20, false)).isNotEqualTo(early);
        assertThat(early >>> 24).isEqualTo(0xB0);
    }

    @Test void sungGradientFollowsTimeAndPositionAndRoundTripsWithDistance() throws Exception {
        var preferences = new ClientPreferences();
        assertThat(preferences.sungLyricsColor(0x80FFFFFF, .5, .5)).isEqualTo(0x80FFFFFF);
        preferences.setSungTextColor(testGradient());
        preferences.setWorldLyricsDistanceRange(24, 48);
        var restored = ClientPreferences.decode(ProtocolCodec.mapper().writeValueAsBytes(preferences));
        assertThat(restored.getWorldLyricsMinimumDistance()).isEqualTo(24);
        assertThat(restored.getWorldLyricsMaximumDistance()).isEqualTo(48);
        assertThat(restored.sungLyricsColor(0x80FFFFFF, 0, 0)).isEqualTo(0x80FF0000);
        assertThat(restored.sungLyricsColor(0x80FFFFFF, 1, 1)).isEqualTo(0x800000FF);
        assertThat(restored.sungLyricsColor(0x80FFFFFF, .5, 0))
                .isNotEqualTo(restored.sungLyricsColor(0x80FFFFFF, .5, 1));
        assertThat(dev.relaylyrics.client.music.WorldLyricsSpace.readingDistance(0, 2, 12, false, 64, 24)).isEqualTo(24);
    }
    @Test void legacyColorsAndExtraStopsSurviveSaving() throws Exception {
        var preferences = ClientPreferences.decode("""
                {"lyricsCustomSungColor":true,"lyricsSungGradient":true,"lyricsSungStartColor":16711680,"lyricsSungEndColor":255}
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(preferences.getSungTextColor()).isEqualTo(testGradient());
        var custom = new SungTextColor(SungTextColor.Mode.GRADIENT, 0xFF0000, java.util.List.of(
                new SungTextColor.Stop(1000, 0x0000FF), new SungTextColor.Stop(500, 0x00FF00),
                new SungTextColor.Stop(0, 0xFF0000)));
        preferences.setSungTextColor(custom);
        var restored = ClientPreferences.decode(ProtocolCodec.mapper().writeValueAsBytes(preferences));
        assertThat(restored.getSungTextColor()).isEqualTo(custom);
        assertThat(restored.sungLyricsColor(0x80FFFFFF, .5, .5)).isEqualTo(0x8000FF00);
    }
    private static SungTextColor testGradient() {
        return new SungTextColor(SungTextColor.Mode.GRADIENT, 0xFF0000, java.util.List.of(
                new SungTextColor.Stop(0, 0xFF0000), new SungTextColor.Stop(1000, 0x0000FF)));
    }
    @Test void rebuiltWorldStyleHasSafeDefaultsAndPreservesCustomFontAndColors() throws Exception {
        var old = ClientPreferences.decode("{\"worldLyricsPositionVersion\":5,\"worldLyrics\":true,\"googleFontFamily\":\"Inter\",\"worldLyricsFaceColor\":1193046}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(old.isWorldLyrics()).isTrue();
        assertThat(old.getWorldLyricsStyle()).isEqualTo(dev.relaylyrics.client.music.WorldLyricsExperience.Style.SPICY);
        assertThat(old.getWorldLyricsRenderMode()).isEqualTo(dev.relaylyrics.client.music.WorldLyricsExperience.RenderMode.AUTO);
        assertThat(old.isWorldLyricsGlow()).isFalse(); assertThat(old.isWorldLyricsSmoothFont()).isTrue();
        assertThat(old.getGoogleFontFamily()).isEqualTo("Inter"); assertThat(old.getWorldLyricsFaceColor()).isEqualTo(0x123456);
        old.setWorldLyricsStyle(dev.relaylyrics.client.music.WorldLyricsExperience.Style.SCULPTED);
        old.setWorldLyricsRenderMode(dev.relaylyrics.client.music.WorldLyricsExperience.RenderMode.SAFE);
        old.setWorldLyricsGlow(false); old.setWorldLyricsSmoothFont(false); old.setWorldLyricsAccent(0x98E7FF);
        var restored = ClientPreferences.decode(ProtocolCodec.mapper().writeValueAsBytes(old));
        assertThat(restored.getWorldLyricsStyle()).isEqualTo(old.getWorldLyricsStyle());
        assertThat(restored.getWorldLyricsRenderMode()).isEqualTo(old.getWorldLyricsRenderMode());
        assertThat(restored.isWorldLyricsGlow()).isFalse(); assertThat(restored.isWorldLyricsSmoothFont()).isFalse();
        assertThat(restored.getWorldLyricsAccent()).isEqualTo(0x98E7FF);
    }
    @Test void oldPresetSelectionIsReplacedWithoutDisablingWorldLyrics() throws Exception {
        var restored = ClientPreferences.decode("{\"worldLyrics\":true,\"lyricsVisible\":false,\"lyricsFont\":\"MINECRAFT\",\"worldLyricsPositionVersion\":4,\"worldLyricsPreset\":\"SCULPTURE\",\"worldLyricsPresetMask\":127,\"enabledWorldLyricsPresets\":[\"CLASSIC\",\"CINEMA\",\"SCULPTURE\"]}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(restored.isWorldLyrics()).isTrue();
        assertThat(restored.isLyricsVisible()).isFalse();
        assertThat(restored.getWorldLyricsPreset()).isEqualTo(WorldLyricsDirector.Preset.SIGN);
        assertThat(restored.isWorldLyricsAutomatic()).isTrue();
        assertThat(restored.getEnabledWorldLyricsPresets()).containsExactlyInAnyOrder(WorldLyricsDirector.Preset.values());
        assertThat(restored.getWorldLyricsPositionVersion()).isEqualTo(5);
    }
    @Test void previousShortDistanceLimitMigratesWithoutChangingTheSelectedStyle() throws Exception {
        var restored = ClientPreferences.decode("{\"worldLyrics\":true,\"worldLyricsMaximumDistance\":10,\"worldLyricsPreset\":\"SCULPTURE\"}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(restored.getWorldLyricsMaximumDistance()).isEqualTo(192);
        assertThat(restored.getWorldLyricsPreset()).isEqualTo(WorldLyricsDirector.Preset.SIGN);
    }
    @Test void baseDistanceMigratesToMinimumAndHandlesCannotCross() throws Exception {
        var restored = ClientPreferences.decode("""
                {"worldLyricsPositionVersion":5,"worldLyricsBaseDistance":24,"worldLyricsMinimumDistance":6,"worldLyricsMaximumDistance":48}
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(restored.getWorldLyricsMinimumDistance()).isEqualTo(24);
        assertThat(restored.getWorldLyricsMaximumDistance()).isEqualTo(48);
        restored.setWorldLyricsMinimumDistance(60);
        assertThat(restored.getWorldLyricsMinimumDistance()).isEqualTo(48);
        restored.setWorldLyricsMaximumDistance(20);
        assertThat(restored.getWorldLyricsMaximumDistance()).isEqualTo(48);
        restored.setWorldLyricsDistanceRange(210, 230);
        var saved = ClientPreferences.decode(ProtocolCodec.mapper().writeValueAsBytes(restored));
        assertThat(saved.getWorldLyricsMinimumDistance()).isEqualTo(210);
        assertThat(saved.getWorldLyricsMaximumDistance()).isEqualTo(230);
    }
    @Test void oldPreferencesReceiveNormalIntensityAndPlayerReactions() throws Exception {
        var preferences = ProtocolCodec.mapper().readValue("{\"worldLyrics\":true}", ClientPreferences.class);
        assertThat(preferences.isWorldLyrics()).isTrue();
        assertThat(preferences.getWorldLyricsIntensity()).isEqualTo(ClientPreferences.WorldIntensity.NORMAL);
        assertThat(preferences.isWorldLyricsPlayerReactions()).isTrue();
        assertThat(preferences.getWorldLyricsSize()).isEqualTo(1.2);
        assertThat(preferences.isWorldLyricsAutoWalls()).isTrue();
        assertThat(preferences.isWorldLyricsCinematic()).isFalse();
        assertThat(preferences.getWorldLyricsPreset()).isEqualTo(WorldLyricsDirector.Preset.SIGN);
        assertThat(preferences.getWorldLyricsMinimumDistance()).isEqualTo(8);
        assertThat(preferences.getWorldLyricsMaximumDistance()).isEqualTo(192);
    }
    @Test void firstActivationUsesTheContextualSignpostPreset() {
        var preferences = new ClientPreferences();
        assertThat(preferences.getWorldLyricsPreset()).isEqualTo(WorldLyricsDirector.Preset.SIGN);
        preferences.enableWorldLyricsWithModernDefaults();
        assertThat(preferences.getWorldLyricsPreset()).isEqualTo(WorldLyricsDirector.Preset.SIGN);
        assertThat(preferences.isWorldLyricsAutomatic()).isTrue();
    }
    @Test void savingBeforeTheFirstActivationKeepsContextualDefaults() throws Exception {
        var restored = ProtocolCodec.mapper().readValue(
                ProtocolCodec.mapper().writeValueAsBytes(new ClientPreferences()), ClientPreferences.class);
        restored.enableWorldLyricsWithModernDefaults();
        assertThat(restored.getWorldLyricsPreset()).isEqualTo(WorldLyricsDirector.Preset.SIGN);
        assertThat(restored.isWorldLyricsAutomatic()).isTrue();
    }
    @Test void newControlsRoundTripWithoutTouchingTheUserConfig() throws Exception {
        var preferences = new ClientPreferences();
        preferences.setWorldLyricsIntensity(ClientPreferences.WorldIntensity.INTENSE);
        preferences.setWorldLyricsPlayerReactions(false);
        preferences.setWorldLyricsSize(1.7);
        preferences.setWorldLyricsAutoWalls(false);
        preferences.setWorldLyricsCinematic(false);
        preferences.setWorldLyricsPreset(WorldLyricsDirector.Preset.ENGRAVED);
        preferences.setWorldLyricsDepth(.2);
        preferences.setWorldLyricsTrails(true);
        preferences.setWorldLyricsSurfacePreference(WorldLyricsDirector.SurfacePreference.SURFACES);
        preferences.setWorldLyricsMaterial(WorldLyricsDirector.Material.METAL);
        preferences.setWorldLyricsEntranceOverride(WorldLyricsDirector.Entrance.WALL_SLIDE);
        preferences.setWorldLyricsBevel(false);
        preferences.setWorldLyricsBevelWidth(.7);
        var restored = ProtocolCodec.mapper().readValue(ProtocolCodec.mapper().writeValueAsBytes(preferences), ClientPreferences.class);
        assertThat(restored.getWorldLyricsIntensity()).isEqualTo(ClientPreferences.WorldIntensity.INTENSE);
        assertThat(restored.isWorldLyricsPlayerReactions()).isFalse();
        assertThat(restored.getWorldLyricsSize()).isEqualTo(1.7);
        assertThat(restored.isWorldLyricsAutoWalls()).isFalse();
        assertThat(restored.isWorldLyricsCinematic()).isFalse();
        assertThat(restored.getWorldLyricsPreset()).isEqualTo(WorldLyricsDirector.Preset.ENGRAVED);
        assertThat(restored.getWorldLyricsDepth()).isEqualTo(.2);
        assertThat(restored.isWorldLyricsTrails()).isTrue();
        assertThat(restored.getWorldLyricsSurfacePreference()).isEqualTo(WorldLyricsDirector.SurfacePreference.SURFACES);
        assertThat(restored.getWorldLyricsMaterial()).isEqualTo(WorldLyricsDirector.Material.METAL);
        assertThat(restored.getWorldLyricsEntranceOverride()).isEqualTo(WorldLyricsDirector.Entrance.WALL_SLIDE);
        assertThat(restored.isWorldLyricsBevel()).isFalse();
        assertThat(restored.getWorldLyricsBevelWidth()).isEqualTo(.7);
    }
}
