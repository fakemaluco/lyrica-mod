package dev.fandroid.client.music;

/** Spatial reading envelope: reserve the travel corridor and keep a complete phrase ahead. */
public final class WorldLyricsSpace {
    /** Reframe on Ji camera cuts and accumulated travel/pans, including while music is paused. */
    public static final class CinematicCamera {
        private boolean active;
        private double x, y, z, lookX, lookY, lookZ;
        private long placedAt;

        public boolean update(boolean cinematic, double cameraX, double cameraY, double cameraZ,
                              double directionX, double directionY, double directionZ, long nanos) {
            boolean changed = cinematic != active;
            active = cinematic;
            if (!cinematic) { placedAt = 0; return changed; }
            double dx = cameraX - x, dy = cameraY - y, dz = cameraZ - z;
            double distanceSquared = dx * dx + dy * dy + dz * dz;
            double alignment = lookX * directionX + lookY * directionY + lookZ * directionZ;
            boolean cut = distanceSquared > 36 || alignment < .5;
            boolean drift = distanceSquared > 9 || alignment < Math.cos(Math.toRadians(30));
            if (changed || placedAt == 0 || cut || drift && nanos - placedAt >= 250_000_000L) {
                x = cameraX; y = cameraY; z = cameraZ;
                lookX = directionX; lookY = directionY; lookZ = directionZ; placedAt = nanos;
                return true;
            }
            return false;
        }

        public void reset() { active = false; placedAt = 0; }
    }
    public enum Position { ADAPTIVE, AROUND, SKY }
    public record Offset(double forward, double side, double up, double yawDegrees) { }
    private WorldLyricsSpace() { }

    /** Keep the phrase inside Ji's letterboxed view, with room for its bottom caption. */
    public static Offset cinematicOffset(Position position, double distance, double phraseWidth, int side) {
        double sign = side < 0 ? -1 : 1;
        return new Offset(distance, sign * (distance * .18 + phraseWidth * .5),
                position == Position.SKY ? distance * .08 : 0, -sign * 6);
    }

    public static double readingDistance(double speed, double remainingSeconds, int characters,
                                         boolean gliding, double viewLimit) {
        return readingDistance(speed, remainingSeconds, characters, gliding, viewLimit, 8);
    }

    public static double readingDistance(double speed, double remainingSeconds, int characters,
                                         boolean gliding, double viewLimit, double baseDistance) {
        return Math.clamp(baseDistance + travelDistance(speed, remainingSeconds, characters, gliding),
                5, Math.max(5, Math.min(256, viewLimit)));
    }

    private static double travelDistance(double speed, double remainingSeconds, int characters, boolean gliding) {
        double travel = Double.isFinite(speed) ? Math.clamp(speed, 0, 160) : 0;
        double reading = Math.clamp(Math.max(characters / 14.0, Math.min(remainingSeconds, 7)), 1.5, 8);
        double seconds = (gliding ? 2.4 : 1.2) + reading * (gliding ? .65 : .4);
        return travel * seconds;
    }

    public record DistanceRange(double minimum, double maximum) {
        public double clamp(double distance) { return Math.clamp(distance, minimum, maximum); }
    }

    public static DistanceRange placementRange(double minimum, double maximum, double viewLimit) {
        double far = Math.min(maximum, viewLimit);
        return new DistanceRange(Math.min(minimum, far), far);
    }

    public static double readingDistance(double speed, double remainingSeconds, int characters,
                                         boolean gliding, DistanceRange range) {
        return range.clamp(range.minimum() + travelDistance(speed, remainingSeconds, characters, gliding));
    }

    public static Offset offset(Position position, boolean outdoors, boolean gliding,
                                double distance, double phraseWidth, double phraseHeight, int side) {
        double sign = side < 0 ? -1 : 1;
        boolean sky = position == Position.SKY || position == Position.ADAPTIVE && outdoors;
        // Bounds, not just the anchor centre, stay outside the central 12-degree corridor.
        double gap = distance * Math.tan(Math.toRadians(gliding ? 14 : 12));
        if (sky) return new Offset(distance, sign * distance * (gliding ? .13 : .08),
                gap + phraseHeight * .5 + .35, sign * 10);
        return new Offset(distance, sign * (gap + phraseWidth * .5 + .35),
                distance * (gliding ? .18 : .08), -sign * 12);
    }

    public static boolean overlapping(long firstStart, long firstEnd, long secondStart, long secondEnd) {
        return firstStart < secondEnd && secondStart < firstEnd;
    }
    public static boolean courseChanged(boolean gliding, double alignment, double elapsedSeconds, double remainingSeconds) {
        return gliding && Double.isFinite(alignment) && alignment < .35
                && elapsedSeconds >= 1.2 && remainingSeconds >= 1.4;
    }

    public enum Relocation { KEEP, ADVANCE, TURN }

    /** A brief bank, a stationary camera or a page boundary must not restart a placement. */
    public static final class RelocationState {
        private long turnSince = Long.MIN_VALUE;
        private long attemptedAt = Long.MIN_VALUE;
        private long lastPosition = Long.MIN_VALUE;

        public Relocation evaluate(long now, double distance, double placedDistance, double closingSpeed,
                                   double speed, double remaining, double anchorAge,
                                   boolean flying, boolean moving) {
            if (lastPosition != Long.MIN_VALUE && now < lastPosition) {
                turnSince = attemptedAt = Long.MIN_VALUE;
            }
            lastPosition = now;
            if (moving || !Double.isFinite(speed) || speed < 4 || remaining < 1.2
                    || attemptedAt != Long.MIN_VALUE && now - attemptedAt < 1200) {
                turnSince = Long.MIN_VALUE;
                return Relocation.KEEP;
            }
            if (courseChanged(flying, closingSpeed / speed, anchorAge, remaining)) {
                if (turnSince == Long.MIN_VALUE) turnSince = now;
                if (now - turnSince >= 450) return Relocation.TURN;
            } else turnSince = Long.MIN_VALUE;
            return WorldLyricsPlacement.shouldRelocate(distance, placedDistance, closingSpeed,
                    remaining, anchorAge, flying) ? Relocation.ADVANCE : Relocation.KEEP;
        }

        /** Also back off when terrain rejects a replacement; do not probe again every frame. */
        public void attempted(long now) {
            attemptedAt = now;
            turnSince = Long.MIN_VALUE;
        }

        /** A successful move uses the new anchor's speed-dependent runway, even in very fast flight. */
        public void placed() {
            attemptedAt = turnSince = Long.MIN_VALUE;
        }
    }
}
