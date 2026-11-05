package dev.fandroid.client.music;

import dev.fandroid.protocol.lyrics.LyricLine;
import dev.fandroid.protocol.lyrics.LyricsDocument;
import dev.fandroid.protocol.lyrics.LyricsMetadata;
import dev.fandroid.protocol.lyrics.LyricsSyncMode;
import java.util.ArrayList;
import java.util.List;

/** Transport-driven planning for the island's single lyric row. No synthetic word timings. */
public final class IslandLyrics {
    public static final long CREDITS_MILLIS = 5_000;
    public enum Kind { METADATA, LYRICS, WAITING, CREDITS }
    public record Frame(Kind kind, LyricLine line, long waitStart, long waitEnd) {
        public double waitProgress(long now) {
            return waitEnd > waitStart ? LyricsAnimation.interludeProgress(now - waitStart, waitEnd - waitStart)
                    : Math.floorMod(now, 3_000) / 3_000.0;
        }
        public double creditsProgress(long now) {
            return Math.clamp((now - waitStart) / (double) CREDITS_MILLIS, 0, 1);
        }
    }
    public static final Frame METADATA = new Frame(Kind.METADATA, null, 0, 0);
    private static final Frame LOADING = new Frame(Kind.WAITING, null, 0, 0);

    /** Keep a completed line across short breaths; real instrumental gaps return to the title. */
    public static Frame sample(LyricsDocument document, long now, boolean loading) {
        if (loading) return LOADING;
        if (document == null || document.metadata().syncMode() == LyricsSyncMode.STATIC) return METADATA;
        LyricLine active = null, previous = null, next = null;
        long vocalsEnd = Long.MIN_VALUE;
        for (LyricLine line : document.lines()) {
            if (line.text() == null || line.text().isBlank()
                    || line.endMillis() <= line.startMillis()) continue;
            vocalsEnd = Math.max(vocalsEnd, line.endMillis());
            if (line.background()) continue;
            if (line.startMillis() > now) { next = line; break; }
            if (now < line.endMillis()) active = line;
            if (previous == null || line.endMillis() > previous.endMillis()) previous = line;
        }
        if (active != null) return new Frame(Kind.LYRICS, active, 0, 0);
        if (next == null) {
            if (previous == null) return METADATA;
            if (now < vocalsEnd) return new Frame(Kind.LYRICS, previous, 0, 0);
            if (now - vocalsEnd < CREDITS_MILLIS && hasCredits(document.metadata()))
                return new Frame(Kind.CREDITS, null, vocalsEnd, vocalsEnd + CREDITS_MILLIS);
            return METADATA;
        }
        long start = previous == null ? 0 : previous.endMillis();
        if (previous != null && next.startMillis() - start < LyricsAnimation.INTERLUDE_MIN_GAP_MILLIS)
            return new Frame(Kind.LYRICS, previous, 0, 0);
        return new Frame(Kind.WAITING, null, start, next.startMillis());
    }

    public enum CreditRole {
        MAKER("made_by"), UPLOADER("uploaded_by"), WRITERS("written_by"), PROVIDER("provided_by"), COMMUNITY("community_provided");
        public final String translation;
        CreditRole(String translation) { this.translation = translation; }
    }
    public record Credit(CreditRole role, String name) { }
    public record CreditPage(Credit credit, double progress) { }

    private static boolean hasCredits(LyricsMetadata metadata) {
        return metadata.hasCommunityCredits() || !metadata.songwriters().isBlank()
                || !metadata.provider().isBlank() || !LyricsMetadata.providerFor(metadata.source()).isBlank();
    }
    /** Reuse provider attribution, preserving contributor roles and only actual supplied names. */
    public static List<Credit> credits(LyricsMetadata metadata) {
        if (metadata == null) return List.of();
        var result = new ArrayList<Credit>();
        if (!metadata.makerUsername().isBlank()) result.add(new Credit(CreditRole.MAKER, metadata.makerUsername().strip()));
        if (!metadata.uploaderUsername().isBlank()) result.add(new Credit(CreditRole.UPLOADER, metadata.uploaderUsername().strip()));
        if (!metadata.songwriters().isBlank()) result.add(new Credit(CreditRole.WRITERS, metadata.songwriters().strip()));
        String provider = metadata.provider().isBlank() ? LyricsMetadata.providerFor(metadata.source()) : metadata.provider();
        if (!provider.isBlank()) result.add(new Credit(CreditRole.PROVIDER, provider.strip()));
        if (metadata.communityProvided() && metadata.makerUsername().isBlank() && metadata.uploaderUsername().isBlank())
            result.add(new Credit(CreditRole.COMMUNITY, ""));
        return List.copyOf(result);
    }
    /** All attribution cards share one five-second window, rather than extending the outro per person. */
    public static CreditPage creditPage(List<Credit> credits, Frame frame, long now) {
        if (credits.isEmpty() || frame.kind() != Kind.CREDITS) return null;
        double position = frame.creditsProgress(now) * credits.size();
        int index = Math.min(credits.size() - 1, (int) position);
        return new CreditPage(credits.get(index), Math.clamp(position - index, 0, 1));
    }

    /** Geometry is measured once with the same font/layout used to draw the row. */
    public record Cell(long start, long end, double x, double width, boolean rightToLeft) { }
    public record Viewport(int first, int end, double offset, double width) { }

    public static Viewport viewport(List<Cell> cells, double available, long now,
                                    long lineStart, long lineEnd, boolean wordTimed) {
        if (cells.isEmpty() || !Double.isFinite(available) || available <= 0)
            return new Viewport(0, 0, 0, 0);
        double full = cells.getLast().x() + cells.getLast().width();
        if (full <= available) return new Viewport(0, cells.size(), 0, full);
        if (!wordTimed) {
            double progress = progress(now, lineStart, lineEnd);
            if (cells.getFirst().rightToLeft()) progress = 1 - progress;
            return new Viewport(0, cells.size(), (full - available) * progress, available);
        }
        int current = 0;
        for (int i = 1; i < cells.size(); i++) {
            if (cells.get(i).start() > now) break;
            current = i;
        }
        // Stable pages change only when the singer reaches a word outside the current page.
        for (int first = 0; first < cells.size();) {
            double left = cells.get(first).x();
            int end = first + 1;
            while (end < cells.size() && cells.get(end).x() + cells.get(end).width() - left <= available) end++;
            if (current < end) {
                Cell cell = cells.get(first);
                double width = cells.get(end - 1).x() + cells.get(end - 1).width() - left;
                double overflow = Math.max(0, width - available);
                double progress = progress(now, cell.start(), cell.end());
                if (cell.rightToLeft()) progress = 1 - progress;
                return new Viewport(first, end, left + overflow * progress, Math.min(width, available));
            }
            first = end;
        }
        throw new IllegalStateException("Active word outside island pages");
    }
    private static double progress(long now, long start, long end) {
        return end <= start ? now >= start ? 1 : 0 : Math.clamp((now - start) / (double) (end - start), 0, 1);
    }
    private IslandLyrics() { }
}
