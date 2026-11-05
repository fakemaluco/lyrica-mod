package dev.fandroid.client.music;

import java.util.ArrayList;
import java.util.List;

/** Wall-clock recovery and bounded search envelopes; independent of Minecraft and lyric timing. */
public final class WorldLyricsRecovery {
    public static final long OBSTRUCTION_NANOS = 200_000_000L;
    public static final long OUT_OF_VIEW_NANOS = 350_000_000L;
    public static final long RETRY_NANOS = 600_000_000L;
    private static final long UNSET = Long.MIN_VALUE;

    private WorldLyricsRecovery() { }

    /** A pause freezes lyric animation, but must not freeze recovery when the camera or obstacles move. */
    public static final class Monitor {
        private long incompleteSince = UNSET, hiddenSince = UNSET, attemptedAt = UNSET, observedAt = UNSET;
        private boolean observed;

        /** Call with the complete phrase's state, rather than a separate monitor for every word. */
        public boolean evaluate(long nowNanos, boolean complete, boolean readable) {
            if (observed && nowNanos < observedAt) reset();
            boolean first = !observed;
            observed = true; observedAt = nowNanos;
            if (complete) incompleteSince = UNSET;
            else if (incompleteSince == UNSET) incompleteSince = nowNanos;
            if (readable) hiddenSince = UNSET;
            else if (hiddenSince == UNSET) hiddenSince = nowNanos;
            if (complete && readable) return false;
            if (attemptedAt != UNSET && nowNanos - attemptedAt < RETRY_NANOS) return false;
            return !complete && (first || nowNanos - incompleteSince >= OBSTRUCTION_NANOS)
                    || !readable && nowNanos - hiddenSince >= OUT_OF_VIEW_NANOS;
        }

        /** Back off after successful and unsuccessful transactions; no per-frame terrain searches. */
        public void attempted(long nowNanos) { attemptedAt = nowNanos; }

        public void reset() {
            incompleteSince = hiddenSince = attemptedAt = observedAt = UNSET;
            observed = false;
        }
    }

    /** Preserve the readable phrase when spatial blocks collapse into one ordered text block. */
    public static WorldLyricsPresentation.Window compactWindow(WorldLyricsPresentation.Cue cue,
                                                                List<WorldLyricsPlan.Token> tokens, long now) {
        if (tokens.isEmpty()) return new WorldLyricsPresentation.Window(0, -1, 0);
        var broad = new WorldLyricsPresentation.Window(0, tokens.size() - 1, 0);
        int lastRevealed = -1, singing = -1;
        for (int index = 0; index < tokens.size(); index++) {
            if (tokens.get(index).startMillis() <= now) singing = index;
            if (WorldLyricsPresentation.sample(cue, tokens, broad, index, now, false).alpha() > 0)
                lastRevealed = index;
        }
        if (lastRevealed < 0) return new WorldLyricsPresentation.Window(0, -1, 0);
        int anchor = Math.min(lastRevealed, Math.max(0, singing));
        int first = 0, words = 0;
        for (int index = anchor; index >= 0; index--) {
            if ((index == 0 || tokens.get(index).startsWord()) && ++words > WorldLyricsPresentation.MAX_WORDS) {
                first = index + 1;
                while (first < tokens.size() && !tokens.get(first).startsWord()) first++;
                break;
            }
        }
        words = 0;
        int last = first - 1;
        for (int index = first; index <= lastRevealed; index++) {
            if ((index == first || tokens.get(index).startsWord()) && ++words > WorldLyricsPresentation.MAX_WORDS) break;
            last = index;
        }
        // NewestGroup zero deliberately removes only the four-scattered-block window. Source reveal
        // times, opacity and singing/highlight times remain authoritative in sample(). Untimed text
        // remains one unit; its actual lexical display budget belongs to the measured word renderer.
        return new WorldLyricsPresentation.Window(first, last, 0);
    }

    /** Dimensions and offsets are world units in the camera's forward/right/up basis. */
    public record Probe(double distance, double side, double up, double width, double height) {
        public Probe {
            if (!Double.isFinite(distance) || !Double.isFinite(side) || !Double.isFinite(up)
                    || !Double.isFinite(width) || !Double.isFinite(height)
                    || distance <= 0 || width <= 0 || height <= 0)
                throw new IllegalArgumentException("Invalid reading envelope");
        }
    }

    /**
     * A compact transaction gathers the visible phrase into one ordered block close to the camera.
     * Extents scale with distance, so moving from eight blocks to eighty centimetres does not retain
     * an eight-block-wide rectangle that could never fit among trunks or inside a tunnel.
     */
    public static List<Probe> compactProbes(double requestedDistance, double width, double height) {
        double reference = Double.isFinite(requestedDistance) ? Math.clamp(requestedDistance, .8, 256) : 8;
        double suppliedWidth = Double.isFinite(width) ? Math.max(.1, width) : 4;
        double suppliedHeight = Double.isFinite(height) ? Math.max(.1, height) : 1.2;
        List<Probe> result = new ArrayList<>();
        double previous = Double.POSITIVE_INFINITY;
        for (double target : new double[]{2, 1.25, .8, .5, .3, .18}) {
            double distance = Math.min(reference, target);
            if (distance == previous) continue;
            previous = distance;
            boolean emergency = distance < .8;
            double fit = Math.min(distance * .9 / suppliedWidth,
                    distance * (emergency ? .34 : .38) / suppliedHeight);
            if (!emergency) fit = Math.min(fit, distance / reference);
            double w = suppliedWidth * fit, h = suppliedHeight * fit;
            double gap = distance * .08;
            double up = h * .5 + gap;
            result.add(new Probe(distance, 0, -up, w, h));
            result.add(new Probe(distance, 0, up, w, h));
            if (!emergency) {
                double side = w * .5 + gap;
                result.add(new Probe(distance, -side, 0, w, h));
                result.add(new Probe(distance, side, 0, w, h));
            }
        }
        return List.copyOf(result);
    }
}
