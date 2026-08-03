package dev.fandroid.protocol.lyrics;

import java.util.List;

public record LyricVocalGroup(
        long startMillis,
        long endMillis,
        LyricLine lead,
        List<LyricLine> backingVocals,
        LyricAlignment alignment
) {
    public LyricVocalGroup {
        if (lead == null) throw new IllegalArgumentException("lead vocal is required");
        backingVocals = backingVocals == null ? List.of() : List.copyOf(backingVocals);
        alignment = alignment == null ? LyricAlignment.NORMAL : alignment;
        startMillis = Math.min(startMillis, lead.startMillis());
        endMillis = Math.max(endMillis, lead.endMillis());
        for (LyricLine backing : backingVocals) {
            startMillis = Math.min(startMillis, backing.startMillis());
            endMillis = Math.max(endMillis, backing.endMillis());
        }
    }

    public boolean activeAt(long positionMillis) {
        return positionMillis >= startMillis && positionMillis <= endMillis;
    }
}
