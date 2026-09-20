package dev.fandroid.client;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LyricsFontTest {
    @Test
    void exposesDefaultAndGeneratedGoogleFontResources() {
        assertThat(LyricsFont.MINECRAFT.next()).isEqualTo(LyricsFont.MINECRAFT);
        assertThat(LyricsFont.UNIFORM.next()).isEqualTo(LyricsFont.MINECRAFT);
        assertThat(LyricsFont.GOOGLE.next()).isEqualTo(LyricsFont.MINECRAFT);
        assertThat(LyricsFont.MINECRAFT.id().toString()).isEqualTo("minecraft:default");
        assertThat(LyricsFont.GOOGLE.id().toString()).isEqualTo("lyrica:google");
    }
}
