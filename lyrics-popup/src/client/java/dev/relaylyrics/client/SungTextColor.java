package dev.relaylyrics.client;

import java.util.List;
import java.util.TreeMap;

public record SungTextColor(Mode mode, int solid, List<Stop> stops, Scope scope) {
    public static final int RANGE = 1_000;
    public static final int MAX_STOPS = 12;
    public static final int START_COLOR = 0xFFE7A3;
    public static final int END_COLOR = 0xE0A7FF;
    public static final SungTextColor DEFAULT = new SungTextColor(Mode.DEFAULT, START_COLOR, List.of());

    public enum Mode { DEFAULT, SOLID, GRADIENT }
    public enum Scope { PHRASE, WORD }

    public SungTextColor(Mode mode, int solid, List<Stop> stops) {
        this(mode, solid, stops, Scope.PHRASE);
    }

    public record Stop(int position, int rgb) {
        public Stop {
            position = Math.clamp(position, 0, RANGE);
            rgb &= 0xFFFFFF;
        }
    }

    public SungTextColor {
        if (mode == null) mode = Mode.DEFAULT;
        if (scope == null) scope = Scope.PHRASE;
        solid &= 0xFFFFFF;
        var byPosition = new TreeMap<Integer, Stop>();
        byPosition.put(0, new Stop(0, START_COLOR));
        byPosition.put(RANGE, new Stop(RANGE, END_COLOR));
        if (stops != null) for (Stop stop : stops) {
            if (stop != null && (byPosition.size() < MAX_STOPS || byPosition.containsKey(stop.position())))
                byPosition.put(stop.position(), stop);
        }
        stops = List.copyOf(byPosition.values());
    }

    public int gradient(double fraction) {
        double position = Math.clamp(fraction, 0, 1) * RANGE;
        for (int index = 1; index < stops.size(); index++) {
            Stop right = stops.get(index);
            if (position > right.position()) continue;
            Stop left = stops.get(index - 1);
            double mix = (position - left.position()) / (right.position() - left.position());
            int color = 0;
            for (int shift = 0; shift <= 16; shift += 8) {
                int from = left.rgb() >> shift & 255, to = right.rgb() >> shift & 255;
                color |= (int) Math.round(from + (to - from) * mix) << shift;
            }
            return color;
        }
        return stops.getLast().rgb();
    }
}
