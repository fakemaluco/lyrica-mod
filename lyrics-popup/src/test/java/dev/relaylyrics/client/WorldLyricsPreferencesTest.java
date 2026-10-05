package dev.relaylyrics.client;

import dev.relaylyrics.protocol.ProtocolCodec;
import dev.relaylyrics.client.music.WorldLyricsDirector;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class WorldLyricsPreferencesTest {
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
    @Test void oldPreferencesReceiveNormalIntensityAndPlayerReactions() throws Exception {
        var preferences = ProtocolCodec.mapper().readValue("{\"worldLyrics\":true}", ClientPreferences.class);
        assertThat(preferences.isWorldLyrics()).isTrue();
        assertThat(preferences.getWorldLyricsIntensity()).isEqualTo(ClientPreferences.WorldIntensity.NORMAL);
        assertThat(preferences.isWorldLyricsPlayerReactions()).isTrue();
        assertThat(preferences.getWorldLyricsSize()).isEqualTo(1.2);
        assertThat(preferences.isWorldLyricsAutoWalls()).isTrue();
        assertThat(preferences.isWorldLyricsCinematic()).isTrue();
        assertThat(preferences.getWorldLyricsPreset()).isEqualTo(WorldLyricsDirector.Preset.SIGN);
        assertThat(preferences.getWorldLyricsMinimumDistance()).isEqualTo(6);
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
