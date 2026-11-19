package dev.relaylyrics.client;

import dev.relaylyrics.client.presence.*;
import dev.relaylyrics.client.karaoke.KaraokeClient;
import dev.relaylyrics.karaoke.KaraokeWire.QueueTrack;
import dev.relaylyrics.karaoke.KaraokeWire.Song;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import static dev.relaylyrics.client.presence.MusicPresenceClient.*;
import static dev.relaylyrics.karaoke.KaraokeWire.QUEUE_LIMIT;

/** Minecraft-free lifecycle shared by the three platform adapters. */
public final class MusicPresence {
    public record Context(MusicPresenceClient.Identity identity, Set<UUID> online) { }
    private static MusicPresenceClient connection;
    private static final PresenceGaze GAZE = new PresenceGaze();
    private static Map<UUID, PresenceTrack> fixture = Map.of();
    private static int ticks;
    private static int scopeTicks;
    private static Context scope;
    private static volatile String invitation;
    private static String confirmedInvitation;
    private static ListenTarget target;
    private static long listenSince, lastHostSeen, lastReply;
    private record SharedQueue(String uri, List<QueueTrack> tracks) { }
    private static volatile SharedQueue sharedQueue;
    private static CompletableFuture<?> queueLookup;
    private static long nextQueueLookup;
    private static ListenHost listeningHost;
    private MusicPresence() { }
    public static void initialize() { PresencePlatform.register(); }
    public static void tick() {
        if (Boolean.getBoolean("lyrica.musicPresenceSelfTest")) { PresencePlatform.probeTick(); return; }
        var p = RelayLyricsClient.preferences();
        boolean presence = p.isMusicPresenceEnabled() && !KaraokeClient.supported();
        boolean wanted = presence || invitation != null || target != null;
        if (wanted && (scope == null || ++scopeTicks % 10 == 0)) scope = PresencePlatform.context();
        Context context = wanted ? scope : null;
        if (context == null) {
            if (connection != null) close();
            updateGaze(p.isMusicPresenceEnabled() ? PresencePlatform.lookingAt() : null);
            return;
        }
        if (connection == null || !connection.identity().equals(context.identity())) {
            closeConnection();
            connection = new MusicPresenceClient(context.identity(), PresencePlatform::prove);
            listenSince = System.nanoTime();
            connection.setMetadataEnabled(presence);
            connection.start();
        } else connection.setMetadataEnabled(presence);
        if (++ticks % 10 == 0) {
            var music = RelayLyricsClient.music();
            var snapshot = !presence || music == null ? null : music.snapshot();
            connection.update(snapshot == null ? new MusicPresenceClient.Playing("", "", "", false)
                    : new MusicPresenceClient.Playing(snapshot.title(), snapshot.artist(), snapshot.artworkUrl(),
                    p.isMusicPresenceSharing() && snapshot.playing()), presence ? context.online() : Set.of());
        }
        updateListen();
        updateGaze(p.isMusicPresenceEnabled() ? PresencePlatform.lookingAt() : null);
    }

    public static boolean listen(String name) {
        if (invitation != null) { notice("host_busy"); return true; }
        String code = name.toLowerCase(Locale.ROOT);
        if (!code.matches("lyr_[0-9a-f]{32}")) {
            UUID player = PresencePlatform.findPlayer(name);
            if (player == null) return false;
            code = player.toString();
        }
        if (PresencePlatform.context() == null) { notice("multiplayer"); return true; }
        KaraokeClient.endRelay();
        target = new ListenTarget(code, name);
        listeningHost = null;
        listenSince = System.nanoTime(); lastHostSeen = 0; lastReply = 0;
        notice("connecting");
        return true;
    }

    public static int shareListen(boolean share) {
        if (!share) {
            if (invitation == null) { notice("share_idle"); return 0; }
            invitation = confirmedInvitation = null; sharedQueue = null; nextQueueLookup = 0;
            notice("unshared"); return 1;
        }
        if (PresencePlatform.context() == null) { notice("multiplayer"); return 0; }
        if (target != null || KaraokeClient.isFollowing()) { notice("host_busy"); return 0; }
        if (invitation == null) invitation = "lyr_" + UUID.randomUUID().toString().replace("-", "");
        if (invitation.equals(confirmedInvitation)) notice("shared", invitation);
        else notice("connecting");
        return 1;
    }

    public static boolean stopListen() {
        if (target == null) return false;
        target = null; listeningHost = null; lastHostSeen = 0;
        KaraokeClient.endRelay();
        return true;
    }

