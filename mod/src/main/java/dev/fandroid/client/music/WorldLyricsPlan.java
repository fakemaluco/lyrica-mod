package dev.fandroid.client.music;

import dev.fandroid.protocol.lyrics.LyricAlignment;
import dev.fandroid.protocol.lyrics.LyricLine;
import dev.fandroid.protocol.lyrics.LyricVocalGroup;
import dev.fandroid.protocol.lyrics.LyricsDocument;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Pure scene planning for the experimental client-side lyrics placed in the world. */
public final class WorldLyricsPlan {
    public static final long ENTER_LEAD_MILLIS = 180;
    public static final long EXIT_TAIL_MILLIS = 450;
    private static final int MAX_VISIBLE_LINES = 3;

    private WorldLyricsPlan() { }

    public static List<WorldLine> visibleLines(LyricsDocument document, long now) {
        if (document == null) return List.of();
        List<WorldLine> result = new ArrayList<>();
        if (document.groups().isEmpty()) {
            for (LyricLine line : document.lines()) {
                addIfVisible(result, line, LyricAlignment.NORMAL, false, now);
            }
            result.sort(Comparator.comparingLong(line -> line.line().startMillis()));
            if (result.size() <= MAX_VISIBLE_LINES) return List.copyOf(result);
            return List.copyOf(result.subList(result.size() - MAX_VISIBLE_LINES, result.size()));
        }
        for (LyricVocalGroup group : document.groups()) {
            addIfVisible(result, group.lead(), group.alignment(), false, now);
            for (LyricLine backing : group.backingVocals()) {
                addIfVisible(result, backing, group.alignment(), true, now);
            }
        }
        result.sort(Comparator.comparingLong(line -> line.line().startMillis()));
        if (result.size() <= MAX_VISIBLE_LINES) return List.copyOf(result);
        return List.copyOf(result.subList(result.size() - MAX_VISIBLE_LINES, result.size()));
    }

    private static void addIfVisible(List<WorldLine> result, LyricLine line,
                                     LyricAlignment alignment, boolean backing, long now) {
        if (now < line.startMillis() - ENTER_LEAD_MILLIS
                || now > line.endMillis() + EXIT_TAIL_MILLIS) return;
        result.add(new WorldLine(line, alignment, backing));
    }

    public static List<Token> tokens(LyricLine line, boolean romanized) {
        List<Token> result = new ArrayList<>();
        for (LyricsRenderText.VisualWord word : LyricsRenderText.visualWords(line, romanized)) {
            boolean first = true;
            for (LyricsRenderText.Fragment fragment : word.fragments()) {
                result.add(new Token(fragment.text(), fragment.timing().startMillis(),
                        fragment.timing().endMillis(), first));
                first = false;
            }
        }
        return List.copyOf(result);
    }

    public static double lineVisibility(LyricLine line, long now) {
        double enter = LyricsAnimation.smooth((now - (line.startMillis() - ENTER_LEAD_MILLIS)) / 320.0);
        double leave = LyricsAnimation.smooth((line.endMillis() + EXIT_TAIL_MILLIS - now) / 450.0);
        return Math.min(enter, leave);
    }

    public record WorldLine(LyricLine line, LyricAlignment alignment, boolean backing) { }

    public record Token(String text, long startMillis, long endMillis, boolean startsWord) {
        public long durationMillis() { return Math.max(0, endMillis - startMillis); }
        public boolean activeAt(long now) { return now >= startMillis && now < endMillis; }
        public boolean sungAt(long now) { return now >= endMillis; }

        /** Only held words or syllables move out of the ground plane. */
        public double emphasis(long now) {
            if (!LyricsAnimation.usesPerGlyphMotion(durationMillis()) || !activeAt(now)) return 0;
            return LyricsAnimation.activeGlowEnvelope(
                    LyricsAnimation.timedProgress(now, startMillis, endMillis));
        }
    }
}
