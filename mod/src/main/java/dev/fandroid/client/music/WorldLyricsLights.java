package dev.fandroid.client.music;

import java.util.Arrays;
import java.util.List;

/** Render offers are cheap; the optional lighting backend is updated at client tick cadence. */
public final class WorldLyricsLights {
    public static final int MAX_SOURCES = 3;
    public static final long EXPIRY_NANOS = 250_000_000L;
    public record Point(double x, double y, double z, double luminance) {
        public Point {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) throw new IllegalArgumentException("Nonfinite light position");
            luminance = Double.isFinite(luminance) ? Math.clamp(luminance, 0, 15) : 0;
        }
        public double lightAt(double x, double y, double z, double falloff) {
            double dx = x-this.x, dy = y-this.y, dz = z-this.z;
            return Math.max(0, luminance - Math.sqrt(dx*dx+dy*dy+dz*dz) * Math.max(0, falloff));
        }
    }
    public interface Backend {
        void update(Object world, List<Point> points);
        void clear();
    }
    private static Backend backend;
    private static final Point[] offered = new Point[MAX_SOURCES];
    private static Object offeredWorld, appliedWorld;
    private static long offeredAt, appliedAt;
    private static int count;
    private WorldLyricsLights() { }
    public static void install(Backend value) {
        if (backend != null) backend.clear(); backend=value;
        offeredWorld=null; appliedWorld=null; offeredAt=appliedAt=0; count=0; Arrays.fill(offered,null);
    }
    public static boolean available() { return backend != null; }
    public static List<Point> latestPoints() { return List.of(Arrays.copyOf(offered,count)); }
    public static void begin(Object world, long clock) {
        offeredWorld=backend==null ? null : world; offeredAt=clock; count=0; Arrays.fill(offered,null);
    }
    public static void offer(Point point) { if (backend!=null && point.luminance() > 0 && count < offered.length) offered[count++]=point; }
    public static void tick(Object world, long clock, boolean enabled) {
        if (backend == null) return;
        if (!enabled || world == null || world != offeredWorld || clock-offeredAt > EXPIRY_NANOS) {
            if (appliedWorld != null) backend.clear();
            appliedWorld=null; appliedAt=0; offeredWorld=null; offeredAt=0;
            count=0; Arrays.fill(offered,null); return;
        }
        if (appliedWorld == world && clock-appliedAt < 100_000_000L) return;
        if (appliedWorld != world) backend.clear();
        appliedWorld=world; appliedAt=clock;
        backend.update(world, List.of(Arrays.copyOf(offered,count)));
    }
}
