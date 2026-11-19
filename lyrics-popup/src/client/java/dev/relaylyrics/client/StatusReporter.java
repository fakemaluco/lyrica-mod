package dev.relaylyrics.client;

import dev.lyrica.engine.StatusMetrics;
import dev.relaylyrics.client.presence.MusicPresenceClient;
import dev.relaylyrics.protocol.ProtocolCodec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class StatusReporter {
    private static final URI ENDPOINT = URI.create(MusicPresenceClient.PROJECT + "/functions/v1/lyrica-status/report");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final boolean PROBE = System.getProperties().stringPropertyNames().stream()
            .anyMatch(key -> key.startsWith("lyrica.") && key.endsWith("SelfTest") && Boolean.getBoolean(key));
    private static volatile boolean enabled;
    private static String session;
    private static int sequence;
    private static long nextPulse;
    private static String pending;
    private static boolean sending;

    private StatusReporter() { }

    public static synchronized void tick(boolean inGame, String server) {
        boolean allowed = !PROBE && RelayLyricsClient.preferences().isPublicStatusMetrics();
        if (allowed != enabled) {
            enabled = allowed;
            StatusMetrics.setEnabled(allowed);
            session = allowed ? UUID.randomUUID().toString() : null;
            pending = null;
            sequence = 0;
            nextPulse = 0;
        }
        long now = System.nanoTime();
        if (!allowed || !inGame || sending || (nextPulse != 0 && now - nextPulse < 0)) return;
        nextPulse = now + 60_000_000_000L;

        try {
            if (pending == null) {
                pending = ProtocolCodec.mapper().writeValueAsString(Map.of(
                        "session", session, "sequence", ++sequence, "network", publicNetwork(server), "counts", StatusMetrics.drain()));
            }
            var request = HttpRequest.newBuilder(ENDPOINT).timeout(Duration.ofSeconds(8))
                    .header("apikey", MusicPresenceClient.PUBLISHABLE).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(pending)).build();
            final String batch = pending;
            sending = true;
            HTTP.sendAsync(request, HttpResponse.BodyHandlers.discarding()).whenComplete((response, failure) -> sent(batch, response));
        } catch (Exception error) {
            sending = false;
        }
    }

    private static synchronized void sent(String batch, HttpResponse<Void> response) {
        sending = false;
        if (pending != batch || response == null) return;
        int status = response.statusCode();
        if (status == 204 || status == 400) pending = null;
    }

    /** At most a known network identifier leaves the game. Private hosts, IPs and ports are omitted. */
    static String publicNetwork(String address) {
        if (address == null) return "";
        String host = address.toLowerCase(Locale.ROOT).strip().replaceFirst(":[0-9]{1,5}$", "").replaceFirst("\\.$", "");
        return switch (host) {
            case "hypixel.net", "mc.hypixel.net" -> "hypixel";
            case "cubecraft.net", "play.cubecraft.net" -> "cubecraft";
            case "mccisland.net", "play.mccisland.net" -> "mcc_island";
            case "wynncraft.com", "play.wynncraft.com" -> "wynncraft";
            case "minemen.club", "na.minemen.club", "eu.minemen.club", "as.minemen.club" -> "minemen";
            case "mush.com.br", "jogar.mush.com.br" -> "mush";
            case "craftlandia.com.br", "jogar.craftlandia.com.br", "mc.craftlandia.com.br" -> "craftlandia";
            case "redesky.com", "jogar.redesky.com" -> "rede_sky";
            case "shotbow.net", "play.shotbow.net", "us.shotbow.net", "eu.shotbow.net" -> "shotbow";
            default -> "";
        };
    }
}
