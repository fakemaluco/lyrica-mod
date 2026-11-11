package dev.fandroid.client.music;

import dev.fandroid.client.ClientPreferences;
import dev.fandroid.protocol.music.PlaybackSnapshot;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Sends a short-lived request to the mixer while the selected trigger is active. */
public final class AudioDucking {
    private static final boolean WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).startsWith("windows");
    private static final long VOICE_HOLD = TimeUnit.MILLISECONDS.toNanos(600);
    private static final long HEARTBEAT = TimeUnit.MILLISECONDS.toNanos(500);
    private static volatile boolean listenForVoice;
    private static volatile long lastSpeech;
    private Request previous;
    private long lastSent;

    public static boolean supported() {
        return WINDOWS;
    }

    private static final ClassValue<PlasmoGain> PLASMO_GAIN = new ClassValue<>() {
        @Override protected PlasmoGain computeValue(Class<?> type) {
            return new PlasmoGain(method(type, "getEffectiveVolume"), method(type, "canHear"));
        }
    };

    public static void heardPlasmo(short[] samples, Object source) {
        if (!listenForVoice) return;
        var gain = PLASMO_GAIN.get(source.getClass());
        try {
            if (gain.effective != null) {
                heard(samples, ((Number) gain.effective.invoke(source)).floatValue());
            } else if (gain.audible != null && Boolean.TRUE.equals(gain.audible.invoke(source))) {
                heard(samples, 1);
            }
        } catch (ReflectiveOperationException | LinkageError ignored) { }
    }

    private static Method method(Class<?> type, String name) {
        try { return type.getMethod(name); }
        catch (NoSuchMethodException ignored) { return null; }
    }

    private record PlasmoGain(Method effective, Method audible) { }

    public static void heard(short[] pcm, float gain) {
        if (!listenForVoice || pcm == null || pcm.length == 0 || gain <= 0) return;
        long energy = 0;
        for (short sample : pcm) energy += (long) sample * sample;
        if (energy / (double) pcm.length * gain * gain >= 160.0 * 160.0) {
            lastSpeech = System.nanoTime();
        }
    }

    public void tick(MediaBridgeConnector bridge, ClientPreferences preferences,
                     PlaybackSnapshot playback, String route, boolean inWorld) {
        long now = System.nanoTime();
        boolean enabled = supported() && preferences.isAudioDucking() && preferences.getDuckingVolume() < 1 && bridge.bridgeAlive();
        var trigger = preferences.getDuckingTrigger();
        listenForVoice = enabled && inWorld && trigger != ClientPreferences.DuckingTrigger.MUSIC;
        if (!listenForVoice) lastSpeech = 0;
        long heard = lastSpeech;
        boolean voice = listenForVoice && heard != 0 && now - heard < VOICE_HOLD;
        String foreground = enabled && !voice && trigger != ClientPreferences.DuckingTrigger.VOICE
                ? foreground(playback, route, bridge) : null;
        Request request = null;
        if (voice || foreground != null) {
            String target = switch (preferences.getDuckingTarget()) {
                case SPOTIFY -> "spotify";
                case APPLICATION -> preferences.getDuckingApplication().strip();
                case ALL -> "*";
            };
            // The custom field cannot select all apps accidentally.
            if (!target.isBlank() && (!target.equals("*") || preferences.getDuckingTarget() == ClientPreferences.DuckingTarget.ALL)) {
                request = new Request(target, preferences.getDuckingVolume(), ProcessHandle.current().pid(), foreground);
            }
        }

        if (java.util.Objects.equals(request, previous) && (request == null || now - lastSent < HEARTBEAT)) return;
        bridge.updateDucking(request);
        previous = request;
        lastSent = now;
    }

    private static String foreground(PlaybackSnapshot playback, String route, MediaBridgeConnector bridge) {
        if (!playback.playing() || playback.observedAt() == null
                || Instant.now().minusSeconds(6).isAfter(playback.observedAt())) return null;
        // Use the local transport identity for web playback only when the OS agrees on the track.
        String app = bridge.activeMediaApp();
        if (app != null && !app.isBlank() && !"account".equals(route)) return app;
        if ("account".equals(route)) return "spotify";
        String name = playback.deviceName();
        return name == null || name.isBlank() ? null : name;
    }

    public record Request(String target, double factor, long gamePid, String foreground) { }
}
