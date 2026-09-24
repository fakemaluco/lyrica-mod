package dev.relaylyrics.client.music;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;

class WorldLyricsDirectorTest {
    @Test void terrainAndIntroStylesHaveDistinctSpatialEntrances() {
        var ground = WorldLyricsDirector.Preset.GROUND;
        var intro = WorldLyricsDirector.Preset.INTRO;
        assertThat(ground.entrance).isEqualTo(WorldLyricsDirector.Entrance.FALL);
        assertThat(intro.entrance).isEqualTo(WorldLyricsDirector.Entrance.POP);
        assertThat(intro.depth).isGreaterThan(ground.depth);
        var settings = new WorldLyricsDirector.Settings(false, intro,
                EnumSet.allOf(WorldLyricsDirector.Preset.class), WorldLyricsDirector.Variation.MEDIUM,
                6, 192, 1, .65, .32, .10, 1, 1, false, WorldLyricsDirector.Quality.MEDIUM);
        assertThat(WorldLyricsDirector.choose(0, "opening", 1, 1200, false, false, 0, settings).preset())
                .isEqualTo(intro);
    }
    private static WorldLyricsDirector.Settings distantSettings() {
        return new WorldLyricsDirector.Settings(true, WorldLyricsDirector.Preset.CINEMA,
                EnumSet.allOf(WorldLyricsDirector.Preset.class), WorldLyricsDirector.Variation.MEDIUM,
                6, 192, 1, .65, .32, .10, 1, 1, false, WorldLyricsDirector.Quality.MEDIUM);
    }

    @Test void readingHorizonDistinguishesWalkingRunningAndFastElytra() {
        var settings = distantSettings();
        double walk = WorldLyricsDirector.readingDistance(4.3, 3, false, false, settings);
        double run = WorldLyricsDirector.readingDistance(5.6, 3, false, false, settings);
        double glide = WorldLyricsDirector.readingDistance(30, 3, true, false, settings);
        assertThat(walk).isGreaterThan(20);
        assertThat(run).isGreaterThan(walk);
        assertThat(glide).isGreaterThan(150).isLessThanOrEqualTo(192);
        assertThat(WorldLyricsDirector.readingDistance(60, 3, true, false, settings)).isEqualTo(192);
        assertThat(WorldLyricsDirector.readingDistance(0, 3, false, true, settings)).isEqualTo(48);
    }

    @Test void longerPhrasesArePlacedFartherAwayToPreserveReadingTime() {
        assertThat(WorldLyricsDirector.readingDistance(12, 5, false, false, distantSettings()))
                .isGreaterThan(WorldLyricsDirector.readingDistance(12, 1, false, false, distantSettings()));
    }

    @Test void worldTextMaintainsAngularSizeAtLandscapeDistances() {
        double near = WorldLyricsLayout.pixelScale(80, 1, 9, 3, 6, 1.2, false, 15);
        double far = WorldLyricsLayout.pixelScale(80, 1, 90, 30, 60, 1.2, false, 15);
        assertThat(far / near).isBetween(9.5, 10.5);
    }
    private static WorldLyricsDirector.Settings settings(boolean automatic) {
        return new WorldLyricsDirector.Settings(automatic, WorldLyricsDirector.Preset.CINEMA,
                EnumSet.allOf(WorldLyricsDirector.Preset.class), WorldLyricsDirector.Variation.MEDIUM,
                3, 10, 1, .65, .32, .10, 1, 1, false, WorldLyricsDirector.Quality.MEDIUM);
    }

    @Test void compositionIsStableForTheSamePhraseAndManualPresetWins() {
        var first = WorldLyricsDirector.choose(4_000, "same phrase", 2, 2_000, false, false, .3, settings(true));
        var second = WorldLyricsDirector.choose(4_000, "same phrase", 2, 2_000, false, false, .3, settings(true));
        assertThat(second.preset()).isEqualTo(first.preset());
        assertThat(WorldLyricsDirector.choose(4_000, "same phrase", 2, 2_000, false, false, .3,
                settings(false)).preset()).isEqualTo(WorldLyricsDirector.Preset.CINEMA);
    }

    @Test void speedMovesNewPhrasesFartherWithinConfiguredLimits() {
        var settings = settings(true);
        assertThat(WorldLyricsDirector.distance(0, false, settings)).isEqualTo(3);
        assertThat(WorldLyricsDirector.distance(1, false, settings)).isEqualTo(10);
        assertThat(WorldLyricsDirector.distance(.5, false, settings)).isBetween(3.0, 10.0);
        assertThat(WorldLyricsDirector.distance(1, true, settings)).isEqualTo(10);
    }

    @Test void wallsUseSculptureInsteadOfCurvedPlacements() {
        var onlyArc = new WorldLyricsDirector.Settings(true, WorldLyricsDirector.Preset.ARC,
                EnumSet.of(WorldLyricsDirector.Preset.ARC), WorldLyricsDirector.Variation.HIGH,
                3, 10, 1, .7, .3, .1, 1, 1, false, WorldLyricsDirector.Quality.HIGH);
        var result = WorldLyricsDirector.choose(0, "wall", 1, 3_000, false, true, 0, onlyArc);
        assertThat(result.preset()).isEqualTo(WorldLyricsDirector.Preset.SCULPTURE);
        assertThat(result.depthLayers()).isEqualTo(14);
    }

    @Test void shortPhrasesAvoidSlowDepthPulse() {
        var sculpture = new WorldLyricsDirector.Settings(false, WorldLyricsDirector.Preset.SCULPTURE,
                EnumSet.allOf(WorldLyricsDirector.Preset.class), WorldLyricsDirector.Variation.MEDIUM,
                3, 10, 1, .6, .3, .1, 1, 1, false, WorldLyricsDirector.Quality.MEDIUM);
        assertThat(WorldLyricsDirector.choose(0, "quick words", 4, 600, false, false, 0, sculpture).motion())
                .isEqualTo(WorldLyricsDirector.Motion.BREATHE);
    }

    @Test void automaticDirectorAvoidsAnImmediateRepeatedPreset() {
        var settings = settings(true);
        var first = WorldLyricsDirector.choose(2_000, "one", 1, 1_000, false, false, 0, settings);
        var next = WorldLyricsDirector.choose(4_000, "two", 1, 1_000, false, false, 0,
                settings, first.preset());
        assertThat(next.preset()).isNotEqualTo(first.preset());
    }

    @Test void presetDepthsRemainDistinctAtTheDefaultDepthControl() {
        var cinema = WorldLyricsDirector.choose(0, "text", 1, 2_000, false, false, 0, settings(false));
        var sculptureSettings = new WorldLyricsDirector.Settings(false, WorldLyricsDirector.Preset.SCULPTURE,
                EnumSet.allOf(WorldLyricsDirector.Preset.class), WorldLyricsDirector.Variation.MEDIUM,
                3, 10, 1, .65, .32, .10, 1, 1, false, WorldLyricsDirector.Quality.MEDIUM);
        var sculpture = WorldLyricsDirector.choose(0, "text", 1, 2_000, false, false, 0, sculptureSettings);
        assertThat(sculpture.depth()).isGreaterThan(cinema.depth());
    }

    @Test void fastMovementSelectsFlightWhenAvailable() {
        var result = WorldLyricsDirector.choose(1_000, "moving", 1, 800,
                false, false, .9, settings(true));
        assertThat(result.preset()).isEqualTo(WorldLyricsDirector.Preset.FLIGHT);
    }
}
