package dev.relaylyrics.client.music;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class PopupDockLayoutTest {
    @Test void titleStaysForFiveSecondsAndHoverRestartsTheWholeHold() {
        var time=new java.util.concurrent.atomic.AtomicLong();var motion=new PopupDockMotion(time::get);
        var full=new PopupDockLayout.Rect(30,100,210,130);motion.initialize(true,PopupDockLayout.Edge.TOP,.5,false);
        time.set(4_999_999_999L);assertThat(motion.step(full,960,540,1,-100,-100,false,false,0,false,false).titlePresence()).isEqualTo(1);
        time.set(5_000_000_000L);var art=motion.step(full,960,540,1,-100,-100,false,false,0,false,false);
        assertThat(art.titlePresence()).isZero();assertThat(art.bounds().width()).isEqualTo(96);assertThat(art.bounds().height()).isEqualTo(32);
        time.set(9_000_000_000L);var over=motion.step(full,960,540,1,art.bounds().centerX(),art.bounds().centerY(),true,false,0,false,false);
        assertThat(over.hover()).isEqualTo(1);assertThat(over.titlePresence()).isEqualTo(1);assertThat(over.bounds().width()).isEqualTo(344);
        time.set(13_999_999_999L);var after=motion.step(full,960,540,1,-100,-100,true,false,0,false,false);
        assertThat(after.bounds().width()).isEqualTo(194);assertThat(after.bounds().height()).isEqualTo(32);
        time.set(14_000_000_000L);assertThat(motion.step(full,960,540,1,-100,-100,true,false,0,false,false).bounds().width()).isEqualTo(96);
    }
    @Test void lateSongAndIdleReversalKeepTheGeometryContinuousIncludingPausedPlayback() {
        var time=new java.util.concurrent.atomic.AtomicLong();var motion=new PopupDockMotion(time::get);
        var full=new PopupDockLayout.Rect(30,100,210,130);motion.initialize(true,PopupDockLayout.Edge.TOP,.5,false);
        time.set(6_000_000_000L);var closing=motion.step(full,960,540,1,-100,-100,false,false,.04,true,false);
        assertThat(closing.titlePresence()).isBetween(0.,1.);motion.revealTitle();
        var reverse=motion.step(full,960,540,1,-100,-100,false,false,0,true,false);
        assertThat(reverse.bounds()).isEqualTo(closing.bounds());
        assertThat(motion.step(full,960,540,1,-100,-100,false,false,0,false,false).bounds().width()).isEqualTo(194);
        time.addAndGet(5_000_000_000L);assertThat(motion.step(full,960,540,1,-100,-100,false,false,0,false,false).bounds().width()).isEqualTo(96);
    }
    @Test void playbackKeepsTheNameAndPausingStartsAFreshFiveSecondHold() {
        var time=new java.util.concurrent.atomic.AtomicLong();var motion=new PopupDockMotion(time::get);
        var full=new PopupDockLayout.Rect(30,100,210,130);motion.initialize(true,PopupDockLayout.Edge.TOP,.5,true);
        time.set(60_000_000_000L);var playing=motion.step(full,960,540,1,-100,-100,false,false,0,false,true);
        assertThat(playing.titlePresence()).isEqualTo(1);assertThat(playing.bounds().width()).isEqualTo(220);
        time.set(62_000_000_000L);motion.step(full,960,540,1,-100,-100,false,false,0,false,false);
        time.set(66_999_999_999L);assertThat(motion.step(full,960,540,1,-100,-100,false,false,0,false,false).titlePresence()).isEqualTo(1);
        time.set(67_000_000_000L);var paused=motion.step(full,960,540,1,-100,-100,false,false,0,false,false);
        assertThat(paused.bounds().width()).isEqualTo(96);assertThat(paused.bounds().height()).isEqualTo(32);
        var resumed=motion.step(full,960,540,1,-100,-100,false,false,0,false,true);
        assertThat(resumed.titlePresence()).isEqualTo(1);assertThat(resumed.bounds().width()).isEqualTo(220);
    }
    @Test void theShellHasAGentleSpringReboundAndReversesWithoutJumping() {
        var time=new java.util.concurrent.atomic.AtomicLong();var motion=new PopupDockMotion(time::get);
        var full=new PopupDockLayout.Rect(30,100,210,130);motion.initialize(true,PopupDockLayout.Edge.TOP,.5,false);
        time.set(6_000_000_000L);double minimum=999;PopupDockMotion.Frame frame=null;
        for(int i=0;i<60;i++){frame=motion.step(full,960,540,1,-100,-100,false,false,1./60,true,false);minimum=Math.min(minimum,frame.bounds().width());}
        assertThat(minimum).isBetween(90.,95.9);assertThat(frame.bounds().width()).isCloseTo(96,offset(.05));
        motion.revealTitle();var reverse=motion.step(full,960,540,1,-100,-100,false,false,0,true,false);
        assertThat(reverse.bounds()).isEqualTo(frame.bounds());
    }
    @Test void liveDragUsesHysteresisAndCanAttachDetachAndReturnWhileStillHeld() {
        for(double scale:new double[]{.5,.85,1,1.5}){
            var target=PopupDockLayout.returnTarget(960,540,scale);
            double x=target.centerX(),y=target.centerY();
            boolean attached=PopupDockLayout.dragAttachment(false,x,y,960,540,scale);assertThat(attached).isTrue();
            double boundary=target.bottom()+26*scale;
            assertThat(PopupDockLayout.dragAttachment(true,x,boundary,960,540,scale)).isTrue();
            assertThat(PopupDockLayout.dragAttachment(false,x,boundary,960,540,scale)).isFalse();
            attached=PopupDockLayout.dragAttachment(attached,x,target.bottom()+40*scale,960,540,scale);assertThat(attached).isFalse();
            assertThat(PopupDockLayout.dragAttachment(attached,x,y,960,540,scale)).isTrue();
            assertThat(PopupDockLayout.dragAttachment(true,Double.NaN,y,960,540,scale)).isFalse();
        }
    }
    @Test void compactIslandStaysAtTheTopAndSeparatesHeaderFromTheTimeline() {
        var full=new PopupDockLayout.Rect(12,200,210,130);
        var motion=new PopupDockMotion();
        motion.initialize(true,PopupDockLayout.Edge.RIGHT,.9);
        var frame=motion.step(full,960,540,1,-100,-100,false,false,0,false);
        assertThat(frame.horizontal()).isTrue(); assertThat(frame.bounds().centerX()).isEqualTo(480);
        assertThat(frame.bounds().y()).isEqualTo(6);
        assertThat(frame.bounds().height()).isEqualTo(32);
        assertThat(frame.track().height()).isLessThanOrEqualTo(3);
        assertThat(frame.grip().bottom()).isLessThan(frame.track().y());
        assertThat(PopupDockLayout.seek(frame.track(),frame.track().x()+frame.track().width()*.75,60_000)).isEqualTo(45_000);
        var selected=motion.minimize(new PopupDockLayout.Rect(700,400,210,130),960,540);
        assertThat(selected.edge()).isEqualTo(PopupDockLayout.Edge.TOP); assertThat(selected.position()).isEqualTo(.5);
    }
    @Test void expandingKeepsPlayerTargetsStableAndHeaderControlsSeparate() {
        var motion=new PopupDockMotion();
        var full=new PopupDockLayout.Rect(24,200,210,130);
        motion.initialize(true,PopupDockLayout.Edge.TOP,.5);
        var idle=motion.step(full,960,540,1,-100,-100,false,false,0,false);
        var expanded=motion.step(full,960,540,1,idle.bounds().centerX(),idle.bounds().centerY(),true,false,0,false);
        assertThat(expanded.bounds().width()).isEqualTo(344);
        assertThat(expanded.bounds().height()).isEqualTo(116);
        assertThat(expanded.track()).isEqualTo(idle.track());
        assertThat(expanded.grip().right()).isLessThan(PopupDockLayout.close(expanded.contents(),1).x());
        assertThat(PopupDockLayout.artwork(expanded.contents(),1).right()).isLessThan(expanded.track().x());
        var artwork=PopupDockLayout.artwork(expanded.contents(),1);
        assertThat(artwork.y()).isEqualTo(expanded.bounds().y());
        assertThat(artwork.height()).isEqualTo(expanded.bounds().height());
        assertThat(artwork.centerY()).isCloseTo(expanded.track().centerY(),offset(1.));
        assertThat(expanded.track().bottom()).isLessThan(PopupDockLayout.play(expanded.contents(),1).y());
        assertThat(PopupDockLayout.next(expanded.contents(),1).right()).isLessThan(expanded.bounds().right());
    }
    @Test void headerCloseCollapsesDetailsUntilThePointerLeavesAndDoesNotStopTheIsland() {
        var motion=new PopupDockMotion(); var full=new PopupDockLayout.Rect(24,200,210,130);
        motion.initialize(true,PopupDockLayout.Edge.TOP,.5);
        var idle=motion.step(full,960,540,1,-100,-100,false,false,0,false);
        double x=idle.bounds().centerX(),y=idle.bounds().centerY();
        motion.step(full,960,540,1,x,y,true,false,0,false);
        motion.collapseDetails();
        motion.step(full,960,540,1,Double.NaN,Double.NaN,false,false,0,false);
        var closed=motion.step(full,960,540,1,x,y,true,false,0,false);
        assertThat(closed.minimized()).isTrue(); assertThat(closed.hover()).isZero();
        motion.step(full,960,540,1,-100,-100,true,false,0,false);
        assertThat(motion.step(full,960,540,1,x,y,true,false,0,false).hover()).isEqualTo(1);
    }
    @Test void pausingFadesBarsAndContractsOnlyTheCompactShellWithContinuousReversal() {
        var full=new PopupDockLayout.Rect(24,200,210,130);var motion=new PopupDockMotion();
        motion.initialize(true,PopupDockLayout.Edge.TOP,.5,true);
        var playing=motion.step(full,960,540,1,-100,-100,true,false,0,false,true);
        var pausing=motion.step(full,960,540,1,-100,-100,true,false,.04,true,false);
        assertThat(pausing.audioPresence()).isBetween(0.,1.);
        assertThat(pausing.bounds().width()).isLessThan(playing.bounds().width());
        assertThat(pausing.bounds().height()).isEqualTo(playing.bounds().height());
        var reversed=motion.step(full,960,540,1,-100,-100,true,false,0,true,true);
        assertThat(reversed.bounds()).isEqualTo(pausing.bounds());
        var paused=motion.step(full,960,540,1,-100,-100,true,false,0,false,false);
        assertThat(paused.audioPresence()).isZero();
        assertThat(paused.bounds().width()).isEqualTo(194);
        assertThat(paused.bounds().centerX()).isEqualTo(480);
        var expanded=motion.step(full,960,540,1,480,paused.bounds().centerY(),true,false,0,false,false);
        assertThat(expanded.bounds().width()).isEqualTo(344);
        assertThat(expanded.track()).isEqualTo(playing.track());
    }
    @Test void nearestEdgeUsesTheWholePopupAndStableTieBreaking() {
        assertThat(PopupDockLayout.nearest(new PopupDockLayout.Rect(12,200,210,130),960,540).edge()).isEqualTo(PopupDockLayout.Edge.LEFT);
        assertThat(PopupDockLayout.nearest(new PopupDockLayout.Rect(730,200,210,130),960,540).edge()).isEqualTo(PopupDockLayout.Edge.RIGHT);
        assertThat(PopupDockLayout.nearest(new PopupDockLayout.Rect(300,10,210,130),960,540).edge()).isEqualTo(PopupDockLayout.Edge.TOP);
        assertThat(PopupDockLayout.nearest(new PopupDockLayout.Rect(300,400,210,130),960,540).edge()).isEqualTo(PopupDockLayout.Edge.BOTTOM);
        assertThat(PopupDockLayout.nearest(new PopupDockLayout.Rect(24,24,210,130),960,540).edge()).isEqualTo(PopupDockLayout.Edge.LEFT);
    }
    @Test void capsulesStayInsideEveryEdgeAndExpansionPreservesTheAttachedEdge() {
        for(var edge:PopupDockLayout.Edge.values()) for(int w:new int[]{40,320,960}) for(int h:new int[]{40,180,540}) for(double position:new double[]{0,.5,1}) {
            var anchor=new PopupDockLayout.Anchor(edge,position);
            for(double hover:new double[]{0,.5,1,1.04}) {
                var box=PopupDockLayout.capsule(anchor,w,h,.5,hover);
                assertThat(box.x()).isGreaterThanOrEqualTo(0); assertThat(box.y()).isGreaterThanOrEqualTo(0);
                assertThat(box.right()).isLessThanOrEqualTo((double)w); assertThat(box.bottom()).isLessThanOrEqualTo((double)h);
                assertThat(box.height()).isPositive(); assertThat(box.width()).isPositive();
            }
        }
    }
    @Test void verticalSeekingClampsBothEndsAndUnknownDurationIsNeverSynthesized() {
        var track=new PopupDockLayout.Rect(4,20,3,100);
        assertThat(PopupDockLayout.seek(track,-100,60_000)).isZero();
        assertThat(PopupDockLayout.seek(track,45,60_000)).isEqualTo(15_000);
        assertThat(PopupDockLayout.seek(track,95,60_000)).isEqualTo(45_000);
        assertThat(PopupDockLayout.seek(track,1000,60_000)).isEqualTo(60_000);
        assertThat(PopupDockLayout.seek(track,50,0)).isZero();
    }
    @Test void liquidMotionKeepsItsPositionWhenReversedAndSettlesWithAnimationsDisabled() {
        var motion=new PopupDockMotion(); var full=new PopupDockLayout.Rect(24,24,210,130);
        motion.initialize(false,PopupDockLayout.Edge.LEFT,.5); motion.minimize(full,960,540);
        var moving=motion.step(full,960,540,1,-100,-100,false,false,.04,true);
        assertThat(moving.folded()).isBetween(0.,1.);
        motion.restore(); var reversed=motion.step(full,960,540,1,-100,-100,false,false,0,true);
        assertThat(reversed.folded()).isCloseTo(moving.folded(),offset(1e-12));
        assertThat(reversed.bounds()).isEqualTo(moving.bounds());
        assertThat(motion.step(full,960,540,1,-100,-100,false,false,0,false).folded()).isZero();
        motion.minimize(full,960,540);
        var closed=motion.step(full,960,540,1,-100,-100,false,false,0,false);
        var over=motion.step(full,960,540,1,closed.bounds().centerX(),closed.bounds().centerY(),true,false,0,false);
        assertThat(over.bounds().width()).isGreaterThan(closed.bounds().width()); assertThat(over.hover()).isEqualTo(1);
    }
}
