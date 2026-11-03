package dev.fandroid.client.music;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.List;

/** Credentials are separate from exported preferences and the bounded shader cache. */
public final class GeminiShaderStore {
    private final Path root, cache;
    private String key = "";
    public GeminiShaderStore(Path root) {
        this.root = root; cache = root.resolve("cache");
        try {
            Path file = root.resolve("api-key.txt");
            if (Files.isRegularFile(file) && Files.size(file) <= 512) {
                String value = Files.readString(file).strip(); if (validKey(value)) key = value;
            }
        } catch (Exception ignored) { }
    }
    public static boolean validKey(String value) { return value != null && value.matches("[A-Za-z0-9_.-]{16,256}"); }
    synchronized String key() { return key; }
    public synchronized boolean configured() { return !key.isBlank(); }
    public synchronized void saveKey(String value) throws Exception {
        if (!validKey(value)) throw new IllegalArgumentException("KEY_INVALID");
        Files.createDirectories(root);
        Path target = root.resolve("api-key.txt"), temp = Files.createTempFile(root, "credential-", ".tmp");
        try {
            try { Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------")); } catch (UnsupportedOperationException ignored) { }
            Files.writeString(temp, value);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            key = value;
        } finally { Files.deleteIfExists(temp); }
    }
    public synchronized void removeKey() throws Exception { Files.deleteIfExists(root.resolve("api-key.txt")); key = ""; }
    synchronized GeminiShaderSpec read(String id) {
        if (!validId(id)) return null;
        Path file = cache.resolve(id + ".json");
        try { return Files.isRegularFile(file) && Files.size(file) <= 32_768 ? GeminiShaderSpec.parse(Files.readString(file)) : null; }
        catch (Exception ignored) { return null; }
    }
    synchronized void put(String id, GeminiShaderSpec shader) {
        if (!validId(id)) return;
        try {
            Files.createDirectories(cache);
            Path temp = Files.createTempFile(cache, "shader-", ".tmp");
            try { Files.writeString(temp, shader.json()); Files.move(temp, cache.resolve(id + ".json"), StandardCopyOption.REPLACE_EXISTING); }
            finally { Files.deleteIfExists(temp); }
            List<Path> files = files();
            for (Path old : files.stream().sorted(Comparator.comparingLong(GeminiShaderStore::modified).reversed()).skip(64).toList()) Files.deleteIfExists(old);
        } catch (Exception ignored) { /* Cache availability must never interrupt playback. */ }
    }
    public synchronized void clear() throws Exception { for (Path file : files()) Files.deleteIfExists(file); }
    private List<Path> files() throws Exception {
        if (!Files.isDirectory(cache)) return List.of();
        try (var paths = Files.list(cache)) { return paths.filter(p -> p.getFileName().toString().matches("[a-f0-9]{64}\\.json")).toList(); }
    }
    private static boolean validId(String id) { return id != null && id.matches("[a-f0-9]{64}"); }
    private static long modified(Path path) { try { return Files.getLastModifiedTime(path).toMillis(); } catch (Exception ignored) { return 0; } }
}
