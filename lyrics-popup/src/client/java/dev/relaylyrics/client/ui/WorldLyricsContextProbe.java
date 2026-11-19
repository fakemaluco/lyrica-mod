package dev.relaylyrics.client.ui;

import dev.relaylyrics.FanDroidMod;
import dev.relaylyrics.client.*;
import dev.relaylyrics.client.music.*;
import dev.relaylyrics.protocol.lyrics.*;
import dev.relaylyrics.protocol.music.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Vec3d;
import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.texture.NativeImage;

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
    private static long pauseSongPosition;
    private WorldLyricsContextProbe() { }

    public static void tick(MinecraftClient client) {
        if (!Boolean.getBoolean("lyrica.worldExperienceSelfTest")) return;
        if (stage < 0 || stage >= SCENES.length) throw new IllegalArgumentException("Unknown contextual QA stage");
        if (++total > 12_000) throw new IllegalStateException("Contextual QA timed out: " + SCENES[stage]);
        if (client.player == null || client.world == null || client.getOverlay() != null || capturing) return;
        if (!initialized) { initialized = true; configure(client); }
        if (!terrainReady) return;
        client.options.pauseOnLostFocus = false;
        if (client.currentScreen != null) client.setScreen(null);
        client.inGameHud.getChatHud().clear(false);
        client.player.setPosition(.5, stage == 4 ? 87 : 81, .5);
        client.player.setYaw(stage <= 3 ? stage * 90 : stage == 11 && ticks >= 70 ? 30 : stage == 13 && ticks >= 70 ? 120 : 0);
        client.player.setPitch(stage == 4 ? 50 : 0);
        client.player.resetPosition();
        client.player.setVelocity(Vec3d.ZERO);
        if (stage == 12) {
            client.player.getAbilities().flying = false;
            client.player.setOnGround(true);
        }
        client.player.setSprinting(stage == 12 && ticks < 70);
        WorldLyricsRenderer.probeFlight(stage == 12 && ticks < 70 ? new Vec3d(0, 0, .3) : null, false);
        // Server commands complete before the updated chunks reach the client. Settle them and
        // the interpolated camera before the first letter is anchored to this scene.
        if (++ticks == 25) lyrics();
        if (Boolean.getBoolean("lyrica.worldPauseSelfTest")) {
            verifyPauseAndSettings(client);
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
                image.writeTo(output.resolve("context-" + version + "-" + SCENES[stage] + ".png"));
                if (++stage == SCENES.length) { FanDroidMod.LOGGER.info("Contextual placement GL probe passed: {} scenes", SCENES.length - FIRST_STAGE); client.stop(); return; }
                ticks = 0; configure(client); capturing = false;
            } catch (Exception error) { throw new IllegalStateException("Contextual screenshot failed", error); }
        });
    }

    private static void configure(MinecraftClient client) {
        terrainReady = false;
        var server = client.getServer();
        if (server == null || !server.getSaveProperties().getLevelName().equals("LyricaWorldQA"))
            throw new IllegalStateException("Contextual QA requires only the disposable LyricaWorldQA world");
        var commands = new ArrayList<String>(List.of("fill -24 81 -24 -1 99 24 minecraft:air",
                "fill 0 81 -24 24 99 24 minecraft:air", "fill -24 80 -24 24 80 24 minecraft:stone",
                "gamemode spectator @a", "time set day", "weather clear"));
        // Isolate wall support: the transparent floor is physical, but cannot win as an opaque surface.
        if (stage <= 3) commands.add("fill -24 80 -24 24 80 24 minecraft:glass");
        if (stage <= 3) commands.add(switch (stage) {
            case 1 -> "fill -8 81 -16 -8 94 16 minecraft:stone";
            case 2 -> "fill -16 81 -8 16 94 -8 minecraft:stone";
            case 3 -> "fill 8 81 -16 8 94 16 minecraft:stone";
            default -> "fill -16 81 8 16 94 8 minecraft:stone";
        });
        if (stage == 6 || stage == 7 || stage == 10) commands.add("fill -20 81 5 20 95 5 minecraft:" + (stage == 7 ? "blue_stained_glass" : "glass"));
        if (stage == 8) commands.add("fill -20 81 5 20 95 5 minecraft:oak_leaves[persistent=true]");
        if (stage == 9) commands.add("fill -20 81 5 20 95 9 minecraft:oak_leaves[persistent=true]");
        if (stage == 10) commands.add("fill -20 81 6 20 95 6 minecraft:stone");
        server.execute(() -> {
            for (String command : commands) WorldLyricsRenderHook.probeCommand(server, command);
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
        p.setWorldLyricsDistanceRange(stage >= 5 && stage <= 8 ? 8 : 3, 48); p.setWorldLyricsSize(.8);
        p.setLyricsAnimations(true); p.setLyricsFont(LyricsFont.MINECRAFT);
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

    private static void verifyPauseAndSettings(MinecraftClient client) {
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
            client.setScreen(screen);
            screen.verifyScrollbarInteraction();
            FanDroidMod.LOGGER.info("World pause, resume, word-by-word and scrollbar fixture passed");
            client.stop();
        }
    }

    private static void screenshot(MinecraftClient client, Consumer<NativeImage> writer) {
        try {
            Class<?> type = Class.forName("net.minecraft.client.util.ScreenshotRecorder");
            try { type.getMethod("takeScreenshot", net.minecraft.client.gl.Framebuffer.class, int.class, Consumer.class).invoke(null, client.getFramebuffer(), 1, writer); }
            catch (NoSuchMethodException ignored) { type.getMethod("takeScreenshot", net.minecraft.client.gl.Framebuffer.class, Consumer.class).invoke(null, client.getFramebuffer(), writer); }
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Contextual screenshot failed", error); }
    }
}
