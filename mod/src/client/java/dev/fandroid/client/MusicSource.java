package dev.fandroid.client;

/** Explicit playback source and command routing policy. */
public enum MusicSource {
    AUTOMATIC,
    WINDOWS_MEDIA,
    SPOTIFY_ACCOUNT,
    YOUTUBE_MUSIC;

    public MusicSource next() { return values()[(ordinal() + 1) % values().length]; }
    public MusicSource previous() { return values()[(ordinal() - 1 + values().length) % values().length]; }
}
