package dev.relaylyrics.client.karaoke;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KaraokeQueueViewTest {
    @Test
    void drawerKeepsItsSideThroughClosingAndSnapsWithReducedMotion() {
        var drawer = new dev.relaylyrics.client.ui.KaraokePanelMotion.Drawer();
        long now = 1_000_000_000L;
        assertEquals(0, drawer.sample(1, now, true).progress());
        assertEquals(1, drawer.sample(1, now + 250_000_000L, true).progress());
        assertEquals(1, drawer.sample(0, now + 250_000_000L, true).side());
        var closing = drawer.sample(0, now + 300_000_000L, true);
        assertEquals(1, closing.side());
        assertTrue(closing.progress() > 0 && closing.progress() < 1);
        assertEquals(0, drawer.sample(0, now + 420_000_000L, true).side());
        assertEquals(1, drawer.sample(-1, now + 450_000_000L, false).progress());
        assertEquals(0, drawer.sample(0, now + 460_000_000L, false).side());
    }
    @Test
    void onlyRevealsTheIconAtTheCenterOfTheAimedEdge() {
        var view = new KaraokeQueueView();
        var canvas = new RecordingCanvas();
        assertEquals(0, KaraokeQueueView.tabAt(0, 0, 0));
        assertEquals(0, KaraokeQueueView.tabAt(-158, 45, 0));
        assertEquals(-1, KaraokeQueueView.tabAt(-158, 4, 0));
        assertEquals(1, KaraokeQueueView.tabAt(170, 4, 0));
        assertEquals(0, KaraokeQueueView.tabAt(170, 4, 1));

        view.draw(canvas, 0, 24, 200, -1, 0, 0, 0, 1_000_000_000L, true);
        assertTrue(canvas.icons.isEmpty());
        view.draw(canvas, 0, 24, 200, -1, -1, 0, 0, 1_000_000_000L, true);
        assertTrue(canvas.icons.isEmpty());
        view.draw(canvas, 0, 24, 200, -1, -1, 0, 0, 1_075_000_000L, true);
        assertEquals(List.of("panel-left-open"), canvas.icons);
        view.draw(canvas, 0, 24, 200, -1, 0, 0, 0, 1_075_000_000L, false);
        assertEquals(List.of("panel-left-open"), canvas.icons);
    }

    @Test
    void searchFieldEditsUnicodeAndRetainsSpaces() {
        var field = new KaraokeQueueSearch();
        assertTrue(field.insert("Céu ☀"));
        field.step(-1, false);
        assertTrue(field.insert("azul "));
        assertEquals("Céu azul ☀", field.value());
        field.end(false);
        assertTrue(field.insert("🎵"));
        assertTrue(field.erase(-1));
        assertEquals("Céu azul ☀", field.value());
        field.selectAll();
        assertTrue(field.insert("x".repeat(101)));
        assertEquals(100, field.value().length());
        assertFalse(field.insert("y"));
    }

    private static final class RecordingCanvas implements KaraokeQueueView.Canvas {
        final List<String> icons = new ArrayList<>();
        @Override public void backdrop(int x, int y, int width, int height, int radius) { }
        @Override public void glass(int x, int y, int width, int height, int radius, double engagement) { }
        @Override public void fill(int x, int y, int endX, int endY, int color) { }
        @Override public void icon(String name, double x, double y, int size, int color) { icons.add(name); }
        @Override public void text(String text, int x, int y, int maxWidth, int color) { }
        @Override public int width(String text) { return text.length(); }
        @Override public String label(String key) { return key; }
    }
}
