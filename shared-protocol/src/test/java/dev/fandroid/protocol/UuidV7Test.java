package dev.fandroid.protocol;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UuidV7Test {
    @Test
    void createsVersionSevenRfcVariantIdsWithSortableTimestamp() {
        var earlier = UuidV7.randomUuid(1_700_000_000_000L);
        var later = UuidV7.randomUuid(1_700_000_000_001L);

        assertThat(earlier.version()).isEqualTo(7);
        assertThat(earlier.variant()).isEqualTo(2);
        assertThat(earlier.compareTo(later)).isNegative();
    }
}

