package dev.relaylyrics.client.karaoke;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

import static dev.relaylyrics.karaoke.KaraokeWire.*;

/** Owns only the nearest visible surfaces. Joining a room still works outside that render budget. */
final class KaraokeSurfaces {
    private static final int MAX_SURFACES = 4;
    private static final Map<KaraokeClient.Room, Surface> SURFACES = new LinkedHashMap<>();

    record Surface(int slot, KaraokePopup popup, KaraokePopupTarget target) implements AutoCloseable {
        Surface(int slot) { this(slot, new KaraokePopup(), new KaraokePopupTarget(slot)); }
        @Override public void close() {
            popup.close();
            target.close();
        }
    }

    static Surface get(KaraokeClient.Room room) { return SURFACES.get(room); }

    static void paint() {
        if (KaraokeClient.rooms().isEmpty()) {
            clear();
            return;
        }
        var hit = KaraokeClient.hit();
        var visible = KaraokeClient.rooms().stream()
                .filter(room -> room.accepted && !room.dismissed)
                .map(room -> Map.entry(room, KaraokePlatform.visibleDistance(room)))
                .filter(entry -> entry.getValue() <= RANGE * RANGE)
                .sorted(Comparator.comparingDouble(entry -> hit != null && hit.room() == entry.getKey()
                        ? -1 : entry.getValue()))
                .limit(MAX_SURFACES).map(Map.Entry::getKey).toList();
        SURFACES.entrySet().removeIf(entry -> {
            if (visible.contains(entry.getKey())) return false;
            entry.getValue().close();
            return true;
        });
        var dragged = KaraokeClient.draggedRoom();
        var dragPose = dragged == null ? null : KaraokeClient.draggedPanel(KaraokePlatform.ray());
        int usedSlots = 0;
        for (var surface : SURFACES.values()) usedSlots |= 1 << surface.slot;
        for (var room : visible) {
            var surface = SURFACES.get(room);
            if (surface == null) {
                int slot = Integer.numberOfTrailingZeros(~usedSlots);
                usedSlots |= 1 << slot;
                surface = new Surface(slot);
                SURFACES.put(room, surface);
            }
            surface.popup.update(room);
            boolean aimed = hit != null && hit.room() == room;
            var panel = room == dragged && dragPose != null ? dragPose : room.panel;
            var popup = surface.popup;
            // Quantize by four logical pixels so small mouse movements reuse the GPU target.
            popup.width = (int) Math.round(WIDTH * panel.zoom() / 4) * 4;
            popup.height = (int) Math.round(HEIGHT * panel.heightZoom() / 4) * 4;
            surface.target.paint(popup, aimed ? popup.pointerX(hit.x()) : Double.NaN,
                    aimed ? popup.pointerY(hit.y()) : Double.NaN);
        }
    }

    static void clear() {
        SURFACES.values().forEach(Surface::close);
        SURFACES.clear();
        KaraokePopupTarget.clearRenderer();
    }
}
