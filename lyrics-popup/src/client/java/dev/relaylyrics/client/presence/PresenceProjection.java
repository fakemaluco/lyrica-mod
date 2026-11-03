package dev.relaylyrics.client.presence;

public final class PresenceProjection {
    public record Point(double x, double y) { }
    private PresenceProjection() { }
    /** Quaternion is the camera-to-world rotation, FOV is vertical and includes sprint/zoom. */
    public static Point project(double x, double y, double z, double qx, double qy, double qz, double qw,
                                double fov, int width, int height) {
        double ix = -qx, iy = -qy, iz = -qz;
        double tx = 2 * (iy * z - iz * y), ty = 2 * (iz * x - ix * z), tz = 2 * (ix * y - iy * x);
        double rx = x + qw * tx + iy * tz - iz * ty;
        double ry = y + qw * ty + iz * tx - ix * tz;
        double rz = z + qw * tz + ix * ty - iy * tx;
        if (rz >= -.1 || width <= 0 || height <= 0 || !Double.isFinite(fov)) return null;
        double focal = height / (2 * Math.tan(Math.toRadians(Math.clamp(fov, 1, 179)) / 2));
        double sx = width / 2.0 + rx / -rz * focal, sy = height / 2.0 - ry / -rz * focal;
        if (!Double.isFinite(sx) || !Double.isFinite(sy) || sx < 0 || sx > width || sy < 0 || sy > height) return null;
        return new Point(sx, sy);
    }
}
