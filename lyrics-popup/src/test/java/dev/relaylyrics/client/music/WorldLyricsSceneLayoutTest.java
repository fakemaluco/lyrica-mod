package dev.relaylyrics.client.music;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.assertThat;

class WorldLyricsSceneLayoutTest {
    @Test void assemblingPhraseKeepsEveryEarlierWordInTheSamePage() {
        var tokens = tokens(12);
        assertThat(WorldLyricsLayout.plan(tokens, true, false, 60, s -> s.length() * 6)).hasSizeGreaterThan(1);
        var scene = WorldLyricsLayout.scenePages(tokens, true, false, 60, s -> s.length() * 6);
        assertThat(scene).hasSize(1);
        assertThat(scene.getFirst().rows().stream().flatMap(row -> row.spans().stream()).count()).isEqualTo(12);
        assertThat(WorldLyricsLayout.pageVisibility(scene, 0, 5_000, true)).isEqualTo(1);
    }

    @Test void extremelyLongLinesStillUseBoundedTimedPages() {
        assertThat(WorldLyricsLayout.scenePages(tokens(60), true, false, 60, s -> s.length() * 6)).hasSizeGreaterThan(1);
    }

    private static List<WorldLyricsPlan.Token> tokens(int count) {
        return IntStream.range(0, count).mapToObj(i -> new WorldLyricsPlan.Token("word" + i, i * 500, (i + 1) * 500, true)).toList();
    }
}
