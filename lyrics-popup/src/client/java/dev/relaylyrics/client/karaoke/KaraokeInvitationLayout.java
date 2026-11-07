package dev.relaylyrics.client.karaoke;

/** Shared by the invitation surface and world-space pointer handling. */
public record KaraokeInvitationLayout(int width, int height) {
    public enum Action { ACCEPT, DECLINE, NONE }

    public int radius() { return Math.clamp(height / 10, 12, 22); }
    public int buttonY() { return height * 67 / 100; }

    public int buttonX(Action action) {
        int offset = radius() + 8;
        return width / 2 + (action == Action.ACCEPT ? offset : -offset);
    }

    public Action actionAt(double x, double y) {
        if (contains(Action.ACCEPT, x, y)) return Action.ACCEPT;
        if (contains(Action.DECLINE, x, y)) return Action.DECLINE;
        return Action.NONE;
    }

    public static String fitLabel(String value, int maxWidth, java.util.function.ToIntFunction<String> measure) {
        if (measure.applyAsInt(value) <= maxWidth) return value;
        int low = 0, high = value.codePointCount(0, value.length());
        while (low < high) {
            int middle = (low + high + 1) / 2;
            String candidate = value.substring(0, value.offsetByCodePoints(0, middle)).stripTrailing() + "…";
            if (measure.applyAsInt(candidate) <= maxWidth) low = middle;
            else high = middle - 1;
        }
        return value.substring(0, value.offsetByCodePoints(0, low)).stripTrailing() + "…";
    }

    private boolean contains(Action action, double x, double y) {
        double dx = x - buttonX(action), dy = y - buttonY();
        return dx * dx + dy * dy <= radius() * radius();
    }
}
