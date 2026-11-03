package dev.relaylyrics.client;

import dev.relaylyrics.client.presence.*;
import java.util.*;

/** Minecraft-free lifecycle shared by the three platform adapters. */
public final class MusicPresence {
    public record Context(MusicPresenceClient.Identity identity, Set<UUID> online) { }
    private static MusicPresenceClient connection;
    private static final PresenceGaze GAZE = new PresenceGaze();
    private static Map<UUID, PresenceTrack> fixture = Map.of();
    private static int ticks;
    private MusicPresence() { }
    public static void initialize() { PresencePlatform.register(); }
    public static void tick() {
        if (Boolean.getBoolean("lyrica.musicPresenceSelfTest")) { PresencePlatform.probeTick(); return; }
        var p = RelayLyricsClient.preferences();
        Context context = p.isMusicPresenceEnabled() ? PresencePlatform.context() : null;
        if (context == null) { if (connection != null) close(); return; }
        if (connection == null || !connection.identity().equals(context.identity())) {
            close();
            connection = new MusicPresenceClient(context.identity(), PresencePlatform::prove);
            connection.start();
        }
        if (++ticks % 10 == 0) {
            var music = RelayLyricsClient.music();
            var snapshot = music == null ? null : music.snapshot();
            connection.update(snapshot == null ? new MusicPresenceClient.Playing("", "", "", false)
                    : new MusicPresenceClient.Playing(snapshot.title(), snapshot.artist(), snapshot.artworkUrl(),
                    p.isMusicPresenceSharing() && snapshot.playing()), context.online());
        }
        updateGaze(PresencePlatform.lookingAt());
    }
    public static void updateGaze(UUID id) { GAZE.update(id != null && track(id) != null ? id : null, System.nanoTime()); }
    public static UUID pinned() { return GAZE.pinned(); }
    public static double cardOpacity() { return GAZE.opacity(System.nanoTime()); }
    public static PresenceTrack track(UUID id) {
        if (!RelayLyricsClient.preferences().isMusicPresenceEnabled() || id == null) return null;
        PresenceTrack t = fixture.get(id);
        return t != null && t.alive(System.nanoTime()) ? t : connection == null ? null : connection.track(id);
    }
    public static String statusKey() {
        if (!RelayLyricsClient.preferences().isMusicPresenceEnabled()) return "disabled";
        return connection == null ? "multiplayer" : connection.status().name().toLowerCase(Locale.ROOT);
    }
    public static int show(String name) {
        if (!RelayLyricsClient.preferences().isMusicPresenceEnabled()) { PresencePlatform.message("music_presence.disabled"); return 0; }
        UUID id = PresencePlatform.findPlayer(name);
        if (id == null) { PresencePlatform.message("music_presence.player_missing", name); return 0; }
        PresenceTrack song = track(id);
        if (song == null) {
            var state = connection == null ? null : connection.status();
            if (state == MusicPresenceClient.Status.CONNECTING || state == MusicPresenceClient.Status.RETRYING)
                PresencePlatform.message("music_presence.connecting");
            else PresencePlatform.message("music_presence.not_listening", name);
            return 0;
        }
        PresencePlatform.message("music_presence.song", song.playerName(), song.title(), song.artist()); return 1;
    }
    public static int sharing(boolean value) {
        var p = RelayLyricsClient.preferences(); p.setMusicPresenceSharing(value); p.save(); ticks = 9;
        if (value && connection != null && connection.status() == MusicPresenceClient.Status.VIEW_ONLY)
            PresencePlatform.message("music_presence.sharing_requires_account");
        else PresencePlatform.message(value ? "music_presence.sharing_on" : "music_presence.sharing_off");
        return 1;
    }
    public static void close() {
        MusicPresenceClient old = connection; connection = null; if (old != null) old.close();
        GAZE.clear(); fixture = Map.of(); ticks = 0; PresencePlatform.clearArtwork();
    }
    public static void probeTracks(Map<UUID, PresenceTrack> tracks) {
        if (!Boolean.getBoolean("lyrica.musicPresenceSelfTest")) throw new IllegalStateException("Presence fixture is opt-in");
        fixture = Map.copyOf(tracks);
    }
}
