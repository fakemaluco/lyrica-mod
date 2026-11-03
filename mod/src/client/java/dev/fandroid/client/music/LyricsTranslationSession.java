package dev.fandroid.client.music;

import dev.fandroid.client.ClientPreferences;
import dev.fandroid.protocol.lyrics.*;
import dev.lyrica.engine.DesktopTranslationEngine;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Polled on the client thread. Old songs/settings can never receive a late translation. */
public final class LyricsTranslationSession implements AutoCloseable {
    private final Path directory;
    private final ClientPreferences preferences;
    private final DesktopTranslationEngine engine;
    @FunctionalInterface interface Translate {
        CompletableFuture<List<String>> run(List<String> lines, String target, String provider, String key, String address, String model);
    }
    private Translate transport;
    private LyricsDocument original, displayed;
    private String settings = "", state = "idle";
    private CompletableFuture<List<String>> pending;
    private boolean manual, closed;
    private long revision;
    private String cachedKeyProvider, cachedKey = "";

    public LyricsTranslationSession(Path directory, ClientPreferences preferences) {
        this.directory = directory;
        this.preferences = preferences;
        engine = new DesktopTranslationEngine(directory.resolve("cache").toFile());
    }

    LyricsTranslationSession(Path directory, ClientPreferences preferences, Translate transport) {
        this.directory = directory; this.preferences = preferences; this.engine = null; this.transport = transport;
    }
    public String status() { return state; }
    public void request() { manual = true; revision++; preferences.setLyricsTranslation(true); }
    private Path keyFile() { return directory.resolve(preferences.getTranslationProvider() + ".key"); }
    public boolean keyConfigured() { return !key().isBlank(); }
    private String key() {
        String provider = preferences.getTranslationProvider();
        if (!provider.equals(cachedKeyProvider)) {
            try { cachedKey = Files.readString(keyFile()).strip(); } catch (Exception ignored) { cachedKey = ""; }
            cachedKeyProvider = provider;
        }
        return cachedKey;
    }
    public boolean saveKey(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            Files.createDirectories(directory);
            Path temporary = Files.createTempFile(directory, "key-", ".tmp");
            try {
                try { Files.setPosixFilePermissions(temporary, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); }
                catch (UnsupportedOperationException ignored) { }
                Files.writeString(temporary, value.strip());
                Files.move(temporary, keyFile(), StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temporary); }
            cachedKeyProvider = null; revision++; return true;
        } catch (Exception ignored) { return false; }
    }
    public boolean removeKey() {
        try { Files.deleteIfExists(keyFile()); cachedKeyProvider = null; revision++; return true; }
        catch (Exception ignored) { return false; }
    }

    public LyricsDocument tick(LyricsDocument current) {
        if (closed) return current;
        boolean changed = current != displayed && current != original;
        String next = preferences.isLyricsTranslation() + ":" + preferences.isAutomaticTranslation() + ":"
                + preferences.getTranslationLanguage() + ":" + preferences.getTranslationProvider() + ":"
                + preferences.getTranslationAddress() + ":" + preferences.translationModel() + ":" + revision;
        if (changed || !settings.equals(next)) {
            if (pending != null) pending.cancel(true);
            pending = null;
            if (changed) { original = current; manual = false; }
            displayed = original;
            settings = next;
            state = "idle";
            if (original != null && !original.lines().isEmpty() && preferences.isLyricsTranslation()
                    && (preferences.isAutomaticTranslation() || manual)) {
                String provider = preferences.getTranslationProvider();
                String secret = key();
                if ((provider.equals("DeepL") || provider.equals("Gemini")) && secret.isBlank()) state = "key_missing";
                else if (provider.equals("OpenAi") && preferences.translationModel().isBlank()) state = "model_missing";
                else {
                    String model = preferences.translationModel();
                    if (provider.equals("Gemini") && model.isBlank()) model = "gemini-flash-latest";
                    state = "loading";
                    pending = (transport != null ? transport : (Translate) engine::translate).run(original.lines().stream().map(LyricLine::text).toList(),
                            preferences.getTranslationLanguage(), provider, secret, preferences.getTranslationAddress(), model);
                }
            }
        }
        if (pending != null && pending.isDone()) {
            try {
                List<String> texts = pending.join();
                displayed = apply(original, texts);
                state = texts.stream().anyMatch(t -> t != null && !t.isBlank()) ? "ready" : "unneeded";
            } catch (Exception ignored) {
                // No exception body, provider response, URL or API key enters diagnostics/UI.
                state = "failed";
            }
            pending = null;
        }
        return displayed;
    }

    static LyricsDocument apply(LyricsDocument original, List<String> translations) {
        if (translations.size() != original.lines().size()) throw new IllegalArgumentException("Line count differs");
        Map<LyricLine, LyricLine> mapped = new HashMap<>();
        var lines = new ArrayList<LyricLine>();
        for (int i = 0; i < translations.size(); i++) {
            LyricLine line = original.lines().get(i);
            String text = translations.get(i);
            LyricLine result = new LyricLine(line.startMillis(), line.endMillis(), line.text(), line.words(),
                    line.agent(), line.background(), text == null || text.isBlank() ? null : text, line.romanization());
            mapped.put(line, result); lines.add(result);
        }
        var groups = original.groups().stream().map(group -> new LyricVocalGroup(group.startMillis(), group.endMillis(),
                mapped.getOrDefault(group.lead(), group.lead()), group.backingVocals().stream()
                .map(line -> mapped.getOrDefault(line, line)).toList(), group.alignment())).toList();
        return new LyricsDocument(lines, groups, original.metadata());
    }

    @Override public void close() { closed = true; if (pending != null) pending.cancel(true); if (engine != null) engine.close(); }
}
