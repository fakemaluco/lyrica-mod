package dev.relaylyrics.client.ui;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class SettingsMotionTest {
    @Test void toggleMovesBetweenEndpointsAndFinishesAtTheDesktopDuration() {
        var knob = new SettingsMotion.Tween(0);
        assertThat(knob.to(1, SettingsMotion.TOGGLE, SettingsMotion.Curve.OUT, 0, true)).isZero();
        assertThat(knob.value(30_000_000L)).isBetween(.25, .6);
        assertThat(knob.value(110_000_000L)).isBetween(.9, .99);
        assertThat(knob.value(220_000_000L)).isEqualTo(1);
    }
    @Test void rapidReversalStartsAtTheVisiblePositionWithoutJumping() {
        var knob = new SettingsMotion.Tween(0);
        knob.to(1, 220, SettingsMotion.Curve.OUT, 0, true);
        double visible = knob.value(50_000_000L);
        assertThat(knob.to(0, 220, SettingsMotion.Curve.OUT, 50_000_000L, true)).isEqualTo(visible);
        assertThat(knob.value(80_000_000L)).isBetween(0., visible);
        assertThat(knob.value(270_000_000L)).isZero();
    }
    @Test void samplingRateDoesNotChangeMotionAndRepeatedTargetsDoNotRestartIt() {
        var fast = new SettingsMotion.Tween(0);
        var slow = new SettingsMotion.Tween(0);
        fast.to(1, 150, SettingsMotion.Curve.STANDARD, 0, true);
        slow.to(1, 150, SettingsMotion.Curve.STANDARD, 0, true);
        for (long time = 1_000_000; time < 100_000_000; time += 1_000_000)
            fast.to(1, 150, SettingsMotion.Curve.STANDARD, time, true);
        assertThat(fast.value(100_000_000L)).isEqualTo(slow.value(100_000_000L));
        assertThat(fast.value(150_000_000L)).isEqualTo(1);
    }
    @Test void disablingAnimationsImmediatelySettlesEvenDuringAnInterruptedTransition() {
        var press = new SettingsMotion.Tween(1);
        press.to(.94, 120, SettingsMotion.Curve.OUT, 0, true);
        assertThat(press.to(.94, 120, SettingsMotion.Curve.OUT, 20_000_000L, false)).isEqualTo(.94);
        assertThat(press.to(1, 120, SettingsMotion.Curve.OUT, 30_000_000L, false)).isEqualTo(1);
        assertThat(press.value(31_000_000L)).isEqualTo(1);
    }
    @Test void curvesUseCssTimeAndMixAllColorChannels() {
        assertThat(SettingsMotion.Curve.STANDARD.at(.5)).isCloseTo(.77556, offset(.0001));
        assertThat(SettingsMotion.mix(0x00000000, 0xFFFFFFFF, .5)).isEqualTo(0x80808080);
        assertThat(SettingsMotion.opacity(0x80D9CAFF, .5)).isEqualTo(0x40D9CAFF);
    }
}
