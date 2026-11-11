package dev.relaylyrics.client.ui;

import dev.relaylyrics.FanDroidMod;
import dev.relaylyrics.client.*;
import dev.relaylyrics.client.music.*;
import dev.relaylyrics.protocol.lyrics.*;
import dev.relaylyrics.protocol.music.*;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.fabricmc.loader.api.FabricLoader;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

/** Native fixtures on a disposable world, with a real optional lighting runtime. */
public final class WorldLyricsProbe {
    private static final boolean OBSTACLES = Boolean.getBoolean("lyrica.worldObstacleSelfTest");
    private static final boolean PRESENTATION = Boolean.getBoolean("lyrica.worldPresentationSelfTest");
    private static String fixedAnchors;
    private static final boolean PLACEMENT = Boolean.getBoolean("lyrica.popupPlacementSelfTest");
    private static final boolean CINEMATIC = Boolean.getBoolean("lyrica.worldCinematicSelfTest");
    private static int cinematicBefore, cinematicReframesBefore, cinematicWorldBefore;
    private static final String[] STAGES=OBSTACLES ? new String[]{"dense-forest", "low-canopy-tunnel", "obstacle-added", "camera-turn-move", "paused-camera-occlusion", "near-stone-wall"} : PRESENTATION ? new String[]{"falling-entry", "falling-formed", "progressive", "coexist", "staircase", "spotlight", "planar", "volume", "paused-camera", "spicy-glyphs", "scattered-words", "mixed-chunks", "motion-disabled"} : PLACEMENT ? new String[]{"align-xaero", "align-center", "align-free", "size-empty", "size-lyrics"} : CINEMATIC ? new String[]{"f1-world","ji-afk-caption","ji-afk-elytra","ji-afk-paused","ji-return"} : new String[]{"around","sky","glyph-lift-a","glyph-lift-b","popup-font-changed","duet",
            "night-unlit","night-lit","night-removed","elytra-short","elytra-long","elytra-turn"};
    private static int total,ticks,stage = Integer.getInteger("lyrica.worldPresentationStage", 0);
    private static boolean initialized,capturePending;
    private static WorldLyricsLights.Point lastLight;
    private static double darkFloor,shortFlight;
    private static java.awt.image.BufferedImage darkFrame;
    private WorldLyricsProbe() { }
    public static void tick(Minecraft client) {
        if (Boolean.getBoolean("lyrica.islandJadeSelfTest") || Boolean.getBoolean("lyrica.musicPresenceSelfTest") || !Boolean.getBoolean("lyrica.worldExperienceSelfTest")) return;
        if (++total>4000) throw new IllegalStateException("Spatial world probe timed out at "+STAGES[stage]);
        if (client.player==null && total%100==0) FanDroidMod.LOGGER.info("World fixture waiting: {}",client.gui.screen());
        if (client.player==null || client.level==null || client.gui.overlay()!=null || capturePending) return;
        // Keep the disposable fixture deterministic even if a movement key is held when the window opens.
        client.player.setPos(.5,81,.5); client.player.setYRot(CINEMATIC && (stage==2 && ticks>=40 || stage==3) ? 105 : !PRESENTATION && stage==11 && ticks>=25 ? 41 : 0); client.player.setXRot(0);
        client.player.setDeltaMovement(Vec3.ZERO); client.player.setSprinting(false);
        if (OBSTACLES && ticks >= 40) {
            if (stage == 3) { client.player.setPos(3.5, 81, .5); client.player.setYRot(135); }
            else if (stage == 4) client.player.setYRot(90);
        }
        if (!initialized) {
            initialized=true;
            client.options.pauseOnLostFocus=false;
            var server=client.getSingleplayerServer();
            if (server==null) throw new IllegalStateException("Use the disposable integrated QA world");
            server.execute(() -> {
                for (String command : List.of("fill -24 80 -24 24 80 24 minecraft:stone",
                        "fill -24 81 -24 -1 99 24 minecraft:air","fill 0 81 -24 24 99 24 minecraft:air","gamemode spectator @a","tp @a 0 81 0 0 0","time set day","weather clear"))
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),command);
            });
            var p=RelayLyricsClient.preferences();
            p.setWorldLyrics(true);p.setLyricsVisible(true);p.setLyricsPinned(false);
            p.setWorldLyricsQuality(WorldLyricsDirector.Quality.HIGH);p.setWorldLyricsSize(1.2);
            p.setWorldLyricsLuminance(15);client.gui.setScreen(null);apply();
        }
        client.gui.hud.getChat().clearMessages(false);
        if (OBSTACLES) { tickObstacles(client); return; }
        if (PRESENTATION) { tickPresentation(client); return; }
        if (PLACEMENT) { tickPlacement(client); return; }
        if (CINEMATIC) { tickCinematic(client); return; }
        if (stage==0 && ticks==39) apply();
        if (stage==11 && ticks==25) WorldLyricsRenderer.probeFlight(new Vec3(-.95,-.06,.35),true);
        if (++ticks<100) return;
        if (!WorldLyricsRenderer.verifyProbe(stage==5 ? 3 : 1,false) || !WorldLyricsRenderer.probeHasVolume()) return;
        if (stage==5 && !WorldLyricsRenderer.probeBackingBelowLead())
            throw new IllegalStateException("Backing vocal did not fit beneath its lead on open ground: " + WorldLyricsRenderer.probeAnchors());
        if (!WorldLyricsRenderer.probeFontMatchesPopup()) throw new IllegalStateException("World font differs from popup font");
        if (Boolean.getBoolean("lyrica.worldTerrainLightSelfTest") && !WorldLyricsLights.available()) throw new IllegalStateException("Lighting API did not initialize");
        if (stage==7 && Boolean.getBoolean("lyrica.worldTerrainLightSelfTest")) {
            var points=WorldLyricsLights.latestPoints();if (points.isEmpty()) throw new IllegalStateException("No word light emitter");
            lastLight=points.getFirst();double level=terrainLightLevel(lastLight);
            if (level<1) throw new IllegalStateException("Terrain engine reported no ground light: "+level+" at "+lastLight);
            FanDroidMod.LOGGER.info("Actual dynamic ground light: {}",level);
        }
        if (stage==8 && lastLight!=null && terrainLightLevel(lastLight)>.01) throw new IllegalStateException("Old word light was not removed");
        if (stage==9) shortFlight=WorldLyricsRenderer.probeAnchorDistance();
        if (stage==10 && (shortFlight<50 || WorldLyricsRenderer.probeAnchorDistance()<shortFlight+5))
            throw new IllegalStateException("Elytra runway did not grow: "+shortFlight+" -> "+WorldLyricsRenderer.probeAnchorDistance());
        if (stage==11 && WorldLyricsRenderer.probeCourseChanges()!=0) throw new IllegalStateException("Elytra turn moved a fixed phrase");
        capturePending=true;capture(client);
    }
    private static boolean placementConfigured;
    private static int placementGuiWarmup;
    private static void tickPlacement(Minecraft client) {
        var overlay = RelayLyricsClient.overlay(); var p = RelayLyricsClient.preferences();
        if (placementGuiWarmup++ == 0) {
            client.options.guiScale().set(Math.clamp(Integer.getInteger("lyrica.popupPlacementGuiScale", 1), 1, 4));
            client.resizeGui();
        }
        if (placementGuiWarmup < 15) return;

        int width = client.getWindow().getGuiScaledWidth(), height = client.getWindow().getGuiScaledHeight();
        var targets = PopupHudTargets.targets(width, height, true, System.nanoTime());
        if (targets.isEmpty()) { ticks++; return; }
        p.setWorldLyrics(false); p.setLyricsPinned(true); p.setPopupDocked(false);
        p.setInventoryPopup(true); p.setLyricsAllowGuiOverlap(true);
        if (client.gui.screen() == null) {
            client.gui.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(client.player));
            ticks = 0; return;
        }
        if (++ticks < 15) return;
        if (!placementConfigured) {
            placementConfigured = true;
            overlay.mouseReleased();
            var line = phrase("As palavras acompanham a música", "v1", false);
            overlay.setLyrics(new LyricsDocument(stage == 3 ? List.of() : List.of(line)));
            overlay.setPlayback(new PlaybackSnapshot("Placement fixture", "Lyrica", "", null, "fixture:placement", "Local fixture",
                    1900, 60000, false, .65, false, "off", false, .4, 0, 110, .5, new double[0], Instant.now(), MusicCapabilities.unavailable()));
            p.setPopupWidth(PopupSizeProfile.LYRICS_ONLY, 260); p.setPopupHeight(PopupSizeProfile.LYRICS_ONLY, 180);
            p.setPopupWidth(PopupSizeProfile.EMPTY, 180); p.setPopupHeight(PopupSizeProfile.EMPTY, 110);
            p.setPopupPosition(24, 24);
            if (stage <= 2) {
                var start = overlay.popupBoundsSnapshot(width, height);
                double dx = start.width() / 2.0, dy = start.height() / 2.0;
                if (!overlay.mousePressed(start.x() + dx, start.y() + dy, com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT))
                    throw new IllegalStateException("Popup did not accept the QA drag");
                var map = targets.getFirst().bounds();
                int targetX = stage == 0 ? map.right() + 6 : (int) Math.round((width - start.width()) / 2.0);
                int targetY = stage == 0 ? map.y() : (int) Math.round((height - start.height()) / 2.0);
                if (targetX + start.width() > width) targetX = map.x() - start.width() - 6;
                overlay.mouseDragged(targetX + dx + (stage == 2 ? 14 : 2), targetY + dy + (stage == 2 ? 11 : 2), stage != 2);
                var actual = overlay.popupBoundsSnapshot(width, height);
                if (stage == 0 && (actual.x() != targetX || actual.y() != targetY || !overlay.hasPopupAlignmentGuide()))
                    throw new IllegalStateException("Xaero alignment missed its exact rectangle: map=" + map + ", popup=" + actual);
                if (stage == 1 && (Math.abs(actual.x() + actual.width() / 2.0 - width / 2.0) > .5
                        || Math.abs(actual.y() + actual.height() / 2.0 - height / 2.0) > .5))
                    throw new IllegalStateException("Screen center alignment missed: " + actual);
                if (stage == 2 && (overlay.hasPopupAlignmentGuide() || actual.x() != targetX + 14 || actual.y() != targetY + 11))
                    throw new IllegalStateException("Releasing Shift did not restore free dragging");
            }
        }
        if (ticks < 70) return;
        if (stage == 3 && (overlay.popupStoredWidth() != 180 || overlay.popupStoredHeight() != 110))
            throw new IllegalStateException("Empty-track size profile did not restore");
        if (stage == 4 && (overlay.popupStoredWidth() != 260 || overlay.popupStoredHeight() != 180))
            throw new IllegalStateException("Lyrics size profile did not restore");
        FanDroidMod.LOGGER.info("Popup placement fixture {} passed: bounds={}, profile={}, minimap={}",
                STAGES[stage], overlay.popupBoundsSnapshot(width, height), overlay.popupSizeProfile(), targets.getFirst().bounds());
        placementConfigured = false; capturePending = true; capture(client);
    }

    private static void tickCinematic(Minecraft client) {
        var overlay = RelayLyricsClient.overlay();
        if (ticks == 0) {
            cinematicBefore = overlay.cinematicCaptionProbeFrames();
            cinematicReframesBefore = WorldLyricsRenderer.probeCinematicReframes();
            cinematicWorldBefore = WorldLyricsRenderer.probeCinematicFrames();
            // Keep 3D enabled through Ji shots, paused playback, and return to the player.
            RelayLyricsClient.preferences().setWorldLyrics(true);
            WorldLyricsRenderer.probeFlight(stage == 2 ? new Vec3(.5, -.08, 1.2) : null, stage == 2);
            if (client.gui.hud.isHidden() != (stage == 0)) client.gui.hud.toggle();
            overlay.setPlayback(new PlaybackSnapshot("AFK fixture", "Lyrica", "", null, "spotify:track:cinematic", "Local fixture",
                    1900, 60000, stage != 3, .65, false, "off", false, .4, 0, 110, .5, new double[0], Instant.now(), MusicCapabilities.unavailable()));
            try {
                var manager = Class.forName("com.ji.afkcinematic.cinematic.CinematicManager");
                var state = Class.forName("com.ji.afkcinematic.cinematic.CinematicState");
                var mode = java.util.Arrays.stream(state.getEnumConstants()).filter(x -> x.toString().equals(stage >= 1 && stage <= 3 ? "CINEMATIC_ACTIVE" : "IDLE")).findFirst().orElseThrow();
                manager.getMethod("setState", state).invoke(null, mode);
                Class.forName("com.ji.afkcinematic.render.HUDController").getMethod("setHidden", boolean.class).invoke(null, stage >= 1 && stage <= 3);
                Class.forName("com.ji.afkcinematic.render.LetterboxRenderer").getMethod(stage == 1 ? "fadeIn" : stage == 4 ? "reset" : "tick").invoke(null);
            } catch (ReflectiveOperationException error) { throw new IllegalStateException("Ji runtime API missing", error); }
        }
        if (++ticks < 100) return;
        if (stage == 0 && (!WorldLyricsRenderer.verifyProbe(1, false) || !WorldLyricsRenderer.probeHasVolume()))
            throw new IllegalStateException("F1 suppressed the world renderer");
        int frames = overlay.cinematicCaptionProbeFrames() - cinematicBefore;
        if (stage == 2 && WorldLyricsRenderer.probeCinematicReframes() <= cinematicReframesBefore)
            throw new IllegalStateException("Ji camera turn left 3D lyrics behind");
        if (stage >= 1 && stage <= 3 && WorldLyricsRenderer.probeCinematicFrames() <= cinematicWorldBefore)
            throw new IllegalStateException("Ji suppressed world lyrics during its camera shots: " + WorldLyricsRenderer.probeSummary() + ", Ji=" + CompatibilityManager.jiAfkCinematicActive() + ", playback=" + overlay.worldPlayback() + ", captions=" + frames);
        if (stage == 4 && !WorldLyricsRenderer.verifyProbe(1, false))
            throw new IllegalStateException("World lyrics did not resume after Ji returned control");
        if ((stage == 1 || stage == 2) && frames < 1) throw new IllegalStateException("Ji cancelled the lyric caption along with its HUD");
        if (stage == 1 && overlay.cinematicWordFillFrames() < 1) throw new IllegalStateException("AFK did not render progressive word fill");
        if (stage >= 3 && frames != 0) throw new IllegalStateException("Caption remained visible while paused or outside AFK");
        FanDroidMod.LOGGER.info("Cinematic fixture {}: caption frames={}, Ji active={}", STAGES[stage], frames, CompatibilityManager.jiAfkCinematicActive());
        capturePending = true; capture(client);
    }
    /** Offline frames covering composition, independent blocks, Spicy glyphs and geometry. */
    private static void applyPresentation() {
        var p = RelayLyricsClient.preferences();
        p.setWorldLyricsDirection(WorldLyricsPresentation.Direction.AUTOMATIC);
        p.setWorldLyricsPresentationIntensity(WorldLyricsPresentation.Intensity.DISCREET);
        p.setWorldLyricsVolumetric(stage == 7); p.setWorldLyricsCinematic(false);
        p.setLyricsAnimations(stage != 12); p.setLyricsFont(LyricsFont.MINECRAFT);
        p.setWorldLyricsSize(1.6); p.setWorldLyricsTerrainLight(false);
        String composition = switch (stage) {
            case 0, 1, 9, 10, 12 -> "FALLING_WORDS"; case 3 -> "COEXIST"; case 4 -> "STAIRCASE";
            case 5, 11 -> "SPOTLIGHT"; default -> "PROGRESSIVE";
        };
        System.setProperty("lyrica.worldComposition", composition);
        var lines = new ArrayList<LyricLine>();
        for (int line = 0; line < 2; line++) {
            long start = line * 4200L;
            String[] words = line == 0 ? new String[]{"Palavras", "formam", "parte", "da", "música"}
                    : new String[]{"Outra", "frase", "fica", "no", "mundo"};
            var timed = new ArrayList<TimedWord>();
            for (int word = 0; word < words.length; word++)
                timed.add(new TimedWord(start + word * 800, start + (word + 1) * 800, (word == 0 ? "" : " ") + words[word]));
            lines.add(new LyricLine(start, start + 4000, String.join(" ", words), timed, "v1", false, null, null));
        }
        if (stage >= 9) {
            lines.clear();
            String[] words = stage == 10 ? new String[]{"I'm", "a", "loser"}
                    : stage == 11 ? new String[]{"Do", "you", "wanna", "tear"} : new String[]{"Glifos", "cantam"};
            long duration = stage == 11 ? 600 : stage == 10 ? 800 : 4_000;
            var timed = new ArrayList<TimedWord>();
            for (int index = 0; index < words.length; index++)
                timed.add(new TimedWord(index * duration, (index + 1) * duration, (index == 0 ? "" : " ") + words[index]));
            lines.add(new LyricLine(0, duration * words.length, String.join(" ", words), timed, "v1", false, null, null));
        }
        var overlay = RelayLyricsClient.overlay();
        overlay.setLyrics(new LyricsDocument(lines));
        long position = stage == 0 ? 100 : stage == 3 ? 4800 : stage == 11 ? 2100 : stage >= 9 ? 1900 : 2850;
        overlay.setPlayback(new PlaybackSnapshot("Composition fixture", "Lyrica", "", null,
                "spotify:track:composition" + stage, "Local fixture", position, 60000, false,
                .65, false, "off", false, .4, 0, 110, .5, new double[0], Instant.now(), MusicCapabilities.unavailable()));
        fixedAnchors = null;
    }

    private static void tickPresentation(Minecraft client) {
        if (++ticks < 60) return;
        if (stage == 8) {
            if (fixedAnchors == null) fixedAnchors = WorldLyricsRenderer.probeAnchors();
            client.player.setYRot(5);
            if (ticks < 90) return;
            if (!fixedAnchors.equals(WorldLyricsRenderer.probeAnchors())) throw new IllegalStateException("Paused camera moved fixed anchors");
        }
        if (!WorldLyricsRenderer.verifyProbe(stage == 3 ? 2 : 1, false))
            throw new IllegalStateException("Missing presentation scene: " + STAGES[stage] + " " + WorldLyricsRenderer.probeSummary());
        if (WorldLyricsRenderer.probeWordCount() > 48) throw new IllegalStateException("Word budget exceeded");
        if (stage == 0 && WorldLyricsRenderer.probeWordCount() != 1) throw new IllegalStateException("Future words revealed");
        if (stage == 1 && WorldLyricsRenderer.probeWordCount() != 4) throw new IllegalStateException("A sung word was lost during scattering: " + WorldLyricsRenderer.probeSummary());
        if ((stage == 6 || stage == 8) && !WorldLyricsRenderer.probeFlat()) throw new IllegalStateException("Planar text acquired thickness");
        if (stage == 7 && !WorldLyricsRenderer.probeHasVolume()) throw new IllegalStateException("Volume toggle did not build geometry");
        if (stage == 9 && WorldLyricsRenderer.probeSpicyMotion() < 1) throw new IllegalStateException("Discreet suppressed Spicy glyph springs");
        if (stage == 12 && WorldLyricsRenderer.probeSpicyMotion() != 0) throw new IllegalStateException("Animation disable did not stop Spicy");
        if (stage >= 10 && stage <= 11 && (WorldLyricsRenderer.probeGroups() < 3 || !WorldLyricsRenderer.probeRotation()))
            throw new IllegalStateException("Word/chunk blocks were not scattered and rotated: " + WorldLyricsRenderer.probeSummary());
        if (WorldLyricsRenderer.probeGroups() > WorldLyricsPresentation.MAX_GROUPS) throw new IllegalStateException("Group budget exceeded");
        capturePending = true; capture(client);
    }

    private static volatile boolean obstacleWorldReady;
    private static boolean obstacleLyricsPending;
    private static volatile boolean obstacleMutationQueued;
    private static int obstacleRecoveryBefore;
    private static String obstacleBeforeAnchors, obstacleStableAnchors;

    /** Dense, reproducible collision fixtures in the disposable QA save; playback stays paused. */
    private static void applyObstacles() {
        var p = RelayLyricsClient.preferences();
        p.setWorldLyricsReposition(true);
        p.setWorldLyricsDirection(WorldLyricsPresentation.Direction.AUTOMATIC);
        p.setWorldLyricsPresentationIntensity(WorldLyricsPresentation.Intensity.DISCREET);
        p.setWorldLyricsVolumetric(false); p.setWorldLyricsCinematic(false);
        p.setLyricsAnimations(true); p.setLyricsRomanization(false); p.setLyricsFont(LyricsFont.MINECRAFT);
        p.setWorldLyricsSize(1.2); p.setWorldLyricsTerrainLight(false);
        System.setProperty("lyrica.worldComposition", "FALLING_WORDS");
        obstacleWorldReady = false; obstacleLyricsPending = true; obstacleMutationQueued = false;
        obstacleBeforeAnchors = obstacleStableAnchors = null;
        var commands = new ArrayList<String>(List.of(
                "fill -24 81 -24 -1 99 24 minecraft:air",
                "fill 0 81 -24 24 99 24 minecraft:air",
                "fill -24 80 -24 24 80 24 minecraft:stone"));
        if (stage == 0) {
            commands.addAll(List.of(
                    "fill -6 81 3 6 89 3 minecraft:oak_leaves[persistent=true]",
                    "fill -6 81 -3 6 89 -3 minecraft:oak_leaves[persistent=true]",
                    "fill -3 81 -3 -3 89 3 minecraft:oak_leaves[persistent=true]",
                    "fill 3 81 -3 3 89 3 minecraft:oak_leaves[persistent=true]",
                    "fill -6 85 -6 6 87 6 minecraft:oak_leaves[persistent=true]",
                    "fill -2 81 2 -2 86 2 minecraft:oak_log",
                    "fill 2 81 2 2 86 2 minecraft:oak_log"));
        } else if (stage == 1) {
            commands.addAll(List.of(
                    "fill -2 81 -2 -1 85 8 minecraft:stone",
                    "fill 1 81 -2 2 85 8 minecraft:stone",
                    "fill -2 83 -2 2 85 8 minecraft:stone",
                    "fill 0 81 3 0 82 3 minecraft:stone"));
        } else if (stage == 5) {
            commands.add("fill -12 81 1 12 94 1 minecraft:stone");
        }
        var client = Minecraft.getInstance();
        var server = client.getSingleplayerServer();
        if (server == null) throw new IllegalStateException("Obstacle QA requires its disposable integrated world");
        server.execute(() -> {
            for (String command : commands) server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
            obstacleWorldReady = true;
        });
    }

    private static void obstacleLyrics() {
        var words = List.of(new TimedWord(0, 700, "As"), new TimedWord(700, 1400, " palavras"),
                new TimedWord(1400, 2100, " continuam"), new TimedWord(2100, 8000, " juntas"));
        var line = new LyricLine(0, 8000, "As palavras continuam juntas", words, "v1", false, null, null);
        var overlay = RelayLyricsClient.overlay();
        overlay.setLyrics(new LyricsDocument(List.of(line)));
        overlay.setPlayback(new PlaybackSnapshot("Obstacle fixture", "Lyrica", "", null,
                "fixture:obstacles:" + stage, "Local fixture", 3000, 60000, false,
                .65, false, "off", false, .4, 0, 110, .5, new double[0], Instant.now(), MusicCapabilities.unavailable()));
    }

    private static void tickObstacles(Minecraft client) {
        if (!obstacleWorldReady) return;
        if (obstacleLyricsPending) { obstacleLyricsPending = false; obstacleLyrics(); ticks = 0; return; }
        if (++ticks == 40) {
            obstacleBeforeAnchors = WorldLyricsRenderer.probeAnchors();
            obstacleRecoveryBefore = WorldLyricsRenderer.probeRecoveryCount();
            if (stage == 2 || stage == 4) {
                obstacleMutationQueued = true;
                var server = client.getSingleplayerServer();
                server.execute(() -> {
                    String command = stage == 2 ? "fill -8 81 2 8 91 2 minecraft:oak_leaves[persistent=true]"
                            : "fill -2 81 -7 -2 90 7 minecraft:oak_leaves[persistent=true]";
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
                    obstacleMutationQueued = false;
                });
            }
        }
        if (ticks < 110 || obstacleMutationQueued) return;
        if (!WorldLyricsRenderer.verifyProbe(1, false) || WorldLyricsRenderer.probeWordCount() != 4
                || !WorldLyricsRenderer.probeReadable())
            throw new IllegalStateException("Obstacle fixture lost readable phrase: " + STAGES[stage] + " " + WorldLyricsRenderer.probeSummary());
        if (WorldLyricsRenderer.probeGroups() > WorldLyricsPresentation.MAX_GROUPS)
            throw new IllegalStateException("Obstacle recovery exceeded the group budget");
        if ((stage == 1 || stage == 5) && !WorldLyricsRenderer.probeCompact())
            throw new IllegalStateException("Blocked space did not select a coherent compact phrase: " + STAGES[stage]);
        if (stage >= 2 && stage <= 4 && (WorldLyricsRenderer.probeRecoveryCount() <= obstacleRecoveryBefore
                || Objects.equals(obstacleBeforeAnchors, WorldLyricsRenderer.probeAnchors())))
            throw new IllegalStateException("Paused playback prevented obstacle/camera recovery: " + STAGES[stage]);
        if (obstacleStableAnchors == null) { obstacleStableAnchors = WorldLyricsRenderer.probeAnchors(); return; }
        if (ticks < 130) return;
        if (!Objects.equals(obstacleStableAnchors, WorldLyricsRenderer.probeAnchors()))
            throw new IllegalStateException("Readable obstacle phrase kept moving: " + STAGES[stage]);
        FanDroidMod.LOGGER.info("Obstacle fixture passed: {}, compact={}, recovery={}, words={}", STAGES[stage],
                WorldLyricsRenderer.probeCompact(), WorldLyricsRenderer.probeRecoveryCount(), WorldLyricsRenderer.probeWordCount());
        capturePending = true; capture(client);
    }

    private static double terrainLightLevel(WorldLyricsLights.Point point) {
        try {
            Class<?> type=Class.forName("dev.lambdaurora.lambdynlights.LambDynLights");Object engine=type.getField("INSTANCE").get(null);
            return ((Number)type.getMethod("getDynamicLightLevel",BlockPos.class).invoke(engine,BlockPos.containing(point.x(),81,point.z()))).doubleValue();
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Could not query actual lighting runtime",error); }
    }
    private static LyricLine phrase(String text,String agent,boolean background) {
        String[] parts=text.split(" ");List<TimedWord> words=new ArrayList<>();long end=0;
        for (int i=0;i<parts.length;i++) { long duration=i==0 ? 6000 : 700;words.add(new TimedWord(end,end+duration,(i==0 ? "" : " ")+parts[i]));end+=duration; }
        return new LyricLine(0,end,text,words,agent,background,null,null);
    }
    private static void apply() {
        if (OBSTACLES) { applyObstacles(); return; }
        if (PRESENTATION) { applyPresentation(); return; }
        if (PLACEMENT) return;
        var p=RelayLyricsClient.preferences();var overlay=RelayLyricsClient.overlay();
        if (stage==11) {
            overlay.setPlayback(new PlaybackSnapshot("Spatial fixture","Lyrica","",null,"spotify:track:spatial10","Local fixture",
                    1900,60000,true,.65,false,"off",false,.4,0,110,.5,new double[0],Instant.now(),MusicCapabilities.unavailable()));
            return;
        }

        p.setLyricsFont(stage==4 ? LyricsFont.UNIFORM : LyricsFont.MINECRAFT);
        p.setLyricsFontWeight(stage==4 ? LyricsFontWeight.BOLD : LyricsFontWeight.REGULAR);
        p.setLyricsAnimations(true);p.setLyricsRomanization(false);
        p.setWorldLyricsVolumetric(true);
        p.setWorldLyricsStyle(stage==2 || stage==3 ? WorldLyricsExperience.Style.SCULPTED : WorldLyricsExperience.Style.SPICY);
        p.setWorldLyricsPosition(stage==1 || stage>=9 ? WorldLyricsSpace.Position.SKY : WorldLyricsSpace.Position.AROUND);
        p.setWorldLyricsTerrainLight(stage!=6 && stage!=8);
        WorldLyricsRenderer.probeFlight(stage>=9 ? new Vec3(.35,-.06,1.0) : null,stage>=9);
        if (stage==6) { var server=Minecraft.getInstance().getSingleplayerServer();server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"time set midnight")); }
        LyricLine lead=phrase(stage>=6 && stage<=8 ? "Ilumina os blocos do mundo" : stage==9 ? "Voa comigo" : stage==10
                ? "Atravessando o céu com uma frase muito maior que precisa de mais espaço para continuar acompanhando a nossa trajetória" : "Movimento faz parte da música","v1",false);
        List<LyricVocalGroup> groups=new ArrayList<>();
        groups.add(new LyricVocalGroup(lead.startMillis(),lead.endMillis(),lead,stage==5 ? List.of(phrase("Voz de fundo","v1",true)) : List.of(),LyricAlignment.NORMAL));
        if (stage==5) { var duet=phrase("Outra voz na canção","v2",false);groups.add(new LyricVocalGroup(duet.startMillis(),duet.endMillis(),duet,List.of(),LyricAlignment.OPPOSITE)); }
        overlay.setLyrics(new LyricsDocument(groups,LyricsMetadata.unknown()));
        overlay.setPlayback(new PlaybackSnapshot("Spatial fixture","Lyrica","",null,"spotify:track:spatial"+stage,"Local fixture",
                stage==2 ? 1300 : 1900,60000,false,.65,false,"off",false,.4,0,110,.5,new double[0],Instant.now(),MusicCapabilities.unavailable()));
    }
    private static void capture(Minecraft client) {
        Consumer<NativeImage> writer=image -> {
            try (image) {
                String version=FabricLoader.getInstance().getModContainer("minecraft").orElseThrow().getMetadata().getVersion().getFriendlyString();
                Path dir=Path.of(System.getProperty("lyrica.worldProbeOutput","build/world-spatial-qa")).toAbsolutePath().normalize();Files.createDirectories(dir);
                Path output=dir.resolve("world-"+version+"-"+STAGES[stage]+".png");image.writeToFile(output);
                if (!PRESENTATION && (stage==6 || stage==7)) {
                    var pixels=ImageIO.read(output.toFile());long sum=0,count=0;
                    for (int y=pixels.getHeight()*72/100;y<pixels.getHeight()*98/100;y++) for (int x=0;x<pixels.getWidth();x++) { int rgb=pixels.getRGB(x,y);sum+=(rgb>>16&255)+(rgb>>8&255)+(rgb&255);count+=3; }
                    double mean=sum/(double)count;
                    if (stage==6) { darkFloor=mean;darkFrame=pixels; }
                    else if (Boolean.getBoolean("lyrica.worldTerrainLightSelfTest")) {
                        double brightest=0;
                        // Lighting is local: compare a floor patch, rather than sky/ambient brightness across the whole view.
                        for (int y=pixels.getHeight()*56/100;y<pixels.getHeight()-64;y+=32) for (int x=0;x<pixels.getWidth()-64;x+=32) {
                            long change=0;
                            for (int py=y;py<y+64;py++) for (int px=x;px<x+64;px++) {
                                int a=darkFrame.getRGB(px,py),b=pixels.getRGB(px,py);
                                change+=(b>>16&255)+(b>>8&255)+(b&255)-(a>>16&255)-(a>>8&255)-(a&255);
                            }
                            brightest=Math.max(brightest,change/(64.0*64*3));
                        }
                        FanDroidMod.LOGGER.info("Ground framebuffer local light increase: {} (whole floor {} -> {})",brightest,darkFloor,mean);
                        if (brightest<.5) throw new IllegalStateException("No floor patch received visible light");
                    }
                }
                FanDroidMod.LOGGER.info("Spatial world screenshot: {} ({}, distance={})",output,WorldLyricsRenderer.probeSummary(),WorldLyricsRenderer.probeAnchorDistance());
                if (++stage==STAGES.length) { FanDroidMod.LOGGER.info(OBSTACLES ? "Obstacle recovery GL probe passed: forest, tunnel, new blocks, moving camera, paused recovery and near wall" : PRESENTATION ? "Cinematic presentation GL probe passed: five compositions, planar/volume, coexistence, future-word gate and fixed paused anchors" : "Spatial world GL probe passed: volumes, popup fonts, sides/sky, lighting and fixed anchors");client.stop();return; }
                ticks=0;apply();capturePending=false;
            } catch (Exception error) { throw new IllegalStateException("Spatial screenshot failed",error); }
        };
        net.minecraft.client.Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(),writer);
    }
}
