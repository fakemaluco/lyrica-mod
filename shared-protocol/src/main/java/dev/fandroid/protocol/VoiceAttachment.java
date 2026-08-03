package dev.fandroid.protocol;

import java.util.List;

public record VoiceAttachment(
        String attachmentId,
        int durationMillis,
        String codec,
        List<Float> waveform,
        List<byte[]> opusFrames
) {
    public VoiceAttachment {
        if (durationMillis < 1 || durationMillis > 60_000) {
            throw new IllegalArgumentException("voice message duration must be 1-60000ms");
        }
        waveform = waveform == null ? List.of() : List.copyOf(waveform);
        opusFrames = opusFrames == null ? List.of() : List.copyOf(opusFrames);
        if (!codec.equalsIgnoreCase("opus")) {
            throw new IllegalArgumentException("only Opus voice attachments are supported");
        }
        if (opusFrames.size() > 3_100) {
            throw new IllegalArgumentException("too many Opus frames");
        }
    }
}
