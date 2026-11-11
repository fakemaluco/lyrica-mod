package dev.relaylyrics.client.karaoke;

import dev.relaylyrics.client.RelayLyricsClient;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

import static dev.relaylyrics.karaoke.KaraokeWire.*;

/** Owns the nearest visible surfaces, prioritizing the aimed panel and active sessions. */
final class KaraokeSurfaces {
    private static final int MAX_SURFACES = 4;
    private static final int MAX_TEXTURE_PIXELS = 640_000;
    private static final Map<KaraokeClient.Room, Surface> SURFACES = new LinkedHashMap<>();
    private static final Map<KaraokeClient.Room, Surface> CLOSING = new LinkedHashMap<>();
    private static Surface idleInvitation;

    static final class Surface implements AutoCloseable {
        private final int slot;
        private final KaraokePopup popup = new KaraokePopup();
        private final KaraokePopupTarget target;
        private long nextPaint;
        private boolean wasAimed;
        private int side;

        Surface(int slot) { this.slot = slot; target = new KaraokePopupTarget(slot); }
        KaraokePopup popup() { return popup; }
        KaraokePopupTarget target() { return target; }
        @Override public void close() {
            popup.close();
            target.close();
        }
    }

    static Surface get(KaraokeClient.Room room) {
        var surface = SURFACES.get(room);
        return surface != null || room.accepted || room.dismissed ? surface : idleInvitation;
    }

    static boolean motionEnabled() {
        return RelayLyricsClient.preferences().isLyricsAnimations();
    }

    static boolean spatialMotionEnabled() {
        return motionEnabled() && !RelayLyricsClient.preferences().isSimpleLyricsMode();
    }

    static void beginClose(KaraokeClient.Room room) {
        var surface = SURFACES.remove(room);
        if (surface == null) return;
        if (!motionEnabled()) {
            surface.close();
            return;
        }
        room.motion.close(System.nanoTime());
        CLOSING.put(room, surface);
    }

    static Surface closingSurface(KaraokeClient.Room room) { return CLOSING.get(room); }

    static Collection<KaraokeClient.Room> renderRooms() {
        if (CLOSING.isEmpty()) return KaraokeClient.rooms();
        var rooms = new ArrayList<>(KaraokeClient.rooms());
        for (var room : CLOSING.keySet()) if (!rooms.contains(room)) rooms.add(room);
        return rooms;
    }

    static int textureDensity(int width, int height) {
        return 4 * width * height <= MAX_TEXTURE_PIXELS ? 2 : 1;
    }

