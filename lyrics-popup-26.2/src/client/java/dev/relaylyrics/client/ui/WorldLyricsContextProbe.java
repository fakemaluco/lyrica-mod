package dev.relaylyrics.client.ui;

import dev.relaylyrics.FanDroidMod;
import dev.relaylyrics.client.*;
import dev.relaylyrics.client.music.*;
import dev.relaylyrics.protocol.lyrics.*;
import dev.relaylyrics.protocol.music.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import com.mojang.blaze3d.platform.NativeImage;

/** Synthetic local scenes; only ever runs in the explicitly selected disposable QA world. */
public final class WorldLyricsContextProbe {
    private static final String[] SCENES = {"wall-south", "wall-west", "wall-north", "wall-east",
            "floor-flat", "floor-upright", "glass", "stained-glass", "sparse-leaves", "dense-leaves",
            "glass-and-stone", "perspective-turn", "sprint-to-stop", "look-away"};
    private static final int FIRST_STAGE = Integer.getInteger("lyrica.worldPresentationStage", 0);
    private static int stage = FIRST_STAGE, ticks, total;
    private static boolean initialized, capturing;
    private static boolean benchmarkStarted;
    private static volatile boolean terrainReady;
    private static String anchors;
    private static List<WorldLyricsRenderer.Anchor> wordAnchors;
    private static long pauseSongPosition;
    private WorldLyricsContextProbe() { }

