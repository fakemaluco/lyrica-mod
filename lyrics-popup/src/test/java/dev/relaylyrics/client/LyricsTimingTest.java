package dev.relaylyrics.client;

import dev.relaylyrics.client.music.LyricsTiming;

import dev.relaylyrics.client.ClientPreferences;
import dev.relaylyrics.protocol.ProtocolCodec;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class LyricsTimingTest {
    @Test void positiveDelayWaitsForAudioAndNegativeDelayAdvancesLyrics() {
        assertThat(LyricsTiming.position(10_000, 350)).isEqualTo(9_650);
        assertThat(LyricsTiming.position(10_000, -350)).isEqualTo(10_350);
        assertThat(LyricsTiming.position(0, 350)).isEqualTo(-350);
        assertThat(LyricsTiming.position(10_000, 0)).isEqualTo(10_000);
    }
    @Test void clickingALyricSeeksToTheCorrespondingAudiblePosition() {
        for (int delay : new int[]{-5_000, -350, 0, 350, 5_000}) {
            long target = LyricsTiming.seekPosition(10_000, delay, 60_000);
            assertThat(LyricsTiming.position(target, delay)).isEqualTo(10_000);
        }
        assertThat(LyricsTiming.seekPosition(100, -350, 60_000)).isZero();
        assertThat(LyricsTiming.seekPosition(59_900, 350, 60_000)).isEqualTo(60_000);
    }
    @Test void manualDelayPersistsAndOldPreferencesKeepZeroDelay() throws Exception {
        var old = ClientPreferences.decode("{\"lyricsWidth\":380}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(old.getLyricsDelayMillis()).isZero();
        old.setLyricsDelayMillis(375);
        var restored = ClientPreferences.decode(ProtocolCodec.mapper().writeValueAsBytes(old));
        assertThat(restored.getLyricsDelayMillis()).isEqualTo(375);
        assertThat(restored.getLyricsWidth()).isEqualTo(380);
        old.setLyricsDelayMillis(-99_999); assertThat(old.getLyricsDelayMillis()).isEqualTo(-5_000);
        old.setLyricsDelayMillis(99_999); assertThat(old.getLyricsDelayMillis()).isEqualTo(5_000);
    }
}
