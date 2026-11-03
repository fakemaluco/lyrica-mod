package dev.relaylyrics.client.presence;

import java.net.URI;
import java.util.Set;
import java.util.UUID;

/** Small, expiring metadata snapshot. No tokens, position, lyrics or listening history. */
public record PresenceTrack(UUID playerId, String playerName, String title, String artist,
                            String coverUrl, long expiresNanos) {
    public PresenceTrack {
        playerName = clean(playerName, 16); title = clean(title, 256); artist = clean(artist, 256);
        coverUrl = safeCover(coverUrl);
    }
    public boolean alive(long now) { return !title.isBlank() && expiresNanos - now > 0; }
    public static String clean(String value, int max) {
        if (value == null) return "";
        String safe = value.replaceAll("§.", "").replaceAll("[\\p{Cc}\\p{Cf}]", "").strip();
        return safe.substring(0, safe.offsetByCodePoints(0, Math.min(max, safe.codePointCount(0, safe.length()))));
    }
    public static String safeCover(String value) {
        if (PresenceArtwork.thumbnail(value)) return value;
        if (value == null || value.length() > 2048) return "";
        try {
            URI uri = URI.create(value);
            Set<String> hosts = Set.of("i.scdn.co", "mosaic.scdn.co", "image-cdn-ak.spotifycdn.com", "image-cdn-fa.spotifycdn.com",
                    "is1-ssl.mzstatic.com", "is2-ssl.mzstatic.com", "is3-ssl.mzstatic.com", "is4-ssl.mzstatic.com", "is5-ssl.mzstatic.com",
                    "i.ytimg.com", "lh3.googleusercontent.com", "lh4.googleusercontent.com");
            return "https".equals(uri.getScheme()) && hosts.contains(uri.getHost()) && uri.getUserInfo() == null && uri.getPort() == -1 ? value : "";
        } catch (RuntimeException ignored) { return ""; }
    }
}
