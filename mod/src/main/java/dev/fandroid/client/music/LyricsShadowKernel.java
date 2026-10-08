package dev.fandroid.client.music;

import java.util.ArrayList;
import java.util.List;

/** Restrained shadow samples; translucent text must never turn into black copies. */
public final class LyricsShadowKernel {
    private static final int[][] AXIAL = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
    private static final int[][] DIAGONAL = {{-1, -1}, {1, -1}, {-1, 1}, {1, 1}};
    /** Minecraft 1.21.1 treats alpha 1-3 as fully opaque. */
    public static final int MIN_TEXT_ALPHA = 4;
    private static final List<Tap> SHARP = List.of(new Tap(0, 0, .48));
    private static final List<Tap> SOFT = create(1);
    private static final List<Tap> VERY_SOFT = create(2);

    private LyricsShadowKernel() { }

    public record Tap(int x, int y, double opacity) { }

    public static List<Tap> taps(int softness) {
        return softness <= 0 ? SHARP : softness == 1 ? SOFT : VERY_SOFT;
    }

    public static int safeAlpha(double alpha) {
        int rounded = (int) Math.round(Math.clamp(alpha, 0, 255));
        return rounded < MIN_TEXT_ALPHA ? 0 : rounded;
    }

    private static List<Tap> create(int softness) {
        List<Tap> result = new ArrayList<>(softness == 1 ? 5 : 9);
        if (softness == 1) {
            result.add(new Tap(0, 0, .32));
            for (int[] direction : AXIAL) result.add(new Tap(direction[0], direction[1], .055));
        } else {
            result.add(new Tap(0, 0, .22));
            for (int[] direction : AXIAL) result.add(new Tap(direction[0], direction[1], .045));
            for (int[] direction : DIAGONAL) result.add(new Tap(direction[0], direction[1], .025));
        }
        return List.copyOf(result);
    }
}
