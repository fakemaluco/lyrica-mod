package dev.relaylyrics.client.ui;

import java.nio.file.*;
import java.util.*;
import java.io.IOException;

/** Opt-in full-frame cadence, not just placement CPU time. No recording during ordinary play. */
public final class WorldLyricsFrameProbe {
    private static final boolean ENABLED = Boolean.getBoolean("lyrica.worldFrameBenchmark");
    private static final int COUNT = Math.clamp(Integer.getInteger("lyrica.worldBenchmarkFrames", 2000), 100, 10_000);
    private static final long[] samples = ENABLED ? new long[COUNT] : null;
    private static long previous;
    private static int warmup, count;
    private static boolean started;
    private WorldLyricsFrameProbe() { }

    public static void start() {
        if (!ENABLED) return;
        started = true; previous = 0; count = 0; warmup = 500;
    }

    public static void frame() {
        if (!ENABLED || !started || count >= COUNT) return;
        long now = System.nanoTime();
        if (previous != 0) {
            if (warmup > 0) warmup--;
            else samples[count++] = now - previous;
        }
        previous = now;
    }

    public static boolean complete() { return ENABLED && started && count == COUNT; }

    public static String write(Path output) throws IOException {
        if (!complete()) throw new IllegalStateException("Frame benchmark incomplete");
        long[] sorted = samples.clone(); Arrays.sort(sorted);
        double median = sorted[sorted.length / 2] / 1e6;
        double p95 = sorted[(int) Math.ceil(sorted.length * .95) - 1] / 1e6;
        String result = String.format(Locale.ROOT, "{\"samples\":%d,\"warmupFrames\":500,\"medianMs\":%.6f,\"p95Ms\":%.6f,\"frameNanos\":%s}",
                count, median, p95, Arrays.toString(samples));
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, result);
        return String.format(Locale.ROOT, "median=%.3f ms, p95=%.3f ms, frames=%d", median, p95, count);
    }
}
