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
        SIGN(Placement.ALTERNATE, Entrance.RISE, Motion.WORD_JUMP, .10, false),
        MURAL(Placement.FRONT, Entrance.WALL_SLIDE, Motion.BREATHE, .09, false),
        ENGRAVED(Placement.FRONT, Entrance.BUILD, Motion.BREATHE, .018, false),
        MONOLITH(Placement.DIAGONAL, Entrance.LAND, Motion.DEPTH_PULSE, .20, false),
        LANDSCAPE(Placement.ARC, Entrance.SLIDE, Motion.WAVE, .11, false),
        FLIGHT(Placement.FRONT, Entrance.DISSOLVE, Motion.BREATHE, .10, false);

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
    public enum Entrance { DISSOLVE, SLIDE, CASCADE, ROTATE, DEPTH, FALL, POP, RISE, LAND, WALL_SLIDE, BUILD }
    public enum SurfacePreference { CONTEXT, SURFACES, HORIZON;
        public SurfacePreference next() { return values()[(ordinal() + 1) % values().length]; }
        public SurfacePreference previous() { return values()[(ordinal() + values().length - 1) % values().length]; }
    }
    public enum Material { AUTO, STONE, METAL, SOFT_LIGHT;
        public Material next() { return values()[(ordinal() + 1) % values().length]; }
        public Material previous() { return values()[(ordinal() + values().length - 1) % values().length]; }
    }
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
            selected = selected == null ? Preset.SIGN : selected;
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
        if (wall) choices.removeIf(p -> p != Preset.MURAL && p != Preset.ENGRAVED);
        else if (speed > .72) choices.removeIf(p -> p != Preset.FLIGHT && p != Preset.LANDSCAPE);
        if (choices.isEmpty()) choices.add(wall ? Preset.MURAL : Preset.LANDSCAPE);
        choices.sort(Enum::compareTo);
        long group = Math.floorDiv(phraseStart, 1_000L * settings.variation().holdPhrases);
        int seed = Objects.hash(group, backing);
        Preset preset = settings.automatic() ? choices.get(Math.floorMod(seed, choices.size())) : settings.selected();
        if (settings.automatic() && !wall && speed > .72 && choices.contains(Preset.FLIGHT)) {
            preset = Preset.FLIGHT;
        } else if (settings.automatic() && !wall && speed < .28 && durationMillis > 3_000
                && wordCount <= 5 && choices.contains(Preset.MONOLITH)) {
            preset = Preset.MONOLITH;
        }
        if (settings.automatic() && choices.size() > 1 && preset == previous) {
            preset = choices.get((choices.indexOf(preset) + 1) % choices.size());
        }
        if (wall && preset != Preset.MURAL && preset != Preset.ENGRAVED) preset = Preset.MURAL;

        double sign = (seed & 1) == 0 ? -1 : 1;
        double yaw = switch (preset.placement) {
            case FRONT -> 0;
            case ALTERNATE -> sign * (12 + settings.centerGap() * 24);
            case ARC -> sign * (20 + settings.arcRadius() * 26);
            case DIAGONAL -> sign * (14 + settings.arcRadius() * 18);
        };
        double vertical = preset == Preset.MONOLITH ? sign * .25 : preset == Preset.FLIGHT ? .75 : 0;
        Motion motion = durationMillis >= 1_800 ? preset.motion
                : wordCount <= 3 ? Motion.WORD_JUMP : preset.motion == Motion.DEPTH_PULSE ? Motion.BREATHE : preset.motion;
        double depth = Math.clamp(preset.depth * (settings.depth() / .10), 0, .30);
        if (preset == Preset.ENGRAVED) depth = Math.min(depth, .025);
        boolean trails = settings.trails();
        return new Composition(preset, preset.placement, preset.entrance, motion, yaw, vertical,
                depth, trails, settings.quality().depthLayers, trails ? settings.quality().trailCopies : 0);
    }

    public static double distance(double smoothedSpeed, boolean flying, Settings settings) {
        double activity = Math.clamp(smoothedSpeed * settings.distanceSensitivity() + (flying ? .22 : 0), 0, 1);
        double eased = activity * activity * (3 - 2 * activity);
        return settings.minimumDistance() + (settings.maximumDistance() - settings.minimumDistance()) * eased;
    }

    public static boolean seekSurface(Preset preset, SurfacePreference preference,
                                      double blocksPerSecond, boolean flying, boolean automatic) {
        boolean manualSurface = !automatic && (preset == Preset.SIGN || preset == Preset.MONOLITH
                || preset == Preset.MURAL || preset == Preset.ENGRAVED);
        if (manualSurface) return true;
        if (preset == Preset.LANDSCAPE || preset == Preset.FLIGHT) return false;
        if (flying || blocksPerSecond >= 5.1 || preference == SurfacePreference.HORIZON) return false;
        return preference == SurfacePreference.SURFACES || preset == Preset.SIGN || preset == Preset.MONOLITH
                || preset == Preset.MURAL || preset == Preset.ENGRAVED;
    }

    /** Arrival must occur after the phrase can be read, including fast gliding and vertical travel. */
    public static double readingDistance(double blocksPerSecond, double remainingSeconds, boolean flying,
                                         boolean landscape, Settings settings) {
        return WorldLyricsPlacement.readingDistance(blocksPerSecond, remainingSeconds, flying, landscape, settings);
    }

    /** Move only as the reader approaches the anchor, leaving enough time for the move itself. */
    public static boolean shouldRelocate(double distanceToAnchor, double placedDistance, double closingSpeed,
                                         double remainingSeconds, double secondsSinceAnchor, boolean flying) {
        return WorldLyricsPlacement.shouldRelocate(distanceToAnchor, placedDistance, closingSpeed,
                remainingSeconds, secondsSinceAnchor, flying);
    }
}
