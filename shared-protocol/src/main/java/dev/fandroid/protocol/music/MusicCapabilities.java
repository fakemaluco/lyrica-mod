package dev.fandroid.protocol.music;

public record MusicCapabilities(
        boolean playPause,
        boolean previous,
        boolean next,
        boolean seek,
        boolean volume,
        boolean shuffle,
        boolean repeat,
        boolean spotifyUri,
        boolean deviceName
) {
    public static MusicCapabilities unavailable() {
        return new MusicCapabilities(false, false, false, false, false,
                false, false, false, false);
    }
}

