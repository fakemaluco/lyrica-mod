package dev.fandroid.client.voice;

import dev.fandroid.protocol.VoiceAttachment;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

public final class VoiceNoteController {
    private boolean recording;

    public boolean available() {
        return FabricLoader.getInstance().isModLoaded("voicechat");
    }

    public boolean recording() { return recording; }

    public void start() {
        invoke("startVoiceNote", new Class<?>[0]);
        recording = true;
    }

    @SuppressWarnings("unchecked")
    public VoiceAttachment finish() {
        Object note = invoke("finishVoiceNote", new Class<?>[0]);
        recording = false;
        try {
            int duration = (int) note.getClass().getMethod("durationMillis").invoke(note);
            List<byte[]> frames = (List<byte[]>) note.getClass().getMethod("opusFrames").invoke(note);
            List<Float> waveform = (List<Float>) note.getClass().getMethod("waveform").invoke(note);
            return new VoiceAttachment(UUID.randomUUID().toString(), duration, "opus", waveform, frames);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not read recorded voice note", e);
        }
    }

    public void play(VoiceAttachment attachment) {
        invoke("decodeAndPlay", new Class<?>[]{UUID.class, List.class},
                UUID.nameUUIDFromBytes(("fandroid-note:" + attachment.attachmentId())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)), attachment.opusFrames());
    }

    private static Object invoke(String method, Class<?>[] types, Object... arguments) {
        if (!FabricLoader.getInstance().isModLoaded("voicechat")) {
            throw new IllegalStateException("Simple Voice Chat is unavailable");
        }
        try {
            Class<?> plugin = Class.forName("dev.fandroid.voice.FanDroidVoicePlugin");
            Method target = plugin.getMethod(method, types);
            return target.invoke(null, arguments);
        } catch (ReflectiveOperationException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Voice Chat operation failed", e);
        }
    }
}
