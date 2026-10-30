package dev.relaylyrics.client.music;

import org.junit.jupiter.api.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class WorldLyricsLightsTest {
    private final Fake backend=new Fake();
    @BeforeEach void install() { WorldLyricsLights.install(backend); }
    @AfterEach void remove() { WorldLyricsLights.install(null); }
    @Test void nearbyTerrainReceivesLightAndFalloffIsBounded() {
        var point=new WorldLyricsLights.Point(2,5,3,14);
        assertThat(point.lightAt(2,5,3,2)).isEqualTo(14);
        assertThat(point.lightAt(2,1,3,2)).isEqualTo(6);
        assertThat(point.lightAt(20,1,3,2)).isZero();
        assertThat(new WorldLyricsLights.Point(0,0,0,100).luminance()).isEqualTo(15);
        assertThatThrownBy(() -> new WorldLyricsLights.Point(Double.NaN,0,0,4)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void emitterCountAndUpdatesAreBoundedAndStaleWordsDoNotKeepLightingTerrain() {
        Object world=new Object(); long start=1_000_000_000L;
        WorldLyricsLights.begin(world,start);
        for (int i=0;i<30;i++) WorldLyricsLights.offer(new WorldLyricsLights.Point(i,2,3,14));
        WorldLyricsLights.tick(world,start,true);
        assertThat(backend.points).hasSize(3); assertThat(backend.updates).isEqualTo(1);
        WorldLyricsLights.tick(world,start+20_000_000L,true);
        assertThat(backend.updates).isEqualTo(1);
        WorldLyricsLights.tick(world,start+WorldLyricsLights.EXPIRY_NANOS+1,true);
        assertThat(backend.points).isEmpty();
    }
    @Test void hidingLyricsDisablingLightingOrChangingWorldRemovesOldSources() {
        Object world=new Object();long time=2_000_000_000L;
        for (int mode=0;mode<3;mode++) {
            WorldLyricsLights.begin(world,time);
            WorldLyricsLights.offer(new WorldLyricsLights.Point(1,2,3,13));
            WorldLyricsLights.tick(world,time,true);
            assertThat(backend.points).hasSize(1);
            WorldLyricsLights.tick(mode==0 ? null : mode==1 ? new Object() : world,time+1,mode!=2);
            assertThat(backend.points).isEmpty();time+=1_000_000_000L;
        }
    }
    @Test void popupGlyphSpringFreezesWithTheMusicAndDisablesCleanly() {
        var motion=new WorldLyricsExperience.Motion(true);
        motion.sample(0,4000,1200,1_000_000_000L,true);
        double scale=motion.scale(),lift=motion.elevation();
        motion.sample(0,4000,1200,2_000_000_000L,true);
        assertThat(motion.scale()).isEqualTo(scale);assertThat(motion.elevation()).isEqualTo(lift);
        motion.sample(0,4000,1300,2_010_000_000L,false);
        assertThat(motion.scale()).isEqualTo(1);assertThat(motion.elevation()).isZero();
    }
    @Test void withoutAnOptionalBackendNoWorldLightsAreRetained() {
        WorldLyricsLights.install(null);
        WorldLyricsLights.begin(new Object(),1_000_000_000L);
        WorldLyricsLights.offer(new WorldLyricsLights.Point(1,2,3,15));
        assertThat(WorldLyricsLights.latestPoints()).isEmpty();
        assertThat(WorldLyricsLights.available()).isFalse();
    }
    private static class Fake implements WorldLyricsLights.Backend {
        List<WorldLyricsLights.Point> points=List.of();int updates;
        public void update(Object world,List<WorldLyricsLights.Point> points) { this.points=points;updates++; }
        public void clear() { points=List.of(); }
    }
}
