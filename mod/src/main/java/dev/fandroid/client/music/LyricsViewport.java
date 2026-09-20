package dev.fandroid.client.music;

/**
 * Allocation-free binary-search helpers for the lyric renderer's virtual window.
 * Arrays are laid out in visual order and must be monotonic.
 */
public final class LyricsViewport {
    private LyricsViewport() { }

    public static int activeIndex(long[] starts, long positionMillis) {
        if (starts.length == 0) return -1;
        int low = 0;
        int high = starts.length;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (starts[middle] <= positionMillis) low = middle + 1;
            else high = middle;
        }
        return Math.max(0, low - 1);
    }

    public static Range visibleRange(double[] starts, double[] ends,
                                     double minimum, double maximum, int overscan) {
        if (starts.length == 0) return Range.EMPTY;
        int first = firstEndAtOrAfter(ends, minimum);
        int lastExclusive = firstStartAfter(starts, maximum);
        int padding = Math.max(0, overscan);
        first = Math.max(0, first - padding);
        lastExclusive = Math.min(starts.length, Math.max(first, lastExclusive + padding));
        return new Range(first, lastExclusive);
    }

    /** Smoothly anticipates the next lyric while keeping fast passages visually stable. */
    public static double predictiveCenter(double currentCenter, double nextCenter, long positionMillis,
                                          long currentStart, long currentEnd, long nextStart) {
        if (nextStart <= currentStart || positionMillis >= nextStart) return nextCenter;
        long cadence = nextStart - currentStart;
        long leadTime = Math.clamp(Math.round(cadence * .22), 180, 520);
        double maximumLead = cadence < 1_000 ? .10 : .18;
        double lead = smooth((positionMillis - (nextStart - leadTime)) / (double) leadTime) * maximumLead;
        if (positionMillis > currentEnd && nextStart > currentEnd) {
            double interlude = smooth((positionMillis - currentEnd) / (double) (nextStart - currentEnd));
            lead = Math.max(lead, .18 + interlude * .54);
        }
        return currentCenter + (nextCenter - currentCenter) * Math.clamp(lead, 0, .72);
    }

    private static double smooth(double value) {
        double t = Math.clamp(value, 0, 1);
        return t * t * (3 - 2 * t);
    }

    private static int firstEndAtOrAfter(double[] ends, double value) {
        int low = 0;
        int high = ends.length;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (ends[middle] < value) low = middle + 1;
            else high = middle;
        }
        return low;
    }

    private static int firstStartAfter(double[] starts, double value) {
        int low = 0;
        int high = starts.length;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (starts[middle] <= value) low = middle + 1;
            else high = middle;
        }
        return low;
    }

    public record Range(int firstInclusive, int lastExclusive) {
        public static final Range EMPTY = new Range(0, 0);

        public int size() {
            return lastExclusive - firstInclusive;
        }
    }
}
