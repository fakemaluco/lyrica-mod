package dev.fandroid.client.music;

import java.util.ArrayList;
import java.util.List;

/** Smart guides in GUI pixels. Pointer motion stays independent of snapped motion. */
public final class PopupAlignment {
    public record Rect(int x, int y, int width, int height) {
        public int right() { return x + width; }
        public int bottom() { return y + height; }
        public double centerX() { return x + width / 2.0; }
        public double centerY() { return y + height / 2.0; }
    }
    public record Target(String id, String label, Rect bounds) { }
    public record Guide(String key, String label, Rect target, double coordinate) { }
    public record Result(Rect bounds, Guide vertical, Guide horizontal) {
        public boolean snapped() { return vertical != null || horizontal != null; }
    }
    private record Candidate(String key, Target target, double coordinate, int position, double distance, double proximity) { }

    public static final class State {
        private String horizontalKey, verticalKey;
        public void clear() { horizontalKey = verticalKey = null; }

        public Result align(Rect popup, int viewportWidth, int viewportHeight, List<Target> targets, double threshold) {
            var xs = new ArrayList<Candidate>();
            var ys = new ArrayList<Candidate>();
            Target screen = new Target("screen", "", new Rect(0, 0, viewportWidth, viewportHeight));
            candidates(popup, screen, true, viewportWidth, threshold, xs);
            candidates(popup, screen, false, viewportHeight, threshold, ys);
            for (Target target : targets) {
                if (target.bounds.width <= 0 || target.bounds.height <= 0) continue;
                candidates(popup, target, true, viewportWidth, threshold, xs);
                candidates(popup, target, false, viewportHeight, threshold, ys);
            }
            Candidate x = closest(xs, verticalKey, threshold), y = closest(ys, horizontalKey, threshold);
            verticalKey = x == null ? null : x.key;
            horizontalKey = y == null ? null : y.key;
            return new Result(new Rect(x == null ? popup.x : x.position, y == null ? popup.y : y.position,
                    popup.width, popup.height), guide(x), guide(y));
        }
    }

    private static Guide guide(Candidate c) {
        return c == null ? null : new Guide(c.key, c.target.label, c.target.bounds, c.coordinate);
    }

    private static Candidate closest(List<Candidate> candidates, String previous, double threshold) {
        Candidate best = null;
        for (Candidate candidate : candidates) {
            if (candidate.key.equals(previous) && candidate.distance <= threshold * 1.6) return candidate;
            if (candidate.distance > threshold) continue;
            if (best == null || candidate.distance < best.distance
                    || candidate.distance == best.distance && candidate.proximity < best.proximity) best = candidate;
        }
        return best;
    }

    private static void candidates(Rect popup, Target target, boolean vertical, int limit,
                                   double threshold, List<Candidate> output) {
        Rect other = target.bounds;
        int size = vertical ? popup.width : popup.height;
        int position = vertical ? popup.x : popup.y;
        double[] anchors = vertical ? new double[]{other.x, other.centerX(), other.right()}
                : new double[]{other.y, other.centerY(), other.bottom()};
        double proximity = vertical ? Math.abs(popup.centerY() - other.centerY()) : Math.abs(popup.centerX() - other.centerX());
        for (int theirs = 0; theirs < 3; theirs++) for (int ours = 0; ours < 3; ours++) {
            for (int gap : new int[]{0, 6, -6}) {
                if (gap != 0 && (target.id.equals("screen") || !(theirs == 0 && ours == 2 && gap < 0
                        || theirs == 2 && ours == 0 && gap > 0))) continue;
                int snapped = (int) Math.round(anchors[theirs] + gap - size * ours / 2.0);
                double distance = Math.abs(position - snapped);
                if (snapped < 0 || snapped + size > limit || distance > threshold * 1.6) continue;
                String key = target.id + (vertical ? ":x:" : ":y:") + theirs + ":" + ours + ":" + gap;
                output.add(new Candidate(key, target, anchors[theirs] + gap, snapped, distance, proximity));
            }
        }
    }

    private PopupAlignment() { }
}
