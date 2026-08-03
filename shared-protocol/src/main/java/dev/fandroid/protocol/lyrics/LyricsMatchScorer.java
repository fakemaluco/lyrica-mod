package dev.fandroid.protocol.lyrics;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Metadata matcher for lyric databases. Keeps non-Latin scripts intact. */
public final class LyricsMatchScorer {
    private static final Pattern FEATURE = Pattern.compile(
            "(?i)\\s*(?:\\(|\\[)?\\s*(?:feat(?:uring)?|ft)\\.?\\s+.+?(?:\\)|\\])?$");
    private static final Pattern VERSION_BRACKETS = Pattern.compile(
            "(?i)\\s*(?:\\([^)]*(?:remaster(?:ed)?|live|acoustic|instrumental|radio edit|single version|album version|"
                    + "sped up|slowed(?: down)?|nightcore|mono|stereo|clean|explicit)[^)]*\\)|"
                    + "\\[[^\\]]*(?:remaster(?:ed)?|live|acoustic|instrumental|radio edit|single version|album version|"
                    + "sped up|slowed(?: down)?|nightcore|mono|stereo|clean|explicit)[^\\]]*\\])\\s*$");
    private static final Pattern ARTIST_SPLIT = Pattern.compile(
            "(?i)\\s+(?:feat(?:uring)?|ft|with|x)\\.?\\s+|\\s*[,;&]\\s*");
    private static final Set<String> VERSION_WORDS = Set.of(
            "remix", "remaster", "remastered", "live", "acoustic", "instrumental", "karaoke",
            "mono", "stereo", "clean", "explicit", "sped", "slowed", "nightcore", "edit", "version");

    private LyricsMatchScorer() { }

    public static double score(String title, String artist, String album,
                               String candidateTitle, String candidateArtist, String candidateAlbum) {
        String fullTitle = normalize(title);
        String candidateFullTitle = normalize(candidateTitle);
        if (fullTitle.isBlank() || candidateFullTitle.isBlank()) return 0;

        double fullTitleScore = similarityNormalized(fullTitle, candidateFullTitle);
        double coreTitleScore = similarityNormalized(coreTitle(title), coreTitle(candidateTitle));
        double titleScore = Math.max(fullTitleScore, coreTitleScore * .97);
        if (fullTitleScore < .96 && versionMismatch(fullTitle, candidateFullTitle)) titleScore *= .78;
        if (titleScore < .56) return 0;

        double artistScore = artistSimilarity(artist, candidateArtist);
        double albumScore = similarity(album, candidateAlbum);
        boolean hasArtist = !normalize(artist).isBlank() && !normalize(candidateArtist).isBlank();
        boolean hasAlbum = !normalize(album).isBlank() && !normalize(candidateAlbum).isBlank();

        double score;
        if (hasArtist && hasAlbum) score = titleScore * .68 + artistScore * .25 + albumScore * .07;
        else if (hasArtist) score = titleScore * .74 + artistScore * .26;
        else if (hasAlbum) score = titleScore * .90 + albumScore * .10;
        else score = titleScore;

        if (artistScore < .28 && hasArtist) score *= .78;
        return Math.clamp(score, 0, 1);
    }

    public static String canonicalKey(String title, String artist, String album) {
        return coreTitle(title) + '\n' + normalizeArtists(artist) + '\n' + normalize(album);
    }

    static double similarity(String first, String second) {
        return similarityNormalized(normalize(first), normalize(second));
    }

    static String normalize(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replace('&', ' ')
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .strip()
                .replaceAll(" +", " ");
    }

    private static String coreTitle(String value) {
        if (value == null) return "";
        String result = value;
        String previous;
        do {
            previous = result;
            result = VERSION_BRACKETS.matcher(result).replaceFirst("");
            result = FEATURE.matcher(result).replaceFirst("");
        } while (!result.equals(previous));
        return normalize(result);
    }

    private static boolean versionMismatch(String first, String second) {
        Set<String> left = versionWords(first);
        Set<String> right = versionWords(second);
        return !left.equals(right) && (!left.isEmpty() || !right.isEmpty());
    }

    private static Set<String> versionWords(String value) {
        Set<String> result = new HashSet<>();
        for (String token : value.split(" ")) {
            if (!VERSION_WORDS.contains(token)) continue;
            result.add(token.equals("remastered") ? "remaster" : token);
        }
        return result;
    }

    private static double artistSimilarity(String first, String second) {
        List<String> left = artists(first);
        List<String> right = artists(second);
        if (left.isEmpty() || right.isEmpty()) return 0;
        double leftCoverage = coverage(left, right);
        double rightCoverage = coverage(right, left);
        return leftCoverage * .62 + rightCoverage * .38;
    }

    private static double coverage(List<String> source, List<String> candidates) {
        double total = 0;
        for (String artist : source) {
            double best = 0;
            for (String candidate : candidates) best = Math.max(best, similarityNormalized(artist, candidate));
            total += best;
        }
        return total / source.size();
    }

    private static List<String> artists(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(ARTIST_SPLIT.split(value))
                .map(LyricsMatchScorer::normalize)
                .filter(part -> !part.isBlank())
                .distinct()
                .toList();
    }

    private static String normalizeArtists(String value) {
        return String.join("|", artists(value).stream().sorted().toList());
    }

    private static double similarityNormalized(String first, String second) {
        if (first == null || second == null || first.isBlank() || second.isBlank()) return 0;
        if (first.equals(second)) return 1;
        int minLength = Math.min(first.length(), second.length());
        if (minLength >= 4 && (first.contains(second) || second.contains(first))) return .90;

        Set<String> left = new HashSet<>(List.of(first.split(" ")));
        Set<String> right = new HashSet<>(List.of(second.split(" ")));
        int intersection = 0;
        for (String token : left) if (right.contains(token)) intersection++;
        double dice = 2.0 * intersection / Math.max(1, left.size() + right.size());
        double edit = 1.0 - levenshtein(first, second) / (double) Math.max(first.length(), second.length());
        return Math.max(0, dice * .58 + edit * .42);
    }

    private static int levenshtein(String first, String second) {
        int[] previous = new int[second.length() + 1];
        int[] current = new int[second.length() + 1];
        for (int j = 0; j <= second.length(); j++) previous[j] = j;
        for (int i = 1; i <= first.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= second.length(); j++) {
                int substitution = previous[j - 1] + (first.charAt(i - 1) == second.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(Math.min(previous[j] + 1, current[j - 1] + 1), substitution);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[second.length()];
    }
}
