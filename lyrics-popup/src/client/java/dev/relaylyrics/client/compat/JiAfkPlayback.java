package dev.relaylyrics.client.compat;

import dev.relaylyrics.client.CompatibilityManager;
import dev.relaylyrics.client.RelayLyricsClient;
import dev.relaylyrics.client.music.MusicService;
import dev.relaylyrics.protocol.music.MusicAction;
import dev.relaylyrics.protocol.music.PlaybackSnapshot;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Coordinates the external player with Ji without changing either player's saved settings. */
public final class JiAfkPlayback {
    private static final long RESUME_TIMEOUT = TimeUnit.SECONDS.toNanos(8);
    private static final Session SESSION = new Session();
    private static JiMusic jiMusic;

    private JiAfkPlayback() { }

    public static void onAfkStarted() {
        MusicService music = RelayLyricsClient.music();
        if (music == null || !RelayLyricsClient.preferences().isJiAfkResumeMusic()
                || !CompatibilityManager.jiAfkCinematicActive()) return;
        PlaybackSnapshot playback = music.snapshot();
        if (SESSION.begin(playback, music.playbackMethod(), System.nanoTime())) {
            music.command(MusicAction.PLAY_PAUSE);
            silenceJi();
        }
    }

    public static void tick(MusicService music, boolean inWorld) {
        if (!CompatibilityManager.jiAfkInstalled()) return;
        PlaybackSnapshot playback = music.snapshot();
        boolean active = inWorld && CompatibilityManager.jiAfkCinematicActive()
                && RelayLyricsClient.preferences().isJiAfkResumeMusic();
        if (SESSION.shouldPause(playback, music.playbackMethod(), active, System.nanoTime())) {
            music.command(MusicAction.PLAY_PAUSE);
        }
        if (blocksJiMusic()) silenceJi();
    }

    public static boolean blocksJiMusic() {
        MusicService music = RelayLyricsClient.music();
        if (music == null) return false;
        PlaybackSnapshot playback = music.snapshot();
        return hasRecentTrack(playback) && (playback.playing()
                || SESSION.awaitingResume(playback, music.playbackMethod(), System.nanoTime()));
    }

    private static boolean hasRecentTrack(PlaybackSnapshot playback) {
        return playback.title() != null && !playback.title().isBlank() && !"Nothing playing".equals(playback.title())
                && playback.observedAt() != null
                && Duration.between(playback.observedAt(), Instant.now()).abs().compareTo(Duration.ofSeconds(8)) < 0;
    }

    private static void silenceJi() {
        if (jiMusic == null) jiMusic = JiMusic.discover();
        jiMusic.stopIfPlaying();
    }

    static final class Session {
        private String source;
        private String device;
        private PlaybackSnapshot pausedTrack;
        private long deadline;
        private boolean resumed;
        private boolean returning;

        boolean begin(PlaybackSnapshot playback, String method, long now) {
            if (pausedTrack != null || !hasRecentTrack(playback) || playback.playing()
                    || !playback.capabilities().playPause()) return false;
            pausedTrack = playback;
            source = method;
            device = playback.deviceName();
            deadline = now + RESUME_TIMEOUT;
            return true;
        }

        boolean awaitingResume(PlaybackSnapshot playback, String method, long now) {
            return pausedTrack != null && !resumed && now - deadline < 0
                    && samePlayer(playback, method) && sameTrack(playback);
        }

        boolean shouldPause(PlaybackSnapshot playback, String method, boolean active, long now) {
            if (pausedTrack == null) return false;
            returning |= !active;
            if (!samePlayer(playback, method) || !hasRecentTrack(playback) || !playback.capabilities().playPause()) {
                clear();
                return false;
            }
            if (!resumed) {
                if (now - deadline >= 0 || !sameTrack(playback)) {
                    clear();
                    return false;
                }
                if (!playback.playing()) return false;
                resumed = true;
            }
            if (!playback.playing() || returning) {
                clear();
                return playback.playing();
            }
            return false;
        }

        private boolean samePlayer(PlaybackSnapshot playback, String method) {
            return Objects.equals(source, method) && Objects.equals(device, playback.deviceName());
        }

        private boolean sameTrack(PlaybackSnapshot playback) {
            if (pausedTrack.spotifyUri() != null && playback.spotifyUri() != null)
                return pausedTrack.spotifyUri().equals(playback.spotifyUri());
            return Objects.equals(pausedTrack.title(), playback.title())
                    && Objects.equals(pausedTrack.artist(), playback.artist())
                    && Objects.equals(pausedTrack.album(), playback.album());
        }

        private void clear() {
            pausedTrack = null;
            resumed = false;
            returning = false;
        }
    }

    private record JiMusic(Field playing, Method stop) {
        static JiMusic discover() {
            try {
                Class<?> manager = Class.forName("com.ji.afkcinematic.music.CinematicMusicManager");
                return new JiMusic(manager.getField("isOurMusicPlaying"), manager.getMethod("forceStop"));
            } catch (ReflectiveOperationException | LinkageError ignored) {
                return new JiMusic(null, null);
            }
        }

        void stopIfPlaying() {
            if (playing == null) return;
            try {
                if (playing.getBoolean(null)) stop.invoke(null);
            } catch (ReflectiveOperationException | LinkageError ignored) { }
        }
    }
}
