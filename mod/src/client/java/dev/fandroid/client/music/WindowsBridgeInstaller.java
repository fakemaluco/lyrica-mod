package dev.fandroid.client.music;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.net.URI;
import javax.net.ssl.HttpsURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

public final class WindowsBridgeInstaller {
    private static final String FILE = "fandroid-media-bridge.exe";
    private static final String ASSET = "relaylyrics-media-bridge.exe";
    private static final int MAX_BYTES = 32 * 1024 * 1024;
    private static final String RELEASES = "https://github.com/fakemaluco/lyrica-mod/releases";

    private WindowsBridgeInstaller() { }

    public static boolean missing() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return os.startsWith("windows") && (arch.equals("amd64") || arch.equals("x86_64"))
                && !Files.isRegularFile(destination())
                && WindowsBridgeInstaller.class.getResource("/fandroid/native/windows-x86_64/" + FILE) == null;
    }

    public static boolean manualOnly() {
        return WindowsBridgeInstaller.class.getResource("/lyrica/manual-bridge-install") != null;
    }

    public static URI releasePage() { return URI.create(RELEASES + "/tag/v" + version()); }

    public static void install() throws Exception {
        if (manualOnly() || !missing()) throw new IOException("Bridge download unavailable");
        String asset = RELEASES + "/download/v" + version() + "/" + ASSET;
        String checksum = new String(download(asset + ".sha256", 128), StandardCharsets.US_ASCII).strip();
        if (!checksum.matches("[0-9a-fA-F]{64}")) throw new IOException("Invalid bridge checksum");
        byte[] binary = download(asset, MAX_BYTES);
        String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(binary));
        if (binary.length < 2 || binary[0] != 'M' || binary[1] != 'Z' || !actual.equalsIgnoreCase(checksum)) {
            throw new IOException("Bridge verification failed");
        }
        Path destination = destination();
        Files.createDirectories(destination.getParent());
        Path temporary = Files.createTempFile(destination.getParent(), "lyrica-bridge-", ".tmp");
        try {
            Files.write(temporary, binary);
            Files.move(temporary, destination);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Path destination() {
        return FabricLoader.getInstance().getConfigDir().resolve("fandroid/bin").resolve(FILE);
    }

    private static String version() {
        String version = FabricLoader.getInstance().getModContainer("lyrica").orElseThrow()
                .getMetadata().getVersion().getFriendlyString();
        if (!version.matches("[0-9][0-9A-Za-z.+-]*")) throw new IllegalStateException("Invalid release version");
        return version;
    }

    private static byte[] download(String url, int limit) throws Exception {
        var connection = (HttpsURLConnection) URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(15_000);
        try {
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException("Bridge download returned HTTP " + status);
            try (var body = connection.getInputStream()) {
                byte[] data = body.readNBytes(limit + 1);
                if (data.length > limit) throw new IOException("Bridge download exceeds size limit");
                return data;
            }
        } finally {
            connection.disconnect();
        }
    }
}
