package dev.fandroid.client.music;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Pure, deterministic direction of a world-lyrics phrase. */
public final class WorldLyricsDirector {
    private WorldLyricsDirector() { }

    public enum Preset {
        CLASSIC(Placement.FRONT, Entrance.DISSOLVE, Motion.WAVE, 0, false),
        CINEMA(Placement.ALTERNATE, Entrance.SLIDE, Motion.BREATHE, .085, false),
        SCULPTURE(Placement.FRONT, Entrance.DEPTH, Motion.DEPTH_PULSE, .16, false),
        ARC(Placement.ARC, Entrance.ROTATE, Motion.TILT_SEQUENCE, .09, false),
        CASCADE(Placement.DIAGONAL, Entrance.CASCADE, Motion.WAVE, .075, false),
        FLIGHT(Placement.FRONT, Entrance.DISSOLVE, Motion.BREATHE, .11, false),
        LANDSCAPE(Placement.FRONT, Entrance.DISSOLVE, Motion.BREATHE, .14, false);

        public final Placement placement;
        public final Entrance entrance;
        public final Motion motion;
        public final double depth;
        public final boolean trails;
        Preset(Placement placement, Entrance entrance, Motion motion, double depth, boolean trails) {
            this.placement = placement; this.entrance = entrance; this.motion = motion;
            this.depth = depth; this.trails = trails;
        }
        public Preset next() { return values()[(ordinal() + 1) % values().length]; }
        public Preset previous() { return values()[(ordinal() + values().length - 1) % values().length]; }
    }

    public enum Placement { FRONT, ALTERNATE, ARC, DIAGONAL }
    public enum Entrance { DISSOLVE, SLIDE, CASCADE, ROTATE, DEPTH }
    public enum Motion { WAVE, WORD_JUMP, BREATHE, TILT_SEQUENCE, DEPTH_PULSE }
    public enum Variation { LOW(4), MEDIUM(2), HIGH(1);
        public final int holdPhrases;
        Variation(int holdPhrases) { this.holdPhrases = holdPhrases; }
        public Variation next() { return values()[(ordinal() + 1) % values().length]; }
        public Variation previous() { return values()[(ordinal() + values().length - 1) % values().length]; }
    }
    public enum Quality { LOW(4, 0), MEDIUM(8, 1), HIGH(14, 2);
        public final int depthLayers, trailCopies;
        Quality(int depthLayers, int trailCopies) { this.depthLayers = depthLayers; this.trailCopies = trailCopies; }
        public Quality next() { return values()[(ordinal() + 1) % values().length]; }
        public Quality previous() { return values()[(ordinal() + values().length - 1) % values().length]; }
    }

    public record Settings(boolean automatic, Preset selected, Set<Preset> enabled,
                           Variation variation, double minimumDistance, double maximumDistance,
                           double distanceSensitivity, double arcRadius, double centerGap,
                           double depth, double animationIntensity, double animationSpeed,
                           boolean trails, Quality quality) {
        public Settings {
            selected = selected == null ? Preset.CINEMA : selected;
            enabled = enabled == null || enabled.isEmpty() ? EnumSet.allOf(Preset.class) : EnumSet.copyOf(enabled);
            variation = variation == null ? Variation.MEDIUM : variation;
            quality = quality == null ? Quality.MEDIUM : quality;
            minimumDistance = Math.clamp(minimumDistance, 3, 64);
            maximumDistance = Math.clamp(maximumDistance, minimumDistance, 256);
            distanceSensitivity = Math.clamp(distanceSensitivity, 0, 2);
            arcRadius = Math.clamp(arcRadius, 0, 1);
            centerGap = Math.clamp(centerGap, 0, .8);
            depth = Math.clamp(depth, 0, .3);
            animationIntensity = Math.clamp(animationIntensity, 0, 2);
            animationSpeed = Math.clamp(animationSpeed, .25, 2);
        }
    }

