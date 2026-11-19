package dev.lyrica.engine;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Aggregates an allowlist of anonymous counters in memory, with no per-request payloads. */
public final class StatusMetrics {
    private static final Set<String> PROVIDERS = Set.of(
            "lrc_red", "lrclib", "spicy_lyrics", "amll_ttml_db", "unison", "spicy_unison",
            "rmm_revival", "kugou", "qq_music", "kuwo", "netease", "lrcmux", "genius", "youtube_transcript");
    private static final Map<String, Integer> COUNTS = new HashMap<>();
    private static boolean enabled;

    private StatusMetrics() { }

    public static synchronized void setEnabled(boolean enable) {
        enabled = enable;
        if (!enable) COUNTS.clear();
    }

    public static void lrcRedRequest() { increment("lrc_requests"); }

    public static void provider(String id, String result) {
        if (PROVIDERS.contains(id)) increment("p." + id + "." + result);
    }

    public static void error(String kind) { increment("e." + kind); }

    private static synchronized void increment(String key) {
        if (enabled) COUNTS.merge(key, 1, (next, added) -> Math.min(600, next + added));
    }

    public static synchronized Map<String, Integer> drain() {
        var counts = Map.copyOf(COUNTS);
        COUNTS.clear();
        return counts;
    }
}
