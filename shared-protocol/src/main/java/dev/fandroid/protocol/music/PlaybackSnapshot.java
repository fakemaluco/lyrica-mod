package dev.fandroid.protocol.music;

import java.time.Instant;

public record PlaybackSnapshot(
        String title,
        String artist,
        String album,
        String artworkUrl,
        String spotifyUri,
        String deviceName,
        long positionMillis,
        long durationMillis,
        boolean playing,
        double volume,
        boolean shuffled,
        String repeatMode,
        boolean liked,
        double intensity,
        double beatPhase,
        double bpm,
        double spectralCentroid,
        double[] intensityBands,
        Instant observedAt,
        MusicCapabilities capabilities
) {
    public static PlaybackSnapshot unavailable() {
        return new PlaybackSnapshot("Nothing playing", "", "", null, null, null,
                0, 0, false, 0, false, "off", false, 0, 0, 0, 0.5, new double[0],
                Instant.now(), MusicCapabilities.unavailable());
    }
}
