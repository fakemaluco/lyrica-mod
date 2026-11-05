package dev.fandroid.client.music;

import java.lang.reflect.Field;
import java.util.List;

/** Receives the minimap's actual rendered rectangle, including Xaero's own HUD transforms. */
public final class PopupHudTargets {
    private record Snapshot(PopupAlignment.Rect bounds, int width, int height, long at) { }
    private static volatile Snapshot minimap;
    private static Class<?> contextClass;
    private static boolean contextSupported;
    private static Field x, y, width, height, screenWidth, screenHeight;

    public static void captureXaero(Object context) {
        if (context == null) return;
        try {
            if (contextClass != context.getClass()) {
                contextClass = context.getClass();
                contextSupported = false;
                x = contextClass.getField("x"); y = contextClass.getField("y");
                width = contextClass.getField("w"); height = contextClass.getField("h");
                screenWidth = contextClass.getField("screenWidth"); screenHeight = contextClass.getField("screenHeight");
                contextSupported = true;
            }
            if (!contextSupported) return;
            recordXaero(new PopupAlignment.Rect(x.getInt(context), y.getInt(context), width.getInt(context), height.getInt(context)),
                    screenWidth.getInt(context), screenHeight.getInt(context), System.nanoTime());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { }
    }

    public static void recordXaero(PopupAlignment.Rect bounds, int viewportWidth, int viewportHeight, long nanos) {
        if (bounds.width() > 0 && bounds.height() > 0 && viewportWidth > 0 && viewportHeight > 0)
            minimap = new Snapshot(bounds, viewportWidth, viewportHeight, nanos);
    }

    public static List<PopupAlignment.Target> targets(int viewportWidth, int viewportHeight, boolean editing, long nanos) {
        Snapshot current = minimap;
        if (current == null) return List.of();
        if (!editing && nanos - current.at > 500_000_000L) { minimap = null; return List.of(); }
        double sx = viewportWidth / (double) current.width, sy = viewportHeight / (double) current.height;
        PopupAlignment.Rect r = current.bounds;
        var scaled = new PopupAlignment.Rect((int) Math.round(r.x() * sx), (int) Math.round(r.y() * sy),
                (int) Math.round(r.width() * sx), (int) Math.round(r.height() * sy));
        return List.of(new PopupAlignment.Target("xaero-minimap", "Xaero's Minimap", scaled));
    }

    public static void clear() { minimap = null; }
    private PopupHudTargets() { }
}
