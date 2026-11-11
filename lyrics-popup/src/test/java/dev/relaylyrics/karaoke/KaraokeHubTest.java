package dev.relaylyrics.karaoke;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import java.util.*;
import static dev.relaylyrics.karaoke.KaraokeWire.*;
import static org.junit.jupiter.api.Assertions.*;

class KaraokeHubTest {
    @Test
    void sharesPresenceWithoutPublishingPlaybackDetails() {
        var hub = new KaraokeHub();
        var host = new Player();
        var guest = new Player();
        hub.join(host);
        hub.join(guest);
        hub.receive(host, message("presence", "song", song("Song"), "player", guest.id), 1000);
        var presence = guest.packet("presence");
        assertEquals(host.id, id(presence, "player"));
        var publicSong = read(presence, "song", Song.class);
        assertEquals("Song", publicSong.title());
        assertEquals("", publicSong.uri());
        assertEquals(0, publicSong.position());
        hub.leave(host.id);
        assertNull(read(guest.packet("presence"), "song", Song.class));
    }

    @Test
    void onlyConsentingPlayersGetTrackAndLegacyLyricsAreIgnored() {
        var hub = new KaraokeHub();
        var host = new Player();
        var guest = new Player();
        hub.join(host);
        hub.join(guest);
        assertTrue(hub.open(host, host.position, 1000));
        hub.receive(host, message("update", "room", host.id, "song", song("Song")), 1250);
        hub.receive(host, message("lyrics", "room", host.id, "part", 0, "sync", "WORD", "lines",
                List.of(Map.of("start", 1000, "end", 2000, "text", "Legacy lyrics"))), 1300);
        assertNull(guest.packet("track"));
        assertNull(guest.packet("lyrics"));
        guest.position = new Panel("world", 35, 0, 0, 0);
        hub.receive(guest, message("accept", "room", host.id), 1500);
        assertNull(guest.packet("accepted"));
        guest.position = host.position;
        hub.receive(guest, message("accept", "room", host.id), 1500);
        assertNotNull(guest.packet("accepted"));
        assertEquals(1250, read(guest.packet("track"), "song", Song.class).position());
        assertNull(guest.packet("lyrics"));

        hub.receive(guest, message("cursor", "room", host.id, "pointer", Pointer.fromPanel(150, 90)), 1600);
        assertNull(guest.packet("cursor"));
        assertEquals(guest.id, id(host.packet("cursor"), "player"));
        var pointer = read(host.packet("cursor"), "pointer", Pointer.class);
        assertEquals(150, pointer.panelX(), .001);
        assertEquals(90, pointer.panelY(), .001);

        var moved = new Panel(host.position.dimension(), 2, 1, 0, 65, 1.7, .8);
        hub.receive(guest, message("move", "room", host.id, "panel", moved), 1650);
        assertEquals(host.position, read(guest.packet("panel"), "panel", Panel.class));
        hub.receive(host, message("move", "room", host.id, "panel", moved), 1700);
        assertEquals(moved, read(guest.packet("panel"), "panel", Panel.class));

        hub.receive(guest, message("control", "room", host.id, "action", "next"), 1800);
        assertEquals("next", string(host.packet("control"), "action"));
        guest.position = new Panel("other_world", 0, 0, 0, 0);
        guest.packets.clear();
        hub.receive(host, message("update", "room", host.id, "song", song("Song")), 2000);
        assertNotNull(guest.packet("track"));
        hub.receive(host, message("update", "room", host.id, "song", song("Different")), 2300);
        assertNull(guest.packet("close"));
        var next = guest.packet("track");
        assertEquals("Different", read(next, "song", Song.class).title());
        assertEquals(2, generation(next));
        hub.receive(host, message("lyrics", "room", host.id, "generation", 2, "part", 0, "lines",
                List.of(Map.of("text", "Legacy lyrics"))), 2500);
        assertNull(guest.packet("lyrics"));

        hub.receive(host, message("update", "room", host.id, "song", null), 2800);
        assertEquals(3, generation(guest.packet("track")));
        assertNull(read(guest.packet("track"), "song", Song.class));
        hub.receive(host, message("update", "room", host.id, "song", null), 15_000);
        hub.tick(16_000);
        assertNull(guest.packet("close"));
        hub.receive(host, message("update", "room", host.id, "song", song("Third")), 16_100);
        assertEquals(4, generation(guest.packet("track")));
        assertEquals("Third", read(guest.packet("track"), "song", Song.class).title());
        hub.stop(host.id);
        assertNotNull(guest.packet("close"));
    }

