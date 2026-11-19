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

    /** Keep nearby words together and alternate longer sky passages across the scene. */
    public static int readingSide(Cue cue, int groupIndex, boolean openSky) {
        int first = switch (cue.region()) {
            case LEFT -> -1;
            case RIGHT -> 1;
            default -> (cue.id().hashCode() & 1) == 0 ? -1 : 1;
        };
        int step = openSky ? groupIndex : groupIndex / 2;
        return (step & 1) == 0 ? first : -first;
    }

    public static boolean keepReadingHeading(double alignment) { return alignment >= .90; }

    public static List<Group> readingGroups(Cue cue, List<Integer> ends) {
        List<Group> groups = new ArrayList<>(ends.size());
        int first = 0;
        int lane = lane(cue.region(), Math.floorMod(cue.id().hashCode(), LANES.length));
        for (int index = 0; index < ends.size(); index++) {
            int end = ends.get(index);
            groups.add(new Group(index, first, end, LANES[(lane + index) % LANES.length], 0, 1));
            first = end;
        }
        return List.copyOf(groups);
    }

    /** Token indices remain in original singing order, even for right-to-left text. */
    public static List<Group> groups(Cue cue, List<WorldLyricsPlan.Token> tokens, Intensity intensity) {
        return groups(cue, tokens, intensity, null);
    }

    /** A movement snapshot is captured at a chunk boundary, never used to regroup singing text per frame. */
    public static List<Group> groups(Cue cue, List<WorldLyricsPlan.Token> tokens, Intensity intensity,
                                     WorldLyricsContext.Snapshot context) {
        if (tokens.isEmpty()) return List.of();
        intensity = intensity == null ? Intensity.DISCREET : intensity;
        int phase = Math.floorMod(cue.id().hashCode(), LANES.length);
        boolean centralAccent = cue.accents().stream().anyMatch(Accent::center);
        Region phraseRegion = centralAccent && cue.region() == Region.CENTER ? Region.AUTO : cue.region();
        int lane = lane(phraseRegion, phase);
        double angle = switch (intensity) { case DISCREET -> 5.5; case BALANCED -> 8; case EXPRESSIVE -> 10; };
        double variation = switch (intensity) { case DISCREET -> .65; case BALANCED -> .85; case EXPRESSIVE -> 1; };
        List<Integer> ends = cue.groupEnds();
        if (cue.composition() == Composition.WORD_BY_WORD)
            ends = WorldLyricsPresentation.groups(tokens, Composition.WORD_BY_WORD, intensity);
        else if (cue.composition() == Composition.COEXIST || !WorldLyricsPlan.hasWordTiming(cue.source().line()))
            ends = List.of(tokens.size());
        else if (context != null && context.shortGroups() && tokens.size() > 1)
            ends = shortGroupEnds(cue.id(), tokens);
        else if (ends.isEmpty() || ends.getLast() != tokens.size()) ends = List.of(tokens.size());
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

    private static List<Integer> shortGroupEnds(String cueId, List<WorldLyricsPlan.Token> tokens) {
        var starts = new ArrayList<Integer>();
        starts.add(0);
        for (int index = 1; index < tokens.size(); index++) if (tokens.get(index).startsWord()) starts.add(index);
        var ends = new ArrayList<Integer>();
        int firstWord = 0;
        while (firstWord < starts.size()) {
            int remaining = starts.size() - firstWord;
            int amount = remaining <= 3 ? remaining : remaining == 4 ? 2
                    : 2 + Math.floorMod(cueId.hashCode() + ends.size(), 2);
            firstWord += amount;
            ends.add(firstWord == starts.size() ? tokens.size() : starts.get(firstWord));
        }
        return List.copyOf(ends);
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
