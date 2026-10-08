package dev.relaylyrics.client.music;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class UnavailableLyricsTransitionTest {
    @Test void terminalNoticeGetsTenSecondsThenOnlyFadesItsOpacity() {
        AtomicLong nanos = new AtomicLong();
        var transition = new UnavailableLyricsTransition(nanos::get);
        transition.track("one"); transition.unavailable();
        nanos.set(10_000_000_000L);
        assertThat(transition.sample(true).messageOpacity()).isOne();
        nanos.set(10_325_000_000L);
        assertThat(transition.sample(true).messageOpacity()).isCloseTo(.5, offset(.001));
        nanos.set(10_650_000_000L);
        assertThat(transition.sample(true).messageOpacity()).isZero();
        nanos.set(90_000_000_000L);
        assertThat(transition.sample(true).messageOpacity()).isZero();
    }

    @Test void repeatedErrorsAndPlaybackUpdatesDoNotRestartNotice() {
        AtomicLong nanos = new AtomicLong();
        var transition = new UnavailableLyricsTransition(nanos::get);
        transition.track("one"); transition.unavailable();
        nanos.set(12_000_000_000L);
        transition.track("one"); transition.unavailable();
        assertThat(transition.sample(true).messageOpacity()).isZero();
    }

    @Test void newTrackRetryAndSuccessfulLyricsRestoreNotice() {
        AtomicLong nanos = new AtomicLong();
        var transition = new UnavailableLyricsTransition(nanos::get);
        transition.track("one"); transition.unavailable();
        nanos.set(12_000_000_000L);
        transition.track("two");
        assertThat(transition.sample(true).messageOpacity()).isOne();
        transition.unavailable(); nanos.addAndGet(12_000_000_000L);
        transition.reset();
        assertThat(transition.sample(true).messageOpacity()).isOne();
        transition.unavailable();
        assertThat(transition.sample(true).messageOpacity()).isOne();
    }

    @Test void disabledAnimationsHideOnlyTheNoticeAfterTenSeconds() {
        AtomicLong nanos = new AtomicLong();
        var transition = new UnavailableLyricsTransition(nanos::get);
        transition.unavailable(); nanos.set(9_999_000_000L);
        assertThat(transition.sample(false).messageOpacity()).isOne();
        nanos.set(10_001_000_000L);
        assertThat(transition.sample(false).messageOpacity()).isZero();
    }

    @Test void longLoadingAndSecondFailureGetFreshGracePeriod() {
        AtomicLong nanos = new AtomicLong();
        var transition = new UnavailableLyricsTransition(nanos::get);
        transition.track("same song"); transition.unavailable();
        nanos.set(12_000_000_000L);
        assertThat(transition.sample(true).messageOpacity()).isZero();
        transition.reset(); nanos.addAndGet(30_000_000_000L);
        assertThat(transition.sample(true).messageOpacity()).isOne();
        transition.unavailable(); nanos.addAndGet(9_000_000_000L);
        assertThat(transition.sample(true).messageOpacity()).isOne();
    }
}
