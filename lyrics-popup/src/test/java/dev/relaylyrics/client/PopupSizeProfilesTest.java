package dev.relaylyrics.client;

import dev.relaylyrics.protocol.ProtocolCodec;
import dev.relaylyrics.client.music.PopupSizeProfile;
import dev.relaylyrics.client.music.ResponsiveLyricsLayout;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PopupSizeProfilesTest {
    @Test void everyContentCombinationKeepsItsOwnDimensionsAcrossSavingAndReset() throws Exception {
        var p = ClientPreferences.decode("{\"lyricsWidth\":380,\"lyricsHeight\":240,\"lyricsVideoWidth\":310,\"lyricsVideoHeight\":290}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String before = ProtocolCodec.mapper().writeValueAsString(p);
        for (var mode : PopupSizeProfile.values()) {
            assertThat(p.getPopupWidth(mode)).isEqualTo(mode.video() ? 310 : 380);
            assertThat(p.getPopupHeight(mode)).isEqualTo(mode.video() ? 290 : 240);
        }
        assertThat(ProtocolCodec.mapper().writeValueAsString(p)).isEqualTo(before);
        for (var mode : PopupSizeProfile.values()) {
            p.setPopupWidth(mode, 200 + mode.ordinal() * 30);
            p.setPopupHeight(mode, 100 + mode.ordinal() * 20);
        }
        var restored = ClientPreferences.decode(ProtocolCodec.mapper().writeValueAsBytes(p));
        for (var mode : PopupSizeProfile.values()) {
            assertThat(restored.getPopupWidth(mode)).isEqualTo(200 + mode.ordinal() * 30);
            assertThat(restored.getPopupHeight(mode)).isEqualTo(100 + mode.ordinal() * 20);
        }
        restored.resetLayout();
        for (var mode : PopupSizeProfile.values()) {
            assertThat(restored.getPopupWidth(mode)).isEqualTo(210);
            assertThat(restored.getPopupHeight(mode)).isEqualTo(130);
        }
    }

    @Test void contentChoosesTheProfileWithoutDependingOnPopupDimensions() {
        assertThat(PopupSizeProfile.choose(true, true, true)).isEqualTo(PopupSizeProfile.CANVAS_WITH_LYRICS);
        assertThat(PopupSizeProfile.choose(false, true, true)).isEqualTo(PopupSizeProfile.CANVAS_ONLY);
        assertThat(PopupSizeProfile.choose(true, false, true)).isEqualTo(PopupSizeProfile.COVER_WITH_LYRICS);
        assertThat(PopupSizeProfile.choose(false, false, true)).isEqualTo(PopupSizeProfile.COVER_ONLY);
        assertThat(PopupSizeProfile.choose(true, false, false)).isEqualTo(PopupSizeProfile.LYRICS_ONLY);
        assertThat(PopupSizeProfile.choose(false, false, false)).isEqualTo(PopupSizeProfile.EMPTY);
    }

    @Test void fractionalDesignCoordinatesPreserveTheExactGuiPixelAfterSaving() throws Exception {
        var p = new ClientPreferences();
        double scale = ResponsiveLyricsLayout.scale(780, 520);
        p.setPopupPosition(315 / scale, 117 / scale);
        var restored = ClientPreferences.decode(ProtocolCodec.mapper().writeValueAsBytes(p));
        var box = ResponsiveLyricsLayout.popup(780, 520, restored.getPopupPreciseX(), restored.getPopupPreciseY(), 210, 130);
        assertThat(box.x()).isEqualTo(315); assertThat(box.y()).isEqualTo(117);
        restored.setLyricsX(42); restored.setLyricsY(57);
        assertThat(restored.getPopupPreciseX()).isEqualTo(42); assertThat(restored.getPopupPreciseY()).isEqualTo(57);
        var reordered = ClientPreferences.decode("{\"popupPreciseX\":387.6923076923,\"popupPreciseY\":144,\"lyricsX\":388,\"lyricsY\":144}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(reordered.getPopupPreciseX()).isEqualTo(387.6923076923);
    }

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
