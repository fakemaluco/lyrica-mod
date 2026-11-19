package dev.relaylyrics.client;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class StatusReporterTest {
    @Test void onlyKnownPublicNetworksAreReported() {
        assertThat(StatusReporter.publicNetwork("MC.Hypixel.net.:25565")).isEqualTo("hypixel");
        assertThat(StatusReporter.publicNetwork("jogar.mush.com.br")).isEqualTo("mush");
        assertThat(StatusReporter.publicNetwork("dev.mc.hypixel.net")).isEmpty();
        assertThat(StatusReporter.publicNetwork("mc.hypixel.net.example.org")).isEmpty();
        assertThat(StatusReporter.publicNetwork("192.168.1.9:25565")).isEmpty();
        assertThat(StatusReporter.publicNetwork("[fe80::1]:25565")).isEmpty();
    }
}
