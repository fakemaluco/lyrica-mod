package dev.relaylyrics.client;

import dev.relaylyrics.protocol.ProtocolCodec;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PopupSizeProfilesTest {
    @Test void dockStatePersistsAndResetReturnsToTheDefaultIsland() throws Exception {
        var prefs=new ClientPreferences(); prefs.setPopupDocked(true);
        prefs.setPopupDockEdge(dev.relaylyrics.client.music.PopupDockLayout.Edge.RIGHT); prefs.setPopupDockAnchor(.75);
        var restored=ClientPreferences.decode(ProtocolCodec.mapper().writeValueAsBytes(prefs));
        assertThat(restored.isPopupDocked()).isTrue(); assertThat(restored.getPopupDockAnchor()).isEqualTo(.5);
        assertThat(restored.getPopupDockEdge()).isEqualTo(dev.relaylyrics.client.music.PopupDockLayout.Edge.TOP);
        restored.setPopupEdgeDocking(false); assertThat(restored.isPopupDocked()).isFalse();
        restored.setPopupEdgeDocking(true); assertThat(restored.isPopupDocked()).isFalse();
        restored.setPopupDocked(false); restored.resetLayout(); assertThat(restored.isPopupDocked()).isTrue();
    }
    @Test void compactDefaultsDoNotOverwriteExistingDimensionsOrExplicitMotionChoices() throws Exception {
        var fresh = new ClientPreferences();
        assertThat(fresh.getLyricsWidth()).isEqualTo(210); assertThat(fresh.getLyricsHeight()).isEqualTo(130);
        assertThat(fresh.isSpotifyCanvas()).isTrue(); assertThat(fresh.isAnimatedCovers()).isTrue(); assertThat(fresh.isCanvasBackground()).isTrue();
        var saved = ClientPreferences.decode("{\"lyricsWidth\":420,\"lyricsHeight\":260,\"canvasBackground\":false}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(saved.getLyricsWidth()).isEqualTo(420); assertThat(saved.getLyricsHeight()).isEqualTo(260);
        assertThat(saved.isCanvasBackground()).isFalse();
        fresh.resetLayout(); assertThat(fresh.getLyricsWidth()).isEqualTo(210);
    }
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
