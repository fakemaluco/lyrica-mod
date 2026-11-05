package dev.fandroid.client.music;

import java.util.ArrayList;
import java.util.List;
import static dev.fandroid.client.music.WorldLyricsPresentation.*;

/** Stable word/phrase blocks. Their anchors and transforms are resolved once by the world adapter. */
public final class WorldLyricsStage {
    private WorldLyricsStage() { }

    public record Group(int index, int firstToken, int endToken, Region region,
                        double rotationDegrees, double scale) { }

    private static final Region[] LANES = { Region.LEFT, Region.TOP, Region.RIGHT, Region.BOTTOM };
    private static final double[] TILTS = { -.80, .55, -.35, .95 };
    private static final double[] SIZES = { 1.04, .94, 1.08, 1.0 };

    /** Token indices remain in original singing order, even for right-to-left text. */
    public static List<Group> groups(Cue cue, List<WorldLyricsPlan.Token> tokens, Intensity intensity) {
        if (tokens.isEmpty()) return List.of();
        intensity = intensity == null ? Intensity.DISCREET : intensity;
        int phase = Math.floorMod(cue.id().hashCode(), LANES.length);
        boolean centralAccent = cue.accents().stream().anyMatch(Accent::center);
        Region phraseRegion = centralAccent && cue.region() == Region.CENTER ? Region.AUTO : cue.region();
        int lane = lane(phraseRegion, phase);
        double angle = switch (intensity) { case DISCREET -> 5.5; case BALANCED -> 8; case EXPRESSIVE -> 10; };
        double variation = switch (intensity) { case DISCREET -> .65; case BALANCED -> .85; case EXPRESSIVE -> 1; };
        List<Integer> ends = cue.groupEnds();
        if (!WorldLyricsPlan.hasWordTiming(cue.source().line()) || ends.isEmpty()
                || ends.getLast() != tokens.size()) ends = List.of(tokens.size());
        List<Group> groups = new ArrayList<>();
        int first = 0;
        for (int index = 0; index < ends.size(); index++) {
            int end = ends.get(index);
            // Keep a word and its timed syllables in the same block.
            if (end < tokens.size() && !tokens.get(end).startsWord()) continue;
            int ordinal = groups.size();
            Region region = ordinal == 0 && phraseRegion != Region.AUTO ? phraseRegion
                    : LANES[(lane + ordinal) % LANES.length];
            for (int token = first; token < end; token++) {
                var accent = cue.accentAt(token);
                if (accent != null && accent.center()) { region = Region.CENTER; break; }
            }
            int variant = (phase + ordinal) % TILTS.length;
            double rotation = TILTS[variant] * angle;
            double scale = 1 + (SIZES[variant] - 1) * variation;
            if (cue.composition() == Composition.COEXIST) { rotation *= .55; scale = 1; }
            groups.add(new Group(index, first, end, region, rotation, scale));
            first = end;
        }
        return List.copyOf(groups);
    }

    /** Repeated upper-lane attempts alternate corners, including fallbacks from a blocked floor lane. */
    public static int topSideSign(Group group, int attempt) {
        return Math.floorMod(group.index() + attempt / 2, 2) == 0 ? -1 : 1;
    }

    private static int lane(Region region, int fallback) {
        for (int i = 0; i < LANES.length; i++) if (LANES[i] == region) return i;
        return fallback;
    }
}
