package dev.fandroid.client.music;

import java.util.Objects;

/** Two-stage waiting entrance, using the island's springs and the settings' 30 ms stagger. */
public final class IslandWaitingMotion {
    public static final long TITLE_DELAY_NANOS = 500_000_000L;
    private static final long STAGGER_NANOS = 30_000_000L;
    private final SpringValue visibility = new SpringValue(0, 4.5, .84);
    private final SpringValue title = new SpringValue(0, 4.5, .84);
    private final SpringValue[] dots = {new SpringValue(0, 4.5, .84), new SpringValue(0, 4.5, .84), new SpringValue(0, 4.5, .84)};
    private boolean initialized, waiting;
    private String track;
    private long entered, sampled, gapStart, gapEnd;

    public record Frame(double visibility, double title, double firstDot, double secondDot, double thirdDot,
                        boolean simple) {
        public double dot(int index) { return switch(index) { case 0 -> firstDot; case 1 -> secondDot; default -> thirdDot; }; }
        public double placement() { return simple ? 1 : title; }
    }

    public Frame update(IslandLyrics.Frame content, String trackKey, long now, boolean animate, boolean simple) {
        boolean next = content.kind() == IslandLyrics.Kind.WAITING;
        if (!initialized || !Objects.equals(track, trackKey)) {
            initialized = true; track = trackKey; sampled = now; waiting = false;
            visibility.snap(0); title.snap(0); for (var dot : dots) dot.snap(0);
        }
        boolean anotherGap = waiting && next && gapEnd > gapStart && content.waitEnd() > content.waitStart()
                && (gapStart != content.waitStart() || gapEnd != content.waitEnd());
        if (next && (!waiting || anotherGap)) entered = now;
        if (simple && next) entered = Math.min(entered, now - TITLE_DELAY_NANOS);
        long previous = Math.min(sampled, now);
        if (!animate) {
            visibility.snap(next ? 1 : 0); title.snap(next ? 1 : 0);
            for (var dot : dots) dot.snap(next ? 1 : 0);
            entered = now - TITLE_DELAY_NANOS;
        } else {
            advance(visibility, next, previous, now, entered);
            advance(title, next, previous, now, simple ? entered : entered + TITLE_DELAY_NANOS);
            for (int i = 0; i < dots.length; i++)
                advance(dots[i], next, previous, now, simple ? entered : entered + i * STAGGER_NANOS);
        }
        sampled = now; waiting = next; gapStart = content.waitStart(); gapEnd = content.waitEnd();
        return new Frame(unit(visibility), unit(title), unit(dots[0]), unit(dots[1]), unit(dots[2]), simple);
    }

    /** Split a long frame at its scheduled goal change, keeping low/high FPS samples identical. */
    private static void advance(SpringValue value, boolean visible, long previous, long now, long gate) {
        if (visible && gate > previous) {
            value.setGoal(0);
            value.step((Math.min(now, gate) - previous) / 1_000_000_000.0 * .85);
            previous = Math.max(previous, gate);
        }
        value.setGoal(visible && now >= gate ? 1 : 0);
        value.step(Math.max(0, now - previous) / 1_000_000_000.0 * .85);
    }
    private static double unit(SpringValue value) { return Math.clamp(value.position(), 0, 1); }
}