    static void paint() {
        long now = System.nanoTime();
        boolean animate = motionEnabled();
        CLOSING.entrySet().removeIf(entry -> {
            if (entry.getKey().motion.opacity(now, animate) > 0) return false;
            entry.getValue().close();
            return true;
        });
        if (KaraokeClient.rooms().isEmpty() && CLOSING.isEmpty()) {
            clear();
            return;
        }
        var hit = KaraokeClient.hit();
        var dragged = KaraokeClient.draggedRoom();
        var dragPose = dragged == null ? null : KaraokeClient.draggedPanel(KaraokePlatform.ray());
        // Keep existing offscreen surfaces available for a quick turn back to the panel.
        var inView = new HashSet<KaraokeClient.Room>();
        var nearby = new ArrayList<Map.Entry<KaraokeClient.Room, Double>>();
        var distances = new java.util.HashMap<KaraokeClient.Room, Double>();
        for (var room : KaraokeClient.rooms()) {
            double distance = KaraokePlatform.visibleDistance(room);
            if (distance > RANGE * RANGE) continue;
            var panel = room == dragged && dragPose != null ? dragPose : room.panel;
            if (KaraokePlatform.inView(room, panel)) inView.add(room);
            if (inView.contains(room) || SURFACES.containsKey(room)) nearby.add(Map.entry(room, distance));
            distances.put(room, distance);
        }
        // An aimed invitation gets its own surface without evicting a joined session.
        int capacity = MAX_SURFACES + (hit != null && !hit.room().accepted ? 1 : 0);
        var ranked = nearby.stream()
                .sorted(Comparator.<Map.Entry<KaraokeClient.Room, Double>>comparingInt(entry -> {
                    var room = entry.getKey();
                    if (!inView.contains(room)) return 3;
                    if (hit != null && hit.room() == room) return 0;
                    return room.accepted ? 1 : 2;
                }).thenComparingDouble(Map.Entry::getValue))
                .map(Map.Entry::getKey).toList();
        var visible = ranked.subList(0, Math.min(capacity, ranked.size()));
        // Distant invitations share an unhighlighted texture. Their aimed version
        // uses its own dimensions, so resize and hover keep the controls aligned.
        var spare = ranked.stream().skip(visible.size()).filter(room -> !room.accepted && inView.contains(room)).findFirst();
        if (spare.isPresent()) {
            if (idleInvitation == null) idleInvitation = new Surface(0);
            if (now >= idleInvitation.nextPaint) {
                idleInvitation.popup.update(spare.get());
                idleInvitation.popup.preview = null;
                idleInvitation.target.paint(idleInvitation.popup, Double.NaN, Double.NaN);
                idleInvitation.nextPaint = now + 500_000_000L;
            }
        } else if (idleInvitation != null) {
            idleInvitation.close();
            idleInvitation = null;
        }
        SURFACES.entrySet().removeIf(entry -> {
            if (visible.contains(entry.getKey())) return false;
            entry.getValue().close();
            return true;
        });
        // Retained exit textures still own their slots. Keep slot zero reserved for the shared invitation.
        int usedSlots = 1;
        for (var surface : SURFACES.values()) usedSlots |= 1 << surface.slot;
        for (var surface : CLOSING.values()) usedSlots |= 1 << surface.slot;
        for (var room : visible) {
            if (!inView.contains(room)) continue;
            var surface = SURFACES.get(room);
            if (surface == null) {
                int slot = Integer.numberOfTrailingZeros(~usedSlots);
                if (slot >= Integer.SIZE) continue;
                usedSlots |= 1 << slot;
                surface = new Surface(slot);
                SURFACES.put(room, surface);
            }
            boolean aimed = hit != null && hit.room() == room;
            var panel = room == dragged && dragPose != null ? dragPose : room.panel;
            var popup = surface.popup;
            // Quantize by four logical pixels so small mouse movements reuse the GPU target.
            int width = (int) Math.round(WIDTH * panel.zoom() / 4) * 4;
            int height = (int) Math.round(HEIGHT * panel.heightZoom() / 4) * 4;
            boolean changed = popup.width != width || popup.height != height || aimed != surface.wasAimed
                    || surface.side != room.queue.side || popup.invitation == room.accepted;
            if (!changed && now < surface.nextPaint) continue;
            surface.popup.update(room);
            popup.width = width;
            popup.height = height;
            if (room.queue.side != 0) room.queue.scroll(0, popup.wingPixels(KaraokeQueueView.DRAWER_WIDTH), height);
            surface.target.paint(popup, aimed ? popup.pointerX(hit.x()) : Double.NaN,
                    aimed ? popup.pointerY(hit.y()) : Double.NaN);
            surface.wasAimed = aimed;
            surface.side = room.queue.side;
            double distance = distances.get(room);
            boolean drawerMoving = popup.drawerProgress > 0 && popup.drawerProgress < 1;
            surface.nextPaint = now + (aimed || room == dragged || room.queue.focused || drawerMoving ? 16_000_000L
                    : room.accepted && distance < 64 ? 25_000_000L
                    : room.accepted ? 50_000_000L : 100_000_000L);
        }
    }

    static void clear() {
        SURFACES.values().forEach(Surface::close);
        SURFACES.clear();
        CLOSING.values().forEach(Surface::close);
        CLOSING.clear();
        if (idleInvitation != null) {
            idleInvitation.close();
            idleInvitation = null;
        }
        KaraokePopupTarget.clearRenderer();
    }
}
