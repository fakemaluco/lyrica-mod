package dev.relaylyrics.client.music;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class IslandWaitingMotionTest {
    private static final IslandLyrics.Frame WAIT=new IslandLyrics.Frame(IslandLyrics.Kind.WAITING,null,0,10000);
    private static IslandWaitingMotion.Frame at(IslandWaitingMotion motion,long millis,IslandLyrics.Frame content) {
        return motion.update(content,"track",millis*1_000_000,true,false);
    }
    @Test void dotsAppearInAStaggerThenWaitAtTheCenterBeforeTheTitleOpens() {
        var motion=new IslandWaitingMotion();
        var first=at(motion,0,WAIT);assertThat(first.visibility()).isZero();assertThat(first.title()).isZero();
        var stagger=at(motion,40,WAIT);
        assertThat(stagger.firstDot()).isGreaterThan(stagger.secondDot());
        assertThat(stagger.secondDot()).isGreaterThan(stagger.thirdDot());assertThat(stagger.thirdDot()).isZero();
        var center=at(motion,300,WAIT);
        assertThat(center.visibility()).isGreaterThan(.99);assertThat(center.thirdDot()).isGreaterThan(.98);
        assertThat(center.title()).isZero();assertThat(center.placement()).isZero();
        assertThat(at(motion,500,WAIT).title()).isZero();
        assertThat(at(motion,580,WAIT).title()).isBetween(.2,.9);
        assertThat(at(motion,800,WAIT).title()).isGreaterThan(.99);
    }
    @Test void longAndShortFramesCrossTheSameStaggerAndTitleBoundaries() {
        var coarse=new IslandWaitingMotion();var fine=new IslandWaitingMotion();
        at(coarse,0,WAIT);at(fine,0,WAIT);
        for(long millis=7;millis<650;millis+=7) at(fine,millis,WAIT);
        var a=at(coarse,650,WAIT);var b=at(fine,650,WAIT);
        assertThat(a.visibility()).isCloseTo(b.visibility(),offset(1e-9));
        assertThat(a.title()).isCloseTo(b.title(),offset(1e-9));
        for(int i=0;i<3;i++) assertThat(a.dot(i)).isCloseTo(b.dot(i),offset(1e-9));
    }
    @Test void exitAndRapidReentryCarryTheirCurrentPositionInsteadOfSnapping() {
        var motion=new IslandWaitingMotion();at(motion,0,WAIT);var shown=at(motion,1000,WAIT);
        assertThat(at(motion,1000,IslandLyrics.METADATA)).isEqualTo(shown);
        var closing=at(motion,1070,IslandLyrics.METADATA);
        assertThat(closing.visibility()).isBetween(.1,.9);
        assertThat(at(motion,1070,WAIT)).isEqualTo(closing);
        var returning=at(motion,1170,WAIT);
        assertThat(returning.visibility()).isGreaterThan(closing.visibility());
        assertThat(at(motion,2000,WAIT).title()).isGreaterThan(.99);
        at(motion,2000,IslandLyrics.METADATA);
        assertThat(at(motion,2500,IslandLyrics.METADATA).visibility()).isLessThan(.001);
    }
    @Test void loadingBecomingATimedGapAndRepeatedPlaybackSamplesDoNotReplayTheEntrance() {
        var motion=new IslandWaitingMotion();
        var loading=new IslandLyrics.Frame(IslandLyrics.Kind.WAITING,null,0,0);
        at(motion,0,loading);at(motion,300,loading);
        assertThat(at(motion,800,WAIT).title()).isGreaterThan(.99);
        assertThat(at(motion,5000,WAIT).title()).isEqualTo(1);
        // The UI clock continues while the lyric clock is paused at the same point.
        assertThat(at(motion,10000,WAIT).title()).isEqualTo(1);
    }
    @Test void seekingToAnotherGapReplaysTheSequenceSmoothlyAndANewTrackStartsClean() {
        var motion=new IslandWaitingMotion();at(motion,0,WAIT);var shown=at(motion,1000,WAIT);
        var nextGap=new IslandLyrics.Frame(IslandLyrics.Kind.WAITING,null,20000,30000);
        assertThat(at(motion,1000,nextGap)).isEqualTo(shown);
        assertThat(at(motion,1300,nextGap).title()).isLessThan(.01);
        assertThat(at(motion,1800,nextGap).title()).isGreaterThan(.99);
        var nextTrack=motion.update(WAIT,"another track",1800_000_000L,true,false);
        assertThat(nextTrack.visibility()).isZero();assertThat(nextTrack.title()).isZero();
    }
    @Test void disabledAnimationIsImmediateAndSimpleModeUsesOnlyAFadeWithoutADelayedSlide() {
        var motion=new IslandWaitingMotion();
        var immediate=motion.update(WAIT,"track",0,false,false);
        assertThat(immediate.visibility()).isEqualTo(1);assertThat(immediate.title()).isEqualTo(1);
        for(int i=0;i<3;i++) assertThat(immediate.dot(i)).isEqualTo(1);
        assertThat(at(motion,100,WAIT).title()).isEqualTo(1);
        var simple=new IslandWaitingMotion();simple.update(WAIT,"track",0,true,true);
        var fade=simple.update(WAIT,"track",100_000_000,true,true);
        assertThat(fade.visibility()).isBetween(.1,.99);assertThat(fade.title()).isEqualTo(fade.visibility());
        assertThat(fade.placement()).isEqualTo(1);
        assertThat(simple.update(WAIT,"track",120_000_000,true,false).title()).isGreaterThan(fade.title());
        assertThat(motion.update(IslandLyrics.METADATA,"track",200_000_000,false,false).visibility()).isZero();
    }
}
