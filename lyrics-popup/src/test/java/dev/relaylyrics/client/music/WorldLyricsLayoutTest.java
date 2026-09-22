package dev.relaylyrics.client.music;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.ToDoubleFunction;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class WorldLyricsLayoutTest {
    private static final ToDoubleFunction<String> WIDTH = value -> value.codePointCount(0, value.length()) * 6;
    private static WorldLyricsPlan.Token token(String text, long start, long end, boolean startsWord) {
        return new WorldLyricsPlan.Token(text, start, end, startsWord);
    }
    private static List<WorldLyricsLayout.Row> rows(List<WorldLyricsLayout.Page> pages) {
        return pages.stream().flatMap(page -> page.rows().stream()).toList();
    }

    @Test void wrapsAtWordBoundariesAndShowsTwoTimedRowsPerPage() {
        List<WorldLyricsPlan.Token> tokens = new ArrayList<>();
        for (int i = 0; i < 6; i++) tokens.add(token("one", i * 500, (i + 1) * 500, true));
        var pages = WorldLyricsLayout.plan(tokens, true, false, 42, WIDTH);
        assertThat(pages).hasSize(2);
        assertThat(pages.getFirst().rows()).hasSize(2);
        assertThat(pages.getLast().startMillis()).isEqualTo(2000);
        assertThat(rows(pages)).hasSize(3).allMatch(row -> row.width() <= 42);
        assertThat(rows(pages).stream().flatMap(row -> row.spans().stream()).map(WorldLyricsLayout.Span::token))
                .containsExactlyElementsOf(tokens);
        assertThat(WorldLyricsLayout.pageVisibility(pages, 0, 2000, true)).isEqualTo(.5);
        assertThat(WorldLyricsLayout.pageVisibility(pages, 1, 2000, true)).isEqualTo(.5);
        assertThat(WorldLyricsLayout.pageVisibility(pages, 0, 2000, false)).isZero();
        assertThat(WorldLyricsLayout.pageVisibility(pages, 1, 2000, false)).isEqualTo(1);
    }

    @Test void oversizedWordsBreakWithoutLosingTextOrRestartingTheirFill() {
        var source = token("abcdefghijkl", 1000, 2200, true);
        var pages = WorldLyricsLayout.plan(List.of(source), true, false, 24, WIDTH);
        var spans = rows(pages).stream().flatMap(row -> row.spans().stream()).toList();
        assertThat(spans).extracting(WorldLyricsLayout.Span::text).containsExactly("abcd", "efgh", "ijkl");
        assertThat(spans).extracting(WorldLyricsLayout.Span::sourceX).containsExactly(0.0, 24.0, 48.0);
        assertThat(spans).extracting(WorldLyricsLayout.Span::startMillis).containsExactly(1000L, 1400L, 1800L);
        assertThat(spans).allMatch(span -> span.token() == source && span.sourceWidth() == 72);
        assertThat(pages.getLast().startMillis()).isEqualTo(1800);
    }

    @Test void syllablesStayTogetherWhenTheirWordFitsOnTheNextRow() {
        var tokens = List.of(token("one", 0, 500, true), token("hel", 500, 900, true), token("lo", 900, 1300, false));
        var rows = rows(WorldLyricsLayout.plan(tokens, true, false, 42, WIDTH));
        assertThat(rows).hasSize(2);
        assertThat(rows.get(1).spans()).extracting(WorldLyricsLayout.Span::text).containsExactly("hel", "lo");
        assertThat(rows.get(1).spans()).extracting(WorldLyricsLayout.Span::x).containsExactly(0.0, 18.0);
    }

    @Test void graphemeClustersAreNeverSplitEvenIfOneClusterExceedsTheLimit() {
        String family = "\ud83d\udc69\u200d\ud83d\udc69\u200d\ud83d\udc67\u200d\ud83d\udc66";
        var pages = WorldLyricsLayout.plan(List.of(token("A\u0301" + family + "B\u0301", 0, 4000, true)), true, false, 12, WIDTH);
        assertThat(rows(pages).stream().flatMap(row -> row.spans().stream()).map(WorldLyricsLayout.Span::text))
                .containsExactly("A\u0301", family, "B\u0301");
    }

    @Test void untimedTextWrapsButNeverReceivesInventedTimedPageTurns() {
        var pages = WorldLyricsLayout.plan(List.of(token("one two three four five six", 1000, 5000, true)), false, false, 36, WIDTH);
        assertThat(pages).hasSize(1);
        assertThat(pages.getFirst().rows().size()).isGreaterThan(2);
        assertThat(WorldLyricsLayout.pageVisibility(pages, 0, 2000, true)).isEqualTo(1);
        assertThat(WorldLyricsLayout.pageVisibility(pages, 0, 4000, true)).isEqualTo(1);
        assertThat(rows(pages).stream().flatMap(row -> row.spans().stream()).map(WorldLyricsLayout.Span::text))
                .containsExactly("one", "two", "three", "four", "five", "six");
    }

    @Test void rtlWordsKeepShapingAndReversePlacementWithinEachRow() {
        var pages = WorldLyricsLayout.plan(List.of(token("\u0645\u0631\u062d\u0628\u0627", 0, 1000, true),
                token("\u0628\u0643", 1000, 2000, true)), true, true, 80, WIDTH);
        var spans = pages.getFirst().rows().getFirst().spans();
        assertThat(spans.getFirst().rtl()).isTrue();
        assertThat(spans.getFirst().x()).isGreaterThan(spans.getLast().x());
        var narrow = WorldLyricsLayout.plan(List.of(token("\u0645\u0631\u062d\u0628\u0627", 0, 1000, true)), true, true, 12, WIDTH);
        assertThat(narrow.getFirst().rows().getFirst().spans()).hasSize(1);
    }

    @Test void overlappingFragmentsAndZeroDurationFragmentsAreNotHiddenByPageTurns() {
        var tokens = List.of(token("first", 0, 1000, true), token("other", 0, 1000, true), token("third", 0, 1000, true));
        assertThat(WorldLyricsLayout.plan(tokens, true, false, 30, WIDTH)).hasSize(1);
        var zero = List.of(token("first", 0, 0, true), token("other", 0, 0, true), token("third", 0, 0, true));
        assertThat(WorldLyricsLayout.plan(zero, true, false, 30, WIDTH)).hasSize(1);
    }

    @Test void fastRapPagesNeverCrossfadeMoreThanTwoPagesAtOnce() {
        List<WorldLyricsPlan.Token> tokens = new ArrayList<>();
        for (int i = 0; i < 20; i++) tokens.add(token("word", i * 20, (i + 1) * 20, true));
        var pages = WorldLyricsLayout.plan(tokens, true, false, 24, WIDTH);
        for (long now = 0; now < 400; now++) {
            int visible = 0; double total = 0;
            for (int i = 0; i < pages.size(); i++) {
                double alpha = WorldLyricsLayout.pageVisibility(pages, i, now, true);
                if (alpha > 0) visible++;
                total += alpha;
            }
            assertThat(visible).isLessThanOrEqualTo(2);
            assertThat(total).isCloseTo(1, within(.00001));
        }
    }

    @Test void scaleFavorsShortPhrasesButRespectsAvailableSpaceAndAnimationMargins() {
        double shortSize = WorldLyricsLayout.pixelScale(30, 1, 15, 5, 6, 1.2, false, 15);
        double longSize = WorldLyricsLayout.pixelScale(150, 2, 15, 5, 6, 1.2, false, 15);
        assertThat(shortSize).isGreaterThan(longSize);
        double tinyWall = WorldLyricsLayout.pixelScale(120, 2, 2.2, 1.1, 12, 2, false, 15);
        assertThat((120 + 6) * tinyWall * 1.16 + .5).isLessThanOrEqualTo(2.200001);
        assertThat(2 * 15 * tinyWall * 1.16 + .5).isLessThanOrEqualTo(1.100001);
        assertThat(tinyWall).isPositive();
        assertThat(WorldLyricsLayout.pixelScale(30, 1, 15, 5, 6, 2, false, 15)).isGreaterThan(shortSize);
    }

    @Test void choosesOnlyASupportedWallRectangleContainingTheOriginalHit() {
        boolean[][] cells = new boolean[7][11];
        for (var row : cells) Arrays.fill(row, true);
        assertThat(WorldLyricsLayout.wallRectangle(cells)).isEqualTo(new WorldLyricsLayout.WallRect(0, 0, 10, 6));
        cells[4][7] = false;
        var rect = WorldLyricsLayout.wallRectangle(cells);
        assertThat(rect).isNotNull();
        assertThat(rect.left()).isLessThanOrEqualTo(5); assertThat(rect.right()).isGreaterThanOrEqualTo(5);
        assertThat(rect.bottom()).isLessThanOrEqualTo(3); assertThat(rect.top()).isGreaterThanOrEqualTo(3);
        for (int y = rect.bottom(); y <= rect.top(); y++) {
            for (int x = rect.left(); x <= rect.right(); x++) assertThat(cells[y][x]).isTrue();
        }
        cells[3][5] = false;
        assertThat(WorldLyricsLayout.wallRectangle(cells)).isNull();
        assertThat(WorldLyricsLayout.wallRectangle(new boolean[0][0])).isNull();
    }
}
