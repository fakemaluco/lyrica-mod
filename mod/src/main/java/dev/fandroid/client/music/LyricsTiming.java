package dev.fandroid.client.music;

/** A lyric-only delay: transport, video and the progress bar keep the actual playback time. */
public final class LyricsTiming {
    public static long position(long playbackMillis, int delayMillis) {
        // Preserve pre-roll, so a line at zero is not highlighted before a positive delay expires.
        return playbackMillis - Math.clamp(delayMillis, -5_000, 5_000);
    }
    public static long seekPosition(long lyricMillis, int delayMillis, long durationMillis) {
        long target = lyricMillis + Math.clamp(delayMillis, -5_000, 5_000);
        return Math.clamp(target, 0, durationMillis > 0 ? durationMillis : Math.max(0, target));
    }
    private LyricsTiming() { }
}