    @Test
    void invitationShowsCurrentMetadataAndHostingKeepsExistingMembership() {
        var hub = new KaraokeHub();
        var host = new Player();
        var guest = new Player();
        hub.join(host);
        hub.join(guest);
        assertTrue(hub.open(host, host.position, 1_000));
        var playing = new Song("Track", "Artist", "https://example.org/cover", "spotify:track:0123456789ABCDEFGHIJKL",
                1_000, 60_000, true);
        hub.receive(host, message("update", "room", host.id, "song", playing), 1_250);
        var invitation = guest.packet("preview").getAsJsonObject("preview");
        assertEquals(Set.of("title", "artist"), invitation.keySet());
        assertEquals(new Preview("Track", "Artist"), read(guest.packet("preview"), "preview", Preview.class));
        assertNull(guest.packet("track"));

        var lateGuest = new Player();
        hub.join(lateGuest);
        assertEquals(new Preview("Track", "Artist"), read(lateGuest.packet("panel"), "preview", Preview.class));

        hub.receive(guest, message("accept", "room", host.id), 1_500);
        assertTrue(hub.open(guest, guest.position, 1_600));
        hub.receive(host, message("update", "room", host.id, "song", song("Next track")), 1_800);
        assertEquals(host.id, id(guest.packet("track"), "room"));
        assertEquals("Next track", read(guest.packet("track"), "song", Song.class).title());
        assertEquals(new Preview("Next track", "Artist"), read(lateGuest.packet("preview"), "preview", Preview.class));
        assertNull(lateGuest.packet("track"));

        hub.receive(host, message("update", "room", host.id, "song", null), 2_100);
        assertNull(read(lateGuest.packet("preview"), "preview", Preview.class));
    }

    @Test
    void acceptsOneCameraBasedSpawnCorrection() {
        var hub = new KaraokeHub();
        var host = new Player();
        hub.join(host);
        assertTrue(hub.open(host, new Panel("world", 0, 1, -3.5, 0), 1000));
        var cameraPose = new Panel("world", 0, 1, 3.5, 180);
        hub.receive(host, message("place", "room", host.id, "panel", cameraPose), 1100);
        assertEquals(cameraPose, read(host.packet("panel"), "panel", Panel.class));
        hub.receive(host, message("place", "room", host.id, "panel", host.position), 1200);
        assertEquals(cameraPose, read(host.packet("panel"), "panel", Panel.class));
    }

    @Test
    void queueOperationsRequireMembershipAndOnlyTheHostCanCompleteThem() {
        var hub = new KaraokeHub();
        var host = new Player();
        var guest = new Player();
        var outsider = new Player();
        hub.join(host);
        hub.join(guest);
        hub.join(outsider);
        assertTrue(hub.open(host, host.position, 1_000));
        String uri = "spotify:track:0123456789ABCDEFGHIJKL";
        hub.receive(host, message("update", "room", host.id, "queue", true,
                "song", new Song("Track", "Artist", "", uri, 0, 100_000, true)), 1_250);
        UUID request = UUID.randomUUID();
        var search = message("queue_request", "room", host.id, "request", request, "action", "search",
                "value", "Search\u0007", "index", 0, "version", 0);
        hub.receive(guest, search, 1_400);
        assertNull(host.packet("queue_execute"));
        hub.receive(guest, message("accept", "room", host.id), 1_500);
        guest.position = new Panel("world", 40, 0, 0, 0);
        hub.receive(guest, search, 1_600);
        assertNull(host.packet("queue_execute"));
        guest.position = host.position;
        hub.receive(guest, search, 1_650);
        assertEquals("Search", string(host.packet("queue_execute"), "value"));
        assertEquals(uri, string(host.packet("queue_execute"), "current"));
        var reply = message("queue_result", "room", host.id, "request", request, "result",
                new QueueResult(List.of(new QueueTrack(uri, "Result", "Artist")), 0, "ok"));
        hub.receive(guest, reply, 1_700);
        assertNull(guest.packet("queue_result"));
        hub.receive(host, reply, 1_750);
        assertEquals(request, id(guest.packet("queue_result"), "request"));
        assertNull(outsider.packet("queue_result"));
        assertNull(outsider.packet("queue_snapshot"));
    }

    private static Song song(String title) {
        return new Song(title, "Artist", "", "", 1000, 60000, true);
    }

    @Test
    void listenSendsTimingOnlyToSubscribersAndStopsWhenSharingIsDisabled() {
        var hub = new KaraokeHub();
        var host = new Player("Host");
        var guest = new Player("Guest");
        var bystander = new Player("Bystander");
        hub.join(host);
        hub.join(guest);
        hub.join(bystander);
        var song = song("First");
        hub.receive(host, message("presence", "listen", true, "song", song), 1_000);
        hub.receive(guest, message("listen", "name", "host"), 1_500);
        assertNotNull(guest.packet("listen_started"));
        assertEquals(1_500, read(guest.packet("listen_track"), "song", Song.class).position());
        assertNull(bystander.packet("listen_track"));
        assertEquals(0, read(bystander.packet("presence"), "song", Song.class).position());
        var paused = new Song(song.title(), song.artist(), "", "", 1_800, 60_000, false);
        hub.receive(host, message("presence", "listen", true, "song", paused), 4_000);
        assertFalse(read(guest.packet("listen_track"), "song", Song.class).playing());
        assertEquals(1_800, read(guest.packet("listen_track"), "song", Song.class).position());
        hub.receive(host, message("presence", "listen", false), 7_000);
        assertNotNull(guest.packet("listen_stopped"));
    }

    private static final class Player implements KaraokeHub.Peer {
        final String name;
        Player() { this("Player"); }
        Player(String name) { this.name = name; }
        final UUID id = UUID.randomUUID();
        Panel position = new Panel("world", 0, 0, 0, 0);
        final List<JsonObject> packets = new ArrayList<>();
        @Override public UUID id() { return id; }
        @Override public String name() { return name; }
        @Override public Panel position() { return position; }
        @Override public void send(JsonObject packet) { packets.add(packet); }
        JsonObject packet(String op) {
            return packets.reversed().stream().filter(packet -> string(packet, "op").equals(op)).findFirst().orElse(null);
        }
    }
}
