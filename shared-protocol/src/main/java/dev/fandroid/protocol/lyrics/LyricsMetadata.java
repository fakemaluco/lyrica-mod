package dev.fandroid.protocol.lyrics;

public record LyricsMetadata(
        LyricsSource source,
        LyricsSyncMode syncMode,
        String trackKey,
        String title,
        String artist,
        String album,
        String songwriters,
        String provider,
        boolean communityProvided,
        String makerUsername,
        String makerAvatar,
        String uploaderUsername,
        String uploaderAvatar
) {
    public LyricsMetadata(LyricsSource source, LyricsSyncMode syncMode, String trackKey,
                          String title, String artist, String album) {
        this(source, syncMode, trackKey, title, artist, album, "");
    }

    public LyricsMetadata(LyricsSource source, LyricsSyncMode syncMode, String trackKey,
                          String title, String artist, String album, String songwriters) {
        this(source, syncMode, trackKey, title, artist, album, songwriters,
                providerFor(source), false, "", "", "", "");
    }

    public LyricsMetadata {
        source = source == null ? LyricsSource.UNKNOWN : source;
        syncMode = syncMode == null ? LyricsSyncMode.LINE : syncMode;
        trackKey = safe(trackKey);
        title = safe(title);
        artist = safe(artist);
        album = safe(album);
        songwriters = safe(songwriters);
        provider = safe(provider);
        makerUsername = safe(makerUsername);
        makerAvatar = safe(makerAvatar);
        uploaderUsername = safe(uploaderUsername);
        uploaderAvatar = safe(uploaderAvatar);
    }

    public static LyricsMetadata unknown() {
        return new LyricsMetadata(LyricsSource.UNKNOWN, LyricsSyncMode.LINE, "", "", "", "");
    }

    public LyricsMetadata withSource(LyricsSource newSource) {
        String nextProvider = provider.isBlank() || provider.equals(providerFor(source))
                ? providerFor(newSource) : provider;
        return new LyricsMetadata(newSource, syncMode, trackKey, title, artist, album, songwriters,
                nextProvider, communityProvided, makerUsername, makerAvatar, uploaderUsername, uploaderAvatar);
    }

    public LyricsMetadata withTrackKey(String newTrackKey) {
        return new LyricsMetadata(source, syncMode, newTrackKey, title, artist, album, songwriters,
                provider, communityProvided, makerUsername, makerAvatar, uploaderUsername, uploaderAvatar);
    }

    public boolean hasCommunityCredits() {
        return communityProvided || !makerUsername.isBlank() || !uploaderUsername.isBlank();
    }

    public static String providerFor(LyricsSource source) {
        if (source == null) return "";
        return switch (source) {
            case SPICY_TTML, SPICY_CACHE -> "Spicy Lyrics";
            case AMLL -> "AMLL";
            case BETTER_LYRICS -> "Better Lyrics";
            case LRCLIB -> "LRCLIB";
            case IMPORTED -> "Imported TTML";
            case UNKNOWN -> "";
        };
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
