package dev.fandroid.client.music;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;
import java.util.regex.Pattern;

/** Measured wrapping and timed pages; independent of Minecraft and of the render frame rate. */
public final class WorldLyricsLayout {
    private static final Pattern WORD = Pattern.compile("\\S+");
    private static final Pattern PHRASE_END = Pattern.compile("[,.!?;:…，。！？、；](?:[\\p{Pe}\\p{Pf}\"']*)$");
    private WorldLyricsLayout() { }

    public enum ReadingFit {
        WORD(1), SHORT(3), MEDIUM(6), FULL(Integer.MAX_VALUE);

        private final int wordLimit;
        ReadingFit(int wordLimit) { this.wordLimit = wordLimit; }

        public static ReadingFit atDistance(double blocks) {
            if (blocks < 3.6) return WORD;
            if (blocks < 5.3) return SHORT;
            if (blocks < 7.2) return MEDIUM;
            return FULL;
        }

        /** Leave a margin at the boundaries so similar reading distances choose the same size. */
        public static ReadingFit atDistance(double blocks, ReadingFit previous) {
            ReadingFit wanted = atDistance(blocks);
            if (previous == null || wanted == previous) return wanted;
            ReadingFit confirmed = atDistance(blocks + (wanted.ordinal() < previous.ordinal() ? .45 : -.45));
            return wanted.ordinal() < previous.ordinal() ? confirmed.limitTo(previous)
                    : confirmed.ordinal() > previous.ordinal() ? confirmed : previous;
        }

        public ReadingFit limitTo(ReadingFit other) {
            return ordinal() <= other.ordinal() ? this : other;
        }

        public static ReadingFit openSky(List<WorldLyricsPlan.Token> words) {
            if (words.size() < 4) return SHORT;
            long duration = words.getLast().endMillis() - words.getFirst().startMillis();
            return duration < words.size() * 240L ? MEDIUM : SHORT;
        }

        /** Keep words whole, with natural breaks preferred inside the available width. */
        public List<Integer> groupEnds(List<WorldLyricsPlan.Token> tokens, double width, ToDoubleFunction<String> measure) {
            List<Integer> ends = new ArrayList<>();
            double space = Math.max(1, measure.applyAsDouble(" "));
            int count = 0;
            double used = 0;
            long groupStart = 0;
            for (int first = 0; first < tokens.size();) {
                int end = first + 1;
                double wordWidth = measure.applyAsDouble(tokens.get(first).text());
                while (end < tokens.size() && !tokens.get(end).startsWord())
                    wordWidth += measure.applyAsDouble(tokens.get(end++).text());
                boolean pause = false, punctuation = false;
                if (count > 0) {
                    var previous = tokens.get(first - 1);
                    pause = tokens.get(first).startMillis() - previous.endMillis() >= 200;
                    punctuation = count >= 2 && previous.endMillis() - groupStart >= 520
                            && PHRASE_END.matcher(previous.text().stripTrailing()).find();
                }
                if (count > 0 && (count == wordLimit || used + space + wordWidth > width || pause || punctuation)) {
                    ends.add(first);
                    count = 0;
                    used = 0;
                }
                if (count == 0) groupStart = tokens.get(first).startMillis();
                used += (count++ == 0 ? 0 : space) + wordWidth;
                first = end;
            }
            if (!tokens.isEmpty()) ends.add(tokens.size());
            return List.copyOf(ends);
        }
    }

    public record Span(String text, WorldLyricsPlan.Token token, double x, double width,
                       double sourceX, double sourceWidth, boolean rtl) {
        public long startMillis() {
            return token.startMillis() + Math.round(token.durationMillis() * sourceX / Math.max(1, sourceWidth));
        }
        public long endMillis() {
            return token.startMillis() + Math.round(token.durationMillis() * (sourceX + width) / Math.max(1, sourceWidth));
        }
    }
    public record Row(List<Span> spans, double width, long startMillis, long endMillis) { }
    public record Page(List<Row> rows, double width, long startMillis, long endMillis) { }
    private record Part(String text, WorldLyricsPlan.Token token, double offset, double width, double sourceWidth) { }

    public static List<Page> plan(List<WorldLyricsPlan.Token> tokens, boolean timed, boolean rtl,
                                  double widthLimit, ToDoubleFunction<String> measure) {
        return plan(tokens, timed, rtl, widthLimit, measure, false);
    }

