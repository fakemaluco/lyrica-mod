package dev.relaylyrics.client;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

final class InventoryPopupTransitionTest {
    @Test
    void easesOutWhenInventoryOpensAndBackInWhenItCloses() {
        InventoryPopupTransition transition = new InventoryPopupTransition();
        long start = 1_000_000_000L;

        transition.setVisible(false, start, true);
        assertThat(transition.value(start, true)).isEqualTo(1);
        assertThat(transition.value(start + InventoryPopupTransition.HIDE_DURATION_NANOS / 10, true))
                .isGreaterThan(1);
        assertThat(transition.value(start + InventoryPopupTransition.HIDE_DURATION_NANOS / 2, true))
                .isBetween(.45, .55);
        assertThat(transition.value(start + InventoryPopupTransition.HIDE_DURATION_NANOS, true)).isZero();

        long close = start + InventoryPopupTransition.HIDE_DURATION_NANOS;
        transition.setVisible(true, close, true);
        assertThat(transition.value(close, true)).isZero();
        assertThat(transition.value(close + InventoryPopupTransition.SHOW_DURATION_NANOS / 10, true))
                .isLessThan(0);
        assertThat(transition.value(close + InventoryPopupTransition.SHOW_DURATION_NANOS / 2, true))
                .isBetween(.45, .55);
        assertThat(transition.value(close + InventoryPopupTransition.SHOW_DURATION_NANOS, true)).isEqualTo(1);
        assertThat(transition.isFullyVisible()).isTrue();
    }

    @Test
    void snapsWhenAnimationsAreDisabled() {
        InventoryPopupTransition transition = new InventoryPopupTransition();
        transition.setVisible(false, 0, false);
        assertThat(transition.value(1, false)).isZero();
        transition.setVisible(true, 2, false);
        assertThat(transition.value(3, false)).isEqualTo(1);
    }
}
