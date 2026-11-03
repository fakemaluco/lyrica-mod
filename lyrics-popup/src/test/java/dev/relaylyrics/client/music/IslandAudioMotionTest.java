package dev.relaylyrics.client.music;

import dev.relaylyrics.protocol.music.MusicAudioLevels;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class IslandAudioMotionTest {
    @Test void barsFollowReceivedLevelsWithoutInventingMovement() {
        var motion=new IslandAudioMotion(); long now=10_000_000_000L;
        motion.accept(MusicAudioLevels.fromMeter(0,2500,5000,7500,10000,now));
        motion.step(true,now,1./60,false);
        assertThat(motion.level(0)).isZero(); assertThat(motion.level(2)).isEqualTo(.5); assertThat(motion.level(4)).isEqualTo(1);
        motion.step(true,now+100_000_000L,1./60,true);
        assertThat(motion.level(2)).isEqualTo(.5); assertThat(motion.level(4)).isEqualTo(1);
    }
    @Test void silencePauseAndExpiredPacketsNeverBecomeSyntheticBars() {
        var motion=new IslandAudioMotion(); long now=10_000_000_000L;
        motion.accept(MusicAudioLevels.fromMeter(10000,10000,10000,10000,10000,now));
        motion.step(true,now,.016,false); assertThat(motion.level(0)).isEqualTo(1);
        motion.step(true,now+161_000_000L,.016,false); assertThat(motion.level(0)).isZero();
        motion.accept(MusicAudioLevels.fromMeter(10000,10000,10000,10000,10000,now));
        motion.step(false,now,.016,false); assertThat(motion.level(0)).isZero();
        motion.accept(MusicAudioLevels.fromMeter(0,0,0,0,0,now));
        motion.step(true,now,.016,false); assertThat(motion.level(4)).isZero();
    }
    @Test void malformedLevelsAreClampedAndPauseDecaysSmoothly() {
        var motion=new IslandAudioMotion(); long now=10_000_000_000L;
        motion.accept(new MusicAudioLevels(Double.NaN,-1,2,Double.POSITIVE_INFINITY,.5,now));
        motion.step(true,now,.016,false);
        assertThat(motion.level(0)).isZero(); assertThat(motion.level(1)).isZero();
        assertThat(motion.level(2)).isEqualTo(1); assertThat(motion.level(3)).isZero();
        motion.step(false,now,.016,true); assertThat(motion.level(2)).isBetween(0.,1.);
        for(int i=0;i<90;i++) motion.step(false,now,.016,true);
        assertThat(motion.level(2)).isZero();
    }
    @Test void quietNoiseIsGatedAndTransientsFallQuickly() {
        var motion=new IslandAudioMotion();long now=10_000_000_000L;
        motion.accept(MusicAudioLevels.fromMeter(100,100,100,100,100,now));
        motion.step(true,now,.016,false);assertThat(motion.level(0)).isZero();
        motion.accept(MusicAudioLevels.fromMeter(10000,10000,10000,10000,10000,now));
        motion.step(true,now,.016,false);
        motion.accept(MusicAudioLevels.fromMeter(0,0,0,0,0,now));
        for(int i=0;i<10;i++) motion.step(true,now,.016,true);
        assertThat(motion.level(0)).isLessThan(.1);
    }
}