    public static void tick(Minecraft client) {
        if (!Boolean.getBoolean("lyrica.worldExperienceSelfTest")) return;
        if (stage < 0 || stage >= SCENES.length) throw new IllegalArgumentException("Unknown contextual QA stage");
        if (++total > 12_000) throw new IllegalStateException("Contextual QA timed out: " + SCENES[stage]);
        if (client.player == null || client.level == null || client.gui.overlay() != null || capturing) return;
        if (!initialized) { initialized = true; configure(client); }
        if (!terrainReady) return;
        client.options.pauseOnLostFocus = false;
        if (client.gui.screen() != null) client.gui.setScreen(null);
        client.gui.hud.getChat().clearMessages(false);
        client.player.setPos(.5, stage == 4 ? 87 : 81, .5);
        boolean turnDuringWord = Boolean.getBoolean("lyrica.worldWordTransitionSelfTest") && ticks >= 71 && ticks < 76;
        client.player.setYRot(turnDuringWord ? 115 : stage <= 3 ? stage * 90 : stage == 11 && ticks >= 70 ? 30 : stage == 13 && ticks >= 70 ? 120 : 0);
        boolean skyFixture = Boolean.getBoolean("lyrica.worldWordTransitionSelfTest");
        boolean lookUp = skyFixture && ticks >= 350 && ticks < 418;
        boolean flight = skyFixture && ticks >= 418 && ticks < 436;
        client.player.setXRot(lookUp ? -34 : stage == 4 ? 50 : 0);
        client.player.setOldPosAndRot();
        client.player.setDeltaMovement(Vec3.ZERO);
        if (stage == 12) {
            client.player.getAbilities().flying = false;
            client.player.setOnGround(true);
        }
        client.player.setSprinting(stage == 12 && ticks < 70);
        WorldLyricsRenderer.probeFlight(flight ? new Vec3(0, 0, .35) : stage == 12 && ticks < 70 ? new Vec3(0, 0, .3) : null, flight);
        // Server commands complete before the updated chunks reach the client. Settle them and
        // the interpolated camera before the first letter is anchored to this scene.
        if (Boolean.getBoolean("lyrica.worldFullPhraseSelfTest")) {
            verifyFullPhrases(client);
            return;
        }
        if (++ticks == 25) lyrics();
        if (Boolean.getBoolean("lyrica.worldPauseSelfTest")) {
            verifyPauseAndSettings(client);
            return;
        }
        if (Boolean.getBoolean("lyrica.worldWordTransitionSelfTest")) {
            verifyWordTransition(client);
            return;
        }
        if (ticks == 65) anchors = WorldLyricsRenderer.probeAnchors();
        if (Boolean.getBoolean("lyrica.worldFrameBenchmark") && ticks >= 80) {
            if (!benchmarkStarted) {
                if (!WorldLyricsRenderer.verifyProbe(1, false) || WorldLyricsRenderer.probeWordCount() == 0)
                    throw new IllegalStateException("Benchmark fixture did not draw lyrics: " + WorldLyricsRenderer.probeSummary());
                FanDroidMod.LOGGER.info("Benchmark workload: words={}, {}", WorldLyricsRenderer.probeWordCount(), WorldLyricsRenderer.probeSummary());
                benchmarkStarted = true; WorldLyricsFrameProbe.start();
            }
            if (WorldLyricsFrameProbe.complete()) {
                try {
                    String summary = WorldLyricsFrameProbe.write(Path.of(System.getProperty("lyrica.worldProbeOutput", "build/world-context-qa")).resolve("frametime.json"));
                    FanDroidMod.LOGGER.info("Contextual full-frame benchmark: {}", summary);
                    client.stop();
                } catch (java.io.IOException error) { throw new IllegalStateException("Frame benchmark output failed", error); }
            }
            return;
        }
        if (ticks < 150) return;
        if (Boolean.getBoolean("lyrica.worldOutlineSelfTest")) {
            verifyOutline(client);
            return;
        }
        FanDroidMod.LOGGER.info("Contextual fixture state: {}, {}, anchors={}", SCENES[stage], WorldLyricsRenderer.probeSummary(), WorldLyricsRenderer.probeAnchors());
        if (WorldLyricsRenderer.probePeakRaycasts() > 128) throw new IllegalStateException("Contextual ray budget exceeded");
        if (stage != 13 && !WorldLyricsRenderer.verifyProbe(1, false))
            throw new IllegalStateException("Contextual phrase missing: " + SCENES[stage] + " " + WorldLyricsRenderer.probeSummary());
        if (stage <= 3 && !WorldLyricsRenderer.probeWall()) throw new IllegalStateException("Active selector did not use wall: " + SCENES[stage]);
        if (stage == 4 && !WorldLyricsRenderer.probeFloorFlat()) throw new IllegalStateException("Active selector did not use a readable flat floor");
        if (stage == 5 && WorldLyricsRenderer.probePlacedAnchors().stream().noneMatch(a -> a.ground() && Math.abs(a.normalY()) < .1))
            throw new IllegalStateException("Active selector did not use upright floor support");
        if (stage >= 6 && stage <= 8 && WorldLyricsRenderer.probePlacedAnchors().stream().noneMatch(a -> a.z() > 6))
            throw new IllegalStateException("The thin transparent layer blocked placement behind it: " + SCENES[stage]);
        if ((stage == 9 || stage == 10) && WorldLyricsRenderer.probePlacedAnchors().stream().anyMatch(a -> a.z() > 5))
            throw new IllegalStateException("The dense or opaque layer failed to block placement: " + SCENES[stage]);
        if (stage == 11 && WorldLyricsRenderer.probeVisibleConflicts() > 0)
            throw new IllegalStateException("Camera turn left visible text groups overlapping");
        if ((stage == 12 || stage == 13) && !Objects.equals(anchors, WorldLyricsRenderer.probeAnchors()))
            throw new IllegalStateException("An already sung group changed position: " + SCENES[stage]);
        if (WorldLyricsRenderer.probeGroups() > WorldLyricsPresentation.MAX_GROUPS) throw new IllegalStateException("Group budget exceeded");
        FanDroidMod.LOGGER.info("Contextual fixture passed: {}, rays={}, summary={}", SCENES[stage],
                WorldLyricsRenderer.probePeakRaycasts(), WorldLyricsRenderer.probeSummary());
        capturing = true;
        screenshot(client, image -> {
            try (image) {
                String version = FabricLoader.getInstance().getModContainer("minecraft").orElseThrow().getMetadata().getVersion().getFriendlyString();
                Path output = Path.of(System.getProperty("lyrica.worldProbeOutput", "build/world-context-qa"));
                Files.createDirectories(output);
                image.writeToFile(output.resolve("context-" + version + "-" + SCENES[stage] + ".png"));
                if (++stage == SCENES.length) { FanDroidMod.LOGGER.info("Contextual placement GL probe passed: {} scenes", SCENES.length - FIRST_STAGE); client.stop(); return; }
                ticks = 0; configure(client); capturing = false;
            } catch (Exception error) { throw new IllegalStateException("Contextual screenshot failed", error); }
        });
    }

