package dev.relaylyrics.client.music;

import dev.relaylyrics.protocol.lyrics.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.stream.IntStream;
import static dev.relaylyrics.client.music.WorldLyricsPresentation.*;
import static org.assertj.core.api.Assertions.*;

class WorldLyricsStageTest {
    @Test void independentlyPlacedWordsUseFourLanesAndStableMildTransforms() {
        var source = source("I'm", "a", "loser", "today");
        var cue = demonstration(source, Composition.FALLING_WORDS);
        var tokens = WorldLyricsPlan.tokens(source.line(), false);
        var stage = WorldLyricsStage.groups(cue, tokens, Intensity.DISCREET);
        assertThat(stage).hasSize(4);
        assertThat(stage).extracting(WorldLyricsStage.Group::region).containsExactly(Region.RIGHT, Region.BOTTOM, Region.LEFT, Region.TOP);
        assertThat(stage).extracting(WorldLyricsStage.Group::firstToken).containsExactly(0, 1, 2, 3);
        assertThat(stage).extracting(WorldLyricsStage.Group::endToken).containsExactly(1, 2, 3, 4);
        assertThat(stage).isEqualTo(WorldLyricsStage.groups(cue, tokens, Intensity.DISCREET));
        assertThat(stage).extracting(WorldLyricsStage.Group::rotationDegrees).doesNotHaveDuplicates();
        for (var group : stage) {
            assertThat(Math.abs(group.rotationDegrees())).isBetween(1.0, 6.0);
            assertThat(group.scale()).isBetween(.9, 1.1);
        }
    }

    @Test void aSungWordKeepsEveryTimedSyllableInOneSpatialBlock() {
        var line = new LyricLine(0, 1_500, "hello world", List.of(new TimedWord(0, 500, "hel"),
                new TimedWord(500, 1_000, "lo", true), new TimedWord(1_000, 1_500, " world")), "v1", false, null, null);
        var source = new WorldLyricsPlan.WorldLine(line, LyricAlignment.NORMAL, false);
        var cue = demonstration(source, Composition.FALLING_WORDS);
        var tokens = WorldLyricsPlan.tokens(line, false);
        assertThat(cue.groupEnds()).containsExactly(2, 3);
        var groups = WorldLyricsStage.groups(cue, tokens, Intensity.DISCREET);
        assertThat(groups).hasSize(2);
        assertThat(groups.getFirst().firstToken()).isZero();
        assertThat(groups.getFirst().endToken()).isEqualTo(2);
        assertThat(tokens).extracting(WorldLyricsPlan.Token::startMillis).containsExactly(0L, 500L, 1_000L);
        assertThat(source.line()).isSameAs(line);
    }

    @Test void spotlightMixesWordsAndChunksAroundTheEmphasizedWord() {
        var source = source("we", "can", "mix", "centerpiece", "with", "short", "chunks");
        var cue = demonstration(source, Composition.SPOTLIGHT);
        var groups = WorldLyricsStage.groups(cue, WorldLyricsPlan.tokens(source.line(), false), Intensity.BALANCED);
        assertThat(groups).extracting(group -> group.endToken() - group.firstToken()).contains(1, 2);
        var accent = cue.accents().getFirst();
        assertThat(groups).anySatisfy(group -> {
            assertThat(group.firstToken()).isEqualTo(accent.tokenIndex());
            assertThat(group.endToken()).isEqualTo(accent.tokenIndex() + 1);
        });
    }

    @Test void localEntrancesVaryWithoutRepeatingOrDependingOnFrameTime() {
        var lines = IntStream.range(0, 24).mapToObj(index -> {
            var source = source("you", "tear", "do", "wanna");
            long offset = index * 4_000L;
            return new LyricLine(offset, offset + 2_000, source.line().text(), source.line().words().stream()
                    .map(word -> new TimedWord(word.startMillis() + offset, word.endMillis() + offset, word.text())).toList(),
                    "v1", false, null, null);
        }).toList();
        var timeline = new Timeline(new LyricsDocument(lines));
        var local = timeline.local(Intensity.DISCREET);
        var entrances = local.values().stream().map(Cue::entrance).toList();
        assertThat(entrances).contains(Entrance.FADE, Entrance.RISE, Entrance.DEPTH, Entrance.FALL);
        assertThat(entrances.subList(0, 12)).isNotEqualTo(entrances.subList(12, 24));
        for (int index = 1; index < entrances.size(); index++)
            assertThat(entrances.get(index)).isNotEqualTo(entrances.get(index - 1));
        assertThat(local.values()).allSatisfy(cue -> assertThat(cue.volumetric()).isFalse());
        assertThat(new Timeline(new LyricsDocument(lines)).local(Intensity.DISCREET)).isEqualTo(local);
    }

