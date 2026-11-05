package dev.fandroid.client.music;

import dev.fandroid.protocol.lyrics.LyricsDocument;
import dev.fandroid.protocol.lyrics.LyricsSyncMode;
import dev.fandroid.protocol.lyrics.LyricAlignment;
import java.util.*;

/** Reading-first direction. The world adapter owns terrain, fonts and GPU resources. */
public final class WorldLyricsExperience {
    private WorldLyricsExperience() { }
    public enum Style { SPICY, SCULPTED, MINIMAL }
    public enum RenderMode { AUTO, SAFE, FULL }
    public record Pose(double lift, double scale, double depth, double glow) { }

    /** Presentation intensity changes composition, never silently disables the Spicy springs. */
    public static double spicyStrength(Style style, WorldLyricsPresentation.Intensity intensity,
                                       double configured, boolean animated) {
        if (!animated) return 0;
        double amount = Double.isFinite(configured) ? Math.clamp(configured, 0, 2) : 1;
        double material = style == Style.MINIMAL ? .45 : style == Style.SCULPTED ? .85 : 1;
        double presentation = intensity == WorldLyricsPresentation.Intensity.EXPRESSIVE ? 1.25
                : intensity == WorldLyricsPresentation.Intensity.BALANCED ? 1.1 : 1;
        return Math.min(2, amount * material * presentation);
    }

    /** The very same word/glyph spring used by the popup, sampled once per render frame. */
    public static final class Motion {
        private final LyricsAnimation.ElementMotion element;
        private long previousClock, previousSong, sampledClock;
        private boolean initialized;
        public Motion(boolean glyph) { element=new LyricsAnimation.ElementMotion(glyph); }
        public void sample(long start, long end, long songTime, long clock, boolean animated) {
            if (sampledClock==clock && initialized) return;
            double progress=LyricsAnimation.timedProgress(songTime,start,end);
            boolean singing=animated;
            if (!initialized || Math.abs(songTime-previousSong)>500 || songTime<previousSong)
                element.reset(progress,singing);
            else element.update(progress,singing,songTime==previousSong ? 0 : Math.clamp((clock-previousClock)/1e9,0,.05));
            initialized=true; previousClock=clock; sampledClock=clock; previousSong=songTime;
        }
        public double scale() { return element.scale(); }
        public double elevation() { return element.elevation(); }
    }

    /** One motion for a whole sung word: syllables keep their own fill timestamps. */
    public static Pose word(long start, long end, long now, Style style, boolean animated, double strength) {
        if (!animated || now < start || now >= end) return new Pose(0, 1, 0, 0);
        double progress = LyricsAnimation.timedProgress(now, start, end);
        double envelope = LyricsAnimation.activeGlowEnvelope(progress);
        double amount = Math.clamp(strength, 0, 1.5);
        double lift = envelope * amount;
        if (style == Style.MINIMAL) return new Pose(0, 1, 0, envelope * .35);
        return new Pose(lift * .065, 1 + Math.max(0, LyricsAnimation.spicyScaleTarget(progress, false) - 1) * amount,
                lift * (style == Style.SCULPTED ? .035 : .016), envelope);
    }

    /** A single restrained rise, without continuous floating, tilting or random entrances. */
    public static double entrance(long start, long now, boolean animated) {
        return animated ? 1 - LyricsAnimation.smoother((now - start + 180) / 360.0) : 0;
    }

    public static WorldLyricsDirector.Composition composition(Style style, boolean wall,
                                                              WorldLyricsDirector.Quality quality, double depth) {
        return new WorldLyricsDirector.Composition(wall ? WorldLyricsDirector.Preset.MURAL : WorldLyricsDirector.Preset.SIGN,
                WorldLyricsDirector.Placement.FRONT, WorldLyricsDirector.Entrance.RISE,
                WorldLyricsDirector.Motion.WORD_JUMP, 0, 0,
                Math.clamp(depth * (style == Style.SCULPTED ? 2.1 : style == Style.MINIMAL ? .8 : 1.6), .04, .30),
                false, quality.depthLayers, 0);
    }