    private static void configure(Minecraft client) {
        terrainReady = false;
        var server = client.getSingleplayerServer();
        if (server == null || !server.getWorldData().getLevelName().equals("LyricaWorldQA"))
            throw new IllegalStateException("Contextual QA requires only the disposable LyricaWorldQA world");
        var commands = new ArrayList<String>(List.of("fill -24 81 -24 -1 99 24 minecraft:air",
                "fill 0 81 -24 24 99 24 minecraft:air", "fill -24 80 -24 24 80 24 minecraft:stone",
                "gamemode spectator @a", "time set day", "weather clear"));
        // Isolate wall support: the transparent floor is physical, but cannot win as an opaque surface.
        if (stage <= 3) commands.add("fill -24 80 -24 24 80 24 minecraft:glass");
        boolean readingFixture = Boolean.getBoolean("lyrica.worldWordTransitionSelfTest")
                || Boolean.getBoolean("lyrica.worldFullPhraseSelfTest");
        if (stage <= 3 && !readingFixture) commands.add(switch (stage) {
            case 1 -> "fill -8 81 -16 -8 94 16 minecraft:stone";
            case 2 -> "fill -16 81 -8 16 94 -8 minecraft:stone";
            case 3 -> "fill 8 81 -16 8 94 16 minecraft:stone";
            default -> "fill -16 81 8 16 94 8 minecraft:stone";
        });
        if (Boolean.getBoolean("lyrica.worldWordTransitionSelfTest")) commands.addAll(horizonScreens("stone"));
        if (stage == 6 || stage == 7 || stage == 10) commands.add("fill -20 81 5 20 95 5 minecraft:" + (stage == 7 ? "blue_stained_glass" : "glass"));
        if (stage == 8) commands.add("fill -20 81 5 20 95 5 minecraft:oak_leaves[persistent=true]");
        if (stage == 9) commands.add("fill -20 81 5 20 95 9 minecraft:oak_leaves[persistent=true]");
        if (stage == 10) commands.add("fill -20 81 6 20 95 6 minecraft:stone");
        server.execute(() -> {
            for (String command : commands) server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
            terrainReady = true;
        });
        var p = RelayLyricsClient.preferences();
        p.setWorldLyrics(true); p.setLyricsVisible(true); p.setLyricsPinned(false);
        p.setWorldLyricsDirection(WorldLyricsPresentation.Direction.AUTOMATIC);
        p.setWorldLyricsPresentationIntensity(WorldLyricsPresentation.Intensity.DISCREET);
        p.setWorldLyricsPosition(stage >= 6 && stage <= 10 ? WorldLyricsSpace.Position.SKY : WorldLyricsSpace.Position.AROUND);
        p.setWorldLyricsAutoWalls(stage < 6);
        p.setWorldLyricsReposition(true); p.setWorldLyricsCinematic(false);
        p.setWorldLyricsVolumetric(false); p.setWorldLyricsTerrainLight(false);
        p.setWorldLyricsDistanceRange(readingFixture || stage >= 5 && stage <= 8 ? 8 : 3, 48); p.setWorldLyricsSize(.8);
        p.setLyricsAnimations(true); p.setLyricsFont(LyricsFont.MINECRAFT);
        if (Boolean.getBoolean("lyrica.worldOutlineSelfTest")) p.setWorldLyricsOutline(true);
        if (Boolean.getBoolean("lyrica.worldWordTransitionSelfTest"))
            p.setWorldLyricsReveal(WorldLyricsPresentation.Reveal.PHRASE_AND_WORD);
        if (Boolean.getBoolean("lyrica.worldPauseSelfTest"))
            p.setWorldLyricsReveal(WorldLyricsPresentation.Reveal.WORD_BY_WORD);
        System.setProperty("lyrica.worldComposition", stage == 11 ? "COEXIST" : "PROGRESSIVE");
    }

