package dev.fandroid.client.music;

import dev.fandroid.protocol.lyrics.LyricAlignment;
import dev.fandroid.protocol.lyrics.LyricLine;
import dev.fandroid.protocol.lyrics.LyricsDocument;
import dev.fandroid.protocol.lyrics.LyricsSyncMode;

import java.util.*;

/** Declarative, song-clock driven direction shared by local and remote directors. */
public final class WorldLyricsPresentation {
    public static final int CONTRACT_VERSION = 3;
    public static final int MAX_GROUPS = 4, MAX_WORDS = 48;
    public static final long DEFAULT_HOLD_MILLIS = 1_500, FADE_MILLIS = 220, MAX_HOLD_MILLIS = 6_000;
    private static final double ENTRY_MILLIS = 240;
    private static final int LETTER_FADE_MILLIS = 125;
    private static final Entrance[] LOCAL_ENTRANCES = { Entrance.FADE, Entrance.RISE, Entrance.DEPTH, Entrance.FALL };

    private WorldLyricsPresentation() { }

    public enum Direction { AUTOMATIC, GEMINI }
    public enum Intensity { DISCREET, BALANCED, EXPRESSIVE }
    public enum Composition { FALLING_WORDS, PROGRESSIVE, COEXIST, STAIRCASE, SPOTLIGHT }
    public enum Region { AUTO, LEFT, RIGHT, TOP, BOTTOM, CENTER }
    public enum Entrance { FADE, FALL, RISE, POP, DEPTH }

    public static Region safeRegion(Region region, boolean cinematic) {
        return region == Region.CENTER && !cinematic ? Region.RIGHT : region;
    }

    /** A reproducible, local-only composition for the world preview and visual QA. */
    public static Cue demonstration(WorldLyricsPlan.WorldLine source, Composition composition) {
        var tokens = WorldLyricsPlan.tokens(source.line(), false);
        Entrance entrance = switch (composition) {
            case FALLING_WORDS -> Entrance.FALL;
            case STAIRCASE -> Entrance.RISE;
            case SPOTLIGHT -> Entrance.POP;
            default -> Entrance.FADE;
        };
        return new Cue(stableId(source, 0), source, composition, Region.RIGHT, entrance, false,
                DEFAULT_HOLD_MILLIS, composition == Composition.SPOTLIGHT && !tokens.isEmpty()
                ? List.of(new Accent(longestToken(tokens), 1.35, -1, true)) : List.of(),
                tokens.isEmpty() ? List.of() : groups(tokens, composition, Intensity.EXPRESSIVE));
    }

    public enum SongFont {
        DEFAULT(""), INTER("settings_compact"), OSWALD("gemini_oswald"), CAVEAT("gemini_caveat");
        public final String resource;
        SongFont(String resource) { this.resource = resource.isEmpty() ? "" : "relaylyrics:" + resource; }
    }

    public record Look(int color, SongFont font) {
        public static final Look DEFAULT = new Look(-1, SongFont.DEFAULT);
    }

    /** tokenIndex references WorldLyricsPlan.tokens(source.line(), false), including syllable fragments. */
    public record Accent(int tokenIndex, double scale, int color, boolean volumetric, Entrance entrance, boolean center) {
        public Accent(int tokenIndex, double scale, int color, boolean volumetric) {
            this(tokenIndex, scale, color, volumetric, null, false);
        }
        public Accent {
            if (tokenIndex < 0 || !Double.isFinite(scale)) throw new IllegalArgumentException("Invalid accent");
            scale = Math.clamp(scale, .75, 1.8);
            color = color < 0 ? -1 : color & 0xFFFFFF;
        }
    }

