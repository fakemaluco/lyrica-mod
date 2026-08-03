package dev.fandroid.client.music;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpringValueTest {
    @Test
    void convergesToTheSamePositionAtDifferentFrameRates() {
        SpringValue sixtyFps = springTowardsOne();
        SpringValue oneFortyFourFps = springTowardsOne();

        stepFor(sixtyFps, 1.0 / 60.0, 120);
        stepFor(oneFortyFourFps, 1.0 / 144.0, 288);

        assertThat(sixtyFps.position()).isCloseTo(oneFortyFourFps.position(),
                org.assertj.core.data.Offset.offset(1E-8));
        assertThat(sixtyFps.position()).isCloseTo(1, org.assertj.core.data.Offset.offset(1E-3));
    }

    @Test
    void snapClearsMomentum() {
        SpringValue spring = springTowardsOne();
        spring.step(1.0 / 60.0);
        spring.snap(.4);

        assertThat(spring.position()).isEqualTo(.4);
        assertThat(spring.isAtRest()).isTrue();
        assertThat(spring.step(1)).isEqualTo(.4);
    }

    @Test
    void rejectsInvalidPhysicalParameters() {
        assertThatThrownBy(() -> new SpringValue(0, 0, .5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SpringValue(0, 1, -.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static SpringValue springTowardsOne() {
        SpringValue spring = new SpringValue(0, 1.18, .56);
        spring.setGoal(1);
        return spring;
    }

    private static void stepFor(SpringValue spring, double delta, int frames) {
        for (int frame = 0; frame < frames; frame++) spring.step(delta);
    }
}
