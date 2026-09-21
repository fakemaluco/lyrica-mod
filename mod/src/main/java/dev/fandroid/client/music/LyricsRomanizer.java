package dev.fandroid.client.music;

import com.ibm.icu.text.Transliterator;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Local Unicode-script fallback used only when the lyrics provider omitted romanization. */
public final class LyricsRomanizer {
    private static final int CACHE_LIMIT = 4_096;
    private static final ThreadLocal<Transliterator> ANY_TO_LATIN = ThreadLocal.withInitial(
            () -> Transliterator.getInstance("Any-Latin"));
    private static final Map<String, String> CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(256, .75F, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > CACHE_LIMIT;
                }
            });

    private LyricsRomanizer() { }

    /** Returns null for already-Latin/non-letter text so the renderer keeps its original path. */
    public static String romanize(String source) {
        if (source == null || source.isBlank() || !containsRomanizableScript(source)) return null;
        String cached = CACHE.get(source);
        if (cached != null) return cached.isEmpty() ? null : cached;
        String result;
        try {
            result = normalize(ANY_TO_LATIN.get().transform(source));
        } catch (RuntimeException failure) {
            result = "";
        }
        if (result.equals(normalize(source)) || !containsLatinLetter(result)) result = "";
        CACHE.put(source, result);
        return result.isEmpty() ? null : result;
    }

    private static boolean containsRomanizableScript(String value) {
        return value.codePoints().anyMatch(codePoint -> {
            if (!Character.isLetter(codePoint)) return false;
            Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
            return script != Character.UnicodeScript.LATIN
                    && script != Character.UnicodeScript.COMMON
                    && script != Character.UnicodeScript.INHERITED
                    && script != Character.UnicodeScript.UNKNOWN;
        });
    }

    private static boolean containsLatinLetter(String value) {
        return value.codePoints().anyMatch(codePoint -> Character.isLetter(codePoint)
                && Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").strip();
    }
}
