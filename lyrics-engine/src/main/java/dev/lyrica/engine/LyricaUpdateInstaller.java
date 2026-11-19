package dev.lyrica.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

/** Standalone helper. It runs outside the Minecraft JVM to release Windows JAR locks. */
public final class LyricaUpdateInstaller {
    private LyricaUpdateInstaller() { }

    public static void main(String[] args) throws Exception {
        Path staged = Path.of(args[2]);
        Path target = Path.of(args[3]);
        try {
            var parent = ProcessHandle.of(Long.parseLong(args[0]));
            if (parent.isPresent() && parent.get().info().startInstant().filter(Instant.parse(args[1])::equals).isPresent())
                parent.get().onExit().get(7, TimeUnit.DAYS);
            for (int attempt = 0; ; attempt++) {
                try {
                    install(staged, target, args[4], args[5]);
                    break;
                } catch (java.nio.file.AccessDeniedException locked) {
                    if (attempt == 19) throw locked;
                    Thread.sleep(250);
                }
            }
            Files.writeString(staged.resolveSibling("result.txt"), "installed");
        } catch (Exception error) {
            Files.writeString(staged.resolveSibling("result.txt"), "failed: " + error.getClass().getSimpleName());
            throw error;
        }
    }

    static void install(Path staged, Path target, String expected, String previous) throws IOException {
        if (Files.isSymbolicLink(target) || !Files.isRegularFile(target) || !hash(target).equals(previous)
                || !hash(staged).equals(expected)) throw new IOException("Update files changed");
        Path backup = staged.resolveSibling("previous.jar");
        if (!Files.exists(backup)) Files.copy(target, backup);
        if (!hash(backup).equals(previous)) throw new IOException("Update backup differs");
        Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    public static String hash(Path file) throws IOException {
        try (var stream = Files.newInputStream(file)) {
            var sha = MessageDigest.getInstance("SHA-256");
            byte[] bytes = new byte[8192];
            for (int read; (read = stream.read(bytes)) != -1;) sha.update(bytes, 0, read);
            return HexFormat.of().formatHex(sha.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