    private static void lyrics() {
        var lines = new ArrayList<LyricLine>();
        lines.add(new LyricLine(0, 12_000, "Aqui a música fica", List.of(new TimedWord(0, 800, "Aqui"),
                new TimedWord(800, 1600, " a"), new TimedWord(1600, 2400, " música"), new TimedWord(2400, 12_000, " fica")), "lead", false, null, null));
        if (stage == 11) lines.add(new LyricLine(1500, 12_000, "Outro lugar", List.of(
                new TimedWord(1500, 2100, "Outro"), new TimedWord(2100, 12_000, " lugar")), "other", false, null, null));
        var overlay = RelayLyricsClient.overlay(); overlay.setLyrics(new LyricsDocument(lines));
        playback(3000, Boolean.getBoolean("lyrica.worldPauseSelfTest"));
    }

    private static void playback(long position, boolean playing) {
        RelayLyricsClient.overlay().setPlayback(new PlaybackSnapshot("Contextual fixture", "Lyrica", "", null, "fixture:context:" + stage,
                "Local fixture", position, 60_000, playing, .65, false, "off", false, .4, 0, 110, .5, new double[0],
                Instant.now(), MusicCapabilities.unavailable()));
    }

    private static void verifyFullPhrases(Minecraft client) {
        boolean first = ticks < 100;
        if (++ticks == 25 || ticks == 105) {
            String text = first ? "Though it's the end of the world,"
                    : "And if it's true, I will surround you and give life to";
            String[] words = text.split(" ");
            var timing = new ArrayList<TimedWord>();
            for (int i = 0; i < words.length; i++)
                timing.add(new TimedWord(i * 900, (i + 1) * 900, (i == 0 ? "" : " ") + words[i]));
            RelayLyricsClient.overlay().setLyrics(new LyricsDocument(List.of(new LyricLine(0,
                    words.length * 900L, text, timing, "lead", false, null, null))));
            playback(950, false);
        }
        if (ticks != 75 && ticks != 155) return;
        int expected = first ? 7 : 12;
        if (WorldLyricsRenderer.probeWordCount() != expected || WorldLyricsRenderer.probePlacedAnchors().size() != 1)
            throw new IllegalStateException("Expected " + expected + " words together, got "
                    + WorldLyricsRenderer.probeWordCount() + ": " + WorldLyricsRenderer.probeSummary());
        capturing = true;
        screenshot(client, image -> {
            try (image) {
                Path output = Path.of(System.getProperty("lyrica.worldProbeOutput", "build/qa-full-phrase"));
                Files.createDirectories(output);
                image.writeToFile(output.resolve(first ? "phrase-though.png" : "phrase-and-if.png"));
                client.execute(() -> {
                    FanDroidMod.LOGGER.info("Full phrase passed: {} words in one panel", expected);
                    capturing = false;
                    if (!first) client.stop();
                });
            } catch (Exception error) { throw new IllegalStateException("Could not save phrase screenshot", error); }
        });
    }