    /** groupEnds are strictly increasing, exclusive token indices, ending at the source token count. */
    public record Cue(String id, WorldLyricsPlan.WorldLine source, Composition composition, Region region,
                      Entrance entrance, boolean volumetric, long holdMillis,
                      List<Accent> accents, List<Integer> groupEnds, Look look) {
        public Cue(String id, WorldLyricsPlan.WorldLine source, Composition composition, Region region, Entrance entrance,
                   boolean volumetric, long holdMillis, List<Accent> accents, List<Integer> groupEnds) {
            this(id, source, composition, region, entrance, volumetric, holdMillis, accents, groupEnds, Look.DEFAULT);
        }
        public Cue withLook(Look value) {
            return new Cue(id, source, composition, region, entrance, volumetric, holdMillis, accents, groupEnds, value);
        }
        public Accent accentAt(int index) {
            for (var accent : accents) if (accent.tokenIndex() == index) return accent;
            return null;
        }
        public Cue {
            Objects.requireNonNull(id); Objects.requireNonNull(source);
            composition = composition == null ? Composition.PROGRESSIVE : composition;
            region = region == null ? Region.AUTO : region;
            entrance = entrance == null ? Entrance.FADE : entrance;
            holdMillis = Math.clamp(holdMillis, 0, MAX_HOLD_MILLIS);
            accents = accents == null ? List.of() : List.copyOf(accents);
            int size = WorldLyricsPlan.tokens(source.line(), false).size();
            if (groupEnds == null || groupEnds.isEmpty()) groupEnds = size == 0 ? List.of() : List.of(size);
            else {
                groupEnds = List.copyOf(groupEnds);
                int previous = 0;
                for (int end : groupEnds) {
                    if (end <= previous || end > size) throw new IllegalArgumentException("Invalid group boundary");
                    previous = end;
                }
                if (previous != size) throw new IllegalArgumentException("Incomplete groups");
            }
            Set<Integer> indices = new HashSet<>();
            for (Accent accent : accents) {
                if (accent.tokenIndex() >= size || !indices.add(accent.tokenIndex()))
                    throw new IllegalArgumentException("Invalid accent token");
            }
        }
        public long visibleUntil() { return saturatedAdd(source.line().endMillis(), holdMillis); }
    }

    /** Distances are fractions of the laid-out line height; the adapter supplies actual world scale. */
    public record Appearance(double alpha, double offsetY, double scale, double offsetX, double offsetDepth,
                             int color, boolean volumetric) { }
    public record Window(int firstToken, int lastStartedToken, int newestGroup) { }

    public static double lineVisibility(Cue cue, long now) {
        if (now < cue.source().line().startMillis() || now >= cue.visibleUntil()) return 0;
        return 1 - smooth(exitProgress(cue, now));
    }

    public static double exitProgress(Cue cue, long now) {
        long duration = Math.min(cue.source().backing() ? 180 : FADE_MILLIS,
                Math.max(1, cue.visibleUntil() - cue.source().line().startMillis()));
        return Math.clamp(1 - (cue.visibleUntil() - now) / (double) duration, 0, 1);
    }

    public record ExitMotion(double offsetY, double offsetDepth, double scale) { }

    /** Keep the exit visible in shader packs that give translucent text a hard alpha cutoff. */
    public static ExitMotion exitMotion(Cue cue, double progress, double glyphPosition) {
        double delay = Math.clamp(glyphPosition, 0, 1) * .16;
        double phase = Math.clamp((progress - delay) / (1 - delay), 0, 1);
        double eased = 1 - (1 - phase) * (1 - phase);
        if (cue.source().backing()) return new ExitMotion(-.52 * eased, 0, 1 - .24 * eased);
        return switch (cue.entrance()) {
            case RISE -> new ExitMotion(-.26 * eased, 0, 1 - .26 * eased);
            case FALL -> new ExitMotion(.26 * eased, 0, 1 - .26 * eased);
            case DEPTH -> new ExitMotion(0, -.32 * eased, 1 - .26 * eased);
            case POP, FADE -> new ExitMotion(0, 0, 1 - .34 * eased);
        };
    }

    private static long localHold(WorldLyricsPlan.WorldLine source) {
        String text = source.line().text();
        int characters = text.codePointCount(0, text.length());
        return (source.backing() ? 260 : 360) + Math.clamp((characters - 28L) * 14, 0, 760);
    }

