package dev.relaylyrics.client.ui;

import dev.relaylyrics.client.karaoke.KaraokeInvitationLayout;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KaraokePanelMotionTest {
    @Test void closingDuringEntranceContinuesFromTheVisibleOpacity() {
        var motion = new KaraokePanelMotion();
        long start = 1_000_000_000L;
        assertEquals(0, motion.opacity(start, true));
        long close = start + 50_000_000L;
        double shown = motion.opacity(close, true);
        assertTrue(shown > 0 && shown < 1);
        motion.close(close);
        assertEquals(shown, motion.opacity(close, true));
        assertEquals(1, motion.scale(shown));
        double fading = motion.opacity(close + 75_000_000L, true);
        assertTrue(fading > 0 && fading < shown);
        assertTrue(motion.scale(fading) < 1);
        assertEquals(0, motion.opacity(close + 150_000_000L, true));
    }

    @Test void animationSettingAndLongLabelsUseSafeFallbacks() {
        var motion = new KaraokePanelMotion();
        assertEquals(1, motion.opacity(1, false));
        motion.close(2);
        assertEquals(0, motion.opacity(2, false));
        assertEquals("A😀…", KaraokeInvitationLayout.fitLabel("A😀BCD", 3,
                value -> value.codePointCount(0, value.length())));
    }
}
