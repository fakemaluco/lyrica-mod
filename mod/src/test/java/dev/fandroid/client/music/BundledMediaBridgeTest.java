package dev.fandroid.client.music;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BundledMediaBridgeTest {
    @Test
    void mapsCommonOperatingSystemsAndArchitecturesToJarResources() {
        assertThat(BundledMediaBridge.platformName("Windows 11", "amd64"))
                .isEqualTo("windows-x86_64");
        assertThat(BundledMediaBridge.platformName("Linux", "x86_64"))
                .isEqualTo("linux-x86_64");
        assertThat(BundledMediaBridge.platformName("Mac OS X", "aarch64"))
                .isEqualTo("macos-arm64");
    }
}
