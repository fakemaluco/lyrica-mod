package dev.fandroid.client.music;

/** Wall-clock movement policy. Input is entity velocity in blocks/second, never camera bob or position delta. */
public final class WorldLyricsContext {
    public static final long CONFIRM_NANOS = 300_000_000L;
    private static final long UNSET = Long.MIN_VALUE;
    private static final double VELOCITY_SECONDS = .24;
    private static final int[] SECTOR_ORDER = {0, 1, -1, 2, -2, 3, -3, 4};

    private WorldLyricsContext() { }

    public enum Mode { STILL, CROUCH, WALK, SPRINT, AIR, SWIM, VEHICLE, FLIGHT }

    public record Input(double vx, double vy, double vz, boolean onGround, boolean crouching,
                        boolean sprinting, boolean swimming, boolean vehicle, boolean flying) { }

    public record Snapshot(Mode mode, double speed, double acceleration, double dirX, double dirY, double dirZ) {
        public static final Snapshot STILL = new Snapshot(Mode.STILL, 0, 0, 0, 0, 1);

        public Snapshot {
            mode = mode == null ? Mode.STILL : mode;
            speed = Double.isFinite(speed) ? Math.clamp(speed, 0, 160) : 0;
            acceleration = Double.isFinite(acceleration) ? Math.clamp(acceleration, -30, 30) : 0;
            double length = Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
            if (!Double.isFinite(length) || length < .0001) { dirX = 0; dirY = 0; dirZ = 1; }
            else { dirX /= length; dirY /= length; dirZ /= length; }
        }

        public boolean shortGroups() {
            return mode == Mode.SPRINT || mode == Mode.FLIGHT || mode == Mode.VEHICLE && speed >= 6;
        }

        /** Angles are 45-degree sectors relative to travel; the world adapter rejects unreadable sectors. */
        public int sector(String cueId, int groupIndex, int attempt) {
            int seed = (cueId == null ? 0 : cueId.hashCode()) + groupIndex * 3;
            int base = shortGroups() ? Math.floorMod(seed, 3) - 1 : Math.floorMod(seed + mode.ordinal(), 8);
            return Math.floorMod(base + SECTOR_ORDER[Math.floorMod(attempt, 8)], 8);
        }

        public double readingDistance(double chunkSeconds, WorldLyricsSpace.DistanceRange range) {
            return readingDistance(chunkSeconds, range, 1);
        }

        /** Only the next chunk's reading time contributes; long verses cannot produce giant runways. */
        public double readingDistance(double chunkSeconds, WorldLyricsSpace.DistanceRange range, double sensitivity) {
            double duration = Double.isFinite(chunkSeconds) ? Math.clamp(chunkSeconds, .35, 2.5) : 1;
            double factor = switch (mode) {
                case STILL -> .35;
                case CROUCH -> .42;
                case WALK, SWIM -> .62;
                case AIR -> .70;
                case SPRINT -> .80;
                case VEHICLE -> .92;
                case FLIGHT -> 1;
            };
            double anticipation = .35 + duration * .45 + Math.clamp(acceleration / 100, 0, .12);
            double amount = Double.isFinite(sensitivity) ? Math.clamp(sensitivity, 0, 2) : 1;
            return range.clamp(range.minimum() + speed * anticipation * factor * amount);
        }
    }

    public static final class Tracker {
        private long observedAt = UNSET, pendingSince = UNSET;
        private Mode mode = Mode.STILL, pending = Mode.STILL;
        private double vx, vy, vz, dirX, dirY, dirZ = 1;
        private Snapshot snapshot = Snapshot.STILL;

        public Snapshot update(long nowNanos, Input input) {
            if (input == null || !valid(input)) return snapshot;
            if (observedAt != UNSET && nowNanos < observedAt) reset();
            Mode requested = classify(input, mode);
            boolean first = observedAt == UNSET;
            double seconds = first ? 0 : (nowNanos - observedAt) / 1_000_000_000.0;
            // A suspended client must not interpret an unobserved interval as a sustained turn or mode.
            if (seconds > 2) { pendingSince = UNSET; seconds = 0; }
            if (first) mode = pending = requested;
            else if (requested == mode) { pending = mode; pendingSince = UNSET; }
            else if (requested != pending || pendingSince == UNSET) {
                pending = requested; pendingSince = nowNanos;
            } else if (nowNanos - pendingSince >= CONFIRM_NANOS) {
                mode = pending; pendingSince = UNSET;
            }
            observedAt = nowNanos;
            // Grounded movement ignores vertical collision/bob impulses; short jumps keep that basis.
            double targetY = switch (mode) { case AIR, SWIM, FLIGHT -> input.vy; default -> 0; };
            double alpha = first ? 1 : -Math.expm1(-seconds / VELOCITY_SECONDS);
            vx += (input.vx - vx) * alpha;
            vy += (targetY - vy) * alpha;
            vz += (input.vz - vz) * alpha;
            double speed = Math.sqrt(vx * vx + vy * vy + vz * vz);
            double acceleration = speed < .0001 ? 0
                    : (vx * (input.vx - vx) + vy * (targetY - vy) + vz * (input.vz - vz))
                    / (speed * VELOCITY_SECONDS);
            // A stopped player retains the last travel bearing; looking around does not redirect it.
            if (speed > .12) { dirX = vx / speed; dirY = vy / speed; dirZ = vz / speed; }
            snapshot = new Snapshot(mode, speed, acceleration, dirX, dirY, dirZ);
            return snapshot;
        }

        public Snapshot snapshot() { return snapshot; }

        public void reset() {
            observedAt = pendingSince = UNSET;
            mode = pending = Mode.STILL;
            vx = vy = vz = dirX = dirY = 0; dirZ = 1;
            snapshot = Snapshot.STILL;
        }

        private static boolean valid(Input input) {
            double squared = input.vx * input.vx + input.vy * input.vy + input.vz * input.vz;
            // Corrupt readings/teleport-derived impulses must not launch lyrics hundreds of blocks away.
            return Double.isFinite(squared) && squared <= 160 * 160;
        }

        private static Mode classify(Input input, Mode current) {
            if (input.flying) return Mode.FLIGHT;
            if (input.vehicle) return Mode.VEHICLE;
            if (input.swimming) return Mode.SWIM;
            if (!input.onGround) return Mode.AIR;
            if (input.crouching) return Mode.CROUCH;
            double horizontal = Math.hypot(input.vx, input.vz);
            if (input.sprinting || horizontal >= (current == Mode.SPRINT ? 4.6 : 5.2)) return Mode.SPRINT;
            return horizontal >= (current == Mode.STILL ? .28 : .12) ? Mode.WALK : Mode.STILL;
        }
    }
}
