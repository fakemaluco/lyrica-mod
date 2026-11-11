package dev.relaylyrics.client.karaoke;

import dev.relaylyrics.client.ui.KaraokePanelMotion;

import java.util.List;
import java.util.UUID;

import static dev.relaylyrics.karaoke.KaraokeWire.*;

final class KaraokeQueueView {
    static final int DRAWER_WIDTH = 232, TAB_WIDTH = 24;
    private static final int TAB_HALF_HEIGHT = 20, TAB_INSET = 22;
    private static final int ROW_HEIGHT = 24, FOOTER = 18, MARGIN = 8;
    private static final String KEY = "karaoke.lyrica.queue.";
    private static final int INK = 0xFFF8F5FF, MUTED = 0xFFD4CADF, ACCENT = 0xFFDFCBFF;

    int side, displaySide, first;
    String status = "", submittedQuery = "";
    boolean searching, focused;
    UUID request;
    long deadline, searchAt, lastRequestAt;
    final KaraokeQueueSearch field = new KaraokeQueueSearch();
    private final KaraokePanelMotion.Tabs tabs = new KaraokePanelMotion.Tabs();
    final KaraokePanelMotion.Drawer drawer = new KaraokePanelMotion.Drawer();
    QueueResult queue = new QueueResult(List.of(), 0, "ok");
    List<QueueTrack> results = List.of();

    interface Canvas {
        void backdrop(int x, int y, int width, int height, int radius);
        void glass(int x, int y, int width, int height, int radius, double engagement);
        void fill(int x, int y, int endX, int endY, int color);
        void icon(String name, double x, double y, int size, int color);
        void text(String text, int x, int y, int maxWidth, int color);
        int width(String text);
        String label(String key);
    }

    private record Rect(int x, int y, int width, int height) {
        boolean contains(double px, double py) {
            return px >= x && px < x + width && py >= y && py < y + height;
        }
    }

    private record Layout(Rect search, Rect list, Rect close, int listTop, int rows) { }

    private static Layout layout(int width, int height) {
        boolean compact = width < 155;
        int button = 22, searchHeight = 25;
        var close = new Rect(width - MARGIN - button, MARGIN, button, button);
        var list = new Rect(close.x - button - 5, MARGIN, button, button);
        var search = compact ? new Rect(MARGIN, 34, width - MARGIN * 2, 21)
                : new Rect(MARGIN, MARGIN - 1, list.x - MARGIN - 5, searchHeight);
        int listTop = search.y + search.height + 5;
        return new Layout(search, list, close, listTop, Math.max(1, (height - listTop - FOOTER) / ROW_HEIGHT));
    }

    static int wing(boolean enabled, int openSide, int side) {
        return !enabled ? 0 : openSide == side ? DRAWER_WIDTH : TAB_WIDTH;
    }

    static int tabAt(double x, double y, int openSide) {
        int edge = x < 0 ? -1 : 1;
        double offset = Math.abs(x) - WIDTH / 2.0;
        return edge != openSide && Math.abs(y) <= TAB_HALF_HEIGHT && offset >= -TAB_INSET && offset <= TAB_WIDTH
                ? edge : 0;
    }

    static boolean contains(double x, double y, int side) {
        int edge = x < 0 ? -1 : 1;
        double distance = Math.abs(x) - WIDTH / 2.0;
        return distance > 0 && (side == edge
                ? distance <= DRAWER_WIDTH && Math.abs(y) <= HEIGHT / 2.0
                : tabAt(x, y, side) != 0);
    }

    List<QueueTrack> entries() { return searching ? results : queue.tracks(); }
    void scroll(int amount, int width, int height) {
        first = Math.clamp(first + amount, 0, Math.max(0, entries().size() - layout(width, height).rows));
    }