    public static int listenQueue() {
        if (listeningHost == null) { notice("idle"); return 0; }
        List<QueueTrack> tracks = listeningHost.queue();
        if (tracks.isEmpty()) { notice("queue_empty"); return 1; }
        notice("queue_title", listeningHost.name());
        for (int i = 0; i < tracks.size(); i++) {
            QueueTrack track = tracks.get(i);
            notice("queue_track", i + 1, track.title(), track.artist());
        }
        return 1;
    }

    private static void updateListen() {
        long now = System.nanoTime();
        var music = RelayLyricsClient.music();
        Song song = invitation == null || music == null || KaraokeClient.isFollowing()
                ? null : KaraokeClient.song(music.snapshot(), music.positionMillis());
        if (song != null && !song.uri().isBlank() && music.spotifyPlaybackActive() && now >= nextQueueLookup
                && (queueLookup == null || queueLookup.isDone())) {
            String uri = song.uri();
            String invite = invitation;
            nextQueueLookup = now + 15_000_000_000L;
            queueLookup = music.karaokeQueue("list", "", 0, 0, uri, QUEUE_LIMIT, () -> invite.equals(invitation))
                    .thenAccept(reply -> {
                        if (invite.equals(invitation) && "ok".equals(reply.status())) sharedQueue = new SharedQueue(uri, reply.entries().stream()
                                .map(e -> new QueueTrack(e.uri(), e.title(), e.artist())).toList());
                    });
        }
        SharedQueue queue = sharedQueue;
        connection.updateListen(new ListenUpdate(invitation, song, now,
                queue != null && song != null && queue.uri().equals(song.uri()) ? queue.tracks() : List.of(), target));
        var reply = connection.listenReply();
        if (reply != null && reply.received() != lastReply) {
            lastReply = reply.received();
            if (invitation != null && invitation.equals(reply.invite())) {
                if (!reply.canShare()) {
                    invitation = confirmedInvitation = null; sharedQueue = null;
                    PresencePlatform.message("music_presence.sharing_requires_account");
                } else if (!invitation.equals(confirmedInvitation)) {
                    confirmedInvitation = invitation;
                    notice("shared", invitation);
                }
            }
            if (target != null && target.equals(reply.target()) && reply.received() >= listenSince && reply.host() != null) {
                lastHostSeen = reply.received(); listeningHost = reply.host();
                KaraokeClient.updateRelay(listeningHost);
            }
        }
        if (target == null) return;
        if (lastHostSeen != 0 && now - lastHostSeen >= 10_000_000_000L) {
            listeningHost = null; KaraokeClient.suspendRelay();
        }
        boolean missingNativeHost = lastHostSeen == 0 && reply != null && target.equals(reply.target())
                && reply.received() >= listenSince && reply.host() == null && KaraokeClient.nativeListenAvailable()
                && !target.key().startsWith("lyr_");
        if (missingNativeHost || now - (lastHostSeen == 0 ? listenSince : lastHostSeen) >= 15_000_000_000L) {
            var previous = target;
            boolean resumeNative = lastHostSeen == 0 && !previous.key().startsWith("lyr_");
            stopListen();
            if (!resumeNative || !KaraokeClient.listenNative(previous.label())) notice("unavailable");
        }
    }

    private static void notice(String key, Object... args) { PresencePlatform.message("music.lyrica.listen." + key, args); }
    public static void updateGaze(UUID id) { GAZE.update(id != null && track(id) != null ? id : null, System.nanoTime()); }
    public static UUID pinned() { return GAZE.pinned(); }
    public static double cardOpacity() { return GAZE.opacity(System.nanoTime()); }
    public static PresenceTrack track(UUID id) {
        if (!RelayLyricsClient.preferences().isMusicPresenceEnabled() || id == null) return null;
        PresenceTrack t = fixture.get(id);
        if (t != null && t.alive(System.nanoTime())) return t;
        if (KaraokeClient.supported()) return KaraokeClient.track(id);
        return connection == null ? null : connection.track(id);
    }
    public static String statusKey() {
        if (!RelayLyricsClient.preferences().isMusicPresenceEnabled()) return "disabled";
        if (KaraokeClient.supported()) return "connected";
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
        scope = null; scopeTicks = 0;
        closeConnection();
        if (target != null && !target.key().startsWith("lyr_")) target = null;
    }
    private static void closeConnection() {
        MusicPresenceClient old = connection; connection = null; if (old != null) old.close();
        listeningHost = null; lastHostSeen = lastReply = listenSince = 0;
        KaraokeClient.endRelay();
        GAZE.clear(); fixture = Map.of(); ticks = 0; PresencePlatform.clearArtwork();
    }
    public static void probeTracks(Map<UUID, PresenceTrack> tracks) {
        if (!Boolean.getBoolean("lyrica.musicPresenceSelfTest")) throw new IllegalStateException("Presence fixture is opt-in");
        fixture = Map.copyOf(tracks);
    }
}
