package dev.relaylyrics.client.ui;

import java.util.ArrayList;
import java.util.List;

/** Closed prism union of the solid glyph pixels. Empty counters remain empty all the way through. */
final class WorldGlyphMesh {
    // FRONT/BACK span rectangles. The other faces are exposed contour segments, never interior cell walls.
    enum Side { FRONT, BACK, LEFT, RIGHT, TOP, BOTTOM }
    record Face(int x0, int y0, int x1, int y1, Side side, int sampleX, int sampleY) { }
    private WorldGlyphMesh() { }

    static List<Face> build(boolean[][] mask) {
        int height = mask.length, width = height == 0 ? 0 : mask[0].length;
        for (boolean[] row : mask) if (row.length != width) throw new IllegalArgumentException("Ragged glyph mask");
        List<Face> faces = new ArrayList<>();
        boolean[][] covered = new boolean[height][width];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            if (!mask[y][x] || covered[y][x]) continue;
            int right = x + 1;
            while (right < width && mask[y][right] && !covered[y][right]) right++;
            int bottom = y + 1;
            outer: while (bottom < height) {
                for (int i = x; i < right; i++) if (!mask[bottom][i] || covered[bottom][i]) break outer;
                bottom++;
            }
            for (int j = y; j < bottom; j++) for (int i = x; i < right; i++) covered[j][i] = true;
            faces.add(new Face(x, y, right, bottom, Side.FRONT, x, y));
            faces.add(new Face(x, y, right, bottom, Side.BACK, x, y));
        }
        for (int y = 0; y < height; y++) for (Side side : new Side[]{Side.TOP, Side.BOTTOM}) {
            int neighbor = y + (side == Side.TOP ? -1 : 1);
            for (int x = 0; x < width;) {
                if (!solid(mask, x, y) || solid(mask, x, neighbor)) { x++; continue; }
                int start = x++;
                while (x < width && solid(mask, x, y) && !solid(mask, x, neighbor)) x++;
                int edgeY = side == Side.TOP ? y : y + 1;
                faces.add(new Face(start, edgeY, x, edgeY, side, start, y));
            }
        }
        for (int x = 0; x < width; x++) for (Side side : new Side[]{Side.LEFT, Side.RIGHT}) {
            int neighbor = x + (side == Side.LEFT ? -1 : 1);
            for (int y = 0; y < height;) {
                if (!solid(mask, x, y) || solid(mask, neighbor, y)) { y++; continue; }
                int start = y++;
                while (y < height && solid(mask, x, y) && !solid(mask, neighbor, y)) y++;
                int edgeX = side == Side.LEFT ? x : x + 1;
                faces.add(new Face(edgeX, start, edgeX, y, side, x, start));
            }
        }
        return List.copyOf(faces);
    }

    private static boolean solid(boolean[][] mask, int x, int y) {
        return y >= 0 && y < mask.length && x >= 0 && x < mask[y].length && mask[y][x];
    }
}
