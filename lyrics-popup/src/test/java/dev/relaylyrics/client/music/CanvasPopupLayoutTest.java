package dev.relaylyrics.client.music;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CanvasPopupLayoutTest {
    @Test void slidersKeepTransportAboveTheTimelineAndTimesBelowTheTrack() {
        for (int width : new int[]{84, 96, 128, 225, 400, 600}) for (int height : new int[]{84, 128, 160, 400, 600}) {
            for (boolean video : new boolean[]{false, true}) {
                var layout = CanvasPopupLayout.playerSliders(width, height, 3, video, 7);
                var progress = layout.progress(); var volume = layout.volume();
                assertThat(progress.x()).isGreaterThanOrEqualTo(5);
                assertThat(progress.x() + progress.width()).isLessThanOrEqualTo(width - 5);
                assertThat(layout.timeY()).isGreaterThan(progress.y() + progress.height());
                assertThat(layout.controlsY() + Math.clamp(Math.min(width, height) / 11, 13, 24)).isLessThan(progress.y());
                assertThat(layout.timeY() + 7).isLessThanOrEqualTo(height - 5);
                assertThat(layout.controlsY() + Math.clamp(Math.min(width, height) / 11, 13, 24)).isLessThanOrEqualTo(height - 5);
                if (layout.volumeVisible()) {
                    var row = CanvasPopupLayout.utilityRow(width, height, 3, video);
                    assertThat(volume.y()).isGreaterThan(row.y() + row.size());
                    assertThat(volume.y() + volume.height()).isLessThan(layout.controlsY());
                    assertThat(video ? volume.x() : width - volume.x() - volume.width()).isBetween(5, 10);
                    assertThat(layout.volumeLeft()).isEqualTo(video);
                }
            }
        }
    }

    @Test void shortCoversHideVolumeRatherThanOverlappingTheTimelineOrToolbar() {
        assertThat(CanvasPopupLayout.playerSliders(84, 84, 3, true, 7).volumeVisible()).isFalse();
        assertThat(CanvasPopupLayout.playerSliders(225, 400, 3, true, 7).volumeVisible()).isTrue();
    }
    @Test void videoUtilityRowsStayAtTheLeftAndFitNarrowPortraitColumns() {
        for (int width : new int[]{84, 96, 120, 225, 400}) for (int count = 1; count <= 3; count++) {
            var row = CanvasPopupLayout.utilityRow(width, 400, count, true);
            assertThat(row.x()).isBetween(5, 10);
            assertThat(row.y()).isBetween(5, 10);
            assertThat(row.size()).isPositive();
            assertThat(row.left(count - 1) + row.size()).isLessThanOrEqualTo(width - row.x());
            if (count > 1) assertThat(row.left(1)).isGreaterThan(row.left(0) + row.size());
        }
    }

    @Test void nonVideoUtilityRowsAreCenteredWithoutChangingTheOriginalSingleSettingsPosition() {
        var one = CanvasPopupLayout.utilityRow(240, 240, 1, false);
        assertThat(one.x()).isEqualTo((240 - one.size()) / 2);
        var three = CanvasPopupLayout.utilityRow(240, 240, 3, false);
        assertThat(three.x()).isEqualTo(240 - three.left(2) - three.size());
    }
    @Test void resizingUsesTheNewHeightAndKeepsTheVisibleCornerUnderThePointer() {
        int before = CanvasPopupLayout.expandedWidth(420, 260, .6);
        var resized = CanvasPopupLayout.resize(before + 60, 300, .6, false, 420);
        assertThat(CanvasPopupLayout.expandedWidth(resized.width(), resized.height(), .6)).isEqualTo(before + 60);
        assertThat(resized.height()).isEqualTo(300);
        var heightOnly = CanvasPopupLayout.resize(before, 300, .6, false, 420);
        assertThat(CanvasPopupLayout.expandedWidth(heightOnly.width(), heightOnly.height(), .6)).isEqualTo(before);
    }
    @Test void squareResizingKeepsTheSavedLyricsWidthAndConstrainedFramesNeverOverflow() {
        var square = CanvasPopupLayout.resize(310, 310, 1, true, 420);
        assertThat(square.width()).isEqualTo(420); assertThat(square.height()).isEqualTo(310);
        assertThat(CanvasPopupLayout.resize(64, 64, 1, true, 96).height()).isEqualTo(64);
        var narrow = CanvasPopupLayout.resize(300, 500, 1, false, 420);
        assertThat(CanvasPopupLayout.expandedWidth(narrow.width(), narrow.height(), 1)).isLessThanOrEqualTo(300);
    }
    @Test void videoAddsHorizontalRoomWithoutChangingHeightOrTheSavedLyricsWidth() {
        int width = CanvasPopupLayout.expandedWidth(420, 260, 9.0 / 16);
        var layout = CanvasPopupLayout.columns(width, 260, 9.0 / 16);
        assertThat(width).isGreaterThan(420);
        assertThat(layout.lyrics().width()).isBetween(395, 397);
        assertThat(layout.artwork().height()).isEqualTo(260);
        assertThat(layout.artwork().x()).isZero(); assertThat(layout.artwork().y()).isZero();
        assertThat(layout.artwork().width() / (double) layout.artwork().height()).isCloseTo(9.0 / 16, org.assertj.core.data.Offset.offset(.005));
        assertThat(CanvasPopupLayout.expandedWidth(420, 260, 0)).isEqualTo(420);
    }
    @Test void allVideoShapesKeepIndependentNonoverlappingColumnsInsideSmallViewports() {
        for (double aspect : new double[]{9.0 / 16, 1, 16.0 / 9, 4}) for (int width : new int[]{96, 240, 420, 900}) for (int height : new int[]{64, 160, 600}) {
            var layout = CanvasPopupLayout.columns(width, height, aspect);
            var art = layout.artwork(); var lyrics = layout.lyrics();
            assertThat(art.x() + art.width()).isLessThan(lyrics.x());
            assertThat(lyrics.x() + lyrics.width()).isLessThanOrEqualTo(width);
            assertThat(art.y()).isZero(); assertThat(art.height()).isEqualTo(height);
            assertThat(layout.videoSurface().height()).isEqualTo(height);
            assertThat(layout.videoSurface().width()).isGreaterThan(art.width()).isLessThan(width);
            assertThat(lyrics.width()).isPositive();
        }
    }
}
