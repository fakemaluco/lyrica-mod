package dev.relaylyrics.client;

import dev.relaylyrics.protocol.ProtocolCodec;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PopupSizeProfilesTest {
    @Test void resizingEachMediaModePreservesTheOtherModesAcrossRestarts() throws Exception {
        var prefs = ClientPreferences.decode("{\"lyricsWidth\":380,\"lyricsHeight\":240}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(prefs.getPopupWidth(true, false)).isEqualTo(380);
        assertThat(prefs.getPopupHeight(true, true)).isEqualTo(240);
        prefs.setPopupWidth(true, false, 310); prefs.setPopupHeight(true, false, 290);
        prefs.setPopupHeight(true, true, 180);
        prefs.setPopupWidth(false, false, 450); prefs.setPopupHeight(false, false, 210);
        var restored = ClientPreferences.decode(ProtocolCodec.mapper().writeValueAsBytes(prefs));
        assertThat(restored.getPopupWidth(false, false)).isEqualTo(450);
        assertThat(restored.getPopupHeight(false, false)).isEqualTo(210);
        assertThat(restored.getPopupWidth(true, false)).isEqualTo(310);
        assertThat(restored.getPopupHeight(true, false)).isEqualTo(290);
        assertThat(restored.getPopupWidth(true, true)).isEqualTo(180);
        assertThat(restored.getPopupHeight(true, true)).isEqualTo(180);
    }
}
