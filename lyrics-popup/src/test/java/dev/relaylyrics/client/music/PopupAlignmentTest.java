package dev.relaylyrics.client.music;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class PopupAlignmentTest {
    @Test void aModdedInventoryCanBeCenteredIndependentlyOfTheScreen() {
        var target = new PopupAlignment.Target("inventory", "GUI", new PopupAlignment.Rect(300, 200, 180, 160));
        var result = new PopupAlignment.State().align(new PopupAlignment.Rect(342, 252, 100, 60), 1000, 600, List.of(target), 6);
        assertThat(result.bounds()).isEqualTo(new PopupAlignment.Rect(340, 250, 100, 60));
        assertThat(result.vertical().label()).isEqualTo("GUI");
        assertThat(result.horizontal().label()).isEqualTo("GUI");
    }

    @Test void centersAndCornersSnapInGuiPixels() {
        var state = new PopupAlignment.State();
        var center = state.align(new PopupAlignment.Rect(398, 248, 200, 100), 1000, 600, List.of(), 6);
        assertThat(center.bounds()).isEqualTo(new PopupAlignment.Rect(400, 250, 200, 100));
        assertThat(center.vertical()).isNotNull(); assertThat(center.horizontal()).isNotNull();
        state.clear();
        var corner = state.align(new PopupAlignment.Rect(797, 497, 200, 100), 1000, 600, List.of(), 6);
        assertThat(corner.bounds()).isEqualTo(new PopupAlignment.Rect(800, 500, 200, 100));
    }

    @Test void minimapEdgesFollowTheActualMovedAndResizedRectangle() {
        var state = new PopupAlignment.State();
        var map = new PopupAlignment.Target("xaero", "Xaero", new PopupAlignment.Rect(20, 20, 128, 128));
        var adjacent = state.align(new PopupAlignment.Rect(153, 22, 200, 100), 1000, 600, List.of(map), 6);
        assertThat(adjacent.bounds()).isEqualTo(new PopupAlignment.Rect(154, 20, 200, 100));
        assertThat(adjacent.vertical().label()).isEqualTo("Xaero");
        state.clear();
        var moved = new PopupAlignment.Target("xaero", "Xaero", new PopupAlignment.Rect(800, 40, 180, 180));
        var left = state.align(new PopupAlignment.Rect(596, 43, 200, 100), 1000, 600, List.of(moved), 6);
        assertThat(left.bounds()).isEqualTo(new PopupAlignment.Rect(594, 40, 200, 100));
    }

    @Test void snapHysteresisDoesNotAccumulatePointerErrorAndClearingReleasesIt() {
        var state = new PopupAlignment.State();
        state.align(new PopupAlignment.Rect(397, 250, 200, 100), 1000, 600, List.of(), 6);
        assertThat(state.align(new PopupAlignment.Rect(408, 250, 200, 100), 1000, 600, List.of(), 6).bounds().x()).isEqualTo(400);
        assertThat(state.align(new PopupAlignment.Rect(411, 250, 200, 100), 1000, 600, List.of(), 6).vertical()).isNull();
        state.clear();
        assertThat(state.align(new PopupAlignment.Rect(408, 250, 200, 100), 1000, 600, List.of(), 6).bounds().x()).isEqualTo(408);
    }

    @Test void smallViewportsNeverChooseAnOffscreenSnap() {
        var result = new PopupAlignment.State().align(new PopupAlignment.Rect(4, 4, 196, 116), 200, 120,
                List.of(new PopupAlignment.Target("outside", "", new PopupAlignment.Rect(-20, -20, 4, 4))), 8);
        assertThat(result.bounds().x()).isBetween(0, 4);
        assertThat(result.bounds().y()).isBetween(0, 4);
    }
}