    /** Divide long line-timed verses into readable pages using the measured text's position. */
    public static List<Page> readingPages(List<WorldLyricsPlan.Token> tokens, boolean timed, boolean rtl,
                                          double widthLimit, ToDoubleFunction<String> measure) {
        return plan(tokens, timed, rtl, widthLimit, measure, true);
    }

    private static List<Page> plan(List<WorldLyricsPlan.Token> tokens, boolean timed, boolean rtl,
                                   double widthLimit, ToDoubleFunction<String> measure, boolean pageUntimed) {
        double limit = Math.max(12, widthLimit);
        List<List<Part>> words = new ArrayList<>();
        for (var token : tokens) {
            double sourceWidth = measure.applyAsDouble(token.text());
            if (!timed) {
                var matcher = WORD.matcher(token.text());
                while (matcher.find()) {
                    words.add(List.of(new Part(matcher.group(), token,
                            measure.applyAsDouble(token.text().substring(0, matcher.start())),
                            measure.applyAsDouble(matcher.group()), sourceWidth)));
                }
            } else {
                if (words.isEmpty() || token.startsWord()) words.add(new ArrayList<>());
                words.getLast().add(new Part(token.text(), token, 0, sourceWidth, sourceWidth));
            }
        }
        double space = Math.max(1, measure.applyAsDouble(" "));
        // Balance measured words, rather than imposing an arbitrary number of words per row.
        int count = words.size();
        double[] widths = new double[count], cost = new double[count + 1];
        int[] breaks = new int[count];
        for (int i = 0; i < count; i++) widths[i] = words.get(i).stream().mapToDouble(Part::width).sum();
        for (int i = count - 1; i >= 0; i--) {
            cost[i] = Double.POSITIVE_INFINITY;
            double width = 0;
            for (int j = i; j < count; j++) {
                width += widths[j] + (j == i ? 0 : space);
                if (j > i && width > limit) break;
                double slack = Math.max(0, limit - width);
                double candidate = cost[j + 1] + slack * slack * (j + 1 == count ? .35 : 1);
                if (j == i && count > 2) candidate += limit * limit * .35;
                if (candidate < cost[i]) { cost[i] = candidate; breaks[i] = j + 1; }
            }
        }
        Builder builder = new Builder(limit, space, rtl);
        int nextBreak = count == 0 ? 0 : breaks[0];
        for (int index = 0; index < words.size(); index++) {
            List<Part> word = words.get(index);
            if (index == nextBreak) { builder.finish(); nextBreak = breaks[index]; }
            boolean first = true;
            for (Part part : word) {
                // A sung word is an indivisible unit, including timed syllables and connected scripts.
                builder.add(part, first);
                first = false;
            }
            builder.wordCount++;
        }
        builder.finish();
        List<Page> result = new ArrayList<>();
        List<Row> pageRows = new ArrayList<>();
        long end = Long.MIN_VALUE;
        for (Row row : builder.rows) {
            // Overlapping vocals/fragments cannot be hidden by a page turn.
            if ((timed || pageUntimed) && pageRows.size() >= (timed ? 2 : 3) && row.startMillis() >= end
                    && row.startMillis() > pageRows.getFirst().startMillis()) {
                result.add(page(pageRows)); pageRows.clear(); end = Long.MIN_VALUE;
            }
            pageRows.add(row); end = Math.max(end, row.endMillis());
        }
        if (!pageRows.isEmpty()) result.add(page(pageRows));
        return List.copyOf(result);
    }

    /** Keep an ordinary cinematic phrase assembled instead of paging its earlier words away. */
    public static List<Page> scenePages(List<WorldLyricsPlan.Token> tokens, boolean timed, boolean rtl,
                                        double widthLimit, ToDoubleFunction<String> measure) {
        List<Page> pages = plan(tokens, timed, rtl, widthLimit, measure);
        long words = timed ? tokens.stream().filter(WorldLyricsPlan.Token::startsWord).count()
                : tokens.stream().mapToLong(token -> WORD.matcher(token.text()).results().count()).sum();
        if (pages.size() <= 1 || words > WorldLyricsPresentation.MAX_WORDS) return pages;
        return List.of(page(pages.stream().flatMap(value -> value.rows().stream()).toList()));
    }

