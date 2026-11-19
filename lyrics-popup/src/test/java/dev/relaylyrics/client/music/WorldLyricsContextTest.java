package dev.relaylyrics.client.music;

import org.junit.jupiter.api.Test;

import static dev.relaylyrics.client.music.WorldLyricsContext.*;
import static org.assertj.core.api.Assertions.*;

class WorldLyricsContextTest {
    @Test void everyLocomotionChangeRequiresThreeHundredMilliseconds() {
        var tracker = new Tracker();
        assertThat(tracker.update(0, ground(0)).mode()).isEqualTo(Mode.STILL);
        var inputs = new Input[]{ground(3), new Input(5.6, 0, 0, true, false, true, false, false, false),
                new Input(1, 0, 0, true, true, false, false, false, false),
                new Input(3, -2, 0, false, false, false, false, false, false),
                new Input(3, 1, 0, false, false, false, true, false, false),
                new Input(12, 0, 0, false, false, false, false, true, false),
                new Input(30, 4, 0, false, false, false, false, false, true), ground(0)};
        var modes = new Mode[]{Mode.WALK, Mode.SPRINT, Mode.CROUCH, Mode.AIR, Mode.SWIM,
                Mode.VEHICLE, Mode.FLIGHT, Mode.STILL};
        for (int index = 0; index < inputs.length; index++) {
            long start = (index + 1) * 1_000_000_000L;
            var previous = tracker.snapshot().mode();
            assertThat(tracker.update(start, inputs[index]).mode()).isEqualTo(previous);
            assertThat(tracker.update(start + CONFIRM_NANOS - 1, inputs[index]).mode()).isEqualTo(previous);
            assertThat(tracker.update(start + CONFIRM_NANOS, inputs[index]).mode()).isEqualTo(modes[index]);
        }
    }

    @Test void briefJumpAndGroundBobDoNotTiltTheTravelBasis() {
        var tracker = new Tracker();
        tracker.update(0, ground(3));
        var jump = new Input(3, 5, 0, false, false, false, false, false, false);
        tracker.update(20_000_000, jump);
        var airborne = tracker.update(270_000_000, jump);
        assertThat(airborne.mode()).isEqualTo(Mode.WALK);
        assertThat(airborne.speed()).isEqualTo(3);
        assertThat(airborne.dirY()).isZero();
        var landed = tracker.update(290_000_000, new Input(3, -.8, 0, true, false, false, false, false, false));
        assertThat(landed.mode()).isEqualTo(Mode.WALK);
        assertThat(landed.dirX()).isEqualTo(1);
        assertThat(landed.dirY()).isZero();
    }

    @Test void smoothingAndAccelerationAreEquivalentAtDifferentFrameRates() {
        var fast = sampled(10_000_000);
        var slow = sampled(40_000_000);
        assertThat(fast.speed()).isCloseTo(slow.speed(), within(1e-10));
        assertThat(fast.acceleration()).isCloseTo(slow.acceleration(), within(1e-10));
        assertThat(fast.dirX()).isCloseTo(slow.dirX(), within(1e-10));
        assertThat(fast.dirZ()).isCloseTo(slow.dirZ(), within(1e-10));
        assertThat(fast.mode()).isEqualTo(Mode.SPRINT);
        assertThat(slow.mode()).isEqualTo(Mode.SPRINT);
    }

    @Test void thresholdNoiseDoesNotAlternateModesAndSustainedAirTravelUsesVerticalDirection() {
        var tracker = new Tracker();
        tracker.update(0, ground(5));
        for (long now = 100_000_000; now <= 1_000_000_000L; now += 100_000_000)
            assertThat(tracker.update(now, ground(now % 200_000_000 == 0 ? 5.3 : 5.1)).mode()).isEqualTo(Mode.WALK);
        var airborne = new Input(3, -6, 0, false, false, false, false, false, false);
        tracker.update(1_100_000_000, airborne);
        assertThat(tracker.update(1_400_000_000, airborne).mode()).isEqualTo(Mode.AIR);
        assertThat(tracker.update(1_600_000_000, airborne).dirY()).isLessThan(-.6);
    }

