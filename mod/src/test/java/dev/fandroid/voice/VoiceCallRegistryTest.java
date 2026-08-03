package dev.fandroid.voice;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class VoiceCallRegistryTest {
    @Test
    void connectsOnlyAfterAnswerAndRemovesCallOnHangup() {
        UUID caller = UUID.randomUUID();
        UUID callee = UUID.randomUUID();
        VoiceCallRegistry.ring(caller, callee);
        assertThat(VoiceCallRegistry.targets(caller)).isEmpty();
        VoiceCallRegistry.answer(callee, caller);
        assertThat(VoiceCallRegistry.targets(caller)).containsExactly(callee);
        assertThat(VoiceCallRegistry.channelId(caller)).isEqualTo(VoiceCallRegistry.channelId(callee));
        VoiceCallRegistry.leave(caller);
        assertThat(VoiceCallRegistry.targets(callee)).isEmpty();
    }
}
