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
    private static final long WORD_TAIL_MILLIS = 1_300;
    private static final long SAME_LINE_WORD_TAIL_MILLIS = 1_500;
    private static final long CHUNK_TAIL_MILLIS = 700;
    public static final int MAX_VISIBLE_WORD_GROUPS = 12;
    public static final int MAX_VISIBLE_CHUNKS = 3;
    private static final int LETTER_FADE_MILLIS = 125;
    private static final Entrance[] LOCAL_ENTRANCES = { Entrance.FADE, Entrance.RISE, Entrance.DEPTH, Entrance.FALL };

    private WorldLyricsPresentation() { }

    public enum Direction { AUTOMATIC, GEMINI }
    public enum Intensity { DISCREET, BALANCED, EXPRESSIVE }
    public enum Reveal { PHRASE_AND_WORD, WORD_BY_WORD, READING_LINE }
    public enum Composition { FALLING_WORDS, PROGRESSIVE, COEXIST, STAIRCASE, SPOTLIGHT, WORD_BY_WORD }

    public static Cue withReveal(Cue cue, Reveal reveal) {
        if (reveal == Reveal.PHRASE_AND_WORD || reveal == Reveal.READING_LINE) {
            if (cue.composition() == Composition.COEXIST && cue.groupEnds().size() <= 1) return cue;
            return new Cue(cue.id(), cue.source(), Composition.COEXIST, cue.region(), cue.entrance(),
                    cue.volumetric(), cue.holdMillis(), cue.accents(), List.of(), cue.look());
        }
        if (reveal != Reveal.WORD_BY_WORD || cue.composition() == Composition.WORD_BY_WORD) return cue;
        return new Cue(cue.id(), cue.source(), Composition.WORD_BY_WORD, cue.region(), cue.entrance(),
                cue.volumetric(), Math.max(cue.holdMillis(), WORD_TAIL_MILLIS), cue.accents(),
                groups(WorldLyricsPlan.wordTokens(cue.source().line(), false), Composition.WORD_BY_WORD, Intensity.DISCREET), cue.look());
    }

    /** Playback time freezes on pause, so exits run on the independent rendering clock. */
    public static final class PauseExit {
        private long pausedAt = Long.MIN_VALUE;

        public double progress(long nowNanos, boolean playing, boolean animations) {
            if (playing) { reset(); return 0; }
            if (pausedAt == Long.MIN_VALUE) pausedAt = nowNanos;
            return animations ? Math.clamp((nowNanos - pausedAt) / 350_000_000.0, 0, 1) : 1;
        }

        public void reset() { pausedAt = Long.MIN_VALUE; }
    }
    public enum Region { AUTO, LEFT, RIGHT, TOP, BOTTOM, CENTER }
    public enum Entrance { FADE, FALL, RISE, POP, DEPTH }

    public static Region safeRegion(Region region, boolean cinematic) {
        return region == Region.CENTER && !cinematic ? Region.RIGHT : region;
    }

    /** A reproducible, local-only composition for the world preview and visual QA. */
    public static Cue demonstration(WorldLyricsPlan.WorldLine source, Composition composition) {
        var tokens = composition == Composition.WORD_BY_WORD
                ? WorldLyricsPlan.wordTokens(source.line(), false) : WorldLyricsPlan.tokens(source.line(), false);
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
            int size = (composition == Composition.WORD_BY_WORD
                    ? WorldLyricsPlan.wordTokens(source.line(), false) : WorldLyricsPlan.tokens(source.line(), false)).size();
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
    public record WordSpan(int firstToken, int endToken, long startsAt, long releasesAt, long expiresAt,
                           double entranceMillis) { }

    public record Window(int firstToken, int lastStartedToken, int newestGroup, List<WordSpan> words) {
        public Window(int firstToken, int lastStartedToken, int newestGroup) {
            this(firstToken, lastStartedToken, newestGroup, List.of());
        }
    }

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
        var line = cue.source().line();
        var tokens = cue.composition() == Composition.WORD_BY_WORD
                ? WorldLyricsPlan.wordTokens(line, false) : WorldLyricsPlan.tokens(line, false);
        return sample(cue, tokens, tokenIndex, now, animated);
    }

    public static Appearance sample(Cue cue, List<WorldLyricsPlan.Token> tokens, int tokenIndex,
                                    long now, boolean animated) {
        return sample(cue, tokens, window(cue, tokens, now, animated), tokenIndex, now, animated);
    }

    /** A verse on one panel stays intact; its clock only advances the sung highlight. */
    public static Window fullPhraseWindow(List<WorldLyricsPlan.Token> tokens) {
        return new Window(0, tokens.size() - 1, 0);
    }

    /** Compute once for a line per frame and reuse it for every glyph. */
    public static Window window(Cue cue, List<WorldLyricsPlan.Token> tokens, long now) {
        return window(cue, tokens, now, false);
    }

    public static Window window(Cue cue, List<WorldLyricsPlan.Token> tokens, long now, boolean animated) {
        return window(cue, tokens, now, animated, Long.MAX_VALUE);
    }

    public static Window window(Cue cue, List<WorldLyricsPlan.Token> tokens, long now, boolean animated, long nextLineStarts) {
        if (cue.composition() == Composition.WORD_BY_WORD) return readingWindow(tokens, List.of(), now, animated, nextLineStarts);
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

    /** Follow the song through separately anchored words or short groups of words. */
    public static Window readingWindow(List<WorldLyricsPlan.Token> tokens, List<Integer> groupEnds,
                                       long now, boolean animated, long nextLineStarts) {
        return readingWindow(tokens, groupEnds, now, animated, nextLineStarts, 1);
    }

    public static long readingTailMillis(boolean words, double animationSpeed) {
        return Math.round((words ? WORD_TAIL_MILLIS : CHUNK_TAIL_MILLIS) / Math.clamp(animationSpeed, .25, 2));
    }

    public static Window readingWindow(List<WorldLyricsPlan.Token> tokens, List<Integer> groupEnds,
                                       long now, boolean animated, long nextLineStarts, double animationSpeed) {
        boolean words = groupEnds.isEmpty();
        int maxGroups = words ? MAX_VISIBLE_WORD_GROUPS : MAX_VISIBLE_CHUNKS;
        double speed = Math.clamp(animationSpeed, .25, 2);
        long lineTail = readingTailMillis(words, speed);
        int first = 0, group = 0;
        Window visible = new Window(0, -1, 0);
        var recent = new ArrayDeque<WordSpan>(maxGroups);
        while (first < tokens.size() && tokens.get(first).startMillis() <= now
                && tokens.get(first).startMillis() < nextLineStarts) {
            int end = groupEnds.isEmpty() ? first + 1 : groupEnds.get(group);
            long endMillis = tokens.get(first).endMillis();
            if (groupEnds.isEmpty()) while (end < tokens.size() && !tokens.get(end).startsWord()) end++;
            for (int token = first + 1; token < end; token++) endMillis = Math.max(endMillis, tokens.get(token).endMillis());
            long nextStart = end < tokens.size() ? tokens.get(end).startMillis() : Long.MAX_VALUE;
            long release = Math.min(Math.min(nextStart, nextLineStarts), endMillis);
            long tail = words && nextStart < nextLineStarts ? Math.round(SAME_LINE_WORD_TAIL_MILLIS / speed) : lineTail;
            long expires = Math.min(saturatedAdd(release, tail), saturatedAdd(nextLineStarts, lineTail));
            if (animated && now < expires) {
                if (recent.size() == maxGroups) recent.removeFirst();
                long start = tokens.get(first).startMillis();
                double entrance = Math.min(165 / speed, Math.max(35, (release - start) * .48));
                recent.addLast(new WordSpan(first, end, start, release, expires, entrance));
            }
            visible = new Window(first, now < release ? end - 1 : first - 1, group++);
            first = end;
        }
        if (!recent.isEmpty())
            return new Window(recent.getFirst().firstToken(), recent.getLast().endToken() - 1,
                    visible.newestGroup(), List.copyOf(recent));
        return visible;
    }

    private static long revealStart(Cue cue, List<WorldLyricsPlan.Token> tokens, int tokenIndex) {
        if (cue.composition() == Composition.WORD_BY_WORD) {
            while (tokenIndex > 0 && !tokens.get(tokenIndex).startsWord()) tokenIndex--;
            return tokens.get(tokenIndex).startMillis();
        }
        if (cue.composition() == Composition.COEXIST) return cue.source().line().startMillis();
        Accent accent = cue.accentAt(tokenIndex);
        if (accent != null && (accent.entrance() != null || accent.center()))
            return tokens.get(tokenIndex).startMillis();
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
        return sampleAt(cue, window, tokenIndex, now, revealStart(cue, tokens, tokenIndex), ENTRY_MILLIS, animated, false);
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
        return sampleAt(cue, window, tokenIndex, now, start, duration, animated, true);
    }

    private static Appearance sampleAt(Cue cue, Window window, int tokenIndex, long now,
                                       long start, double duration, boolean animated, boolean letter) {
        if (cue.composition() == Composition.WORD_BY_WORD) return sampleReading(cue, window, tokenIndex, now, animated);
        int group = groupIndex(cue, tokenIndex);
        double alpha = lineVisibility(cue, now);
        boolean expiredGroup = group < window.newestGroup() - MAX_GROUPS + 1;
        if (now < start || expiredGroup
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
            case FADE -> { if (letter) y = .30 * (1 - ease); }
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

    /** Stagger the whole measured phrase without changing its cue, placement, or exit. */
    public static Appearance samplePhraseLetter(Cue cue, List<WorldLyricsPlan.Token> tokens, Window window,
                                                int tokenIndex, long now, boolean animated, double fraction) {
        var base = sample(cue, tokens, window, tokenIndex, now, animated);
        if (!animated || base.alpha() == 0) return base;
        long start = cue.source().line().startMillis();
        double duration = Math.clamp((cue.source().line().endMillis() - start) * .45, 90, 260);
        double settled = letterLanding(now - start, fraction, duration);
        if (settled >= 1) return base;
        return new Appearance(base.alpha() * settled, base.offsetY() + .36 * (1 - settled), base.scale(),
                base.offsetX(), base.offsetDepth(), base.color(), base.volumetric());
    }

    public static Appearance sampleReading(Cue cue, Window window, int tokenIndex, long now, boolean animated) {
        return sampleReading(cue, window, tokenIndex, now, animated, false);
    }

    public static Appearance sampleReading(Cue cue, Window window, int tokenIndex, long now, boolean animated,
                                           boolean shaderActive) {
        return sampleReadingLetter(cue, window, tokenIndex, now, animated, shaderActive, null, 0, 0);
    }

    public static Appearance sampleReadingLetter(Cue cue, Window window, int tokenIndex, long now, boolean animated,
                                                  boolean shaderActive, double[] offsets, double lineWidth, double letterX) {
        double alpha = 0;
        double scale = 1;
        double lift = 0;
        if (!animated) {
            if (tokenIndex >= window.firstToken() && tokenIndex <= window.lastStartedToken()) alpha = 1;
        } else for (var group : window.words()) {
            if (tokenIndex < group.firstToken() || tokenIndex >= group.endToken()) continue;
            double entrance = smooth((now - group.startsAt()) / group.entranceMillis());
            double tail = group.expiresAt() - (double) group.releasesAt();
            double elapsed = now - (double) group.releasesAt();
            double exit = Math.clamp(elapsed / Math.max(1, tail), 0, 1);
            double departure = smooth(exit);
            alpha = (.24 + .76 * entrance) * (1 - departure) * (1 - .38 * smooth(exit / .16));
            scale = .96 + .04 * entrance - .06 * departure;
            lift = .24 * departure - .14 * (1 - entrance);
            if (shaderActive && exit > 0) {
                alpha = Math.max(alpha, .82 * (1 - smooth((exit - .88) / .12)));
                scale *= shaderExitScale(exit);
            }
            if (offsets != null) {
                lift = .24 * departure;
                double duration = Math.clamp(group.entranceMillis() * 1.15, 78, 190);
                double sinceStart = now - (double) group.startsAt();
                if (sinceStart < duration) {
                    double begin = offsets[group.firstToken()];
                    double end = group.endToken() < offsets.length ? offsets[group.endToken()] : lineWidth;
                    double settled = letterLanding(sinceStart, (letterX - begin) / Math.max(1, end - begin), duration);
                    alpha *= settled;
                    lift += .34 * (1 - settled);
                }
            }
            break;
        }
        Accent accent = cue.accentAt(tokenIndex);
        int color = accent != null && accent.color() >= 0 ? accent.color() : cue.look().color();
        return new Appearance(alpha, lift, scale, 0, 0, color, cue.volumetric() || accent != null && accent.volumetric());
    }

    private static double letterLanding(double elapsed, double fraction, double duration) {
        double delay = .58 * duration * Math.clamp(fraction, 0, 1);
        double remaining = 1 - Math.clamp((elapsed - delay) / (.42 * duration), 0, 1);
        return 1 - remaining * remaining * remaining;
    }

    public static double shaderExitScale(double progress) {
        return Math.max(.015, 1 - smooth(progress));
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
        private final long[] leadStarts;

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
            leadStarts = cues.stream().filter(cue -> !cue.source().backing())
                    .mapToLong(cue -> cue.source().line().startMillis()).distinct().toArray();
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

        public long nextLeadStart(long startMillis) {
            int index = Arrays.binarySearch(leadStarts, startMillis);
            index = index >= 0 ? index + 1 : -index - 1;
            return index < leadStarts.length ? leadStarts[index] : Long.MAX_VALUE;
        }
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
                case FALLING_WORDS, STAIRCASE, WORD_BY_WORD -> 1;
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
