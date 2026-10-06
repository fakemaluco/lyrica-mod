package dev.fandroid.client.music;

/** Version-independent page entrance and exit. A disabled animation is exactly static. */
public final class WorldLyricsMotion {
    private WorldLyricsMotion() { }

    public record Pose(double zoom, double vertical, double horizontal, double rotation, double depth) {
        public static final Pose STATIC = new Pose(1, 0, 0, 0, 0);
    }

    public static Pose page(WorldLyricsDirector.Entrance entrance, long start, long end, long now,
                            double height, double intensity, double speed, boolean enabled) {
        if (!enabled) return Pose.STATIC;
        double enter = LyricsAnimation.smoother((now - start + 180) / (360.0 / speed));
        // Exit begins only after the last timed word of this page has finished.
        double exit = LyricsAnimation.smoother((now - end) / (450.0 / speed));
        double hold = LyricsAnimation.activeGlowEnvelope(LyricsAnimation.timedProgress(now, start, end));
        double zoom = (.94 + .06 * enter) * (1 - .04 * exit) * (1 + .025 * hold);
        double phase = (1 - enter - exit) * intensity;
        double vertical = 0, horizontal = 0, rotation = 0, depth = 0;
        switch (entrance) {
            case SLIDE -> horizontal = phase * .22;
            case CASCADE -> vertical = phase * .26;
            case ROTATE -> rotation = phase * 7;
            case DEPTH -> depth = phase * -.12;
            case FALL -> vertical = phase * .60;
            case POP -> { zoom *= 1 - .22 * phase; depth = phase * -.10; }
            case RISE -> { vertical = -phase * .06; zoom *= 1 - .55 * Math.clamp(phase, 0, 1); }
            case LAND -> vertical = phase * Math.min(.5, height * .2);
            case WALL_SLIDE -> horizontal = phase * .40;
            case BUILD -> { depth = -phase * .14; zoom *= 1 - .10 * phase; }
            case DISSOLVE -> { }
        }
        return new Pose(zoom, vertical, horizontal, rotation, depth);
    }
}
