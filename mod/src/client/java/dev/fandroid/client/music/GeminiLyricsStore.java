package dev.fandroid.client.music;

import java.nio.file.*;
import java.util.Comparator;

/** Separate credential and bounded cache for lyrics direction, independent of the shader experiment. */
public final class GeminiLyricsStore {
    private final GeminiShaderStore credential;
    private final Path cache;
    public GeminiLyricsStore(Path root) { credential = new GeminiShaderStore(root); cache = root.resolve("cache"); }
    public boolean configured() { return credential.configured(); }
    String key() { return credential.key(); }
    public void saveKey(String key) throws Exception { credential.saveKey(key); }
    public void removeKey() throws Exception { credential.removeKey(); }
    synchronized GeminiLyricsSpec read(String id, WorldLyricsPresentation.Timeline timeline) {
        if (!valid(id)) return null;
        Path file = cache.resolve(id + ".json");
        try { return Files.isRegularFile(file) && Files.size(file) <= 262_144
                ? GeminiLyricsSpec.parse(Files.readString(file), timeline) : null; }
        catch (Exception ignored) { return null; }
    }
    synchronized void put(String id, GeminiLyricsSpec spec) {
        if (!valid(id)) return;
        try {
            Files.createDirectories(cache);
            Path temporary = Files.createTempFile(cache, "direction-", ".tmp");
            try { Files.writeString(temporary, spec.json()); Files.move(temporary, cache.resolve(id + ".json"), StandardCopyOption.REPLACE_EXISTING); }
            finally { Files.deleteIfExists(temporary); }
            try (var files = Files.list(cache)) {
                for (Path old : files.filter(p -> p.getFileName().toString().matches("[a-f0-9]{64}\\.json"))
                        .sorted(Comparator.comparingLong(GeminiLyricsStore::modified).reversed()).skip(64).toList()) Files.deleteIfExists(old);
            }
        } catch (Exception ignored) { /* Optional cache never interrupts playback. */ }
    }
    private static boolean valid(String id) { return id != null && id.matches("[a-f0-9]{64}"); }
    private static long modified(Path path) { try { return Files.getLastModifiedTime(path).toMillis(); } catch (Exception ignored) { return 0; } }
}
