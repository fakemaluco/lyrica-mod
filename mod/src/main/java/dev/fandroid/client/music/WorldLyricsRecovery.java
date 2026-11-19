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
        if (cue.composition() == WorldLyricsPresentation.Composition.WORD_BY_WORD)
            return WorldLyricsPresentation.window(cue, tokens, now);
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

    /** Keep the normal groups in a side lane and leave a small gap below the final group for the aim. */
    public static List<Probe> skyProbes(WorldLyricsSpace.DistanceRange range, double width, double height, int side) {
        double far = range.maximum(), near = range.minimum();
        List<Probe> probes = new ArrayList<>();
        for (double depth : new double[]{1, .88, .73, .55, .36, .18, 0}) {
            double distance = near + (far - near) * depth;
            double w = Math.min(width * distance / far, distance * .48);
            double h = Math.min(height * distance / far, distance * .18);
            if (side == 0) {
                probes.add(new Probe(distance, 0, distance * .16, w, h));
                probes.add(new Probe(distance, 0, distance * .30, w, h));
                probes.add(new Probe(distance, 0, -distance * .18, w, h));
            } else {
                double offset = Math.min(distance * .44, distance * .18 + w * .46);
                probes.add(new Probe(distance, side * offset, distance * .065, w, h));
                probes.add(new Probe(distance, side * offset, distance * .27, w * .85, h));
                probes.add(new Probe(distance, side * offset, -distance * .20, w * .85, h));
            }
            if (near == far) break;
        }
        return List.copyOf(probes);
    }

    /** Read from ahead first, including down the length of a narrow passage. */
    public static List<Probe> wordProbes(double preferred, double width, double height) {
        double distance = Math.clamp(preferred, 2.2, 256);
        double[] distances = {distance, Math.max(2.6, distance * .78), Math.max(2.6, distance * .57),
                Math.max(2.6, distance * .42), 2.6, 2.2, 1.65, 1.15};
        List<Probe> probes = new ArrayList<>();
        double previous = Double.POSITIVE_INFINITY;
        for (double target : distances) {
            if (target < 1.15 || target >= previous - .1) continue;
            previous = target;
            double fit = Math.min(target / distance, Math.min(target * .62 / width, target * .30 / height));
            double w = width * fit, h = height * fit;
            double y = target * .105 + h * .5;
            probes.add(new Probe(target, 0, target * .05, w, h));
            probes.add(new Probe(target, 0, y, w, h));
            // Look beside nearby trunks without pushing the word toward the edge of view.
            double side = Math.min(target * .22, w * .5 + target * .06);
            probes.add(new Probe(target, side, 0, w, h));
            probes.add(new Probe(target, -side, 0, w, h));
            if (target >= 2.6) {
                double offset = target * .36;
                probes.add(new Probe(target, offset, y * .65, w, h));
                probes.add(new Probe(target, -offset, y * .65, w, h));
                probes.add(new Probe(target, offset, -y * .35, w, h));
                probes.add(new Probe(target, -offset, -y * .35, w, h));
                // Preserve the viewing distance when the remaining gaps between words are narrow.
                double outer = target * .42;
                double raised = target * .24;
                double compactWidth = w * .74, compactHeight = h * .84;
                probes.add(new Probe(target, 0, raised, compactWidth, compactHeight));
                probes.add(new Probe(target, 0, -raised, compactWidth, compactHeight));
                probes.add(new Probe(target, outer, raised, compactWidth, compactHeight));
                probes.add(new Probe(target, -outer, raised, compactWidth, compactHeight));
                probes.add(new Probe(target, outer, -raised * .75, compactWidth, compactHeight));
                probes.add(new Probe(target, -outer, -raised * .75, compactWidth, compactHeight));
            }
            probes.add(new Probe(target, 0, -y, w, h));
        }
        return List.copyOf(probes);
    }

    /** Prefer a clear side slot, keeping closer and centered probes available for tight spaces. */
    public static List<Probe> readingProbes(double preferred, double width, double height, int side) {
        return wordProbes(preferred, width, height).stream()
                .sorted(java.util.Comparator.comparingDouble(Probe::distance).reversed()
                        .thenComparingInt(probe -> {
                            boolean sideGap = Math.abs(probe.side()) - probe.width() / 2 > probe.distance() * .045;
                            boolean upGap = Math.abs(probe.up()) - probe.height() / 2 > probe.distance() * .05;
                            if (sideGap && probe.side() * side > 0) return 0;
                            if (sideGap || upGap) return 1;
                            return probe.side() * side > 0 ? 2 : 3;
                        })).toList();
    }

    /**
     * A compact transaction gathers the visible phrase into one ordered block close to the camera.
     * Extents scale with distance, so moving from eight blocks to eighty centimetres does not retain
     * an eight-block-wide rectangle that could never fit among trunks or inside a tunnel.
     */
    public static List<Probe> compactProbes(double requestedDistance, double width, double height) {
        return compactProbes(requestedDistance, width, height, .18);
    }

    public static List<Probe> compactProbes(double requestedDistance, double width, double height, double minimum) {
        double reference = Double.isFinite(requestedDistance) ? Math.clamp(requestedDistance, .8, 256) : 8;
        double suppliedWidth = Double.isFinite(width) ? Math.max(.1, width) : 4;
        double suppliedHeight = Double.isFinite(height) ? Math.max(.1, height) : 1.2;
        List<Probe> result = new ArrayList<>();
        double previous = Double.POSITIVE_INFINITY;
        double[] targets = minimum > 2 ? new double[]{minimum, reference * .68, reference}
                : new double[]{2, 1.25, .8, .5, .3, .18};
        for (double target : targets) {
            double distance = Math.min(reference, Math.max(minimum, target));
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