    @Test void stoppingReducesTheNextReadingDistanceSmoothlyAndRetainsBearing() {
        var tracker = new Tracker();
        var range = new WorldLyricsSpace.DistanceRange(8, 96);
        var moving = tracker.update(0, ground(8));
        var first = tracker.update(10_000_000, ground(0));
        var confirming = tracker.update(310_000_000, ground(0));
        var stopped = tracker.update(1_310_000_000, ground(0));
        assertThat(first.speed()).isBetween(7.5, moving.speed());
        assertThat(confirming.mode()).isEqualTo(Mode.STILL);
        assertThat(stopped.readingDistance(1, range)).isLessThan(confirming.readingDistance(1, range));
        assertThat(stopped.readingDistance(1, range)).isCloseTo(range.minimum(), within(.02));
        assertThat(stopped.dirX()).isEqualTo(1);
    }

    @Test void teleportImpulsesInvalidSamplesAndClockResetDoNotLeakIntoRunway() {
        var tracker = new Tracker();
        var initial = tracker.update(1_000_000_000, ground(3));
        assertThat(tracker.update(1_050_000_000, ground(2_000))).isSameAs(initial);
        assertThat(tracker.update(1_100_000_000, ground(Double.NaN))).isSameAs(initial);
        assertThat(tracker.update(500_000_000, ground(0))).isEqualTo(Snapshot.STILL);
        tracker.update(600_000_000, ground(6));
        assertThat(tracker.update(3_600_000_000L, ground(6)).mode()).isEqualTo(Mode.STILL);
        tracker.reset();
        assertThat(tracker.snapshot()).isEqualTo(Snapshot.STILL);
    }

    @Test void sectorsExploreTheWholeCircleDeterministicallyAndFastModesStartNearTravel() {
        for (var mode : Mode.values()) {
            var context = new Snapshot(mode, 12, 0, 1, 0, 0);
            var sectors = java.util.stream.IntStream.range(0, 8).map(i -> context.sector("stable cue", 2, i)).boxed().toList();
            assertThat(sectors).containsExactlyInAnyOrder(0, 1, 2, 3, 4, 5, 6, 7);
            assertThat(context.sector("stable cue", 2, 0)).isEqualTo(context.sector("stable cue", 2, 8));
            if (context.shortGroups()) assertThat(sectors.getFirst()).isIn(0, 1, 7);
        }
    }

    @Test void runwayUsesABoundedChunkAndRespectsUserDistanceAndSensitivity() {
        var range = new WorldLyricsSpace.DistanceRange(8, 64);
        var flight = new Snapshot(Mode.FLIGHT, 25, 0, 1, 0, 0);
        var sprint = new Snapshot(Mode.SPRINT, 6, 0, 1, 0, 0);
        assertThat(flight.readingDistance(1, range)).isBetween(25.0, 32.0);
        assertThat(flight.readingDistance(120, range)).isEqualTo(flight.readingDistance(2.5, range));
        assertThat(flight.readingDistance(1, range, 0)).isEqualTo(8);
        assertThat(new Snapshot(Mode.FLIGHT, 160, 30, 1, 0, 0).readingDistance(2.5, range)).isEqualTo(64);
        assertThat(sprint.readingDistance(1, range)).isLessThan(flight.readingDistance(1, range));
        assertThat(Snapshot.STILL.readingDistance(Double.NaN, range)).isEqualTo(8);
    }

    private static Snapshot sampled(long step) {
        var tracker = new Tracker();
        tracker.update(0, ground(0));
        for (long now = step; now <= 1_000_000_000L; now += step)
            tracker.update(now, new Input(6, 0, 2, true, false, true, false, false, false));
        return tracker.snapshot();
    }

    private static Input ground(double speed) { return new Input(speed, 0, 0, true, false, false, false, false, false); }
}
