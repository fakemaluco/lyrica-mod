package dev.fandroid.client.music;

import com.ibm.icu.text.Transliterator;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.text.Normalizer;

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

    private static volatile java.util.Set<String> japaneseDocument = java.util.Set.of();
    public static void setDocumentContext(List<String> sources) {
        japaneseDocument = sources.stream().anyMatch(LyricsRomanizer::hasKana)
                ? java.util.Set.copyOf(sources.stream().map(String::strip).limit(8192).toList()) : java.util.Set.of();
    }
    private static boolean japaneseContext(String source) { return hasKana(source) || japaneseDocument.contains(source.strip()); }
    private LyricsRomanizer() { }
    public static void warmup() { java.util.concurrent.CompletableFuture.runAsync(() -> japanese(List.of("日本語の歌"))); }

    /** Returns null for already-Latin/non-letter text so the renderer keeps its original path. */
    public static String romanize(String source) {
        if (source == null || source.isBlank() || !containsRomanizableScript(source)) return null;
        String cacheKey = japaneseContext(source) ? "ja:" + source : source;
        String cached = CACHE.get(cacheKey);
        if (cached != null) return cached.isEmpty() ? null : cached;
        String result;
        try {
            result = japaneseContext(source) ? normalize(japanese(List.of(source)).getFirst()) : normalize(ANY_TO_LATIN.get().transform(source));
        } catch (RuntimeException failure) {
            result = "";
        }
        if (result.equals(normalize(source)) || !containsLatinLetter(result)) result = "";
        CACHE.put(cacheKey, result);
        return result.isEmpty() ? null : result;
    }

    /** Read Japanese syllables together so a kanji fragment cannot become Chinese pinyin. */
    public static List<String> romanizeFragments(List<String> fragments) {
        if (!japaneseContext(String.join("", fragments))) return fragments.stream().map(LyricsRomanizer::romanize).toList();
        return japanese(fragments);
    }
    private static boolean hasKana(String text) {
        return text.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HIRAGANA
                || Character.UnicodeScript.of(c) == Character.UnicodeScript.KATAKANA);
    }
    private static final Map<List<String>, List<String>> JAPANESE_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(64, .75F, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<List<String>, List<String>> entry) { return size() > 256; }
            });
    @SuppressWarnings("unchecked")
    private static List<String> japanese(List<String> fragments) {
        var key = List.copyOf(fragments);
        var cached = JAPANESE_CACHE.get(key); if (cached != null) return cached;
        List<String> readings;
        try {
            // Optional adapter: standalone bundles the dictionary engine; the full mod can run without it.
            Class<?> type = Class.forName("com.tx24.spicyplayer.lyrics.spicy.romanization.JapaneseRomanizer");
            readings = (List<String>)type.getMethod("romanizeLine", List.class).invoke(type.getField("INSTANCE").get(null), key);
        } catch (ReflectiveOperationException | LinkageError error) {
            var kana = Transliterator.getInstance("Katakana-Latin; Hiragana-Latin");
            readings = key.stream().map(kana::transform).toList();
        }
        // Plain romaji, without Mandarin tone marks or combining accents. Preserve leading word boundaries.
        var result = readings.stream().map(value -> Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").replaceAll("\\s+", " ")).toList();
        JAPANESE_CACHE.put(key, result); return result;
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
