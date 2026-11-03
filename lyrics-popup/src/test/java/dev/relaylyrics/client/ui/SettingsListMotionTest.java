package dev.relaylyrics.client.ui;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class SettingsListMotionTest {
    private static SettingsListMotion.Item item(String id) { return new SettingsListMotion.Item(id,60); }
    private static SettingsListMotion.Frame frame(SettingsListMotion motion,String id,long now) {
        return motion.sample(now,true).stream().filter(f->f.id().equals(id)).findFirst().orElseThrow();
    }
    @Test void expansionRetainsHeaderAndMovesFollowingRowsAsChildrenAppear() {
        var motion = new SettingsListMotion();
        motion.retarget(List.of(item("header"),item("next")),0,false,true);
        motion.retarget(List.of(item("header"),item("child1"),item("child2"),item("next")),1,true,false);
        assertThat(frame(motion,"header",1).opacity()).isEqualTo(1);
        assertThat(frame(motion,"child1",1).opacity()).isZero();
        assertThat(frame(motion,"next",60_000_001).y()).isBetween(60.,180.);
        assertThat(frame(motion,"child1",60_000_001).opacity()).isGreaterThan(frame(motion,"child2",60_000_001).opacity());
        assertThat(frame(motion,"next",300_000_001).y()).isEqualTo(180);
    }
    @Test void closingRetainsExitUntilItsSlotCollapsesAndRapidReopeningReversesSmoothly() {
        var motion = new SettingsListMotion();
        var open=List.of(item("header"),item("child"),item("next"));
        motion.retarget(open,0,false,true);
        motion.retarget(List.of(item("header"),item("next")),1,true,false);
        var closing=frame(motion,"child",50_000_001);
        assertThat(closing.leaving()).isTrue(); assertThat(closing.height()).isBetween(0.,60.);
        motion.retarget(open,50_000_001,true,false);
        var reversed=frame(motion,"child",50_000_001);
        assertThat(reversed.height()).isEqualTo(closing.height());
        assertThat(reversed.opacity()).isEqualTo(closing.opacity()); assertThat(reversed.leaving()).isFalse();
        assertThat(frame(motion,"child",300_000_001).height()).isEqualTo(60);
        motion.retarget(List.of(item("header"),item("next")),400_000_001,true,false);
        assertThat(motion.sample(600_000_001,true)).extracting(SettingsListMotion.Frame::id).containsExactly("header","next");
    }
    @Test void reducedMotionSettlesAllRowsAndExpandedHeightsWithoutDelay() {
        var motion = new SettingsListMotion();
        motion.retarget(List.of(item("a"),item("b"),item("c")),0,true,true);
        assertThat(motion.sample(20_000_000,false)).allMatch(f->f.opacity()==1 && f.height()==60);
        motion.retarget(List.of(item("a")),30_000_000,true,false);
        assertThat(motion.sample(35_000_000,false)).hasSize(1);
    }
    @Test void repeatedReconciliationDoesNotRestartMotionAndCategoryReplacementClearsExits() {
        var motion = new SettingsListMotion();
        var wanted=List.of(item("a"),item("b"));
        motion.retarget(wanted,0,true,true);
        double alpha=frame(motion,"a",50_000_000).opacity();
        motion.retarget(wanted,50_000_000,true,false);
        assertThat(frame(motion,"a",50_000_000).opacity()).isCloseTo(alpha,offset(1e-10));
        motion.retarget(List.of(item("other")),60_000_000,true,true);
        assertThat(motion.sample(300_000_000,true)).extracting(SettingsListMotion.Frame::id).containsExactly("other");
    }
}
