package dev.relaylyrics.client.presence;

import java.net.IDN;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

public final class PresenceScope {
    private static final String LOCAL_INTERFACE = localInterface();
    private PresenceScope() { }
    public static String serverHash(String address) {
        if (address == null || address.isBlank()) throw new IllegalArgumentException("Multiplayer server required");
        String raw = address.strip().toLowerCase(Locale.ROOT);
        String host; int port = 25565;
        if (raw.startsWith("[")) {
            int end = raw.indexOf(']'); if (end < 0) throw new IllegalArgumentException("Invalid IPv6 address");
            host = raw.substring(1, end);
            if (raw.length() > end + 1) port = Integer.parseInt(raw.substring(end + 2));
        } else if (raw.indexOf(':') == raw.lastIndexOf(':') && raw.contains(":")) {
            int at = raw.lastIndexOf(':'); host = raw.substring(0, at); port = Integer.parseInt(raw.substring(at + 1));
        } else host = raw;
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (!host.contains(":")) host = IDN.toASCII(host);
        if (host.equals("localhost") || host.equals("::1") || host.equals("127.0.0.1")) host = LOCAL_INTERFACE;
        if (host.isBlank() || port < 1 || port > 65535) throw new IllegalArgumentException("Invalid server address");
        return hash("lyrica-presence-v1\n[" + host + "]:" + port);
    }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    /** Resolve the default LAN interface once, without sending a datagram or doing DNS on a game tick. */
    static String localInterface() {
        try (var socket = new java.net.DatagramSocket()) {
            socket.connect(java.net.InetAddress.getByAddress(new byte[]{8, 8, 8, 8}), 53);
            var address = socket.getLocalAddress();
            if (address instanceof java.net.Inet4Address && !address.isAnyLocalAddress() && !address.isLoopbackAddress())
                return address.getHostAddress();
        } catch (java.io.IOException | SecurityException ignored) { }
        return "127.0.0.1";
    }
}
