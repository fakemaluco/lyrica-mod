package dev.relaylyrics.client;

import dev.relaylyrics.protocol.ProtocolCodec;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class IslandLyricsPreferencesTest {
    @Test void islandIsOnByDefaultButExplicitOptOutSurvivesSaving() throws Exception {
        assertThat(new ClientPreferences().isIslandLyrics()).isTrue();
        assertThat(ClientPreferences.decode("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)).isIslandLyrics()).isTrue();
        var p=new ClientPreferences();p.setIslandLyrics(false);
        var restored=ClientPreferences.decode(ProtocolCodec.mapper().writeValueAsBytes(p));
        assertThat(restored.isIslandLyrics()).isFalse();
    }
    @Test void islandSettingAndAppearanceResetUseTheSamePersistentPreference() {
        var p=new ClientPreferences();
        var c=new LyricsSettingsCatalog(p,new LyricsSettingsCatalog.Actions() {
            public void run(String action) { }
            public String status(String name) { return name; }
        });
        c.expand("motion_artwork");c.expand("island");
        var entry=c.entries(LyricsSettingsCatalog.Category.APPEARANCE).stream()
                .filter(e->e.key.equals(LyricsSettingsCatalog.PREFIX+"island.lyrics")).findFirst().orElseThrow();
        entry.activate.run();assertThat(p.isIslandLyrics()).isFalse();
        c.restore("appearance");assertThat(p.isIslandLyrics()).isTrue();
    }

    @Test void worldDefaultsAndOldHueKeysAreHandledOnImport() throws Exception {
        var prefs = ClientPreferences.decode("""
                {"worldLyrics":false,"lyricsReactiveHue":true,"lyricsReactiveHueAuto":true,"lyricsReactiveHueAmount":180,
                 "worldLyricsReveal":"WORD_BY_WORD"}
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(new ClientPreferences().isWorldLyrics()).isTrue();
        assertThat(prefs.isWorldLyrics()).isFalse();
        var encoded = ProtocolCodec.mapper().writeValueAsBytes(prefs);
        assertThat(ProtocolCodec.mapper().readTree(encoded).has("lyricsReactiveHue")).isFalse();
        assertThat(ClientPreferences.decode(encoded).getWorldLyricsReveal())
                .isEqualTo(dev.relaylyrics.client.music.WorldLyricsPresentation.Reveal.WORD_BY_WORD);
    }
}