    private static void verifyWordTransition(Minecraft client) {
        if (ticks == 57) {
            if (WorldLyricsRenderer.probeConfined() || WorldLyricsRenderer.probeWordCount() <= 1)
                throw new IllegalStateException("Open area did not preserve the phrase: " + WorldLyricsRenderer.probeSummary());
            RelayLyricsClient.preferences().setWorldLyricsDistanceRange(4.6, 48);
            frozenWordTime(450);
        } else if (ticks == 65) {
            int words = WorldLyricsRenderer.probeWordCount();
            if (words < 2 || words > 3)
                throw new IllegalStateException("Intermediate distance did not limit the phrase: " + WorldLyricsRenderer.probeSummary());
            RelayLyricsClient.preferences().setWorldLyricsDistanceRange(3, 48);
            frozenWordTime(1430);
        } else if (ticks == 71) {
            wordAnchors = WorldLyricsRenderer.probePlacedAnchors();
            if (WorldLyricsRenderer.probeWordCount() != 1)
                throw new IllegalStateException("Expected one word before the open-area transition");
        } else if (ticks == 76) {
            if (!wordAnchors.equals(WorldLyricsRenderer.probePlacedAnchors()))
                throw new IllegalStateException("Turning the camera moved an already placed word");
        } else if (ticks == 77) {
            frozenWordTime(1660);
        } else if (ticks == 84) {
            var placed = WorldLyricsRenderer.probePlacedAnchors();
            if (WorldLyricsRenderer.probeWordCount() != 2 || WorldLyricsRenderer.probeVisibleConflicts() != 0
                    || placed.size() != 2 || !placed.containsAll(wordAnchors))
                throw new IllegalStateException("The new word did not receive a separate anchor: " + WorldLyricsRenderer.probeSummary()
                        + ", time=" + RelayLyricsClient.overlay().worldPlayback().positionMillis()
                        + ", previous=" + wordAnchors + ", placed=" + placed);
            frozenWordTime(2100);
        } else if (ticks == 90) {
            if (WorldLyricsRenderer.probeWordCount() != 2 || WorldLyricsRenderer.probePlacedAnchors().size() != 2)
                throw new IllegalStateException("The previous word disappeared too soon");
            RelayLyricsClient.preferences().setWorldLyricsDistanceRange(8, 48);
            frozenWordTime(3000);
        } else if (ticks == 97) {
            if (WorldLyricsRenderer.probeReadingFit() != WorldLyricsLayout.ReadingFit.MEDIUM
                    || WorldLyricsRenderer.probePlacedAnchors().stream().noneMatch(anchor -> !anchor.ground() && anchor.y() > 81.5))
                throw new IllegalStateException("Open air did not receive an elevated lyric: " + WorldLyricsRenderer.probeAnchors());
            corridor(client, "stone");
        } else if (ticks == 125) {
            if (!WorldLyricsRenderer.probeConfined() || WorldLyricsRenderer.probeReadingFit() != WorldLyricsLayout.ReadingFit.MEDIUM)
                throw new IllegalStateException("A room change replaced an already presented line");
            frozenWordTime(1430);
        } else if (ticks == 132) {
            if (!WorldLyricsRenderer.probeConfined() || WorldLyricsRenderer.probeWordCount() != 1
                    || WorldLyricsRenderer.probePlacedAnchors().stream()
                            .noneMatch(a -> Math.hypot(a.x() - .5, a.z() - .5) >= 1.15))
                throw new IllegalStateException("Corridor did not receive a nearby isolated word: " + WorldLyricsRenderer.probeSummary());
            wordAnchors = WorldLyricsRenderer.probePlacedAnchors();
            frozenWordTime(1660);
        } else if (ticks == 143) {
            if (WorldLyricsRenderer.probeWordCount() < 1 || WorldLyricsRenderer.probeVisibleConflicts() != 0
                    || wordAnchors.equals(WorldLyricsRenderer.probePlacedAnchors()))
                throw new IllegalStateException("Corridor handoff lost the new word or left overlapping words");
            corridor(client, "air");
        } else if (ticks == 183) {
            if (WorldLyricsRenderer.probeConfined() || WorldLyricsRenderer.probeReadingFit() != WorldLyricsLayout.ReadingFit.WORD)
                throw new IllegalStateException("Leaving the corridor replaced an already presented word");
            frozenWordTime(3000);
        } else if (ticks == 192) {
            if (WorldLyricsRenderer.probeConfined() || WorldLyricsRenderer.probeWordCount() <= 1)
                throw new IllegalStateException("Phrase did not return after leaving the corridor: " + WorldLyricsRenderer.probeSummary());
            var p = RelayLyricsClient.preferences();
            p.setWorldLyricsReveal(WorldLyricsPresentation.Reveal.WORD_BY_WORD);
            p.setWorldLyricsAnimationSpeed(.65);
            var overlay = RelayLyricsClient.overlay();
            overlay.setLyrics(new LyricsDocument(List.of(
                    new LyricLine(0, 1200, "Eu como carne", List.of(new TimedWord(0, 400, "Eu"),
                            new TimedWord(400, 800, " como"), new TimedWord(800, 1200, " carne")), "a", false, null, null),
                    new LyricLine(1200, 4500, "Amanhã", List.of(new TimedWord(1200, 4500, "Amanhã")), "a", false, null, null))));
            frozenWordTime(200);
        } else if (ticks == 200) {
            frozenWordTime(540);
        } else if (ticks == 208) {
            frozenWordTime(960);
        } else if (ticks == 216) {
            wordAnchors = WorldLyricsRenderer.probePlacedAnchors();
            if (WorldLyricsRenderer.probeWordCount() != 3 || wordAnchors.size() != 3)
                throw new IllegalStateException("The three recent words were not visible together: " + WorldLyricsRenderer.probeSummary()
                        + ", visible words=" + WorldLyricsRenderer.probeWordCount() + ", anchors=" + wordAnchors);
            frozenWordTime(1420);
        } else if (ticks == 225) {
            var placed = WorldLyricsRenderer.probePlacedAnchors();
            if (WorldLyricsRenderer.probeWordCount() < 3 || WorldLyricsRenderer.probeVisibleConflicts() != 0
                    || !placed.containsAll(wordAnchors) || placed.size() != 4)
                throw new IllegalStateException("The new line cut or overlapped the outgoing words: " + WorldLyricsRenderer.probeSummary());
            frozenWordTime(2060);
        } else if (ticks == 231) {
            frozenWordTime(2700);
        } else if (ticks == 237) {
            frozenWordTime(3330);
        } else if (ticks == 243) {
            if (WorldLyricsRenderer.probeWordCount() != 1 || WorldLyricsRenderer.probePlacedAnchors().size() != 1)
                throw new IllegalStateException("The outgoing words did not finish fading: " + WorldLyricsRenderer.probeSummary());
            var p = RelayLyricsClient.preferences();
            p.setWorldLyricsCinematic(true);
            p.setWorldLyricsReveal(WorldLyricsPresentation.Reveal.READING_LINE);
            String[] words = {"Cada", "passo", "marca", "forte", "dentro", "deste", "ritmo", "sem", "pausa"};
            var timing = new ArrayList<TimedWord>();
            for (int i = 0; i < words.length; i++)
                timing.add(new TimedWord(i * 150L, (i + 1) * 150L, i == 0 ? words[i] : " " + words[i]));
            RelayLyricsClient.overlay().setLyrics(new LyricsDocument(List.of(
                    new LyricLine(0, 1350, String.join(" ", words), timing, "a", false, null, null))));
            frozenWordTime(160);
        } else if (ticks == 252) {
            wordAnchors = WorldLyricsRenderer.probePlacedAnchors();
            if (WorldLyricsRenderer.probeWordCount() < 2 || wordAnchors.size() != 1)
                throw new IllegalStateException("The fast line did not group its first words: " + WorldLyricsRenderer.probeSummary());
            frozenWordTime(590);
        } else if (ticks == 261) {
            var placed = WorldLyricsRenderer.probePlacedAnchors();
            if (WorldLyricsRenderer.probeWordCount() < 4 || placed.size() < 2 || !placed.containsAll(wordAnchors)
                    || new HashSet<>(placed).size() < 2)
                throw new IllegalStateException("The old chunk did not coexist with the new chunk: " + WorldLyricsRenderer.probeSummary()
                        + ", visible words=" + WorldLyricsRenderer.probeWordCount() + ", previous=" + wordAnchors + ", placed=" + placed);
            corridor(client, "stone");
        } else if (ticks == 295) {
            if (!WorldLyricsRenderer.probeConfined()) throw new IllegalStateException("Fast fixture corridor was not detected");
            RelayLyricsClient.preferences().setWorldLyricsReveal(WorldLyricsPresentation.Reveal.WORD_BY_WORD);
            String[] words = {"Step", "to", "the", "beat", "keep", "your", "feet", "on", "time"};
            var timing = new ArrayList<TimedWord>();
            for (int i = 0; i < words.length; i++)
                timing.add(new TimedWord(i * 115L, (i + 1) * 115L, i == 0 ? words[i] : " " + words[i]));
            RelayLyricsClient.overlay().setLyrics(new LyricsDocument(List.of(
                    new LyricLine(0, 1035, String.join(" ", words), timing, "b", false, null, null))));
            frozenWordTime(60);
        } else if (ticks >= 301 && ticks <= 349 && (ticks - 301) % 6 == 0) {
            if (WorldLyricsRenderer.probeReadingFit() != WorldLyricsLayout.ReadingFit.WORD
                    || !WorldLyricsRenderer.probeNewestGroupDrawn())
                throw new IllegalStateException("Fast word missing in the corridor at "
                        + RelayLyricsClient.overlay().worldPlayback().positionMillis() + ": " + WorldLyricsRenderer.probeSummary());
            if (ticks < 349) frozenWordTime(60 + ((ticks - 301) / 6 + 1) * 115L);
            else {
                corridor(client, "air");
                var server = client.getSingleplayerServer();
                server.execute(() -> {
                    for (String command : horizonScreens("air"))
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
                });
                FanDroidMod.LOGGER.info("Progressive chunks and fast English words passed; Iris shader active={}",
                        CompatibilityManager.worldShaderActive());
            }
        } else if (ticks == 379) {
            var p = RelayLyricsClient.preferences();
            p.setWorldLyricsReveal(WorldLyricsPresentation.Reveal.PHRASE_AND_WORD);
            p.setWorldLyricsPosition(WorldLyricsSpace.Position.ADAPTIVE);
            p.setWorldLyricsDistanceRange(8, 96);
            String[] words = {"Sol", "no", "ar,", "luz", "no", "mar,", "vem", "me", "ver"};
            var timing = new ArrayList<TimedWord>();
            for (int i = 0; i < words.length; i++)
                timing.add(new TimedWord(i * 330L, (i + 1) * 330L, i == 0 ? words[i] : " " + words[i]));
            RelayLyricsClient.overlay().setLyrics(new LyricsDocument(List.of(
                    new LyricLine(0, 2970, String.join(" ", words), timing, "sky", false, null, null))));
            frozenWordTime(500);
        } else if (ticks == 386) {
            wordAnchors = WorldLyricsRenderer.probePlacedAnchors();
            if (!WorldLyricsRenderer.probeOpenSky() || WorldLyricsRenderer.probeReadingFit() != WorldLyricsLayout.ReadingFit.SHORT
                    || WorldLyricsRenderer.probeWordCount() != 3 || wordAnchors.size() != 1
                    || Math.abs(wordAnchors.getFirst().distance() - 96) > .1)
                throw new IllegalStateException("Open sky did not open with three words at the selected range: "
                        + WorldLyricsRenderer.probeSummary() + ", anchors=" + wordAnchors);
            frozenWordTime(1100);
        } else if (ticks == 395) {
            var placed = WorldLyricsRenderer.probePlacedAnchors();
            if (placed.size() != 2 || !placed.containsAll(wordAnchors)
                    || (placed.getLast().x() - .5) * (wordAnchors.getFirst().x() - .5) >= 0
                    || Math.abs(placed.getLast().distance() - 96) > .1)
                throw new IllegalStateException("The open sky groups did not alternate sides: " + placed);
            frozenWordTime(1750);
        } else if (ticks == 403) {
            frozenWordTime(2220);
        } else if (ticks == 412) {
            var placed = WorldLyricsRenderer.probePlacedAnchors();
            if (!WorldLyricsRenderer.probeNewestGroupDrawn() || placed.isEmpty()
                    || Math.abs(placed.getLast().x() - .5) > .5 || Math.abs(placed.getLast().distance() - 96) > .1)
                throw new IllegalStateException("The final sky group did not appear near the center: " + placed);
        } else if (ticks == 424) {
            RelayLyricsClient.preferences().setWorldLyricsDistanceRange(5, 14);
            frozenWordTime(500);
        } else if (ticks == 435) {
            var placed = WorldLyricsRenderer.probePlacedAnchors();
            if (!WorldLyricsRenderer.probeOpenSky() || WorldLyricsRenderer.probeWordCount() != 3 || placed.size() != 1
                    || Math.abs(placed.getFirst().distance() - 14) > .1)
                throw new IllegalStateException("Flight ignored the shorter configured range: " + placed);
        } else if (ticks == 465) {
            RelayLyricsClient.preferences().setWorldLyricsDistanceRange(8, 40);
            frozenWordTime(500);
        } else if (ticks == 478) {
            var placed = WorldLyricsRenderer.probePlacedAnchors();
            if (!WorldLyricsRenderer.probeOpenSky() || WorldLyricsRenderer.probeWordCount() != 3 || placed.size() != 1
                    || Math.abs(placed.getFirst().distance() - 40) > .1)
                throw new IllegalStateException("A stationary player did not get sky placement on a wide horizon: " + placed);
            FanDroidMod.LOGGER.info("Sky scene passed: stationary upward view, wide horizon at rest, and shorter flight limit");
            FanDroidMod.LOGGER.info("Word transition and tight spaces fixture passed");
            client.stop();
        }
    }

