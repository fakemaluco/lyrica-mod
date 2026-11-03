package dev.fandroid.client.music;

/** Spatial reading envelope: reserve the travel corridor and keep a complete phrase ahead. */
public final class WorldLyricsSpace {
    public enum Position { ADAPTIVE, AROUND, SKY }
    public record Offset(double forward, double side, double up, double yawDegrees) { }
    private WorldLyricsSpace() { }

    public static double readingDistance(double speed, double remainingSeconds, int characters,
                                         boolean gliding, double viewLimit) {
        double travel = Double.isFinite(speed) ? Math.clamp(speed, 0, 160) : 0;
        double reading = Math.clamp(Math.max(characters / 14.0, Math.min(remainingSeconds, 7)), 1.5, 8);
        double seconds = (gliding ? 2.4 : 1.2) + reading * (gliding ? .65 : .4);
        return Math.clamp(8 + travel * seconds, 5, Math.max(5, Math.min(256, viewLimit)));
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