    public record Composition(Preset preset, Placement placement, Entrance entrance, Motion motion,
                              double yawOffsetDegrees, double verticalOffset, double depth,
                              boolean trails, int depthLayers, int trailCopies) { }

    public static Composition choose(long phraseStart, String text, int wordCount, long durationMillis,
                                     boolean backing, boolean wall, double speed, Settings settings) {
        return choose(phraseStart, text, wordCount, durationMillis, backing, wall, speed, settings, null);
    }

    public static Composition choose(long phraseStart, String text, int wordCount, long durationMillis,
                                     boolean backing, boolean wall, double speed, Settings settings,
                                     Preset previous) {
        List<Preset> choices = new ArrayList<>(settings.enabled());
        choices.sort(Enum::compareTo);
        long group = Math.floorDiv(phraseStart, 1_000L * settings.variation().holdPhrases);
        int seed = Objects.hash(group, backing);
        Preset preset = settings.automatic() ? choices.get(Math.floorMod(seed, choices.size())) : settings.selected();
        if (settings.automatic() && !wall && speed > .72 && choices.contains(Preset.FLIGHT)) {
            preset = Preset.FLIGHT;
        } else if (settings.automatic() && durationMillis > 3_000 && wordCount <= 5
                && choices.contains(Preset.SCULPTURE)) {
            preset = Preset.SCULPTURE;
        }
        if (settings.automatic() && choices.size() > 1 && preset == previous) {
            preset = choices.get((choices.indexOf(preset) + 1) % choices.size());
        }
        if (wall && (preset == Preset.ARC || preset == Preset.FLIGHT)) preset = Preset.SCULPTURE;

        double sign = (seed & 1) == 0 ? -1 : 1;
        double yaw = switch (preset.placement) {
            case FRONT -> 0;
            case ALTERNATE -> sign * (12 + settings.centerGap() * 24);
            case ARC -> sign * (20 + settings.arcRadius() * 26);
            case DIAGONAL -> sign * (14 + settings.arcRadius() * 18);
        };
        double vertical = preset.placement == Placement.DIAGONAL ? sign * .6 : preset == Preset.FLIGHT ? .75 : 0;
        Motion motion = durationMillis >= 1_800 ? preset.motion
                : wordCount <= 3 ? Motion.WORD_JUMP : preset.motion == Motion.DEPTH_PULSE ? Motion.BREATHE : preset.motion;
        double depth = Math.clamp(preset.depth * (settings.depth() / .10), 0, .30);
        if (preset == Preset.CLASSIC) depth = 0;
        boolean trails = settings.trails();
        return new Composition(preset, preset.placement, preset.entrance, motion, yaw, vertical,
                depth, trails, settings.quality().depthLayers, trails ? settings.quality().trailCopies : 0);
    }

    public static double distance(double smoothedSpeed, boolean flying, Settings settings) {
        double activity = Math.clamp(smoothedSpeed * settings.distanceSensitivity() + (flying ? .22 : 0), 0, 1);
        double eased = activity * activity * (3 - 2 * activity);
        return settings.minimumDistance() + (settings.maximumDistance() - settings.minimumDistance()) * eased;
    }

    /** Arrival must occur after the phrase can be read, including fast gliding and vertical travel. */
    public static double readingDistance(double blocksPerSecond, double remainingSeconds, boolean flying,
                                         boolean landscape, Settings settings) {
        double speed = Double.isFinite(blocksPerSecond) ? Math.clamp(blocksPerSecond, 0, 160) : 0;
        double seconds = Math.clamp(remainingSeconds + 1.25, 2.5, 7);
        double base = landscape ? Math.max(48, settings.minimumDistance()) : settings.minimumDistance();
        if (flying) base = Math.max(base, 32);
        return Math.clamp(base + speed * seconds * settings.distanceSensitivity(),
                settings.minimumDistance(), settings.maximumDistance());
    }
}
