package dev.fandroid.client.music;

import dev.fandroid.FanDroidMod;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** Installs the platform helper carried inside the mod JAR into the config directory. */
final class BundledMediaBridge {
    private static final int MAX_BINARY_BYTES = 32 * 1024 * 1024;

    private BundledMediaBridge() {
    }

    static Path install(Path directory) throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String fileName = os.contains("win") ? "fandroid-media-bridge.exe" : "fandroid-media-bridge";
        String platform = platformName(os, System.getProperty("os.arch", ""));
        String resource = "/fandroid/native/" + platform + "/" + fileName;

        byte[] binary;
        try (InputStream stream = BundledMediaBridge.class.getResourceAsStream(resource)) {
            if (stream == null) {
                FanDroidMod.LOGGER.warn("No bundled media bridge for {} ({})", platform, resource);
                return null;
            }
            binary = stream.readNBytes(MAX_BINARY_BYTES + 1);
        }
        if (binary.length > MAX_BINARY_BYTES) {
            throw new IOException("Bundled media bridge exceeds the 32 MiB safety limit");
        }

        Files.createDirectories(directory);
        String digest = sha256(binary);
        String suffix = os.contains("win") ? ".exe" : "";
        Path destination = directory.resolve("fandroid-media-bridge-bundled-" + platform + suffix);
        Path checksum = destination.resolveSibling(destination.getFileName() + ".sha256");

        if (!matches(destination, digest)) {
            Path temporary = Files.createTempFile(directory, ".fandroid-media-bridge-", ".tmp");
            try {
                Files.write(temporary, binary);
                makeExecutable(temporary);
                replaceAtomically(temporary, destination);
            } finally {
                Files.deleteIfExists(temporary);
            }
            FanDroidMod.LOGGER.info("Extracted bundled FanDroid media bridge to {}", destination);
        }
        makeExecutable(destination);
        Files.writeString(checksum, digest + "  " + destination.getFileName() + System.lineSeparator(),
                StandardCharsets.US_ASCII);
        return destination;
    }

    static String platformName(String osName, String architecture) {
        String os = osName.toLowerCase(Locale.ROOT);
        String arch = architecture.toLowerCase(Locale.ROOT);
        arch = switch (arch) {
            case "amd64", "x86_64" -> "x86_64";
            case "aarch64", "arm64" -> "arm64";
            default -> arch.replaceAll("[^a-z0-9_-]", "_");
        };
        String family = os.contains("win") ? "windows"
                : os.contains("mac") || os.contains("darwin") ? "macos" : "linux";
        return family + '-' + arch;
    }

    private static boolean matches(Path file, String expected) throws IOException {
        if (!Files.isRegularFile(file)) return false;
        if (Files.size(file) > MAX_BINARY_BYTES) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                sha256(file).getBytes(StandardCharsets.US_ASCII));
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream stream = Files.newInputStream(file)) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = stream.read(buffer)) >= 0) {
                    if (read > 0) digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void replaceAtomically(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void makeExecutable(Path path) throws IOException {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) return;
        if (!path.toFile().setExecutable(true, true) && !Files.isExecutable(path)) {
            throw new IOException("Could not mark the bundled media bridge as executable");
        }
    }
}