    private static Page page(List<Row> rows) {
        return new Page(List.copyOf(rows), rows.stream().mapToDouble(Row::width).max().orElse(0),
                rows.stream().mapToLong(Row::startMillis).min().orElse(0),
                rows.stream().mapToLong(Row::endMillis).max().orElse(0));
    }
    private static final class Builder {
        final double limit, space;
        final boolean rtl;
        final List<Row> rows = new ArrayList<>();
        final List<Span> spans = new ArrayList<>();
        double width;
        int wordCount;
        Builder(double limit, double space, boolean rtl) { this.limit = limit; this.space = space; this.rtl = rtl; }
        void add(Part part, boolean wordStart) {
            double gap = wordStart && !spans.isEmpty() ? space : 0;
            width += gap;
            spans.add(new Span(part.text(), part.token(), width, part.width(), part.offset(), part.sourceWidth(),
                    LyricsAnimation.isRightToLeft(part.text())));
            width += part.width();
        }
        void finish() {
            if (spans.isEmpty()) return;
            double rowWidth = width;
            List<Span> placed = rtl ? spans.stream().map(span -> new Span(span.text(), span.token(),
                    rowWidth - span.x() - span.width(), span.width(), span.sourceX(), span.sourceWidth(), span.rtl())).toList()
                    : List.copyOf(spans);
            rows.add(new Row(placed, width, spans.stream().mapToLong(Span::startMillis).min().orElse(0),
                    spans.stream().mapToLong(Span::endMillis).max().orElse(0)));
            spans.clear(); width = 0; wordCount = 0;
        }
    }

    /** Crossfade only adjacent pages, using actual timed word boundaries. Untimed text has one page. */
    public static double pageVisibility(List<Page> pages, int index, long now, boolean animated) {
        long start = pages.get(index).startMillis();
        long next = index + 1 < pages.size() ? pages.get(index + 1).startMillis() : Long.MAX_VALUE;
        if (!animated) return (index == 0 || now >= start) && now < next ? 1 : 0;
        double enterWindow = transitionWindow(pages, index);
        double exitWindow = transitionWindow(pages, index + 1);
        double enter = index == 0 ? 1 : LyricsAnimation.smoother((now - start + enterWindow) / (2 * enterWindow));
        double exit = next == Long.MAX_VALUE ? 1 : 1 - LyricsAnimation.smoother((now - next + exitWindow) / (2 * exitWindow));
        return enter * exit;
    }

    private static double transitionWindow(List<Page> pages, int index) {
        if (index <= 0 || index >= pages.size()) return 80;
        double half = Math.min(80, (pages.get(index).startMillis() - pages.get(index - 1).startMillis()) / 2.0);
        if (index + 1 < pages.size()) half = Math.min(half,
                (pages.get(index + 1).startMillis() - pages.get(index).startMillis()) / 2.0);
        return Math.max(1, half);
    }

    /** Distance-aware physical size; fit includes animation margins and never exceeds the measured surface. */
    public static double pixelScale(double rowWidth, int rows, double availableWidth, double availableHeight,
                                    double distance, double size, boolean backing, double lineHeight) {
        double desired = .052 * size * Math.clamp(distance / 6, .35, 24);
        double margin = .5 * Math.clamp(distance / 6, .8, 43);
        double fitX = Math.max(.1, availableWidth - margin) / Math.max(1, (rowWidth + 6) * 1.16);
        double fitY = Math.max(.1, availableHeight - margin) / Math.max(1, rows * lineHeight * 1.16);
        return Math.min(desired * (backing ? .88 : 1), Math.min(fitX, fitY));
    }

    public record WallRect(int left, int bottom, int right, int top) { }

    /** Largest fully supported rectangle containing the ray hit. Holes and edges are excluded. */
    public static WallRect wallRectangle(boolean[][] supported) {
        if (supported.length == 0 || supported[0].length == 0) return null;
        int cy = supported.length / 2, cx = supported[0].length / 2;
        if (!supported[cy][cx]) return null;
        WallRect best = null; int bestArea = 0;
        for (int bottom = 0; bottom <= cy; bottom++) {
            for (int top = cy; top < supported.length; top++) {
                int left = cx, right = cx;
                if (!column(supported, cx, bottom, top)) continue;
                while (left > 0 && column(supported, left - 1, bottom, top)) left--;
                while (right + 1 < supported[0].length && column(supported, right + 1, bottom, top)) right++;
                int area = (right - left) * (top - bottom);
                if (area > bestArea) { bestArea = area; best = new WallRect(left, bottom, right, top); }
            }
        }
        return best;
    }
    private static boolean column(boolean[][] cells, int x, int bottom, int top) {
        for (int y = bottom; y <= top; y++) if (!cells[y][x]) return false;
        return true;
    }
}