    @Test void directedGroupingIsHonoredAndLongSpatialScenesShowAtMostFourBlocks() {
        var source = source("one", "two", "three", "four", "five", "six", "seven", "eight");
        var base = demonstration(source, Composition.PROGRESSIVE);
        var directed = new Cue(base.id(), source, Composition.PROGRESSIVE, Region.TOP, Entrance.FADE,
                false, 1_500, List.of(), List.of(1, 4, 5, 8));
        var tokens = WorldLyricsPlan.tokens(source.line(), false);
        var groups = WorldLyricsStage.groups(directed, tokens, Intensity.EXPRESSIVE);
        assertThat(groups).extracting(WorldLyricsStage.Group::endToken).containsExactly(1, 4, 5, 8);
        assertThat(groups.getFirst().region()).isEqualTo(Region.TOP);
        var falling = demonstration(source, Composition.FALLING_WORDS);
        var window = window(falling, tokens, 3_750);
        long count = WorldLyricsStage.groups(falling, tokens, Intensity.EXPRESSIVE).stream()
                .filter(group -> IntStream.range(group.firstToken(), group.endToken())
                        .anyMatch(token -> sample(falling, tokens, window, token, 3_750, false).alpha() > 0)).count();
        assertThat(count).isEqualTo(4);
    }

    @Test void rightToLeftWordsKeepSourceOrderAndUntimedPhrasesRemainWhole() {
        var source = source("مرحبا", "بك", "هنا");
        var cue = demonstration(source, Composition.FALLING_WORDS);
        var tokens = WorldLyricsPlan.tokens(source.line(), false);
        var stage = WorldLyricsStage.groups(cue, tokens, Intensity.DISCREET);
        assertThat(stage).extracting(WorldLyricsStage.Group::firstToken).containsExactly(0, 1, 2);
        assertThat(tokens).extracting(WorldLyricsPlan.Token::text).containsExactly("مرحبا", "بك", "هنا");
        var line = new LyricLine(0, 3_000, "an untimed phrase", List.of(), "v1", false, null, null);
        var phrase = demonstration(new WorldLyricsPlan.WorldLine(line, LyricAlignment.NORMAL, false), Composition.FALLING_WORDS);
        assertThat(WorldLyricsStage.groups(phrase, WorldLyricsPlan.tokens(line, false), Intensity.EXPRESSIVE)).hasSize(1);
    }

    @Test void upperLaneFallbackTriesBothCornersInsteadOfRepeatingTheOccupiedOne() {
        for (int index : new int[]{0, 1, 2, 3, Integer.MAX_VALUE}) {
            var group = new WorldLyricsStage.Group(index, 0, 1, Region.TOP, 0, 1);
            int first = WorldLyricsStage.topSideSign(group, 0);
            int opposite = WorldLyricsStage.topSideSign(group, 2);
            assertThat(first).isIn(-1, 1);
            assertThat(opposite).isEqualTo(-first);
            assertThat(WorldLyricsStage.topSideSign(group, 4)).isEqualTo(first);
            assertThat(WorldLyricsStage.topSideSign(group, 6)).isEqualTo(opposite);
        }
    }

    private static WorldLyricsPlan.WorldLine source(String... text) {
        var words = IntStream.range(0, text.length)
                .mapToObj(i -> new TimedWord(i * 500, (i + 1) * 500, text[i])).toList();
        return new WorldLyricsPlan.WorldLine(new LyricLine(0, text.length * 500, String.join(" ", text), words,
                "v1", false, null, null), LyricAlignment.NORMAL, false);
    }
}