    public static int groupIndex(Cue cue, int tokenIndex) {
        int low = 0, high = cue.groupEnds().size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (cue.groupEnds().get(mid) <= tokenIndex) low = mid + 1; else high = mid;
        }
        return low;
    }

    public static Appearance sample(Cue cue, int tokenIndex, long now, boolean animated) {
        return sample(cue, WorldLyricsPlan.tokens(cue.source().line(), false), tokenIndex, now, animated);
    }

    public static Appearance sample(Cue cue, List<WorldLyricsPlan.Token> tokens, int tokenIndex,
                                    long now, boolean animated) {
        return sample(cue, tokens, window(cue, tokens, now), tokenIndex, now, animated);
    }

    /** Compute once for a line per frame and reuse it for every glyph. */
    public static Window window(Cue cue, List<WorldLyricsPlan.Token> tokens, long now) {
        int lastSung = -1, lastRevealed = -1;
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).startMillis() <= now) lastSung = i;
            if (revealStart(cue, tokens, i) <= now) lastRevealed = i;
        }
        // A phrase/chunk may reveal future text. Keep the singing word inside the bounded window.
        int currentGroup = groupIndex(cue, Math.max(0, lastSung));
        int firstGroup = Math.max(0, currentGroup - MAX_GROUPS + 1);
        int first = firstTokenOfLastWords(tokens, Math.max(0, lastSung), MAX_WORDS);
        if (firstGroup > 0) first = Math.max(first, cue.groupEnds().get(firstGroup - 1));
        int words = 0, lastAllowed = first - 1;
        for (int i = first; i <= lastRevealed; i++) {
            if (groupIndex(cue, i) >= firstGroup + MAX_GROUPS) break;
            if (tokens.get(i).startsWord() && ++words > MAX_WORDS) break;
            lastAllowed = i;
        }
        return new Window(first, lastAllowed, lastAllowed < 0 ? 0 : groupIndex(cue, lastAllowed));
    }

    private static long revealStart(Cue cue, List<WorldLyricsPlan.Token> tokens, int tokenIndex) {
        Accent accent = cue.accentAt(tokenIndex);
        if (accent != null && (accent.entrance() != null || accent.center()))
            return tokens.get(tokenIndex).startMillis();
        if (!WorldLyricsPlan.hasWordTiming(cue.source().line()) || cue.composition() == Composition.COEXIST)
            return cue.source().line().startMillis();
        if (cue.composition() == Composition.PROGRESSIVE) {
            int group = groupIndex(cue, tokenIndex);
            int first = group == 0 ? 0 : cue.groupEnds().get(group - 1);
            return tokens.get(Math.min(first, tokens.size() - 1)).startMillis();
        }
        return tokens.get(tokenIndex).startMillis();
    }

    public static Appearance sample(Cue cue, List<WorldLyricsPlan.Token> tokens, Window window,
                                    int tokenIndex, long now, boolean animated) {
        if (tokenIndex < 0 || tokenIndex >= tokens.size()) return new Appearance(0, 0, 1, 0, 0, -1, cue.volumetric());
        return sampleAt(cue, window, tokenIndex, now, revealStart(cue, tokens, tokenIndex), ENTRY_MILLIS, animated);
    }

    /** Compress the existing grapheme timing into a short entrance while the sung token is active. */
    public static long letterStart(WorldLyricsPlan.Token token, long glyphStart, boolean timed, double lineFraction) {
        long duration = token.durationMillis();
        if (duration == 0) return token.startMillis();
        long delay = timed ? Math.round((glyphStart - token.startMillis()) * Math.min(1, 420.0 / duration))
                : Math.round(Math.min(ENTRY_MILLIS, duration * .5) * Math.clamp(lineFraction, 0, 1));
        return token.startMillis() + Math.clamp(delay, 0, duration);
    }

    /** Keep the window and accent from the cue, with a separate entrance clock for each letter. */
    public static Appearance sampleLetter(Cue cue, Window window, int tokenIndex, long now,
                                          long start, long end, boolean animated) {
        double duration = Math.clamp((end - start) * .65, 1, LETTER_FADE_MILLIS);
        return sampleAt(cue, window, tokenIndex, now, start, duration, animated);
    }

    private static Appearance sampleAt(Cue cue, Window window, int tokenIndex, long now,
                                       long start, double duration, boolean animated) {
        int group = groupIndex(cue, tokenIndex);
        double alpha = lineVisibility(cue, now);
        if (now < start || group < window.newestGroup() - MAX_GROUPS + 1
                || tokenIndex < window.firstToken() || tokenIndex > window.lastStartedToken()) alpha = 0;
        double progress = Math.clamp((now - start) / duration, 0, 1);
        double ease = 1 - Math.pow(1 - progress, 3);
        if (animated) alpha *= ease;
        double y = 0, depth = 0, scale = 1;
        Accent accent = cue.accentAt(tokenIndex);
        Entrance entrance = accent != null && accent.entrance() != null ? accent.entrance() : cue.entrance();
        if (animated) switch (entrance) {
            case FALL -> y = .35 * (1 - ease);
            case RISE -> y = -.35 * (1 - ease);
            case POP -> scale = .88 + .12 * ease + .08 * Math.sin(progress * Math.PI);
            case DEPTH -> depth = -.30 * (1 - ease);
            case FADE -> { }
        }
        // Geometry never depends on which earlier tokens are currently visible.
        if (cue.composition() == Composition.STAIRCASE) y -= (group % MAX_GROUPS) * .45;
        int color = cue.look().color();
        boolean volumetric = cue.volumetric();
        if (accent != null) {
            scale *= accent.scale();
            if (accent.color() >= 0) color = accent.color();
            volumetric |= accent.volumetric();
        }
        return new Appearance(alpha, y, scale, 0, depth, color, volumetric);
    }

    /** Enforces a word window without cutting a retained syllable from the start of its word. */
    private static int firstTokenOfLastWords(List<WorldLyricsPlan.Token> tokens, int last, int maximum) {
        int words = 0, first = 0;
        for (int i = last; i >= 0; i--) if (tokens.get(i).startsWord() && ++words > maximum) {
            first = i + 1;
            while (first < tokens.size() && !tokens.get(first).startsWord()) first++;
            break;
        }
        return first;
    }

    private static double smooth(double t) { t = Math.clamp(t, 0, 1); return t * t * (3 - 2 * t); }
    private static long saturatedAdd(long value, long increment) {
        return value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
    }

    /** Indexed once per document. Queries and sampling reconstruct deterministically after a seek. */
    public static final class Timeline {
        private final List<Cue> cues;
        private final Map<String, List<WorldLyricsPlan.Token>> tokens = new HashMap<>();
        private final Map<WorldLyricsPlan.WorldLine, String> ids = new HashMap<>();
        private final Map<WorldLyricsPlan.WorldLine, WorldLyricsPlan.WorldLine> backingLeads = new HashMap<>();
        private final Set<WorldLyricsPlan.WorldLine> pairedLeads;
        private final EnumMap<Intensity, Map<String, Cue>> locals = new EnumMap<>(Intensity.class);
        private final long[] maximumEnd;

        public Timeline(LyricsDocument document) {
            List<WorldLyricsPlan.WorldLine> lines = new ArrayList<>();
            if (document != null && document.metadata().syncMode() != LyricsSyncMode.STATIC) {
                Set<LyricLine> grouped = Collections.newSetFromMap(new IdentityHashMap<>());
                for (var group : document.groups()) {
                    var lead = new WorldLyricsPlan.WorldLine(group.lead(), group.alignment(), group.lead().background());
                    lines.add(lead);
                    grouped.add(group.lead());
                    for (var backing : group.backingVocals()) {
                        var voice = new WorldLyricsPlan.WorldLine(backing, group.alignment(), true);
                        lines.add(voice);
                        grouped.add(backing);
                        if (!lead.backing() && !lead.line().text().isBlank() && !backing.text().isBlank()
                                && backing.startMillis() < lead.line().endMillis() && lead.line().startMillis() < backing.endMillis()) {
                            backingLeads.put(voice, lead);
                        }
                    }
                }
                for (var line : document.lines()) if (!grouped.contains(line))
                    lines.add(new WorldLyricsPlan.WorldLine(line, LyricAlignment.NORMAL, line.background()));
            }
            lines.sort(Comparator.comparingLong(l -> l.line().startMillis()));
            List<Cue> built = new ArrayList<>();
            Map<String, Integer> duplicates = new HashMap<>();
            for (var line : lines) {
                if (line.line().text().isBlank() || line.line().endMillis() <= line.line().startMillis()) continue;
                String stem = stableId(line, 0);
                String id = stableId(line, duplicates.merge(stem, 1, Integer::sum) - 1);
                var fragments = WorldLyricsPlan.tokens(line.line(), false);
                if (fragments.isEmpty()) continue;
                tokens.put(id, fragments); ids.putIfAbsent(line, id);
                built.add(new Cue(id, line, Composition.PROGRESSIVE, Region.AUTO, Entrance.FADE,
                        false, DEFAULT_HOLD_MILLIS, List.of(), List.of(fragments.size())));
            }
            cues = List.copyOf(built);
            maximumEnd = new long[cues.size()]; long end = Long.MIN_VALUE;
            for (int i = 0; i < cues.size(); i++) maximumEnd[i] = end = Math.max(end, cues.get(i).source().line().endMillis());
            pairedLeads = Set.copyOf(backingLeads.values());
            for (Intensity intensity : Intensity.values()) {
                Map<String, Cue> map = new LinkedHashMap<>();
                int previousLeadStyle = -1, previousBackingStyle = -1;
                for (Cue base : cues) {
                    var random = new SplittableRandom(base.id().hashCode());
                    boolean backing = base.source().backing();
                    int previousStyle = backing ? previousBackingStyle : previousLeadStyle;
                    int style = random.nextInt(LOCAL_ENTRANCES.length - (previousStyle < 0 ? 0 : 1));
                    if (previousStyle >= 0 && style >= previousStyle) style++;
                    Entrance entrance = LOCAL_ENTRANCES[style];
                    if (backing) previousBackingStyle = style;
                    else previousLeadStyle = style;
                    Region region = random.nextBoolean() ? Region.LEFT : Region.RIGHT;
                    boolean singlePanel = backing || pairedLeads.contains(base.source());
                    Composition composition = !WorldLyricsPlan.hasWordTiming(base.source().line()) ? Composition.COEXIST
                            : singlePanel || intensity == Intensity.DISCREET ? Composition.PROGRESSIVE
                            : switch (random.nextInt(4)) {
                                case 0 -> Composition.FALLING_WORDS;
                                case 1 -> Composition.STAIRCASE;
                                case 2 -> Composition.SPOTLIGHT;
                                default -> Composition.PROGRESSIVE;
                            };
                    List<Accent> accents = composition == Composition.SPOTLIGHT
                            ? List.of(new Accent(longestToken(tokens.get(base.id())), 1.35, -1, intensity == Intensity.EXPRESSIVE)) : List.of();
                    var fragments = tokens.get(base.id());
                    var ends = singlePanel ? List.of(fragments.size()) : groups(fragments, composition, intensity);
                    map.put(base.id(), new Cue(base.id(), base.source(), composition, region, entrance, false,
                            localHold(base.source()), accents, ends));
                }
                locals.put(intensity, Collections.unmodifiableMap(map));
            }
        }

        public List<Cue> cues() { return cues; }
        public WorldLyricsPlan.WorldLine leadFor(WorldLyricsPlan.WorldLine backing) { return backingLeads.get(backing); }
        public boolean hasBacking(WorldLyricsPlan.WorldLine lead) { return pairedLeads.contains(lead); }
        public Map<String, Cue> local(Intensity intensity) { return locals.get(intensity == null ? Intensity.DISCREET : intensity); }
        public List<WorldLyricsPlan.Token> tokens(Cue cue) { return tokens.getOrDefault(cue.id(), List.of()); }
        public Cue cueFor(WorldLyricsPlan.WorldLine line, Map<String, Cue> overrides, Intensity intensity) {
            String id = ids.get(line);
            if (id == null) return null;
            Cue original = local(intensity).get(id);
            return directed(original, overrides);
        }
        private Cue directed(Cue original, Map<String, Cue> overrides) {
            String id = original.id();
            Cue directed = overrides == null ? null : overrides.get(id);
            return directed != null && directed.source().equals(original.source()) ? directed : original;
        }

        public List<Cue> visible(long now, Map<String, Cue> overrides, Intensity intensity) {
            int low = 0, high = cues.size();
            while (low < high) {
                int mid = (low + high) >>> 1;
                if (cues.get(mid).source().line().startMillis() <= now) low = mid + 1; else high = mid;
            }
            List<Cue> candidates = new ArrayList<>();
            for (int i = low - 1; i >= 0 && saturatedAdd(maximumEnd[i], MAX_HOLD_MILLIS) > now; i--) {
                Cue cue = directed(local(intensity).get(cues.get(i).id()), overrides);
                if (cue != null && now < cue.visibleUntil()) candidates.add(cue);
            }
            candidates.sort(Comparator.comparingInt((Cue cue) -> now < cue.source().line().endMillis() ? 0 : 1)
                    .thenComparing(cue -> cue.source().backing())
                    .thenComparing(Comparator.comparingLong((Cue cue) -> cue.source().line().startMillis()).reversed()));
            List<Cue> selected = new ArrayList<>();
            int groups = 0, words = 0;
            boolean retainedLead = false, retainedBacking = false;
            for (Cue cue : candidates) {
                if (selected.size() >= MAX_GROUPS) break;
                boolean old = now >= cue.source().line().endMillis();
                boolean backing = cue.source().backing();
                if (intensity == Intensity.DISCREET && old && (backing ? retainedBacking : retainedLead)
                        && (overrides == null || !overrides.containsKey(cue.id()))) continue;
                int[] cost = cost(cue, tokens.get(cue.id()), now);
                if (groups + cost[0] > MAX_GROUPS || words + cost[1] > MAX_WORDS) continue;
                selected.add(cue); groups += cost[0]; words += cost[1];
                if (old && backing) retainedBacking = true;
                if (old && !backing) retainedLead = true;
            }
            selected.sort(Comparator.comparingLong(cue -> cue.source().line().startMillis()));
            return List.copyOf(selected);
        }
    }

    public static String stableId(WorldLyricsPlan.WorldLine source, int occurrence) {
        var line = source.line();
        return "l" + line.startMillis() + "-" + line.endMillis() + "-"
                + Integer.toUnsignedString(Objects.hash(line.text(), line.agent(), source.alignment().name(), source.backing()), 36)
                + "-" + occurrence;
    }

    private static int longestToken(List<WorldLyricsPlan.Token> tokens) {
        int best = 0;
        for (int i = 1; i < tokens.size(); i++) if (tokens.get(i).text().codePointCount(0, tokens.get(i).text().length())
                > tokens.get(best).text().codePointCount(0, tokens.get(best).text().length())) best = i;
        return best;
    }

    static List<Integer> groups(List<WorldLyricsPlan.Token> tokens, Composition composition, Intensity intensity) {
        if (tokens.isEmpty()) return List.of();
        if (composition == Composition.COEXIST) return List.of(tokens.size());
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) if (i == 0 || tokens.get(i).startsWord()) starts.add(i);
        starts.add(tokens.size());
        List<Integer> ends = new ArrayList<>();
        int accent = longestToken(tokens), accentWord = 0;
        for (int i = 0; i + 1 < starts.size(); i++) if (starts.get(i) <= accent) accentWord = i;
        int word = 0, group = 0;
        while (word + 1 < starts.size()) {
            int length = switch (composition) {
                case FALLING_WORDS, STAIRCASE -> 1;
                case SPOTLIGHT -> word == accentWord ? 1 : group % 3 == 0 ? 2 : group % 3 == 1 ? 1 : 3;
                default -> intensity == Intensity.DISCREET ? 3 : 2;
            };
            // A highlighted word gets its own block; never divide its timed syllables.
            if (composition == Composition.SPOTLIGHT && word < accentWord) length = Math.min(length, accentWord - word);
            word = Math.min(starts.size() - 1, word + length);
            ends.add(starts.get(word)); group++;
        }
        return List.copyOf(ends);
    }

    private static int[] cost(Cue cue, List<WorldLyricsPlan.Token> tokens, long now) {
        Set<Integer> groups = new HashSet<>(); int words = 0;
        Window window = window(cue, tokens, now);
        for (int i = 0; i < tokens.size(); i++) if (sample(cue, tokens, window, i, now, false).alpha() > 0) {
            groups.add(groupIndex(cue, i));
            if (tokens.get(i).startsWord()) {
                // Line-only text is a single timed unit, but still consumes its actual word budget.
                String text = tokens.get(i).text().strip();
                words += text.isEmpty() ? 0 : text.split("\\s+").length;
            }
        }
        return new int[]{ Math.min(MAX_GROUPS, groups.size()), Math.min(MAX_WORDS, words) };
    }
}
