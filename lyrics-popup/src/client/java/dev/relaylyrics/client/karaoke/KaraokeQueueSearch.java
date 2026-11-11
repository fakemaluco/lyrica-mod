package dev.relaylyrics.client.karaoke;

/** A Unicode text field drawn on the world panel while the player keeps the same view. */
final class KaraokeQueueSearch {
    private static final int MAX_LENGTH = 100;
    private String value = "";
    private int cursor, anchor;

    String value() { return value; }
    int cursor() { return cursor; }
    int selectionStart() { return Math.min(cursor, anchor); }
    int selectionEnd() { return Math.max(cursor, anchor); }
    String selection() { return value.substring(selectionStart(), selectionEnd()); }

    void selectAll() { anchor = 0; cursor = value.length(); }
    void home(boolean extend) { move(0, extend); }
    void end(boolean extend) { move(value.length(), extend); }

    void step(int direction, boolean extend) {
        if (!extend && cursor != anchor) {
            move(direction < 0 ? selectionStart() : selectionEnd(), false);
        } else if (direction < 0 ? cursor > 0 : cursor < value.length()) {
            move(value.offsetByCodePoints(cursor, direction), extend);
        }
    }

    private void move(int position, boolean extend) {
        cursor = position;
        if (!extend) anchor = cursor;
    }

    boolean erase(int direction) {
        if (cursor == anchor && (direction < 0 ? cursor > 0 : cursor < value.length())) {
            anchor = value.offsetByCodePoints(cursor, direction);
        }
        return insert("");
    }

    boolean insert(String text) {
        int start = selectionStart(), end = selectionEnd();
        int available = MAX_LENGTH - value.codePointCount(0, start) - value.codePointCount(end, value.length());
        var replacement = new StringBuilder();
        text.codePoints().filter(cp -> !Character.isISOControl(cp) && Character.getType(cp) != Character.FORMAT
                && Character.getType(cp) != Character.SURROGATE && cp != '§')
                .limit(available).forEach(replacement::appendCodePoint);
        String updated = value.substring(0, start) + replacement + value.substring(end);
        move(start + replacement.length(), false);
        if (updated.equals(value)) return false;
        value = updated;
        return true;
    }
}
