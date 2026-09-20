package dev.fandroid.voice;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatClientApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;
import de.maxhenkel.voicechat.api.events.ClientSoundEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class FanDroidVoicePlugin implements VoicechatPlugin {
    private static final int SAMPLE_RATE = 48_000;
    private static final int MAX_SAMPLES = SAMPLE_RATE * 60;
    private static volatile VoicechatApi api;
    private static volatile VoicechatClientApi clientApi;
    private static final VoiceNoteRecorder RECORDER = new VoiceNoteRecorder();
    private static final Map<UUID, StaticAudioChannel> CALL_CHANNELS = new ConcurrentHashMap<>();

    @Override
    public String getPluginId() {
        return "fandroid";
    }

    @Override
    public void initialize(VoicechatApi voicechatApi) {
        api = voicechatApi;
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(ClientSoundEvent.class, this::onClientSound);
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophonePacket);
    }

    private void onClientSound(ClientSoundEvent event) {
        clientApi = event.getVoicechat();
        if (RECORDER.recording()) {
            event.cancel();
            RECORDER.append(event.getRawAudio());
        }
    }

    private void onMicrophonePacket(MicrophonePacketEvent event) {
        if (event.getSenderConnection() == null) return;
        UUID senderId = event.getSenderConnection().getPlayer().getUuid();
        Set<UUID> targets = VoiceCallRegistry.targets(senderId);
        if (targets.isEmpty()) return;
        event.cancel();
        if (VoiceCallRegistry.isMuted(senderId)) return;
        UUID channelId = VoiceCallRegistry.channelId(senderId);
        if (channelId == null) return;
        StaticAudioChannel channel = CALL_CHANNELS.computeIfAbsent(channelId,
                id -> event.getVoicechat().createStaticAudioChannel(id));
        channel.clearTargets();
        for (UUID target : targets) {
            var connection = event.getVoicechat().getConnectionOf(target);
            if (connection != null && connection.isConnected()) channel.addTarget(connection);
        }
        channel.send(event.getPacket());
    }

    public static void startVoiceNote() {
        if (api == null) throw new IllegalStateException("Simple Voice Chat is unavailable");
        RECORDER.start();
    }

    public static VoiceNote finishVoiceNote() {
        short[] pcm = RECORDER.finish();
        if (pcm.length == 0) throw new IllegalStateException("no voice audio was recorded");
        List<byte[]> opusFrames = new ArrayList<>();
        OpusEncoder encoder = api.createEncoder();
        try {
            for (int offset = 0; offset < pcm.length; offset += 960) {
                short[] frame = Arrays.copyOfRange(pcm, offset, Math.min(offset + 960, pcm.length));
                if (frame.length < 960) frame = Arrays.copyOf(frame, 960);
                opusFrames.add(encoder.encode(frame));
            }
        } finally {
            encoder.close();
        }
        return new VoiceNote(pcm.length * 1_000 / SAMPLE_RATE, opusFrames, waveform(pcm));
    }

    public static void playLocal(UUID channelId, short[] pcm) {
        if (clientApi == null) throw new IllegalStateException("Simple Voice Chat client is unavailable");
        clientApi.createStaticAudioChannel(channelId).play(pcm);
    }

    public static void decodeAndPlay(UUID channelId, List<byte[]> opusFrames) {
        if (clientApi == null || api == null) throw new IllegalStateException("Simple Voice Chat client is unavailable");
        var decoder = api.createDecoder();
        try {
            List<short[]> decoded = new ArrayList<>(opusFrames.size());
            int samples = 0;
            for (byte[] frame : opusFrames) {
                short[] pcm = decoder.decode(frame);
                decoded.add(pcm);
                samples += pcm.length;
            }
            short[] all = new short[samples];
            int offset = 0;
            for (short[] frame : decoded) { System.arraycopy(frame, 0, all, offset, frame.length); offset += frame.length; }
            playLocal(channelId, all);
        } finally {
            decoder.close();
        }
    }

    private static List<Float> waveform(short[] pcm) {
        int buckets = Math.min(96, Math.max(1, pcm.length / 960));
        List<Float> values = new ArrayList<>(buckets);
        for (int bucket = 0; bucket < buckets; bucket++) {
            int start = bucket * pcm.length / buckets;
            int end = (bucket + 1) * pcm.length / buckets;
            long sum = 0;
            for (int i = start; i < end; i++) sum += Math.abs((int) pcm[i]);
            values.add((float) (sum / (double) Math.max(1, end - start) / Short.MAX_VALUE));
        }
        return values;
    }

    private static final class VoiceNoteRecorder {
        private final List<short[]> chunks = new ArrayList<>();
        private int samples;
        private boolean recording;

        synchronized void start() { chunks.clear(); samples = 0; recording = true; }
        synchronized boolean recording() { return recording; }
        synchronized void append(short[] audio) {
            if (!recording) return;
            int remaining = MAX_SAMPLES - samples;
            if (remaining <= 0) { recording = false; return; }
            short[] copy = Arrays.copyOf(audio, Math.min(audio.length, remaining));
            chunks.add(copy);
            samples += copy.length;
            if (samples >= MAX_SAMPLES) recording = false;
        }
        synchronized short[] finish() {
            recording = false;
            short[] pcm = new short[samples];
            int offset = 0;
            for (short[] chunk : chunks) { System.arraycopy(chunk, 0, pcm, offset, chunk.length); offset += chunk.length; }
            chunks.clear();
            samples = 0;
            return pcm;
        }
    }

    public record VoiceNote(int durationMillis, List<byte[]> opusFrames, List<Float> waveform) { }
}