    void click(KaraokeClient.Room room, double panelX, double panelY, int height, int width) {
        double relative = panelX - side * WIDTH / 2.0 + (side < 0 ? DRAWER_WIDTH : 0);
        double x = relative * width / DRAWER_WIDTH;
        double y = (panelY / HEIGHT + .5) * height;
        var layout = layout(width, height);
        if (layout.close.contains(x, y)) {
            side = 0;
            searchAt = 0;
            KaraokeClient.blurQueue(room);
        } else if (layout.list.contains(x, y)) {
            KaraokeClient.blurQueue(room);
            searching = false;
            first = 0;
            status = "";
            searchAt = 0;
            KaraokeClient.requestQueue(room, "list", "", 0);
        } else if (layout.search.contains(x, y)) {
            if (!searching) {
                searching = true;
                first = 0;
                status = "";
                if (!field.value().isBlank() && results.isEmpty()) searchAt = System.nanoTime();
            }
            KaraokeClient.focusQueue(room);
        } else if (y >= height - FOOTER) {
            scroll(x < width / 2.0 ? -layout.rows : layout.rows, width, height);
        } else if (y >= layout.listTop && x >= MARGIN && x < width - MARGIN && request == null && searchAt == 0) {
            int row = (int) (y - layout.listTop) / ROW_HEIGHT;
            int index = first + row;
            if (row < layout.rows && index < entries().size()) {
                String uri = entries().get(index).uri();
                if (!uri.isBlank()) {
                    String action = searching ? "add" : "jump";
                    KaraokeClient.blurQueue(room);
                    KaraokeClient.requestQueue(room, action, uri, index);
                }
            }
        }
    }

    void open(KaraokeClient.Room room, int edge) {
        side = edge;
        if (!searching) KaraokeClient.requestQueue(room, "list", "", 0);
    }

    void edited(long now) {
        results = List.of();
        status = "";
        first = 0;
        // Keep incremental input below the server's queue request limit.
        searchAt = field.value().isBlank() ? 0 : now + 700_000_000L;
    }

    void submit(KaraokeClient.Room room, long now) {
        if (side == 0 || !searching || searchAt == 0 || now < searchAt || request != null
                || now - lastRequestAt < 650_000_000L) return;
        searchAt = 0;
        submittedQuery = text(field.value(), 100);
        if (!submittedQuery.isBlank()) KaraokeClient.requestQueue(room, "search", submittedQuery, 0);
    }

    void draw(Canvas canvas, int x, int width, int height, int edge, int hoveredTab, double pointerX,
              double pointerY, long now, boolean animate) {
        if (displaySide != edge) {
            double reveal = tabs.reveal(edge, hoveredTab == edge, now, animate);
            if (reveal <= .01) return;
            int size = Math.min(width - 2, 16);
            double inset = (width - size) / 2.0 - edge * (1 - reveal) * Math.min(4, (width - size) / 2.0);
            canvas.icon(edge < 0 ? "panel-left-open" : "panel-right-open", x + inset, (height - size) / 2.0,
                    size, (int) Math.round(reveal * 255) << 24 | (INK & 0xFFFFFF));
            return;
        }
        tabs.reveal(edge, false, now, false);
        var layout = layout(width, height);
        canvas.backdrop(x + 2, 1, width - 4, height - 2, Math.clamp(width / 12, 9, 18));
        double px = pointerX - x;
        iconButton(canvas, x, layout.list, "list-music", px, pointerY, !searching);
        iconButton(canvas, x, layout.close, "x", px, pointerY, false);
        drawSearch(canvas, x, layout.search, px, pointerY);

        List<QueueTrack> items = entries();
        int end = Math.min(items.size(), first + layout.rows);
        for (int index = first; index < end; index++) {
            int y = layout.listTop + (index - first) * ROW_HEIGHT;
            var item = items.get(index);
            var row = new Rect(MARGIN, y, width - MARGIN * 2, ROW_HEIGHT - 2);
            boolean hover = row.contains(px, pointerY) && request == null;
            if (hover) canvas.glass(x + row.x, row.y, row.width, row.height, 8, .58);
            else canvas.fill(x + MARGIN + 6, y + ROW_HEIGHT - 2, x + width - MARGIN - 6, y + ROW_HEIGHT - 1, 0x26F0E8FF);
            int endX = width - MARGIN;
            int textWidth = width - 47;
            canvas.text(item.title(), x + MARGIN + 6, y + 2, textWidth, INK);
            canvas.text(item.artist(), x + MARGIN + 6, y + 12, textWidth, MUTED);
            if (request == null && !item.uri().isBlank()) {
                int playX = endX - 15;
                iconButton(canvas, x, new Rect(playX, y, 15, ROW_HEIGHT), searching ? "plus" : "play", px, pointerY, false);
            }
        }
        String feedback = searchAt != 0 || request != null ? "loading" : status;
        if (items.isEmpty()) {
            if (feedback.isEmpty() && (!searching || !field.value().isBlank())) feedback = "empty";
            if (!feedback.isEmpty()) {
                canvas.text(canvas.label(KEY + feedback), x + MARGIN + 3, layout.listTop + 6, width - MARGIN * 2 - 6, MUTED);
            }
        } else if (first > 0 || end < items.size()) {
            iconButton(canvas, x, new Rect(MARGIN, height - 17, 15, 14), "chevron:-8", px, pointerY, false);
            iconButton(canvas, x, new Rect(width - MARGIN - 15, height - 17, 15, 14), "chevron:8", px, pointerY, false);
            String footer = feedback.isEmpty() ? (first + 1) + "–" + end + " / " + items.size() : canvas.label(KEY + feedback);
            canvas.text(footer, x + MARGIN + 20, height - 14, width - MARGIN * 2 - 40, MUTED);
        } else if (!feedback.isEmpty()) {
            canvas.text(canvas.label(KEY + feedback), x + MARGIN + 3, height - 14, width - MARGIN * 2 - 6, MUTED);
        }
    }