    /** Set a precise paused playback time without sending an explicit seek to the renderer. */
    private static void frozenWordTime(long position) {
        playback(position, true);
        playback(position, false);
    }

    private static List<String> horizonScreens(String block) {
        return List.of("fill -16 81 20 -16 86 40 minecraft:" + block,
                "fill 16 81 20 16 86 40 minecraft:" + block);
    }

    private static void corridor(Minecraft client, String block) {
        var server = client.getSingleplayerServer();
        server.execute(() -> {
            for (int x : new int[]{-1, 1}) {
                String fill = "fill " + x + " 81 -4 " + x + " 84 4 minecraft:" + block;
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), fill);
            }
        });
    }

    private static void verifyPauseAndSettings(Minecraft client) {
        if (ticks == 63) {
            if (!WorldLyricsRenderer.verifyProbe(1, false) || WorldLyricsRenderer.probeWordCount() != 1)
                throw new IllegalStateException("Word-by-word playback did not draw exactly one word");
            pauseSongPosition = RelayLyricsClient.overlay().worldPlayback().positionMillis();
            playback(pauseSongPosition, false);
        } else if (ticks == 64 && !WorldLyricsRenderer.verifyProbe(1, false)) {
            throw new IllegalStateException("Pause did not retain the phrase during its exit animation");
        } else if (ticks == 83) {
            if (WorldLyricsRenderer.probeWordCount() != 0)
                throw new IllegalStateException("World lyrics remained visible after pausing");
            playback(pauseSongPosition, true);
        } else if (ticks == 103) {
            if (!WorldLyricsRenderer.verifyProbe(1, false) || WorldLyricsRenderer.probeWordCount() != 1)
                throw new IllegalStateException("Exactly one world lyric word should return after resuming");
            var screen = new LyricsConfigScreen(null, new ClientPreferences());
            client.gui.setScreen(screen);
            screen.verifyScrollbarInteraction();
            FanDroidMod.LOGGER.info("World pause, resume, word-by-word and scrollbar fixture passed");
            client.stop();
        }
    }

    private static void verifyOutline(Minecraft client) {
        boolean black = ticks == 150;
        if (!black && ticks < 155) return;
        if (!WorldLyricsRenderer.verifyProbe(1, false))
            throw new IllegalStateException("The outline fixture did not draw world lyrics");
        capturing = true;
        screenshot(client, image -> {
            try (image) {
                var path = Path.of(System.getProperty("lyrica.worldProbeOutput", "build/world-context-qa"));
                Files.createDirectories(path);
                image.writeToFile(path.resolve(black ? "outline-black.png" : "outline-custom.png"));
                client.execute(() -> {
                    if (black) {
                        RelayLyricsClient.preferences().setWorldLyricsOutlineColor(0xFF39CF);
                        capturing = false;
                    } else {
                        FanDroidMod.LOGGER.info("World lyrics outline capture complete");
                        client.stop();
                    }
                });
            } catch (Exception error) {
                throw new IllegalStateException("The outline screenshot could not be saved", error);
            }
        });
    }

    private static void screenshot(Minecraft client, Consumer<NativeImage> writer) {
        net.minecraft.client.Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), writer);
    }
}
