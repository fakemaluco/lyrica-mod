package dev.fandroid.protocol.lyrics;

public enum LyricAlignment {
    NORMAL,
    OPPOSITE;

    /** TTML duet agents have stable sides even when voice 2 opens the song. */
    public static LyricAlignment forAgent(String agent, String primaryAgent) {
        String value = agent == null ? "" : agent.strip().toLowerCase(java.util.Locale.ROOT);
        if (value.equals("v2") || value.equals("v2000") || value.equals("voice2") || value.equals("voice_2")) return OPPOSITE;
        if (value.equals("v1") || value.equals("v1000") || value.equals("voice1") || value.equals("voice_1")) return NORMAL;
        String primary = primaryAgent == null ? ""
                : primaryAgent.strip().toLowerCase(java.util.Locale.ROOT);
        return !primary.isBlank() && !primary.equals(value) ? OPPOSITE : NORMAL;
    }
}