    private void drawSearch(Canvas canvas, int origin, Rect bounds, double px, double py) {
        int x = origin + bounds.x, y = bounds.y;
        canvas.glass(x, y, bounds.width, bounds.height, bounds.height / 2, focused ? 1 : bounds.contains(px, py) ? .72 : .30);
        canvas.icon("search", x + 8, y + (bounds.height - 13) / 2.0, 13, focused ? INK : MUTED);
        int textX = x + 27, textY = y + (bounds.height - 8) / 2, available = bounds.width - 36;
        String value = field.value();
        if (!focused) {
            canvas.text(value.isBlank() ? canvas.label(KEY + "search") : value, textX, textY, available, value.isBlank() ? MUTED : INK);
            return;
        }
        int start = 0, caret = field.cursor();
        while (start < caret && canvas.width(value.substring(start, caret)) > available - 1)
            start = value.offsetByCodePoints(start, 1);
        int end = value.length();
        while (end > caret && canvas.width(value.substring(start, end)) > available)
            end = value.offsetByCodePoints(end, -1);
        if (field.selectionEnd() > field.selectionStart()) {
            int lo = Math.clamp(field.selectionStart(), start, end), hi = Math.clamp(field.selectionEnd(), start, end);
            canvas.fill(textX + canvas.width(value.substring(start, lo)), textY - 2,
                    textX + canvas.width(value.substring(start, hi)), textY + 9, 0x607E5EAC);
        }
        canvas.text(value.substring(start, end), textX, textY, available, INK);
        int cursorX = textX + canvas.width(value.substring(start, caret));
        canvas.fill(cursorX, textY - 1, cursorX + 1, textY + 9, INK);
    }

    private static void iconButton(Canvas canvas, int origin, Rect bounds, String name, double px, double py, boolean active) {
        int x = origin + bounds.x;
        boolean hover = bounds.contains(px, py);
        int size = Math.min(hover ? 14 : 12, Math.min(bounds.width, bounds.height) - 2);
        canvas.icon(name, x + (bounds.width - size) / 2.0, bounds.y + (bounds.height - size) / 2.0,
                size, hover ? 0xFFFFFFFF : active ? ACCENT : MUTED);
    }
}
