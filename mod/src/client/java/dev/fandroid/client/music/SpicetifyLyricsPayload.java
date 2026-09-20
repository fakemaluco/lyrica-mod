package dev.fandroid.client.music;

import java.util.List;

public record SpicetifyLyricsPayload(String uri, String format, String payload, String source,
                                     List<String> songwriters, String providerCode,
                                     boolean communityProvided, String makerUsername,
                                     String makerAvatar, String uploaderUsername,
                                     String uploaderAvatar) {
    public SpicetifyLyricsPayload(String uri, String format, String payload, String source) {
        this(uri, format, payload, source, List.of(), "", false, "", "", "", "");
    }

    public SpicetifyLyricsPayload(String uri, String format, String payload, String source,
                                  List<String> songwriters) {
        this(uri, format, payload, source, songwriters, "", false, "", "", "", "");
    }

    public SpicetifyLyricsPayload {
        songwriters = songwriters == null ? List.of() : songwriters.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip).distinct().limit(32).toList();
        providerCode = clean(providerCode, 24);
        makerUsername = clean(makerUsername, 160);
        makerAvatar = clean(makerAvatar, 2_048);
        uploaderUsername = clean(uploaderUsername, 160);
        uploaderAvatar = clean(uploaderAvatar, 2_048);
    }

    private static String clean(String value, int maximumLength) {
        if (value == null) return "";
        String cleaned = value.strip();
        return cleaned.length() <= maximumLength ? cleaned : cleaned.substring(0, maximumLength);
    }
}
