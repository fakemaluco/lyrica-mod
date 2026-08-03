package dev.fandroid.client.ui;

import dev.fandroid.client.CompatibilityManager;

import java.util.ArrayList;
import java.util.List;

/** Keeps the lyrics HUD away from persistent HUD elements owned by optional mods. */
final class HudSafeArea {
    private static final int GAP = 6;

    private HudSafeArea() { }

    static Position adjust(int x, int y, int width, int height, int screenWidth, int screenHeight) {
        return adjust(x, y, width, height, screenWidth, screenHeight,
                CompatibilityManager.xaeroMinimap(), CompatibilityManager.appleSkin());
    }

    static Position adjust(int x, int y, int width, int height, int screenWidth, int screenHeight,
                           boolean xaero, boolean appleSkin) {
        int nextX = Math.clamp(x, 0, Math.max(0, screenWidth - width));
        int nextY = Math.clamp(y, 0, Math.max(0, screenHeight - height));
        List<Area> obstacles = new ArrayList<>();
        if (xaero) {
            int minimap = Math.clamp(Math.min(screenWidth, screenHeight) / 5, 112, 168);
            obstacles.add(new Area(0, 0, minimap + GAP, minimap + GAP));
            obstacles.add(new Area(Math.max(0, screenWidth - minimap - GAP), 0,
                    minimap + GAP, minimap + GAP));
        }
        if (appleSkin) {
            int barWidth = Math.min(124, screenWidth);
            obstacles.add(new Area(Math.max(0, screenWidth - barWidth),
                    Math.max(0, screenHeight - 44), barWidth, 44));
        }
        for (Area obstacle : obstacles) {
            if (!intersects(nextX, nextY, width, height, obstacle)) continue;
            Position best = closest(nextX, nextY, width, height, screenWidth, screenHeight, obstacle);
            nextX = best.x();
            nextY = best.y();
        }
        return new Position(nextX, nextY);
    }

    private static Position closest(int x, int y, int width, int height,
                                    int screenWidth, int screenHeight, Area obstacle) {
        Position[] candidates = {
                new Position(obstacle.x() - width - GAP, y),
                new Position(obstacle.right() + GAP, y),
                new Position(x, obstacle.y() - height - GAP),
                new Position(x, obstacle.bottom() + GAP)
        };
        Position best = new Position(x, y);
        long bestDistance = Long.MAX_VALUE;
        for (Position candidate : candidates) {
            int candidateX = Math.clamp(candidate.x(), 0, Math.max(0, screenWidth - width));
            int candidateY = Math.clamp(candidate.y(), 0, Math.max(0, screenHeight - height));
            if (intersects(candidateX, candidateY, width, height, obstacle)) continue;
            long dx = candidateX - (long) x;
            long dy = candidateY - (long) y;
            long distance = dx * dx + dy * dy;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = new Position(candidateX, candidateY);
            }
        }
        return best;
    }

    private static boolean intersects(int x, int y, int width, int height, Area area) {
        return x < area.right() && x + width > area.x()
                && y < area.bottom() && y + height > area.y();
    }

    record Position(int x, int y) { }
    private record Area(int x, int y, int width, int height) {
        private int right() { return x + width; }
        private int bottom() { return y + height; }
    }
}
