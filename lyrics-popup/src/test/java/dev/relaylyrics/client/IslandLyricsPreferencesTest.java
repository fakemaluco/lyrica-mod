package dev.relaylyrics.client;

import dev.relaylyrics.protocol.ProtocolCodec;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class IslandLyricsPreferencesTest {
    @Test void optInSurvivesSavingAndOldConfigsStayOff() throws Exception {
        assertThat(new ClientPreferences().isIslandLyrics()).isFalse();
        assertThat(ClientPreferences.decode("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)).isIslandLyrics()).isFalse();
        var p=new ClientPreferences();p.setIslandLyrics(true);
        var restored=ClientPreferences.decode(ProtocolCodec.mapper().writeValueAsBytes(p));
        assertThat(restored.isIslandLyrics()).isTrue();
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
        entry.activate.run();assertThat(p.isIslandLyrics()).isTrue();
        c.restore("appearance");assertThat(p.isIslandLyrics()).isFalse();
    }
}
