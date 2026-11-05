package dev.relaylyrics.client;

import dev.relaylyrics.client.music.LyricsAnimation;
import dev.relaylyrics.protocol.ProtocolCodec;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

class LineLyricsAnimationTest {
    @Test void phraseGlowMatchesTheReferenceAndScalesOpacityWithoutChangingBlur() {
        assertThat(LyricsAnimation.spicyLineGlowTarget(-1)).isZero();
        assertThat(LyricsAnimation.spicyLineGlowTarget(0)).isZero();
        assertThat(LyricsAnimation.spicyLineGlowTarget(.25)).isEqualTo(.6875);
        assertThat(LyricsAnimation.spicyLineGlowTarget(.5)).isEqualTo(1);
        assertThat(LyricsAnimation.spicyLineGlowTarget(.75)).isEqualTo(.6875);
        assertThat(LyricsAnimation.spicyLineGlowTarget(1)).isZero();
        assertThat(LyricsAnimation.spicyLineGlowTarget(2)).isZero();
        var normal = LyricsAnimation.lineGlowProfile(1, 1);
        var soft = LyricsAnimation.lineGlowProfile(1, .55);
        assertThat(normal.coreOpacity()).isEqualTo(.5);
        assertThat(normal.coreRadius()).isCloseTo(12 * 9.0 / 28, offset(1E-12));
        assertThat(soft.coreOpacity()).isEqualTo(.275);
        assertThat(soft.coreRadius()).isEqualTo(normal.coreRadius());
        assertThat(normal.renderPlan().activeGlowTextLayers()).isEqualTo(1);
        assertThat(normal.outerOpacity()).isZero();
        assertThat(LyricsAnimation.lineGlowProfile(0, 1).needsBloom()).isFalse();
        assertThat(LyricsAnimation.lineGlowProfile(1, 0).needsBloom()).isFalse();
    }

    @Test void phraseGlowSettlesAfterTheLineAndResetsOnSeekOrReducedMotion() {
        var glow = new LyricsAnimation.LineGlowMotion();
        glow.reset(.5, true);
        assertThat(glow.amount()).isEqualTo(1);
        glow.update(1, true, 1 / 60.0);
        assertThat(glow.amount()).isBetween(0.0, 1.0);
        assertThat(glow.amount()).isPositive();
        for (int frame = 0; frame < 600; frame++) glow.update(1, true, 1 / 60.0);
        assertThat(glow.amount()).isCloseTo(0, offset(.0001));
        glow.reset(.25, true);
        assertThat(glow.amount()).isEqualTo(.6875);
        glow.reset(-1, true);
        assertThat(glow.amount()).isZero();
        glow.reset(.5, true);
        glow.update(.5, false, 0);
        assertThat(glow.amount()).isZero();
        glow.reset(.5, false);
        assertThat(glow.amount()).isZero();
    }

    @Test void oldPreferencesDefaultToVerticalAndTheTimedChoiceSurvivesSaving() throws Exception {
        var old = ClientPreferences.decode("{\"lyricsAnimations\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(old.getLineLyricsAnimation()).isEqualTo(ClientPreferences.LineLyricsAnimation.TOP_TO_BOTTOM);
        old.setLineLyricsAnimation(ClientPreferences.LineLyricsAnimation.TIMED);
        var restored = ClientPreferences.decode(ProtocolCodec.mapper().writeValueAsBytes(old));
        assertThat(restored.getLineLyricsAnimation()).isEqualTo(ClientPreferences.LineLyricsAnimation.TIMED);
        restored.setLineLyricsAnimation(null);
        assertThat(restored.getLineLyricsAnimation()).isEqualTo(ClientPreferences.LineLyricsAnimation.TOP_TO_BOTTOM);
    }

    @Test void verticalFillFollowsTheCompleteDurationOfEachLine() {
        assertThat(LyricsAnimation.lineProgress(999, 1000, 9000, true)).isZero();
        for (long end : new long[]{2000, 9000, 90000}) {
            long duration = end - 1000;
            assertThat(LyricsAnimation.lineProgress(1000, 1000, end, true)).isZero();
            assertThat(LyricsAnimation.lineProgress(1000 + duration / 4, 1000, end, true)).isEqualTo(.25);
            assertThat(LyricsAnimation.lineProgress(1000 + duration / 2, 1000, end, true)).isEqualTo(.5);
            assertThat(LyricsAnimation.lineProgress(1000 + duration * 3 / 4, 1000, end, true)).isEqualTo(.75);
            assertThat(LyricsAnimation.lineProgress(end, 1000, end, true)).isEqualTo(1);
            assertThat(LyricsAnimation.lineProgress(end + 1, 1000, end, true)).isEqualTo(1);
        }
        assertThat(LyricsAnimation.lineProgress(1200, 1000, 9000, true)).isEqualTo(.025);
        assertThat(LyricsAnimation.lineHighlight(.5, .1)).isEqualTo(1);
        assertThat(LyricsAnimation.lineHighlight(.5, .9)).isZero();
        assertThat(LyricsAnimation.lineHighlight(1, 1)).isEqualTo(1);
        // Seeking uses the exact position in the line; reduced motion remains immediate.
        assertThat(LyricsAnimation.lineProgress(5000, 1000, 9000, true)).isEqualTo(.5);
        assertThat(LyricsAnimation.lineProgress(1000, 1000, 9000, false)).isEqualTo(1);
        assertThat(LyricsAnimation.lineProgress(1001, 1000, 1000, true)).isEqualTo(1);
    }
}
