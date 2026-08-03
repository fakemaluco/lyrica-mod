package dev.fandroid.protocol.lyrics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public record LyricsDocument(
        List<LyricLine> lines,
        List<LyricVocalGroup> groups,
        LyricsMetadata metadata
) {
    public LyricsDocument(List<LyricLine> lines) {
        this(lines, deriveGroups(lines), inferMetadata(lines));
    }

    public LyricsDocument(List<LyricVocalGroup> groups, LyricsMetadata metadata) {
        this(flatten(groups), groups, metadata);
    }

    public LyricsDocument {
        lines = lines == null ? List.of() : lines.stream()
                .sorted(Comparator.comparingLong(LyricLine::startMillis)
                        .thenComparing(LyricLine::background))
                .toList();
        groups = groups == null ? deriveGroups(lines) : groups.stream()
                .sorted(Comparator.comparingLong(LyricVocalGroup::startMillis))
                .toList();
        metadata = metadata == null ? inferMetadata(lines) : metadata;
    }

    public int lineAt(long positionMillis) {
        int candidate = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startMillis() > positionMillis) {
                break;
            }
            if (!lines.get(i).background()) candidate = i;
        }
        if (candidate >= 0) return candidate;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startMillis() > positionMillis) break;
            candidate = i;
        }
        return candidate;
    }

    public List<LyricVocalGroup> activeGroupsAt(long positionMillis) {
        return groups.stream().filter(group -> group.activeAt(positionMillis)).toList();
    }

    public LyricsDocument withMetadata(LyricsMetadata newMetadata) {
        return new LyricsDocument(lines, groups, newMetadata);
    }

    private static LyricsMetadata inferMetadata(List<LyricLine> lines) {
        boolean wordSynced = lines != null && lines.stream().anyMatch(line -> line.words().size() > 1);
        return new LyricsMetadata(LyricsSource.UNKNOWN,
                wordSynced ? LyricsSyncMode.WORD : LyricsSyncMode.LINE, "", "", "", "");
    }

    private static List<LyricLine> flatten(List<LyricVocalGroup> groups) {
        if (groups == null) return List.of();
        List<LyricLine> result = new ArrayList<>();
        for (LyricVocalGroup group : groups) {
            result.add(group.lead());
            result.addAll(group.backingVocals());
        }
        return result;
    }

    private static List<LyricVocalGroup> deriveGroups(List<LyricLine> lines) {
        if (lines == null || lines.isEmpty()) return List.of();
        List<LyricLine> sorted = lines.stream()
                .sorted(Comparator.comparingLong(LyricLine::startMillis)
                        .thenComparing(LyricLine::background))
                .toList();
        List<LyricVocalGroup> result = new ArrayList<>();
        LyricLine currentLead = null;
        List<LyricLine> backgrounds = new ArrayList<>();
        String firstAgent = "";
        for (LyricLine line : sorted) {
            if (!line.background()) {
                if (currentLead != null) result.add(group(currentLead, backgrounds, firstAgent));
                currentLead = line;
                backgrounds = new ArrayList<>();
                if (firstAgent.isBlank()) firstAgent = line.agent();
            } else if (currentLead != null && line.startMillis() <= currentLead.endMillis()) {
                backgrounds.add(line);
            }
        }
        if (currentLead != null) result.add(group(currentLead, backgrounds, firstAgent));
        return List.copyOf(result);
    }

    private static LyricVocalGroup group(LyricLine lead, List<LyricLine> backgrounds, String firstAgent) {
        long start = lead.startMillis();
        long end = lead.endMillis();
        for (LyricLine background : backgrounds) {
            start = Math.min(start, background.startMillis());
            end = Math.max(end, background.endMillis());
        }
        LyricAlignment alignment = !firstAgent.isBlank() && !firstAgent.equals(lead.agent())
                ? LyricAlignment.OPPOSITE : LyricAlignment.NORMAL;
        return new LyricVocalGroup(start, end, lead, backgrounds, alignment);
    }
}
