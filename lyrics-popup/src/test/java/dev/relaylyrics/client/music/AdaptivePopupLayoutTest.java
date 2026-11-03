package dev.relaylyrics.client.music;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AdaptivePopupLayoutTest {
    @Test void videoTakesPriorityAndLyricsReturnAsSoonAsBothHaveReadableSpace() {
        assertThat(AdaptivePopupLayout.choose(480,280,.5625,true,1)).isEqualTo(AdaptivePopupLayout.Mode.VIDEO_LYRICS);
        assertThat(AdaptivePopupLayout.choose(320,200,.5625,true,1)).isEqualTo(AdaptivePopupLayout.Mode.VIDEO_LYRICS);
        assertThat(AdaptivePopupLayout.choose(210,130,.5625,true,1)).isEqualTo(AdaptivePopupLayout.Mode.VIDEO_ONLY);
        assertThat(AdaptivePopupLayout.choose(140,86,.5625,true,1)).isEqualTo(AdaptivePopupLayout.Mode.VIDEO_ONLY);
        assertThat(AdaptivePopupLayout.choose(96,64,.5625,true,1)).isEqualTo(AdaptivePopupLayout.Mode.VIDEO_ONLY);
    }
    @Test void largeTextUsesMoreRoomWithoutReplacingVideoWithAStaticCover() {
        assertThat(AdaptivePopupLayout.choose(400,260,1.8,true,1)).isEqualTo(AdaptivePopupLayout.Mode.VIDEO_LYRICS);
        assertThat(AdaptivePopupLayout.choose(560,260,1.8,true,1)).isEqualTo(AdaptivePopupLayout.Mode.VIDEO_LYRICS);
        assertThat(AdaptivePopupLayout.choose(420,240,.5625,true,1.5)).isEqualTo(AdaptivePopupLayout.Mode.VIDEO_LYRICS);
        assertThat(AdaptivePopupLayout.choose(420,280,.5625,true,1.8)).isEqualTo(AdaptivePopupLayout.Mode.VIDEO_LYRICS);
        assertThat(AdaptivePopupLayout.choose(480,280,0,true,1)).isEqualTo(AdaptivePopupLayout.Mode.PHOTO_LYRICS);
        assertThat(AdaptivePopupLayout.choose(400,260,.56,false,1)).isEqualTo(AdaptivePopupLayout.Mode.VIDEO_ONLY);
    }
    @Test void smallTextCanShareACompactVideoFrameWithoutReplacingTheVideo() {
        assertThat(AdaptivePopupLayout.choose(210,130,.6,true,.6)).isEqualTo(AdaptivePopupLayout.Mode.VIDEO_LYRICS);
        assertThat(AdaptivePopupLayout.choose(140,86,.6,true,.6)).isEqualTo(AdaptivePopupLayout.Mode.VIDEO_ONLY);
    }
    @Test void minimumPlayerKeepsEveryButtonAndTimelineInsideWithoutOverlappingMetadata() {
        for (int width : new int[]{96,140,159,160,210,400}) for (int height : new int[]{64,86,99,100,130,169}) {
            var layout = AdaptivePopupLayout.player(width,height,width>=160 && height>=100);
            var boxes = java.util.List.of(layout.previous(),layout.play(),layout.next(),layout.settings(),layout.progress());
            for (var box : boxes) {
                assertThat(box.x()).isGreaterThanOrEqualTo(0); assertThat(box.y()).isGreaterThanOrEqualTo(0);
                assertThat(box.x()+box.width()).isLessThanOrEqualTo(width);
                assertThat(box.y()+box.height()).isLessThanOrEqualTo(height);
            }
            assertThat(layout.metadata().y()+23).isLessThanOrEqualTo(layout.play().y());
            assertThat(layout.play().y()+layout.play().height()).isLessThan(layout.progress().y());
            assertThat(layout.previous().x()+layout.previous().width()).isLessThan(layout.play().x());
            assertThat(layout.play().x()+layout.play().width()).isLessThan(layout.next().x());
            assertThat(layout.metadata().x()+layout.metadata().width()).isLessThan(layout.settings().x());
        }
    }
    @Test void resizeNeverAddsOrSubtractsVideoWidthAndRepeatedDecisionsCannotOscillate() {
        for (int width=96;width<=600;width+=7) {
            var frame=AdaptivePopupLayout.resize(width,180);
            assertThat(frame.width()).isEqualTo(width); assertThat(frame.height()).isEqualTo(180);
            var mode=AdaptivePopupLayout.choose(width,180,.5625,true,1);
            assertThat(AdaptivePopupLayout.choose(frame.width(),frame.height(),.5625,true,1)).isEqualTo(mode);
        }
        assertThat(AdaptivePopupLayout.choose(Double.NaN,64,Double.NaN,true,Double.NaN)).isEqualTo(AdaptivePopupLayout.Mode.TEXT_PLAYER);
    }
}
