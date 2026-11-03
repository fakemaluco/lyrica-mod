package dev.relaylyrics.client.compat;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class JadeIslandMotionTest {
    @Test void contentResizeAndClosingCanReverseWithoutJumping() {
        var motion=new JadeIslandMotion();
        for(int i=0;i<90;i++) motion.step(true,160,48,42,480,.016,true);
        var resized=motion.step(true,250,70,42,480,.016,true);
        assertThat(resized.bounds().width()).isBetween(160.,250.);
        var closing=motion.step(false,250,70,42,480,.04,true);
        assertThat(closing.opacity()).isBetween(0.,1.);
        var reversed=motion.step(true,250,70,42,480,0,true);
        assertThat(reversed.bounds()).isEqualTo(closing.bounds());
        assertThat(reversed.opacity()).isEqualTo(closing.opacity());
        assertThat(reversed.bounds().centerX()).isEqualTo(480);
    }
    @Test void disabledAnimationsResolveImmediatelyAndKeepContentReadable() {
        var motion=new JadeIslandMotion();
        var frame=motion.step(true,180,55,42,480,.016,false);
        assertThat(frame.opacity()).isEqualTo(1);
        assertThat(frame.bounds().width()).isEqualTo(180);
        assertThat(frame.bounds().height()).isEqualTo(55);
        assertThat(frame.bounds().y()).isEqualTo(42);
        assertThat(motion.step(false,180,55,42,480,.016,false).opacity()).isZero();
    }
}
