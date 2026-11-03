package dev.fandroid.client.music;

import java.util.List;

/** Version-independent placement policy. Minecraft adapters only gather terrain and camera samples. */
public final class WorldLyricsPlacement {
    private WorldLyricsPlacement() { }

    public enum Surface { GROUND, WALL, AIR }

    public record Candidate(Surface surface, double distance, double desiredDistance,
                            double angleDegrees, double facing, double relief,
                            boolean loaded, boolean supported, boolean dry, boolean clear) { }

    /** The next timed page may enter during its short crossfade, without moving the old page. */
    public static int pageIndex(List<WorldLyricsLayout.Page> pages, long now) {
        int result = 0;
        for (int i = 1; i < pages.size(); i++) {
            if (now < pages.get(i).startMillis() - 80) break;
            result = i;
        }
        return result;
    }

    /** Invalid candidates cannot be rescued by an attractive distance or orientation. */
    public static double score(Candidate candidate) {
        if (!candidate.loaded || !candidate.supported || !candidate.dry || !candidate.clear
                || !Double.isFinite(candidate.distance) || candidate.distance < 1.5
                || !Double.isFinite(candidate.desiredDistance)
                || !Double.isFinite(candidate.angleDegrees) || Math.abs(candidate.angleDegrees) > 42
                || !Double.isFinite(candidate.relief) || !Double.isFinite(candidate.facing)
                || candidate.relief > .25 || candidate.facing < .4) return Double.NEGATIVE_INFINITY;
        return 12 - Math.abs(candidate.distance - candidate.desiredDistance) * .18
                - Math.abs(candidate.angleDegrees) * .055 + candidate.facing * 3
                - candidate.relief * 5 + (candidate.surface == Surface.GROUND ? .3 : 0);
    }

    /** Distance is a reading-time budget, capped by both the user's setting and available chunks. */
    public static double readingDistance(double blocksPerSecond, double remainingSeconds, boolean flying,
                                         boolean landscape, WorldLyricsDirector.Settings settings) {
        double speed = Double.isFinite(blocksPerSecond) ? Math.clamp(blocksPerSecond, 0, 160) : 0;
        double placementSpeed = flying && speed >= 4 ? Math.max(speed, 14) : speed;
        double seconds = Math.clamp(remainingSeconds + (flying ? 2.0 : 1.25), 2.5, 7);
        double base = landscape ? Math.max(48, settings.minimumDistance()) : settings.minimumDistance();
        if (flying) base = Math.max(base, 32);
        return Math.clamp(base + placementSpeed * seconds * settings.distanceSensitivity(),
                settings.minimumDistance(), settings.maximumDistance());
    }

    /** A page remains fixed until the player is about to consume its reading runway. */
    public static boolean shouldRelocate(double distanceToAnchor, double placedDistance, double closingSpeed,
                                         double remainingSeconds, double secondsSinceAnchor, boolean flying) {
        if (!Double.isFinite(distanceToAnchor) || !Double.isFinite(placedDistance)
                || !Double.isFinite(closingSpeed) || placedDistance <= 0
                || remainingSeconds < 1.2 || closingSpeed < 4) return false;
        double cooldown = flying ? Math.clamp(placedDistance / closingSpeed * .45, .45, 1.4) : 1.0;
        if (secondsSinceAnchor < cooldown) return false;
        double runway = Math.max(flying ? 16 : 5, closingSpeed * (flying ? 1.45 : 1.1));
        return distanceToAnchor > 0 && distanceToAnchor <= Math.min(runway, placedDistance * .65);
    }
}