    public static int tint(int from, int to, double amount) {
        double t = Math.clamp(amount, 0, 1);
        int r = (int) Math.round((from >> 16 & 255) * (1-t) + (to >> 16 & 255) * t);
        int g = (int) Math.round((from >> 8 & 255) * (1-t) + (to >> 8 & 255) * t);
        int b = (int) Math.round((from & 255) * (1-t) + (to & 255) * t);
        return r << 16 | g << 8 | b;
    }

    /** Geometry and halo have a shared, finite frame budget, independent of phrase length. */
    public static final class Budget {
        private int faces, halos;
        public Budget(WorldLyricsDirector.Quality quality, boolean safe) {
            faces = switch (quality) { case LOW -> 12288; case MEDIUM -> 32768; case HIGH -> 65536; };
            halos = 0;
        }
        public int remainingFaces() { return faces; }
        public boolean mesh(int count) {
            if (count < 1 || count > faces) return false;
            faces -= count; return true;
        }
        public boolean halo() { if (halos <= 0) return false; halos--; return true; }
    }

    /** Built on song change, then queried by binary search; old verses are never scanned each frame. */
    public static final class Timeline {
        private final List<WorldLyricsPlan.WorldLine> lines;
        private final long[] maximumEnd;
        public Timeline(LyricsDocument document) {
            List<WorldLyricsPlan.WorldLine> sorted = new ArrayList<>();
            if (document != null && document.metadata().syncMode() != LyricsSyncMode.STATIC) {
                if (document.groups().isEmpty()) document.lines().forEach(l -> sorted.add(new WorldLyricsPlan.WorldLine(l, LyricAlignment.NORMAL, false)));
                else for (var group : document.groups()) {
                    sorted.add(new WorldLyricsPlan.WorldLine(group.lead(), group.alignment(), false));
                    group.backingVocals().forEach(l -> sorted.add(new WorldLyricsPlan.WorldLine(l, group.alignment(), true)));
                }
            }
            sorted.sort(Comparator.comparingLong(l -> l.line().startMillis()));
            lines = List.copyOf(sorted); maximumEnd = new long[lines.size()];
            long end = Long.MIN_VALUE;
            for (int i = 0; i < lines.size(); i++) maximumEnd[i] = end = Math.max(end, lines.get(i).line().endMillis());
        }
        public List<WorldLyricsPlan.WorldLine> visible(long now) {
            int low = 0, high = lines.size();
            while (low < high) {
                int mid = (low + high) >>> 1;
                if (lines.get(mid).line().startMillis() <= now + WorldLyricsPlan.ENTER_LEAD_MILLIS) low = mid + 1;
                else high = mid;
            }
            List<WorldLyricsPlan.WorldLine> active = new ArrayList<>(3);
            for (int i = low - 1; i >= 0 && maximumEnd[i] >= now - WorldLyricsPlan.EXIT_TAIL_MILLIS; i--) {
                var line = lines.get(i);
                if (line.line().endMillis() >= now - WorldLyricsPlan.EXIT_TAIL_MILLIS) active.add(0, line);
            }
            active.sort(Comparator.comparing(WorldLyricsPlan.WorldLine::backing)
                    .thenComparing(Comparator.comparingLong((WorldLyricsPlan.WorldLine l) -> l.line().startMillis()).reversed()));
            if (active.size() > 3) active.subList(3, active.size()).clear();
            active.sort(Comparator.comparingLong(l -> l.line().startMillis()));
            return List.copyOf(active);
        }
        public WorldLyricsPlan.Interlude interlude(long now) {
            int low = 0, high = lines.size();
            while (low < high) {
                int mid = (low + high) >>> 1;
                if (lines.get(mid).line().startMillis() <= now) low = mid + 1; else high = mid;
            }
            long end = low == 0 ? 0 : maximumEnd[low - 1];
            if (end > now || low >= lines.size()) return null;
            long next = lines.get(low).line().startMillis();
            return next - end >= LyricsAnimation.INTERLUDE_MIN_GAP_MILLIS ? new WorldLyricsPlan.Interlude(end, next) : null;
        }
    }
}
