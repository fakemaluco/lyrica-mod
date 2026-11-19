package dev.fandroid.client.music;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Camera, surface and obstruction policy without Minecraft or render-thread dependencies. */
public final class WorldLyricsSpatial {
    public static final int SEGMENTS_PER_TICK = 128;
    private static final double EPSILON = 1.0e-8;
    private WorldLyricsSpatial() { }

    public record Vector(double x, double y, double z) {
        public Vector add(Vector other) { return new Vector(x + other.x, y + other.y, z + other.z); }
        public Vector subtract(Vector other) { return new Vector(x - other.x, y - other.y, z - other.z); }
        public Vector scale(double factor) { return new Vector(x * factor, y * factor, z * factor); }
        public double dot(Vector other) { return x * other.x + y * other.y + z * other.z; }
        public Vector cross(Vector other) {
            return new Vector(y * other.z - z * other.y, z * other.x - x * other.z, x * other.y - y * other.x);
        }
        public double length() { return Math.sqrt(dot(this)); }
        public boolean finite() { return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z); }
        public Vector normalized() {
            double length = length();
            return finite() && length > EPSILON ? scale(1 / length) : new Vector(0, 0, 0);
        }
    }

    /** Right cross up equals the outward surface normal, including horizontal floors. */
    public record Basis(Vector right, Vector up, Vector normal) {
        public static Basis facing(Vector normal, Vector preferredRight) {
            Vector n = normal.normalized();
            if (n.length() < EPSILON) n = new Vector(0, 0, 1);
            Vector r = preferredRight.subtract(n.scale(preferredRight.dot(n))).normalized();
            if (r.length() < EPSILON) {
                Vector reference = Math.abs(n.y) < .9 ? new Vector(0, 1, 0) : new Vector(0, 0, 1);
                r = reference.cross(n).normalized();
            }
            return new Basis(r, n.cross(r).normalized(), n);
        }
    }

    /** Extents and animation margin are in world units; depth is the full extrusion depth. */
    public record Panel(Vector center, Basis basis, double width, double height, double depth,
                        double animationMargin) {
        public List<Vector> corners() {
            double margin = Math.max(0, animationMargin);
            double halfWidth = Math.max(0, width) * .5 + margin;
            double halfHeight = Math.max(0, height) * .5 + margin;
            double halfDepth = Math.max(.01, depth) * .5 + margin;
            var result = new ArrayList<Vector>(8);
            for (int x : new int[]{-1, 1}) for (int y : new int[]{-1, 1}) for (int z : new int[]{-1, 1})
                result.add(center.add(basis.right.scale(x * halfWidth)).add(basis.up.scale(y * halfHeight))
                        .add(basis.normal.scale(z * halfDepth)));
            return List.copyOf(result);
        }
    }

    /** Forward points into the scene. Projected coordinates use the usual [-1, 1] viewport. */
    public record Camera(Vector position, Vector forward, Vector up, double verticalFovDegrees,
                         double aspect, double nearPlane) {
        public Camera {
            forward = forward.normalized();
            if (forward.length() < EPSILON) forward = new Vector(0, 0, 1);
            Vector right = forward.cross(up).normalized();
            if (right.length() < EPSILON)
                right = forward.cross(Math.abs(forward.y) < .9 ? new Vector(0, 1, 0) : new Vector(0, 0, 1)).normalized();
            up = right.cross(forward).normalized();
            verticalFovDegrees = Double.isFinite(verticalFovDegrees) ? Math.clamp(verticalFovDegrees, 1, 175) : 70;
            aspect = Double.isFinite(aspect) && aspect > 0 ? aspect : 16.0 / 9;
            nearPlane = Double.isFinite(nearPlane) && nearPlane > 0 ? nearPlane : .05;
        }
        public Vector right() { return forward.cross(up).normalized(); }
    }

    public record Point(double x, double y) { }
    public record Projected(List<Point> vertices, double minX, double minY, double maxX, double maxY,
                            double depth, boolean visible, boolean nearClipped) {
        public double width() { return maxX - minX; }
        public double height() { return maxY - minY; }
        public boolean clearsAim() {
            return visible && (maxX < -.045 || minX > .045 || maxY < -.060 || minY > .060);
        }
        /** The narrow screen extent, independent of in-plane rotation of a long phrase. */
        public double textHeight() {
            double minimum = Double.POSITIVE_INFINITY;
            for (int i = 0; i < vertices.size(); i++) {
                Point a = vertices.get(i), b = vertices.get((i + 1) % vertices.size());
                double dx = -(b.y - a.y), dy = b.x - a.x, length = Math.hypot(dx, dy);
                if (length < EPSILON) continue;
                dx /= length; dy /= length;
                double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
                for (Point vertex : vertices) {
                    double dot = vertex.x * dx + vertex.y * dy;
                    min = Math.min(min, dot); max = Math.max(max, dot);
                }
                minimum = Math.min(minimum, max - min);
            }
            return Double.isFinite(minimum) ? minimum : 0;
        }
        private static Projected hidden(double depth) { return new Projected(List.of(), 0, 0, 0, 0, depth, false, false); }
    }

    /** Clip the full oriented box against the near plane before computing its convex screen hull. */
    public static Projected project(Panel panel, Camera camera) {
        List<Vector> corners = panel.corners();
        var local = new ArrayList<Vector>(8);
        Vector right = camera.right();
        for (Vector corner : corners) {
            Vector offset = corner.subtract(camera.position);
            if (!offset.finite()) return Projected.hidden(0);
            local.add(new Vector(offset.dot(right), offset.dot(camera.up), offset.dot(camera.forward)));
        }
        double depth = panel.center.subtract(camera.position).dot(camera.forward);
        var clipped = new ArrayList<Vector>(20);
        int behind = 0;
        for (Vector point : local) {
            if (point.z >= camera.nearPlane) clipped.add(point);
            else behind++;
        }
        if (clipped.isEmpty()) return Projected.hidden(depth);
        // Corner order is x, then y, then z: cube edges differ in exactly one bit.
        for (int i = 0; i < 8; i++) for (int bit : new int[]{1, 2, 4}) {
            int j = i ^ bit;
            if (j <= i) continue;
            Vector a = local.get(i), b = local.get(j);
            if ((a.z < camera.nearPlane) == (b.z < camera.nearPlane)) continue;
            double t = (camera.nearPlane - a.z) / (b.z - a.z);
            clipped.add(a.add(b.subtract(a).scale(t)));
        }
        double tangent = Math.tan(Math.toRadians(camera.verticalFovDegrees) * .5);
        var points = new ArrayList<Point>(clipped.size());
        for (Vector point : clipped)
            points.add(new Point(point.x / (point.z * tangent * camera.aspect), point.y / (point.z * tangent)));
        List<Point> hull = hull(points);
        if (hull.size() < 3) return Projected.hidden(depth);
        double minX = hull.stream().mapToDouble(Point::x).min().orElse(0);
        double maxX = hull.stream().mapToDouble(Point::x).max().orElse(0);
        double minY = hull.stream().mapToDouble(Point::y).min().orElse(0);
        double maxY = hull.stream().mapToDouble(Point::y).max().orElse(0);
        boolean visible = polygonsOverlap(hull, List.of(new Point(-1, -1), new Point(1, -1),
                new Point(1, 1), new Point(-1, 1)), 0);
        return new Projected(hull, minX, minY, maxX, maxY, depth, visible, behind != 0);
    }

    /** Test polygons, rather than their axis-aligned bounds, including a text-height-relative gap. */
    public static boolean overlap(Projected first, Projected second, double marginRatio) {
        if (!first.visible || !second.visible) return false;
        double margin = Math.min(first.textHeight(), second.textHeight()) * Math.max(0, marginRatio);
        return polygonsOverlap(viewportHull(first.vertices), viewportHull(second.vertices), margin);
    }

    /** Physical clearance is independent of camera projection and visual transparency. */
    public static boolean worldOverlap(Panel first, Panel second, double margin) {
        var firstAxes = List.of(first.basis.right, first.basis.up, first.basis.normal);
        var secondAxes = List.of(second.basis.right, second.basis.up, second.basis.normal);
        Vector separation = second.center.subtract(first.center);
        double gap = Math.max(0, margin);
        for (Vector axis : firstAxes) if (separates(first, second, separation, axis, gap)) return false;
        for (Vector axis : secondAxes) if (separates(first, second, separation, axis, gap)) return false;
        for (Vector a : firstAxes) for (Vector b : secondAxes) {
            if (separates(first, second, separation, a.cross(b), gap)) return false;
        }
        return true;
    }

    // Project the center and the three half-extents of each box instead of its eight corners.
    private static boolean separates(Panel first, Panel second, Vector separation, Vector axis, double gap) {
        double lengthSquared = axis.dot(axis);
        return lengthSquared >= EPSILON * EPSILON
                && Math.abs(separation.dot(axis)) > radius(first, axis) + radius(second, axis)
                + gap * Math.sqrt(lengthSquared);
    }

    private static double radius(Panel panel, Vector axis) {
        double motion = Math.max(0, panel.animationMargin);
        return Math.abs(axis.dot(panel.basis.right)) * (Math.max(0, panel.width) * .5 + motion)
                + Math.abs(axis.dot(panel.basis.up)) * (Math.max(0, panel.height) * .5 + motion)
                + Math.abs(axis.dot(panel.basis.normal)) * (Math.max(.01, panel.depth) * .5 + motion);
    }

    /** A surface facing the camera is readable; grazing and back-facing surfaces fade smoothly. */
    public static double facingAlpha(Panel panel, Camera camera) {
        Vector toCamera = camera.position.subtract(panel.center).normalized();
        double alignment = panel.basis.normal.dot(toCamera);
        double t = Math.clamp((alignment - .10) / .28, 0, 1);
        return t * t * (3 - 2 * t);
    }

    public enum Material { CLEAR, GLASS, FOLIAGE, OPAQUE }

    /** An axis-aligned piece of a block's real outline or collision shape, in world coordinates. */
    public record Box(Vector minimum, Vector maximum) { }
    public record SegmentCoverage(double thickness, double exitFraction) {
        public boolean intersects() { return thickness > EPSILON; }
    }

    /** Union all intersected shape pieces so multipart panes neither become full cubes nor count twice. */
    public static SegmentCoverage coverage(Vector start, Vector end, List<Box> shape) {
        Vector delta = end.subtract(start);
        double length = delta.length();
        if (!start.finite() || !end.finite() || length < EPSILON) return new SegmentCoverage(0, 0);
        var intervals = new ArrayList<double[]>(shape.size());
        for (Box box : shape) {
            double enter = 0, exit = 1;
            boolean intersects = true;
            for (int axis = 0; axis < 3; axis++) {
                double origin = component(start, axis), direction = component(delta, axis);
                double min = component(box.minimum, axis), max = component(box.maximum, axis);
                if (Math.abs(direction) < EPSILON) {
                    if (origin < min || origin > max) { intersects = false; break; }
                } else {
                    double first = (min - origin) / direction, second = (max - origin) / direction;
                    enter = Math.max(enter, Math.min(first, second));
                    exit = Math.min(exit, Math.max(first, second));
                    if (exit <= enter) { intersects = false; break; }
                }
            }
            if (intersects && exit > enter) intervals.add(new double[]{enter, exit});
        }
        if (intervals.isEmpty()) return new SegmentCoverage(0, 0);
        intervals.sort(Comparator.comparingDouble(interval -> interval[0]));
        double covered = 0, enter = intervals.get(0)[0], exit = intervals.get(0)[1];
        for (int i = 1; i < intervals.size(); i++) {
            double[] interval = intervals.get(i);
            if (interval[0] > exit) { covered += exit - enter; enter = interval[0]; }
            exit = Math.max(exit, interval[1]);
        }
        return new SegmentCoverage((covered + exit - enter) * length, exit);
    }

    private static double component(Vector vector, int axis) {
        return axis == 0 ? vector.x : axis == 1 ? vector.y : vector.z;
    }

    /** Count each intersected block once with its actual segment length, never its bounding cube. */
    public static final class VisualOcclusion {
        private double transmission = 1;
        public VisualOcclusion add(Material material, double thicknessBlocks) {
            if (material == Material.CLEAR || !(thicknessBlocks > 0)) return this;
            if (material == Material.OPAQUE) { transmission = 0; return this; }
            double thickness = Math.min(thicknessBlocks, 4);
            double unitTransmission = material == Material.GLASS ? .96 : .68;
            transmission *= Math.pow(unitTransmission, thickness);
            return this;
        }
        public double opacity() { return 1 - transmission; }
        public boolean blocked() { return opacity() >= .72; }
    }

    /** UNKNOWN is a deferred query, never evidence of an obstacle. */
    public enum Clearance { CLEAR, BLOCKED, UNKNOWN }

    /** Shared by support, collision and visibility queries; tick identifiers come from the game. */
    public static final class RayBudget {
        private long tick = Long.MIN_VALUE;
        private int used;
        public void beginTick(long tick) {
            if (this.tick != tick) { this.tick = tick; used = 0; }
        }
        public boolean tryAcquire() {
            if (used >= SEGMENTS_PER_TICK) return false;
            used++;
            return true;
        }
        public int used() { return used; }
        public boolean exhausted() { return used >= SEGMENTS_PER_TICK; }
        public int remaining() { return SEGMENTS_PER_TICK - used; }
    }

    private static List<Point> hull(List<Point> points) {
        var sorted = new ArrayList<>(points);
        sorted.sort(Comparator.comparingDouble(Point::x).thenComparingDouble(Point::y));
        var hull = new ArrayList<Point>(sorted.size() * 2);
        for (Point point : sorted) {
            while (hull.size() >= 2 && cross(hull.get(hull.size() - 2), hull.get(hull.size() - 1), point) <= EPSILON)
                hull.remove(hull.size() - 1);
            hull.add(point);
        }
        int lower = hull.size();
        for (int i = sorted.size() - 2; i >= 0; i--) {
            Point point = sorted.get(i);
            while (hull.size() > lower && cross(hull.get(hull.size() - 2), hull.get(hull.size() - 1), point) <= EPSILON)
                hull.remove(hull.size() - 1);
            hull.add(point);
        }
        if (!hull.isEmpty()) hull.remove(hull.size() - 1);
        return List.copyOf(hull);
    }

    private static double cross(Point a, Point b, Point c) {
        return (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
    }

    /** Only intersections inside the actual viewport can hide another caption. */
    private static List<Point> viewportHull(List<Point> polygon) {
        List<Point> clipped = polygon;
        for (int boundary = 0; boundary < 4; boundary++) {
            if (clipped.isEmpty()) return clipped;
            var next = new ArrayList<Point>(clipped.size() + 2);
            Point previous = clipped.get(clipped.size() - 1);
            double previousDistance = viewportDistance(previous, boundary);
            for (Point current : clipped) {
                double currentDistance = viewportDistance(current, boundary);
                if ((previousDistance >= 0) != (currentDistance >= 0)) {
                    double t = previousDistance / (previousDistance - currentDistance);
                    next.add(new Point(previous.x + (current.x - previous.x) * t,
                            previous.y + (current.y - previous.y) * t));
                }
                if (currentDistance >= 0) next.add(current);
                previous = current;
                previousDistance = currentDistance;
            }
            clipped = next;
        }
        return clipped;
    }

    private static double viewportDistance(Point point, int boundary) {
        return switch (boundary) {
            case 0 -> point.x + 1;
            case 1 -> 1 - point.x;
            case 2 -> point.y + 1;
            default -> 1 - point.y;
        };
    }

    private static boolean polygonsOverlap(List<Point> first, List<Point> second, double margin) {
        if (first.size() < 3 || second.size() < 3) return false;
        return !separatingAxis(first, second, margin) && !separatingAxis(second, first, margin);
    }

    private static boolean separatingAxis(List<Point> axes, List<Point> other, double margin) {
        for (int i = 0; i < axes.size(); i++) {
            Point a = axes.get(i), b = axes.get((i + 1) % axes.size());
            double dx = -(b.y - a.y), dy = b.x - a.x;
            double length = Math.hypot(dx, dy);
            if (length < EPSILON) continue;
            dx /= length; dy /= length;
            double minA = Double.POSITIVE_INFINITY, maxA = Double.NEGATIVE_INFINITY;
            double minB = Double.POSITIVE_INFINITY, maxB = Double.NEGATIVE_INFINITY;
            for (Point point : axes) { double dot = point.x * dx + point.y * dy; minA = Math.min(minA, dot); maxA = Math.max(maxA, dot); }
            for (Point point : other) { double dot = point.x * dx + point.y * dy; minB = Math.min(minB, dot); maxB = Math.max(maxB, dot); }
            if (maxA + margin < minB || maxB + margin < minA) return true;
        }
        return false;
    }
}
