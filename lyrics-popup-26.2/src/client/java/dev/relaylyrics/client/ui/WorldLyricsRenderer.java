package dev.relaylyrics.client.ui;

import dev.relaylyrics.client.ClientPreferences;
import dev.relaylyrics.client.SungTextColor;
import dev.relaylyrics.client.CompatibilityManager;
import dev.relaylyrics.client.music.WorldLyricsExperience;
import dev.relaylyrics.client.music.WorldLyricsPresentation;
import dev.relaylyrics.client.music.WorldLyricsStage;
import dev.relaylyrics.client.music.WorldLyricsRecovery;
import dev.relaylyrics.client.music.WorldLyricsSpace;
import dev.relaylyrics.client.music.WorldLyricsLights;
import dev.relaylyrics.client.LyricsFont;
import dev.relaylyrics.client.LyricsFontWeight;
import dev.relaylyrics.client.RelayLyricsClient;
import dev.relaylyrics.client.WorldLyricsRenderHook;
import dev.relaylyrics.client.music.LyricsAnimation;
import dev.relaylyrics.client.music.LyricsRenderText;
import dev.relaylyrics.client.music.WorldLyricsPlan;
import dev.relaylyrics.client.music.WorldLyricsLayout;
import dev.relaylyrics.client.music.WorldLyricsLayout.ReadingFit;
import dev.relaylyrics.client.music.WorldLyricsDirector;
import dev.relaylyrics.client.music.WorldLyricsPlacement;
import dev.relaylyrics.client.music.WorldLyricsMotion;
import dev.relaylyrics.client.music.WorldLyricsContext;
import dev.relaylyrics.client.music.WorldLyricsSpatial;
import dev.relaylyrics.protocol.lyrics.LyricLine;
import dev.relaylyrics.protocol.lyrics.LyricAlignment;
import dev.relaylyrics.protocol.lyrics.TimedWord;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;



import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;
import org.joml.Quaternionf;

import java.util.*;

/** Local-only, fixed world anchors. Layout is rebuilt only when a line or font changes. */
public final class WorldLyricsRenderer {
    private static final double FORWARD_DISTANCE = 6.25;
    private static final float BASE_PIXEL_SCALE = .055F;
    private static final LinkedHashMap<LineKey, SceneLine> LINES = new LinkedHashMap<>();
    private static final WorldLyricsPlan.SceneState STATE = new WorldLyricsPlan.SceneState();
    private static WorldLyricsPlan.Interlude currentInterlude;
    private static Anchor interludeAnchor;
    private static String layoutStyle;
    private static Object fontStorage;
    private static Boolean wallMode;
    private static String anchorStyle;
    private static WorldLyricsDirector.Preset lastPreset;
    private static Anchor stageAnchor;
    private static Object timelineDocument;
    private static WorldLyricsExperience.Timeline timeline;
    private static WorldLyricsPresentation.Timeline presentation;
    private static List<WorldLyricsPresentation.Cue> visibleCues = List.of();
    private static int visibleWords;
    private static int recoveryCount;
    private static SceneLine placementOwner;
    private static Vec3 searchView;
    private static long placementSongTime;
    private static final WorldLyricsContext.Tracker MOTION = new WorldLyricsContext.Tracker();
    private static WorldLyricsContext.Snapshot context = WorldLyricsContext.Snapshot.STILL;
    private static final WorldLyricsSpatial.RayBudget RAYS = new WorldLyricsSpatial.RayBudget();
    private static final Map<RayKey, CachedRay> RAY_CACHE = new LinkedHashMap<>();
    private static final Map<Anchor, Boolean> PHYSICAL_CLEARANCE = new HashMap<>();
    private static long rayTick;
    private static int peakRaycasts, perspectiveConflicts;
    static boolean probeGround() { return LINES.values().stream().flatMap(scene -> scene.groupAnchors.values().stream()).anyMatch(anchor -> anchor.ground); }
    static boolean probeFloorFlat() { return LINES.values().stream().flatMap(scene -> scene.groupAnchors.values().stream()).anyMatch(anchor -> anchor.ground && anchor.normalY > .99); }
    static int probeRaycasts() { return RAYS.used(); }
    static int probePeakRaycasts() { return peakRaycasts; }
    static int probePerspectiveConflicts() { return perspectiveConflicts; }
    private static final List<Anchor> PROBE_SUBMITTED = new ArrayList<>();
    private static final boolean TRACE_PLACEMENT = Boolean.getBoolean("lyrica.worldContextTrace");
    private static void tracePlacement(String result, Anchor anchor) {
        if (TRACE_PLACEMENT) dev.relaylyrics.FanDroidMod.LOGGER.info("World lyric placement {}: {}", result, anchor);
    }
    static List<Anchor> probePlacedAnchors() {
        return LINES.values().stream().flatMap(scene -> scene.groupAnchors.values().stream()).toList();
    }
    static int probeVisibleConflicts() {
        var client = Minecraft.getInstance();
        var view = viewpoint(client, WorldLyricsRenderHook.cameraPosition(client));
        var panels = PROBE_SUBMITTED.stream().map(anchor -> WorldLyricsSpatial.project(panel(anchor), view)).toList();
        int conflicts = 0;
        for (int i = 0; i < panels.size(); i++) for (int j = i + 1; j < panels.size(); j++)
            if (WorldLyricsSpatial.overlap(panels.get(i), panels.get(j), 0)) conflicts++;
        return conflicts;
    }
    static boolean probeConfined() { return confined; }
    private static boolean newestGroupDrawn;
    static boolean probeNewestGroupDrawn() { return newestGroupDrawn; }
    static boolean probeOpenSky() { return LINES.values().stream().anyMatch(scene -> scene.openSky); }
    static ReadingFit probeReadingFit() {
        return LINES.values().stream()
                .filter(scene -> !scene.cue.source().backing() && scene.cue.source().line().startMillis() <= lastDrawSongTime
                        && lastDrawSongTime < scene.cue.source().line().endMillis())
                .max(Comparator.comparingLong(scene -> scene.cue.source().line().startMillis()))
                .map(scene -> scene.fit).orElse(null);
    }
    static boolean probeCompact() { return LINES.values().stream().anyMatch(scene -> scene.compact); }
    static int probeRecoveryCount() { return recoveryCount; }
    static boolean probeReadable() { return !LINES.isEmpty() && LINES.values().stream().allMatch(scene -> scene.readable); }
    private static Map<String, WorldLyricsPresentation.Cue> lastOverrides;
    private static WorldLyricsPresentation.Intensity lastIntensity;
    private static WorldLyricsPresentation.Reveal lastReveal;
    private static long nextClearanceCheck, clearanceChangeAt;
    private static boolean confined;
    private static ReadingFit roomFit = ReadingFit.FULL, candidateFit = ReadingFit.FULL;
    private static ReadingFit distanceFit;
    private static List<WorldLyricsPlan.WorldLine> visibleCache = List.of();
    private static long visibleAt = Long.MIN_VALUE;
    private static WorldLyricsExperience.Budget frameBudget;
    private static boolean safeRendering;
    private static long configureAt;
    private static String trackCache;
    private static Object trackIdentity;
    private static long trackRevision;
    private static long frameClock;
    private static int courseChanges;
    private static boolean cinematicActive;
    private static final WorldLyricsSpace.CinematicCamera CINEMATIC_CAMERA = new WorldLyricsSpace.CinematicCamera();
    private static long cinematicHandoffAt;
    private static int cinematicReframes;
    static int probeCinematicReframes() { return cinematicReframes; }
    static int probeCourseChanges() { return courseChanges; }
    private static int cinematicWorldFrames;
    static int probeCinematicFrames() { return cinematicWorldFrames; }
    private static Vec3 probeVelocity;
    private static boolean probeGliding;
    static void probeFlight(Vec3 velocity, boolean gliding) {
        if (!Boolean.getBoolean("lyrica.worldExperienceSelfTest")) throw new IllegalStateException("Opt-in probe only");
        probeVelocity=velocity; probeGliding=gliding;
    }
    private static boolean gliding(Minecraft client) {
        return !cinematicActive && (Boolean.getBoolean("lyrica.worldExperienceSelfTest") && probeGliding || WorldLyricsRenderHook.gliding(client.player));
    }
    static double probeAnchorDistance() { return LINES.values().stream().filter(scene -> scene.base != null).mapToDouble(scene -> scene.base.distance).findFirst().orElse(0); }
    static double probeTravelAlignment() {
        var client=Minecraft.getInstance();
        if (LINES.isEmpty()) return -1;
        Anchor anchor=LINES.values().iterator().next().base;
        return new Vec3(anchor.x,anchor.y,anchor.z).subtract(WorldLyricsRenderHook.cameraPosition(client)).normalize().dot(travel(client).normalize());
    }
    static int probeWordCount() { return visibleWords; }
    static boolean probeBackingBelowLead() {
        for (var scene : LINES.values()) {
            if (scene.lead == null || scene.lead.groupAnchors.isEmpty()) continue;
            Anchor lead = scene.lead.groupAnchors.lastEntry().getValue();
            if (scene.groupAnchors.values().stream().anyMatch(anchor -> anchor.y < lead.y
                    && Math.hypot(anchor.x - lead.x, anchor.z - lead.z) < lead.distance * .05)) return true;
        }
        return false;
    }
    static boolean probeFlat() { return drawnPieces > 0 && drawnMeshes == 0; }
    static String probeAnchors() { return LINES.values().stream().flatMap(scene -> scene.groupAnchors.values().stream()).map(Object::toString).sorted().toList().toString(); }
    static boolean probeHasVolume() { return drawnMeshes>0 && drawnHalos==0; }
    static boolean probeFontMatchesPopup() {
        var expected=PlatformTextStyles.withFont(Component.literal("X"),RelayLyricsClient.preferences().getLyricsFont().id()).getStyle().getFont();
        return LINES.values().stream().filter(line -> line.layout!=null).flatMap(line -> line.layout.drawn.stream())
                .flatMap(page -> page.rows.stream()).flatMap(row -> row.pieces.stream()).allMatch(piece -> piece.text.getStyle().getFont().equals(expected));
    }

    private static final WorldLyricsPresentation.PauseExit PAUSE_EXIT = new WorldLyricsPresentation.PauseExit();
    private static long lastDrawSongTime;
    private static boolean leavingForPause;
    private static double pauseProgress;

    private static int drawnPieces, drawnMeshes, drawnHalos, spicyMotionPieces;
    static int probeSpicyMotion() { return spicyMotionPieces; }
    static int probeGroups() { return LINES.values().stream().mapToInt(scene -> scene.groupAnchors.size()).sum(); }
    static boolean probeRotation() { return LINES.values().stream().filter(scene -> scene.layout != null).flatMap(scene -> scene.layout.groups.stream()).anyMatch(group -> Math.abs(group.rotationDegrees()) > 1); }
    static boolean verifyProbe(int expectedLines, boolean safe) { return LINES.size() >= expectedLines && drawnPieces > 0 && (!safe || drawnMeshes == 0); }
    static boolean probeWall() { return LINES.values().stream().flatMap(scene -> scene.groupAnchors.values().stream()).anyMatch(anchor -> anchor.wall); }
    static String probeSummary() { return "wall=" + probeWall() + ", lines=" + LINES.size() + ", groups=" + probeGroups() + ", spicy=" + probeSpicyMotion() + ", pieces=" + drawnPieces + ", meshes=" + drawnMeshes + ", halos=" + drawnHalos + ", compact=" + probeCompact() + ", readable=" + probeReadable() + ", recovery=" + recoveryCount + ", safe=" + safeRendering; }


    private static Object travelWorld;
    private static Vec3 previousPlayerPosition;
    private static Vec3 playerTravelPerTick = Vec3.ZERO;
    private static final LyricLine PREVIEW_LINE = new LyricLine(0, 15_000,
            "Letras fazem parte do mundo",
            List.of(new TimedWord(0, 3_000, "Letras"), new TimedWord(3_000, 6_000, "fazem"),
                    new TimedWord(6_000, 9_000, "parte"), new TimedWord(9_000, 12_000, "do"),
                    new TimedWord(12_000, 15_000, "mundo")), null, false, null, null);
    private static long previewStartedNanos;

    private WorldLyricsRenderer() { }

    /** Shows a timed sample in the current world even when no music is playing. */
    public static void previewCurrentPhrase() { clear(); previewStartedNanos = System.nanoTime(); }

    public static void tickPlayerMotion(Minecraft client) {
        if (client.player == null || client.level == null || !RelayLyricsClient.preferences().isWorldLyrics()) {
            clear(); STATE.clear(); timelineDocument=null; timeline=null; visibleCache=List.of();
            previousPlayerPosition = null; travelWorld = null; playerTravelPerTick = Vec3.ZERO; MOTION.reset(); context = WorldLyricsContext.Snapshot.STILL;
            CINEMATIC_CAMERA.reset(); cinematicActive = false; cinematicHandoffAt = 0; return;
        }
        Vec3 position = new Vec3(client.player.getX(), client.player.getY(), client.player.getZ());
        if (travelWorld == client.level && previousPlayerPosition != null) {
            Vec3 delta = position.subtract(previousPlayerPosition);
            if (delta.lengthSqr() > 64) { clear(); STATE.clear(); MOTION.reset(); context = WorldLyricsContext.Snapshot.STILL; playerTravelPerTick = client.player.getDeltaMovement(); }
            else playerTravelPerTick = delta;
        } else playerTravelPerTick = client.player.getDeltaMovement();
        if (travelWorld != client.level) {
            MOTION.reset(); context = WorldLyricsContext.Snapshot.STILL;
            nextClearanceCheck = clearanceChangeAt = 0;
            confined = false;
            roomFit = candidateFit = ReadingFit.FULL;
        }
        previousPlayerPosition = position; travelWorld = client.level;
    }

    private static Vec3 travel(Minecraft client) {
        if (cinematicActive) return Vec3.ZERO;
        if (Boolean.getBoolean("lyrica.worldExperienceSelfTest") && probeVelocity!=null) return probeVelocity;
        return travelWorld == client.level && previousPlayerPosition != null ? playerTravelPerTick : client.player.getDeltaMovement();
    }

    public static void render(PoseStack matrices, MultiBufferSource consumers, Vec3 cameraPos) {
        Minecraft client = Minecraft.getInstance();
        ClientPreferences preferences = RelayLyricsClient.preferences();
        LyricsOverlay overlay = RelayLyricsClient.overlay();
        // Auxiliary shader passes must not advance the scene from a light's camera.
        if (CompatibilityManager.worldShadowPass()) return;
        WorldLyricsFrameProbe.frame();
        if (!preferences.isLyricsVisible()) return;
        if (!preferences.isWorldLyrics() || overlay == null || client.player == null
                || client.level == null || matrices == null || consumers == null) {
            clear(); STATE.clear(); return;
        }
        if (!Objects.equals(wallMode, preferences.isWorldLyricsAutoWalls())) {
            clear(); wallMode = preferences.isWorldLyricsAutoWalls();
        }
        cinematicActive = CompatibilityManager.jiAfkCinematicActive();
        frameClock=System.nanoTime(); WorldLyricsLights.begin(client.level,frameClock);
        long tick = client.level.getGameTime();
        if (tick != rayTick) {
            rayTick = tick;
            PHYSICAL_CLEARANCE.clear();
            RAY_CACHE.entrySet().removeIf(entry -> rayTick - entry.getValue().tick > 4);
        }
        RAYS.beginTick(rayTick);
        Vec3 actualVelocity = (Boolean.getBoolean("lyrica.worldExperienceSelfTest") && probeVelocity != null ? probeVelocity : cinematicActive ? Vec3.ZERO : client.player.isPassenger() ? client.player.getRootVehicle().getDeltaMovement() : client.player.getDeltaMovement()).scale(20);
        context = MOTION.update(frameClock, new WorldLyricsContext.Input(actualVelocity.x, actualVelocity.y, actualVelocity.z,
                client.player.onGround(), client.player.isShiftKeyDown(), client.player.isSprinting(), client.player.isSwimming(),
                client.player.isPassenger(), client.player.getAbilities().flying || gliding(client)));
        // Ji owns the viewpoint. Reframe on entry, camera cuts/pans, and return to the player.
        Vec3 cameraLook = placementDirection(client);
        if (preferences.isWorldLyricsReposition() && CINEMATIC_CAMERA.update(cinematicActive, cameraPos.x, cameraPos.y, cameraPos.z,
                cameraLook.x, cameraLook.y, cameraLook.z, frameClock)) {
            clear(); STATE.clear(); cinematicHandoffAt = frameClock; cinematicReframes++;
        }
        var playback = overlay.worldPlayback();
        long elapsedPreview = (System.nanoTime() - previewStartedNanos) / 1_000_000;
        boolean preview = previewStartedNanos != 0 && elapsedPreview >= 0 && elapsedPreview <= 15_450;
        // Untimed text belongs in the scrollable popup; it cannot be placed at a musical moment.
        if (!preview && (overlay.document() == null || overlay.document().metadata().syncMode()
                == dev.relaylyrics.protocol.lyrics.LyricsSyncMode.STATIC)) {
            clear(); STATE.clear(); return;
        }
        long now = preview ? elapsedPreview : playback.positionMillis();
        if (trackIdentity != playback.track() || trackRevision != playback.revision()) {
            trackIdentity = playback.track(); trackRevision = playback.revision();
            trackCache = playback.track() + "|seek=" + playback.revision();
        }
        String track = preview ? "world-preview" : trackCache;
        boolean playing = preview || playback.playing();
        List<LyricLine> sourceLines = preview ? List.of(PREVIEW_LINE)
                : overlay.document() == null ? List.of() : overlay.document().lines();
        boolean reactive = false;
        Vec3 velocity = travel(client);
        if (STATE.update(client.level, preview ? PREVIEW_LINE : overlay.document(), track, now, playing, System.nanoTime(),
                client.player.onGround(), velocity.length() > .05 && (client.player.getAbilities().flying || gliding(client)),
                velocity.length(), reactive)) clear();
        // Placement fixtures intentionally freeze the audio clock. The pause fixture uses real pause behavior.
        boolean frozenFixture = Boolean.getBoolean("lyrica.worldExperienceSelfTest")
                && !Boolean.getBoolean("lyrica.worldPauseSelfTest");
        leavingForPause = !playing && !frozenFixture;
        pauseProgress = PAUSE_EXIT.progress(frameClock, !leavingForPause, preferences.isLyricsAnimations());
        if (leavingForPause) {
            drawnPieces = drawnMeshes = drawnHalos = spicyMotionPieces = visibleWords = 0;
            PROBE_SUBMITTED.clear();
            if (pauseProgress < 1) {
                for (var scene : LINES.values()) if (scene.layout != null && scene.base != null)
                    renderLine(matrices, consumers, cameraPos, scene, lastDrawSongTime, preferences, STATE.reaction(false), false);
            }
            return;
        }
        lastDrawSongTime = now;
        WorldLyricsDirector.Settings director = directorSettings(preferences);
        long configureNow = System.nanoTime();
        if (configureNow - configureAt >= 50_000_000L) {
            configureAt = configureNow;
            String anchors = preferences.getWorldLyricsStyle() + "|" + preferences.getWorldLyricsPosition()
                    + "|" + preferences.getWorldLyricsDepth() + "|" + preferences.getWorldLyricsQuality()
                    + "|" + preferences.isWorldLyricsCinematic() + "|" + preferences.getWorldLyricsPresentationIntensity()
                    + "|" + preferences.getWorldLyricsReveal() + "|" + distanceRange(client, preferences);
            if (!Objects.equals(anchors, anchorStyle)) { clear(); anchorStyle = anchors; }
            String style = preferences.getLyricsFont().id() + "|" + preferences.getGoogleFontFamily()
                    + "|" + preferences.getLyricsFontWeight() + "|" + preferences.isLyricsRomanization()
                    + "|" + overlay.fontLayoutRevision() + "|" + preferences.getWorldLyricsSize()
                    + "|" + preferences.isWorldLyricsGeminiFont();
            if (!Objects.equals(style, layoutStyle) || fontStorage != client.font) {
                LINES.values().forEach(scene -> {
                    scene.layout = null;
                    scene.compactLayout = null;
                    scene.preparedIndex = -1;
                    scene.preparedAnchor = null;
                    scene.nextVisibilityCheck = 0;
                });
                WorldTextMesh.clear(); layoutStyle = style; fontStorage = client.font;
            }
            safeRendering = false;
        }
        if (timelineDocument != overlay.document() || timeline == null) {
            timelineDocument = overlay.document(); timeline = new WorldLyricsExperience.Timeline(overlay.document());
            presentation = new WorldLyricsPresentation.Timeline(overlay.document());
            visibleAt = Long.MIN_VALUE;
        }
        // The session is updated by MusicService.tick(), never from a render pass.
        var overrides = preferences.getWorldLyricsDirection() == WorldLyricsPresentation.Direction.GEMINI
                && RelayLyricsClient.music() != null ? RelayLyricsClient.music().geminiLyrics().overrides()
                : Map.<String, WorldLyricsPresentation.Cue>of();
        if (Boolean.getBoolean("lyrica.worldPresentationSelfTest")) {
            var demo = new LinkedHashMap<String, WorldLyricsPresentation.Cue>();
            var composition = WorldLyricsPresentation.Composition.valueOf(System.getProperty("lyrica.worldComposition", "PROGRESSIVE"));
            for (var original : presentation.cues()) {
                var example = WorldLyricsPresentation.demonstration(original.source(), composition);
                demo.put(original.id(), new WorldLyricsPresentation.Cue(original.id(), example.source(), example.composition(),
                        example.region(), example.entrance(), example.volumetric(), example.holdMillis(), example.accents(), example.groupEnds()));
            }
            overrides = demo;
        }
        var reveal = preferences.getWorldLyricsReveal();
        updateRoomClearance(client, cameraPos);
        if (lastReveal != reveal) {
            clear();
            lastReveal = reveal;
        }
        boolean wordOnly = reveal == WorldLyricsPresentation.Reveal.WORD_BY_WORD;
        boolean sequential = wordOnly || reveal == WorldLyricsPresentation.Reveal.READING_LINE;
        boolean animateReading = preferences.isLyricsAnimations();
        double readingSpeed = preferences.getWorldLyricsAnimationSpeed();
        long wordTail = animateReading ? WorldLyricsPresentation.readingTailMillis(true, readingSpeed) : 0;
        if (visibleAt == Long.MIN_VALUE || now < visibleAt || now - visibleAt >= 25
                || overrides != lastOverrides || lastIntensity != preferences.getWorldLyricsPresentationIntensity()) {
            visibleAt = now; lastOverrides = overrides; lastIntensity = preferences.getWorldLyricsPresentationIntensity();
            visibleCues = presentation.visible(now, overrides, lastIntensity);
        }
        if (preview) {
            var sample = new WorldLyricsPlan.WorldLine(PREVIEW_LINE, LyricAlignment.NORMAL, false);
            var cue = WorldLyricsPresentation.demonstration(sample, WorldLyricsPresentation.Composition.PROGRESSIVE);
            String demo = System.getProperty("lyrica.worldComposition", "");
            if (!demo.isEmpty()) {
                try { cue = WorldLyricsPresentation.demonstration(sample, WorldLyricsPresentation.Composition.valueOf(demo)); }
                catch (IllegalArgumentException ignored) { }
            }
            visibleCues = List.of(new WorldLyricsPresentation.Cue(cue.id(), cue.source(), cue.composition(),
                    cue.region(), cue.entrance(), cue.volumetric(), 450, cue.accents(), cue.groupEnds(), cue.look()));
        }
        var frameCues = sequential ? visibleCues.stream()
                .filter(cue -> cue.source().line().startMillis() <= now && now - cue.source().line().endMillis() < wordTail)
                .sorted(Comparator.comparing((WorldLyricsPresentation.Cue cue) -> cue.source().line().endMillis() <= now)
                        .thenComparing(cue -> cue.source().backing())
                        .thenComparing(Comparator.comparingLong((WorldLyricsPresentation.Cue cue) -> cue.source().line().startMillis()).reversed()))
                .limit(1).toList() : visibleCues;
        List<WorldLyricsPlan.WorldLine> visible = frameCues.stream().map(WorldLyricsPresentation.Cue::source).toList();
        visibleWords = 0;
        drawnPieces = drawnMeshes = drawnHalos = spicyMotionPieces = 0;
        PROBE_SUBMITTED.clear();
        newestGroupDrawn = false;
        frameBudget = new WorldLyricsExperience.Budget(preferences.getWorldLyricsQuality(), safeRendering);
        Set<LineKey> keys = new HashSet<>();
        for (var line : visible) keys.add(key(line));
        LINES.entrySet().removeIf(entry -> {
            if (keys.contains(entry.getKey())) return false;
            var scene = entry.getValue();
            if (scene.fit != ReadingFit.FULL) return !animateReading || scene.layout == null || scene.groupAnchors.isEmpty()
                    || now - Math.min(scene.wordsStopAt, scene.cue.source().line().endMillis()) >=
                            WorldLyricsPresentation.readingTailMillis(scene.fit == ReadingFit.WORD, readingSpeed);
            if (!preferences.isLyricsAnimations() || scene.layout == null || scene.base == null
                    || now < scene.cue.source().line().endMillis() || now >= scene.cue.visibleUntil()) return true;
            if (scene.yieldedAt == Long.MIN_VALUE) scene.yieldedAt = now;
            return now - scene.yieldedAt >= 150;
        });
        for (var scene : LINES.values()) pruneGroupAnchors(scene, now, animateReading, readingSpeed);
        var priority = Comparator.comparingInt((WorldLyricsPresentation.Cue cue) -> cue.source().line().endMillis() <= now
                ? 2 : cue.source().backing() ? 1 : 0);
        Boolean openSky = null;
        for (var cue : frameCues.stream().sorted(priority).toList()) {
            var planned = cue.source();
            LineKey key = key(planned);
            SceneLine scene = LINES.get(key);
            if (scene == null) {
                var selected = composition(planned.line(), planned.backing(), false, director, lastPreset);
                double distance = readingDistance(planned.line(), now, selected, director);
                scene = new SceneLine(null, 0, selected, now, new PhraseBounds(1, 1), 15);
                if (openSky == null) openSky = openSkyView(client, cameraPos, preferences);
                scene.openSky = openSky && !planned.backing() && !Boolean.getBoolean("lyrica.worldPresentationSelfTest");
                scene.requestedDistance = scene.openSky ? distanceRange(client, preferences).maximum() : distance;
                if (!preview) scene.wordsStopAt = presentation.nextLeadStart(planned.line().startMillis());
                scene.cue = Boolean.getBoolean("lyrica.worldPresentationSelfTest") && !wordOnly
                        ? cue : WorldLyricsPresentation.withReveal(cue, reveal);
                if (scene.cue.composition() == WorldLyricsPresentation.Composition.WORD_BY_WORD) {
                    scene.fit = ReadingFit.WORD;
                } else if (reveal == WorldLyricsPresentation.Reveal.READING_LINE) {
                    scene.fit = ReadingFit.SHORT;
                    scene.fitLocked = true;
                } else if (scene.cue.composition() == WorldLyricsPresentation.Composition.COEXIST
                        && !Boolean.getBoolean("lyrica.worldPresentationSelfTest")) {
                    ReadingFit fit = ReadingFit.atDistance(distance, distanceFit);
                    if (!planned.backing() && now < planned.line().endMillis()) distanceFit = fit;
                    scene.fit = fit.limitTo(roomFit).limitTo(ReadingFit.MEDIUM);
                }
                if (scene.openSky) {
                    if (scene.fit != ReadingFit.WORD && reveal != WorldLyricsPresentation.Reveal.READING_LINE)
                        scene.fit = ReadingFit.openSky(WorldLyricsPlan.wordTokens(planned.line(), false));
                    scene.fitLocked = true;
                }
                LINES.put(key, scene);
            }
            var lead = presentation.leadFor(planned);
            var leadScene = lead != null && lead.line().startMillis() <= now && now < lead.line().endMillis()
                    ? LINES.get(key(lead)) : null;
            if (leadScene != null && leadScene.groupAnchors.isEmpty()) leadScene = null;
            if (leadScene != null && scene.lead != leadScene && scene.fit == ReadingFit.FULL) {
                scene.groupAnchors.clear();
                scene.compactAnchor = null;
                scene.observedGroups = Set.of();
                scene.nextVisibilityCheck = 0;
                scene.recovery.reset();
            }
            if (leadScene != null) scene.lead = leadScene;
            if (scene.layout == null) {
                scene.layout = layout(planned.line(), preferences, scene.requestedDistance, scene.cue, false, scene.layoutContext, scene.frozenGroups, scene.fit, scene.openSky);
                scene.activePage = WorldLyricsPlacement.pageIndex(scene.layout.pages, now);
            }
            if (playing && !scene.layout.pages.isEmpty()) {
                // Timed pages share the phrase placement until its current view becomes unreadable.
                scene.activePage = WorldLyricsPlacement.pageIndex(scene.layout.pages, now);
                // Anchors stay fixed until this phrase leaves the scene.
            }
            renderLine(matrices, consumers, cameraPos, scene, now, preferences, STATE.reaction(reactive), true);
        }
        for (var entry : LINES.entrySet()) {
            if (!keys.contains(entry.getKey()))
                renderLine(matrices, consumers, cameraPos, entry.getValue(), now, preferences, STATE.reaction(reactive), entry.getValue().fit == ReadingFit.FULL);
        }
        var gap = preview || !visible.isEmpty() || !LINES.isEmpty() ? null : timeline.interlude(now);
        if (visible.isEmpty() && gap != null && gap.visibility(now) > .01) {
            if (!gap.equals(currentInterlude) || interludeAnchor == null) {
                currentInterlude = gap;
                var calm = new WorldLyricsDirector.Composition(WorldLyricsDirector.Preset.LANDSCAPE,
                        WorldLyricsDirector.Placement.FRONT, WorldLyricsDirector.Entrance.DISSOLVE,
                        WorldLyricsDirector.Motion.BREATHE, 0, 0, 0, false, 1, 0);
                try { interludeAnchor = createAnchor(client, cameraPos, 1, preferences, calm,
                        WorldLyricsDirector.readingDistance(STATE.blocksPerSecond(), 2, STATE.flying(), false, director),
                        new PhraseBounds(1.5, 1)); } catch (DeferredSearch ignored) { /* Continue next tick. */ }
            }
            if (interludeAnchor != null) renderInterlude(matrices, consumers, cameraPos, gap, now, preferences);
        } else { currentInterlude = null; interludeAnchor = null; }
        if (cinematicActive && drawnPieces > 0) cinematicWorldFrames++;
    }

    private static void clear() {
        distanceFit = null;
        LINES.clear(); RAY_CACHE.clear(); PHYSICAL_CLEARANCE.clear(); currentInterlude = null; interludeAnchor = null; lastPreset = null; stageAnchor = null; visibleAt = Long.MIN_VALUE;
    }
    private static LineKey key(WorldLyricsPlan.WorldLine value) {
        return new LineKey(value.line(), value.backing());
    }

    private static WorldLyricsDirector.Settings directorSettings(ClientPreferences p) {
        return new WorldLyricsDirector.Settings(false, WorldLyricsDirector.Preset.SIGN,
                Set.of(WorldLyricsDirector.Preset.SIGN), WorldLyricsDirector.Variation.LOW,
                7, 256, 1, 0, 0, p.getWorldLyricsDepth(), p.getWorldLyricsAnimationIntensity(),
                1, false, p.getWorldLyricsQuality());
    }

    private static double readingDistance(LyricLine line, long now, WorldLyricsDirector.Composition composition,
                                          WorldLyricsDirector.Settings settings) {
        var tokens = WorldLyricsPlan.tokens(line, false);
        double seconds = Math.min(2.5, Math.max(.35, (line.endMillis() - now) / 1000.0));
        if (WorldLyricsPlan.hasWordTiming(line) && !tokens.isEmpty()) {
            int words = 0; long end = tokens.getFirst().endMillis();
            for (var token : tokens) {
                if (token.endMillis() < now) continue;
                if (token.startsWord() && ++words > 3) break;
                end = token.endMillis();
            }
            seconds = Math.max(.35, (end - now) / 1000.0);
        }
        return context.readingDistance(seconds,
                distanceRange(Minecraft.getInstance(), RelayLyricsClient.preferences()), RelayLyricsClient.preferences().getWorldLyricsDistanceSensitivity());
    }

    private static WorldLyricsSpace.DistanceRange distanceRange(Minecraft client, ClientPreferences preferences) {
        return WorldLyricsSpace.placementRange(preferences.getWorldLyricsMinimumDistance(),
                preferences.getWorldLyricsMaximumDistance(), Math.max(24, client.options.renderDistance().get()*14));
    }

    private static Vec3 atDistance(Vec3 camera, Vec3 center, double distance) {
        return camera.add(center.subtract(camera).normalize().scale(distance));
    }

    private static Vec3 placementDirection(Minecraft client) {
        Vec3 look = searchDirection(client);
        Vec3 movement = travel(client);
        if (gliding(client) && movement.length() > .05) return movement.normalize();
        if (movement.length() > .1 && look.dot(movement.normalize()) > .4)
            return look.scale(.7).add(movement.normalize().scale(.3)).normalize();
        return look;
    }

    private static boolean reusable(Minecraft client, Vec3 camera, Anchor anchor) {
        Vec3 to = new Vec3(anchor.x, anchor.y, anchor.z).subtract(camera);
        return to.length() >= Math.max(2.5, anchor.distance * .45) && to.length() <= anchor.distance * 1.8
                && to.normalize().dot(placementDirection(client)) > .55 && freePanel(client, camera, anchor);
    }

    private static void keepReadingHorizon(Minecraft client, Vec3 camera, SceneLine scene,
                                           LyricLine line, long now, WorldLyricsDirector.Settings settings,
                                           boolean animate) {
        if (scene.base.wall || scene.base.ground) return;
        Anchor previous = scene.base;
        long pageEnd = Math.min(line.endMillis(), scene.layout.pages.get(scene.activePage).endMillis());
        // Simultaneous voices share one decision, including at different timed page boundaries.
        for (var entry : LINES.entrySet()) {
            SceneLine sibling = entry.getValue();
            if (sibling.base != previous || sibling.layout == null || sibling.layout.pages.isEmpty()) continue;
            int page = WorldLyricsPlacement.pageIndex(sibling.layout.pages, now);
            pageEnd = Math.max(pageEnd, Math.min(entry.getKey().line().endMillis(),
                    sibling.layout.pages.get(page).endMillis()));
        }
        double remaining = (pageEnd - now) / 1000.0;
        Vec3 toAnchor = new Vec3(previous.x, previous.y, previous.z).subtract(camera);
        double ahead = toAnchor.length();
        double closingSpeed = ahead > 0 ? travel(client).dot(toAnchor.scale(1 / ahead)) * 20 : 0;
        double movementSpeed = travel(client).length() * 20;
        var relocation = scene.relocation.evaluate(now, ahead, previous.distance, closingSpeed,
                movementSpeed, remaining, (now - scene.anchoredAt) / 1000.0, STATE.flying(),
                scene.from != null && now - scene.moveStartedAt < scene.moveDuration);
        if (relocation == WorldLyricsSpace.Relocation.KEEP) return;
        boolean courseChanged = relocation == WorldLyricsSpace.Relocation.TURN;
        scene.relocation.attempted(now);
        double target = readingDistance(line,now,scene.composition,settings);
        // Move only when the current reading runway is nearly exhausted.
        Anchor replacement = createAnchor(client, camera, previous.bands, RelayLyricsClient.preferences(),
                scene.composition, target, scene.bounds);
        if (replacement == null) return;
        if (!courseChanged && replacement.distance <= ahead + Math.max(8, closingSpeed * .45)) return;
        Anchor displayed = scene.display(now, animate);
        double span = new Vec3(displayed.x, displayed.y, displayed.z)
                .distanceTo(new Vec3(replacement.x, replacement.y, replacement.z));
        long duration = Math.round(Math.clamp(Math.min(Math.min(300 + span * (STATE.flying() ? 5 : 12),
                (pageEnd - now) * .55), closingSpeed > 0 ? ahead / closingSpeed * 800 : 850),
                300, STATE.flying() ? 850 : 1100));
        double rise = animate && !courseChanged ? relocationRise(client, displayed, replacement, scene.composition.depth()) : 0;
        if (animate && !courseChanged && !clearRelocationPath(client, displayed, replacement, rise)) {
            return;
        }
        scene.relocation.placed();
        if (courseChanged) courseChanges++;
        if (stageAnchor == previous) stageAnchor = replacement;
        for (SceneLine sibling : LINES.values()) if (sibling.base == previous) {
            sibling.from = animate && !courseChanged ? sibling.display(now, true) : null;
            sibling.handoffAt = courseChanged ? System.nanoTime() : 0;
            sibling.base = replacement;
            sibling.moveStartedAt = now;
            sibling.moveDuration = duration;
            sibling.moveRise = rise;
            sibling.anchoredAt = now;
            sibling.worldLight = Math.clamp(client.level.getMaxLocalRawBrightness(
                    BlockPos.containing(replacement.x, replacement.y, replacement.z)), 0, 15);
        }
    }

    /** A few terrain checks at relocation time keep the moving phrase above intervening ground. */
    private static double relocationRise(Minecraft client, Anchor from, Anchor to, double depth) {
        double rise = 0;
        for (double t : new double[]{.25, .5, .75}) {
            Anchor sample = interpolateAnchor(from, to, t, 0);
            Vec3 point = new Vec3(sample.x, sample.y, sample.z);
            Vec3 right = new Vec3(sample.rightX, 0, sample.rightZ);
            Vec3 safe = liftAboveGround(client, point, right, sample.distance, sample.height, depth);
            rise = Math.max(rise, (safe.y - point.y) / Math.sin(Math.PI * t));
        }
        return rise;
    }

    private static boolean clearRelocationPath(Minecraft client, Anchor from, Anchor to, double rise) {
        // Probe once per attempted move, including both edges. Never move the text through a wall.
        for (double side : new double[]{-.35, 0, .35}) {
            Vec3 previous = null;
            for (int step = 0; step <= 6; step++) {
                double t = step / 6.0;
                Anchor sample = interpolateAnchor(from, to, t, rise);
                Vec3 point = new Vec3(sample.x + sample.rightX * sample.width * side + sample.normalX * .25,
                        sample.y + sample.normalY * .25,
                        sample.z + sample.rightZ * sample.width * side + sample.normalZ * .25);
                if (previous != null && ray(client, previous, point).getType() == HitResult.Type.BLOCK) return false;
                previous = point;
            }
        }
        return true;
    }

    private static WorldLyricsDirector.Composition composition(LyricLine line, boolean backing, boolean wall,
                                                                WorldLyricsDirector.Settings settings,
                                                                WorldLyricsDirector.Preset previous) {
        ClientPreferences p = RelayLyricsClient.preferences();
        return WorldLyricsExperience.composition(p.getWorldLyricsStyle(), wall, p.getWorldLyricsQuality(), p.getWorldLyricsDepth());
    }

    private static WorldLyricsDirector.Composition compatibleComposition(Anchor anchor, Vec3 camera,
                                                                          WorldLyricsDirector.Composition selected) {
        return selected;
    }

    private static Layout layout(LyricLine line, ClientPreferences preferences, double distance, WorldLyricsPresentation.Cue cue) {
        return layout(line, preferences, distance, cue, false);
    }

    private static Layout layout(LyricLine line, ClientPreferences preferences, double distance, WorldLyricsPresentation.Cue cue, boolean compact) {
        return layout(line, preferences, distance, cue, compact, context, List.of(), ReadingFit.FULL, false);
    }

    private static Layout layout(LyricLine line, ClientPreferences preferences, double distance, WorldLyricsPresentation.Cue cue,
                                 boolean compact, WorldLyricsContext.Snapshot grouping, List<WorldLyricsStage.Group> frozen, ReadingFit fit,
                                 boolean openSky) {
        Font renderer = Minecraft.getInstance().font;
        boolean romanized = preferences.isLyricsRomanization() && LyricsRenderText.hasRomanization(line);
        boolean wordOnly = fit == ReadingFit.WORD;
        boolean limited = fit != ReadingFit.FULL;
        boolean timed = limited || WorldLyricsPlan.hasWordTiming(line, romanized);
        var tokens = limited ? WorldLyricsPlan.wordTokens(line, romanized) : WorldLyricsPlan.tokens(line, romanized);
        if (wordOnly) cue = WorldLyricsPresentation.withReveal(cue, WorldLyricsPresentation.Reveal.WORD_BY_WORD);
        // Only use token-index directives if romanization preserved the original timed segments.
        if (romanized && !wordOnly && !cue.accents().isEmpty()) {
            var original = WorldLyricsPlan.tokens(line, false);
            boolean mapped = original.size() == tokens.size();
            for (int index = 0; mapped && index < tokens.size(); index++) {
                var before = original.get(index);
                var after = tokens.get(index);
                mapped = before.startMillis() == after.startMillis() && before.endMillis() == after.endMillis()
                        && before.startsWord() == after.startsWord();
            }
            if (!mapped)
                cue = new WorldLyricsPresentation.Cue(cue.id(), cue.source(), WorldLyricsPresentation.Composition.COEXIST,
                        cue.region(), cue.entrance(), cue.volumetric(), cue.holdMillis(), List.of(), List.of()).withLook(cue.look());
        }
        final var styleCue = cue;
        double prefScale = .052 * preferences.getWorldLyricsSize() * Math.clamp(distance / 6, .35, 43);
        var fitEnds = limited && !wordOnly
                ? fit.groupEnds(tokens, openSky ? Double.POSITIVE_INFINITY : Math.clamp(distance * .43 / prefScale, 42, 130),
                        text -> renderer.width(styled(text, preferences, styleCue))) : List.<Integer>of();
        var tokenIndices = new IdentityHashMap<WorldLyricsPlan.Token, Integer>();
        for (int index = 0; index < tokens.size(); index++) tokenIndices.put(tokens.get(index), index);
        String displayed = tokens.stream().map(WorldLyricsPlan.Token::text).reduce("", (a,b) -> a+" "+b);
        double desiredScale = .052 * preferences.getWorldLyricsSize() * Math.clamp(distance / 6, .35, 43);
        double wrapWidth = Math.clamp(distance * .65 / (desiredScale * 1.16), 80, 240);
        List<WorldLyricsStage.Group> groups;
        List<WorldLyricsLayout.Page> pages;
        if (compact && !wordOnly && cue.composition() != WorldLyricsPresentation.Composition.COEXIST) {
            pages = WorldLyricsLayout.readingPages(tokens, timed, LyricsAnimation.isRightToLeft(displayed), 190,
                    value -> renderer.width(styled(value, preferences, styleCue)));
            var compactGroups = new ArrayList<WorldLyricsStage.Group>();
            for (int pageIndex = 0; pageIndex < pages.size(); pageIndex++) {
                int first = tokens.size(), last = 0;
                for (var row : pages.get(pageIndex).rows()) for (var span : row.spans()) {
                    int tokenIndex = tokenIndices.get(span.token());
                    first = Math.min(first, tokenIndex); last = Math.max(last, tokenIndex + 1);
                }
                compactGroups.add(new WorldLyricsStage.Group(pageIndex, Math.min(first, last), last,
                        WorldLyricsPresentation.Region.BOTTOM, 0, 1));
            }
            groups = List.copyOf(compactGroups);
        } else {
            groups = fitEnds.isEmpty() ? WorldLyricsStage.groups(cue, tokens, preferences.getWorldLyricsPresentationIntensity(), grouping)
                    : WorldLyricsStage.readingGroups(cue, fitEnds);
            if (!wordOnly && !frozen.isEmpty()) {
                var combined = new ArrayList<>(frozen);
                int cutoff = frozen.getLast().endToken();
                for (var group : groups) if (group.endToken() > cutoff) combined.add(new WorldLyricsStage.Group(combined.size(),
                        Math.max(cutoff, group.firstToken()), group.endToken(), group.region(), group.rotationDegrees(), group.scale()));
                groups = List.copyOf(combined);
            }
            pages = new ArrayList<>();
            for (var group : groups) {
                var subset = tokens.subList(group.firstToken(), group.endToken());
                var wrapped = WorldLyricsLayout.plan(subset, timed, LyricsAnimation.isRightToLeft(displayed), wrapWidth,
                        value -> renderer.width(styled(value, preferences, styleCue)));
                var rows = wrapped.stream().flatMap(page -> page.rows().stream()).toList();
                pages.add(new WorldLyricsLayout.Page(rows, rows.stream().mapToDouble(WorldLyricsLayout.Row::width).max().orElse(0),
                        rows.stream().mapToLong(WorldLyricsLayout.Row::startMillis).min().orElse(line.startMillis()),
                        rows.stream().mapToLong(WorldLyricsLayout.Row::endMillis).max().orElse(line.endMillis())));
            }
        }
        double[] phraseOffsets = new double[tokens.size()];
        GradientRange[] wordRanges = new GradientRange[tokens.size()];
        double phraseWidth = 0;
        int wordStart = 0;
        double separator = renderer.width(styled(" ", preferences, styleCue));
        for (int index = 0; index < tokens.size(); index++) {
            var token = tokens.get(index);
            if (index > 0 && token.startsWord()) {
                var range = new GradientRange(phraseOffsets[wordStart], phraseWidth - phraseOffsets[wordStart]);
                Arrays.fill(wordRanges, wordStart, index, range);
                wordStart = index;
                phraseWidth += separator;
            }
            phraseOffsets[index] = phraseWidth;
            phraseWidth += renderer.width(styled(token.text(), preferences, styleCue));
        }
        if (!tokens.isEmpty())
            Arrays.fill(wordRanges, wordStart, tokens.size(),
                    new GradientRange(phraseOffsets[wordStart], phraseWidth - phraseOffsets[wordStart]));
        List<DrawPage> drawn = new ArrayList<>();
        int wordOrdinal = 0;
        for (var page : pages) {
            List<DrawRow> rows = new ArrayList<>();
            for (var row : page.rows()) {
                List<Piece> pieces = new ArrayList<>();
                for (var span : row.spans()) {
                    int ordinal = wordOrdinal++;
                    var token = span.token();
                    var segment = new WorldLyricsExperience.Motion(false);
                    boolean glyphMotion = timed && LyricsAnimation.usesPerGlyphMotion(token.durationMillis());
                    if (!span.rtl()) {
                        for (var glyph : LyricsRenderText.timedGraphemes(span.text(),token.startMillis(),token.endMillis())) {
                            if (glyph.text().isBlank()) continue;
                            double begin = renderer.width(styled(span.text().substring(0,glyph.startIndex()),preferences,styleCue));
                            double end = renderer.width(styled(span.text().substring(0,glyph.endIndex()),preferences,styleCue));
                            double lineFraction = (span.sourceX() + begin) / Math.max(1, span.sourceWidth());
                            long entry = WorldLyricsPresentation.letterStart(token, glyph.startMillis(), timed, lineFraction);
                            pieces.add(new Piece(styled(glyph.text(),preferences,styleCue),token,tokenIndices.get(token),ordinal,span.x()+begin,end-begin,
                                    span.sourceX()+begin,span.sourceWidth(),span.sourceX(),span.width(),(begin+end)/2/Math.max(1,span.width()),false,
                                    span.x()+span.width()/2,segment,glyphMotion ? new WorldLyricsExperience.Motion(true) : null,
                                    glyph.startMillis(),glyph.endMillis(),entry));
                        }
                    } else {
                        // Keep contextual shaping and bidirectional ordering inside the word.
                        long entry = WorldLyricsPresentation.letterStart(token, token.startMillis(), timed,
                                span.sourceX() / Math.max(1, span.sourceWidth()));
                        pieces.add(new Piece(styled(span.text(),preferences,styleCue),token,tokenIndices.get(token),ordinal,span.x(),span.width(),
                            span.sourceX(),span.sourceWidth(),span.sourceX(),span.width(),.5,span.rtl(),span.x()+span.width()/2,segment,null,
                            token.startMillis(),token.endMillis(),entry));
                    }
                }
                rows.add(new DrawRow(List.copyOf(pieces),row.width(),null,0));
            }
            drawn.add(new DrawPage(List.copyOf(rows)));
        }
        return new Layout(List.copyOf(pages),List.copyOf(drawn),timed,List.copyOf(tokens),cue,groups,phraseOffsets,wordRanges,phraseWidth,fit,fitEnds);
    }

    /** Measure the shaped words before probing the terrain. The drawing layout uses the same planner. */
    private static PhraseBounds measure(LyricLine line, ClientPreferences preferences, double distance, int bands) {
        Font renderer = Minecraft.getInstance().font;
        boolean romanized = preferences.isLyricsRomanization() && LyricsRenderText.hasRomanization(line);
        var tokens = WorldLyricsPlan.tokens(line, romanized);
        String displayed = tokens.stream().map(WorldLyricsPlan.Token::text).reduce("", (a, b) -> a + " " + b);
        double scale = .052 * preferences.getWorldLyricsSize() * Math.clamp(distance / 6, .8, 43);
        double available = Math.clamp(distance * .92 / (scale * 1.16), 100, 220);
        var pages = WorldLyricsLayout.scenePages(tokens, WorldLyricsPlan.hasWordTiming(line, romanized),
                LyricsAnimation.isRightToLeft(displayed), available,
                value -> renderer.width(styled(value, preferences)));
        double width = 1.5, height = 1;
        for (var page : pages) {
            width = Math.max(width, page.width() * scale * 1.08);
            height = Math.max(height, page.rows().size() * (renderer.lineHeight + 6) * scale);
        }
        return new PhraseBounds(Math.min(width, distance * .95), height * bands + .45);
    }

    private static PhraseBounds measureGroup(List<LyricLine> lines, LyricLine focus,
                                             ClientPreferences preferences, double distance, int bands) {
        PhraseBounds result = measure(focus, preferences, distance, bands);
        for (LyricLine other : lines) {
            if (other == focus || other.startMillis() >= focus.endMillis()
                    || focus.startMillis() >= other.endMillis()) continue;
            PhraseBounds candidate = measure(other, preferences, distance, bands);
            result = new PhraseBounds(Math.max(result.width, candidate.width),
                    Math.max(result.height, candidate.height));
        }
        return result;
    }

    private static void adaptFutureGroups(SceneLine scene, long now, ClientPreferences preferences) {
        if (scene.compact || scene.layout == null || scene.fit != ReadingFit.FULL
                || scene.cue.composition() == WorldLyricsPresentation.Composition.COEXIST
                || scene.layoutContext.shortGroups() == context.shortGroups()) return;
        var frozen = new ArrayList<WorldLyricsStage.Group>();
        int protectedEnd = 0;
        for (var group : scene.layout.groups) {
            if (scene.groupAnchors.containsKey(group.index()) || scene.layout.tokens.get(group.firstToken()).startMillis() <= now)
                protectedEnd = Math.max(protectedEnd, group.endToken());
        }
        for (var group : scene.layout.groups) if (group.firstToken() < protectedEnd) frozen.add(group);
        if (protectedEnd >= scene.layout.tokens.size()) return;
        scene.layout = layout(scene.cue.source().line(), preferences, scene.requestedDistance, scene.cue, false, context, frozen, scene.fit, scene.openSky);
        scene.layoutContext = context; scene.frozenGroups = List.copyOf(frozen); scene.nextVisibilityCheck = 0;
    }

    /** Revalidate a whole phrase on the real clock, and commit replacements as one transaction. */
    private static boolean preparePlacement(Minecraft client, Vec3 camera, SceneLine scene, long songTime,
                                            ClientPreferences preferences) {
        var saved = new LinkedHashMap<>(scene.groupAnchors);
        Anchor base = scene.base, compact = scene.compactAnchor;
        boolean wasCompact = scene.compact, wasReadable = scene.readable;
        var observed = scene.observedGroups;
        double requestedDistance = scene.requestedDistance;
        long repairFadeAt = scene.repairFadeAt;
        try { return preparePlacementNow(client, camera, scene, songTime, preferences); }
        catch (DeferredSearch deferred) {
            scene.groupAnchors.clear(); scene.groupAnchors.putAll(saved);
            scene.base = base; scene.compactAnchor = compact; scene.compact = wasCompact;
            scene.readable = wasReadable; scene.placementFailed = false;
            scene.nextVisibilityCheck = 0; scene.observedGroups = observed;
            scene.requestedDistance = requestedDistance; scene.repairFadeAt = repairFadeAt;
            scene.recovery.reset();
            return base != null && !saved.isEmpty();
        }
    }

    private static boolean safeRunway(SceneLine scene, int index, Vec3 camera, Anchor anchor, long songTime) {
        long end = scene.cue.source().line().endMillis();
        if (!scene.compact) for (int page = 0; page < scene.layout.groups.size(); page++)
            if (scene.layout.groups.get(page).index() == index) { end = scene.layout.pages.get(page).endMillis(); break; }
        // Retained verses stay in the world; only a currently read chunk needs an approach horizon.
        if (end <= songTime) return true;
        Vec3 to = new Vec3(anchor.x, anchor.y, anchor.z).subtract(camera);
        double distance = to.length();
        double closing = context.speed() * (context.dirX() * to.x + context.dirY() * to.y + context.dirZ() * to.z) / Math.max(.001, distance);
        double horizon = Math.clamp((end - songTime) / 1000.0 * .55 + .1, .2, .85);
        boolean isolated = scene.fit != ReadingFit.FULL;
        double clearance = isolated ? Math.min(2.0, anchor.distance * .65) : Math.clamp(anchor.distance * .8, .12, .8);
        if (isolated) {
            double warning = .18 + .18 * Math.clamp((anchor.distance - 2) / 4, 0, 1);
            return distance >= clearance && !(closing > .85 && (distance - clearance) / closing < warning);
        }
        return distance >= Math.max(clearance, anchor.distance * .18) && !(closing > 4 && distance / closing < horizon);
    }

    private static boolean preparePlacementNow(Minecraft client, Vec3 camera, SceneLine scene, long songTime,
                                            ClientPreferences preferences) {
        if (scene.fit != ReadingFit.FULL)
            return prepareReadingPlacement(client, camera, scene, songTime, preferences);
        if (scene.compact && scene.compactLayout == null)
            scene.compactLayout = layout(scene.cue.source().line(), preferences, scene.requestedDistance, scene.cue, true);
        if (scene.yieldedAt != Long.MIN_VALUE && songTime >= scene.yieldedAt) {
            scene.readable = true;
            return songTime - scene.yieldedAt < 150 && !scene.groupAnchors.isEmpty();
        }
        boolean fullPhrase = scene.cue.composition() == WorldLyricsPresentation.Composition.COEXIST;
        var window = fullPhrase ? WorldLyricsPresentation.fullPhraseWindow(scene.layout.tokens)
                : WorldLyricsPresentation.window(scene.layout.cue, scene.layout.tokens, songTime,
                        preferences.isLyricsAnimations(), scene.wordsStopAt);
        var active = new LinkedHashSet<Integer>();
        for (var group : scene.layout.groups)
            if (groupVisible(scene.layout, group, window, songTime, preferences.isLyricsAnimations())) active.add(group.index());
        if (active.isEmpty()) { scene.readable = true; return false; }
        boolean reposition = preferences.isWorldLyricsReposition();
        boolean changed = !active.equals(scene.observedGroups);
        if (changed) scene.observedGroups = Set.copyOf(active);
        boolean complete = scene.compact ? scene.compactAnchor != null : scene.groupAnchors.keySet().containsAll(active);
        if (!reposition && complete) {
            if (frameClock >= scene.nextVisibilityCheck) { updatePerspective(client, camera, scene, songTime); scene.nextVisibilityCheck = frameClock + 120_000_000L; }
            scene.readable = true; return true;
        }
        if (!reposition && !changed) return !scene.groupAnchors.isEmpty();
        if (!changed && frameClock < scene.nextVisibilityCheck) return complete && !scene.placementFailed;
        scene.nextVisibilityCheck = frameClock + 120_000_000L;
        boolean clear = complete, onScreen = complete;
        if (complete) for (Anchor anchor : scene.groupAnchors.values()) {
            boolean inView = withinView(client, camera, anchor, scene.compact);
            clear &= physicallyFreePanel(client, anchor) && (!inView || visuallyClearPanel(client, camera, anchor));
            onScreen &= inView;
        }
        scene.readable = complete && clear && onScreen;
        updatePerspective(client, camera, scene, songTime);
        // Looking away never pulls an existing phrase back to the crosshair.
        boolean healthy = complete && clear;
        if (healthy) for (var entry : scene.groupAnchors.entrySet())
            healthy &= safeRunway(scene, entry.getKey(), camera, entry.getValue(), songTime);
        boolean readable = healthy && (!fullPhrase || onScreen);
        boolean repair = scene.recovery.evaluate(frameClock, healthy, readable);
        if (readable && !changed) {
            scene.placementFailed = false; scene.repairFadeAt = 0;
            return true;
        }
        if ((!changed || scene.placementFailed) && !repair)
            return complete && !scene.placementFailed;
        if (complete && repair && preferences.isLyricsAnimations()) {
            if (scene.repairFadeAt == 0) scene.repairFadeAt = frameClock;
            if (frameClock - scene.repairFadeAt < 180_000_000L) return true;
        }
        scene.recovery.attempted(frameClock);
        var previous = new LinkedHashMap<>(scene.groupAnchors);
        scene.groupAnchors.clear();
        Vec3 liveCamera = camera;
        var liveContext = context;
        if (scene.searchCamera == null || frameClock - scene.searchAt > 750_000_000L) {
            scene.searchCamera = camera; scene.searchView = cameraDirection(client); scene.searchUp = WorldLyricsRenderHook.cameraUp(client); scene.searchContext = context;
            scene.searchAt = frameClock; scene.searchRays.clear();
        }
        camera = scene.searchCamera; searchView = scene.searchView; context = scene.searchContext;
        placementOwner = scene; placementSongTime = songTime;
        try {
            if (!scene.compact) {
                // Reserve healthy anchors before searching, so a replacement cannot overlap a surviving group.
                for (var entry : previous.entrySet()) {
                    Anchor anchor = entry.getValue();
                    boolean inView = withinView(client, liveCamera, anchor);
                    if (active.contains(entry.getKey()) && physicallyFreePanel(client, anchor)
                            && (!reposition || safeRunway(scene, entry.getKey(), liveCamera, anchor, songTime)
                            && (!inView || visuallyClearPanel(client, liveCamera, anchor))))
                        scene.groupAnchors.put(entry.getKey(), anchor);
                }
                boolean success = true;
                for (int index = 0; index < scene.layout.groups.size(); index++) {
                    var group = scene.layout.groups.get(index);
                    if (!active.contains(group.index()) || scene.groupAnchors.containsKey(group.index())) continue;
                    var page = scene.layout.pages.get(index);
                    double chunkDistance = context.readingDistance(Math.max(.35, (page.endMillis() - page.startMillis()) / 1000.0), distanceRange(client, preferences), preferences.getWorldLyricsDistanceSensitivity());
                    // Smooth the next placement; the currently sung group retains its original scale and anchor.
                    double groupDistance = distanceRange(client, preferences).clamp(scene.requestedDistance * .65 + chunkDistance * .35);
                    double desired = .052 * preferences.getWorldLyricsSize() * Math.clamp(groupDistance / 6, .35, 43);
                    double width = (page.width() + 6) * desired * 1.35;
                    double height = (page.rows().size() * (client.font.lineHeight + 2) + 4) * desired * 1.35;
                    double angle = Math.toRadians(group.rotationDegrees());
                    var bounds = new PhraseBounds(width * Math.abs(Math.cos(angle)) + height * Math.abs(Math.sin(angle)),
                            height * Math.abs(Math.cos(angle)) + width * Math.abs(Math.sin(angle)));
                    Anchor anchor = findBackingAnchor(client, camera, scene, bounds, false);
                    if (anchor == null)
                        anchor = createDirectedAnchor(client, camera, preferences, scene.composition,
                                groupDistance, bounds, scene.cue, group, presentation.hasBacking(scene.cue.source()),
                                scene.lead != null);
                    if (anchor == null) { success = false; break; }
                    scene.groupAnchors.put(group.index(), anchor);
                    scene.requestedDistance = groupDistance;
                }
                if (success) {
                    validateNewAnchors(client, liveCamera, liveContext, scene, previous);
                    scene.searchCamera = null;
                    scene.base = scene.groupAnchors.values().iterator().next();
                    scene.placementFailed = false; scene.readable = true; recoveryCount++;
                    if (scene.repairFadeAt != 0) scene.handoffAt = frameClock; scene.repairFadeAt = 0;
                    yieldRetainedPhrases(scene, songTime);
                    return true;
                }
                if (!reposition && !previous.isEmpty()) {
                    scene.searchCamera = null;
                    scene.readable = false;
                    return !scene.groupAnchors.isEmpty();
                }
                scene.groupAnchors.clear();
            }
            if (scene.compactLayout == null)
                scene.compactLayout = layout(scene.cue.source().line(), preferences, scene.requestedDistance, scene.cue, true);
            Anchor compact = findCompactAnchor(client, camera, scene, preferences);
            if (compact != null) {
                tracePlacement("compact", compact);
                scene.compact = true;
                scene.groupAnchors.put(0, compact);
                validateNewAnchors(client, liveCamera, liveContext, scene, Map.of());
                scene.searchCamera = null;
                scene.compactAnchor = compact; scene.base = compact;
                scene.placementFailed = false; scene.readable = true; recoveryCount++;
                if (scene.repairFadeAt != 0) scene.handoffAt = frameClock;
                scene.repairFadeAt = 0;
                yieldRetainedPhrases(scene, songTime);
                return true;
            }
            scene.groupAnchors.putAll(previous);
            scene.readable = false;
            scene.placementFailed = !scene.readable;
            scene.searchCamera = null;
            return scene.readable;
        } finally {
            placementOwner = null; searchView = null; context = liveContext;
            if (scene.searchCamera == null) scene.searchRays.clear();
        }
    }

    private static void validateNewAnchors(Minecraft client, Vec3 camera, WorldLyricsContext.Snapshot liveContext, SceneLine scene, Map<Integer, Anchor> previous) {
        var owner = placementOwner; var frozenView = searchView; var frozenContext = context;
        placementOwner = null; searchView = null; context = liveContext;
        try {
            for (var entry : scene.groupAnchors.entrySet()) if (previous.get(entry.getKey()) != entry.getValue()) {
                if (!withinView(client, camera, entry.getValue(), scene.compact)
                        || !safeRunway(scene, entry.getKey(), camera, entry.getValue(), placementSongTime)
                        || !freePanel(client, camera, entry.getValue())) {
                    scene.searchCamera = null; scene.searchRays.clear(); throw DeferredSearch.INSTANCE;
                }
            }
        } finally { placementOwner = owner; searchView = frozenView; context = frozenContext; }
    }

    private static void yieldRetainedPhrases(SceneLine owner, long songTime) {
        if (owner.cue.source().line().endMillis() <= songTime) return;
        for (var other : LINES.values()) {
            if (other == owner || other.fit != ReadingFit.FULL || other.cue.source().line().endMillis() > songTime) continue;
            boolean overlaps = owner.compact;
            for (var a : owner.groupAnchors.values()) for (var b : other.groupAnchors.values())
                overlaps |= Math.hypot(a.x - b.x, a.z - b.z) < (a.width + b.width) * .55
                        && Math.abs(a.y - b.y) < (a.height + b.height) * .55;
            if (overlaps && other.yieldedAt == Long.MIN_VALUE) other.yieldedAt = songTime;
        }
    }

    private static boolean prepareReadingPlacement(Minecraft client, Vec3 camera, SceneLine scene, long songTime,
                                                   ClientPreferences preferences) {
        var window = readingWindow(scene, songTime, preferences.isLyricsAnimations(), preferences.getWorldLyricsAnimationSpeed());
        int index = window.newestGroup();
        if (window.firstToken() > window.lastStartedToken() || index >= scene.layout.groups.size()
                || songTime >= scene.wordsStopAt) return !scene.groupAnchors.isEmpty();

        var group = scene.layout.groups.get(index);
        if (group.firstToken() > window.lastStartedToken()) return !scene.groupAnchors.isEmpty();
        var page = scene.layout.pages.get(index);
        if (!scene.groupAnchors.containsKey(index) && songTime < page.endMillis() + 160) {
            Anchor anchor = scene.preparedIndex == index ? scene.preparedAnchor : null;
            if (anchor != null && !reusableReadingAnchor(client, camera, anchor))
                anchor = null;
            if (anchor == null) {
                try { anchor = searchReadingAnchor(client, camera, scene, group, songTime, preferences); }
                catch (DeferredSearch deferred) { /* An occupied scene can still reuse a readable panel. */ }
            }
            if (anchor == null) anchor = reuseReadingAnchor(client, camera, scene);
            if (anchor != null) {
                scene.groupAnchors.put(index, anchor);
                scene.base = anchor;
                scene.readable = true;
                recoveryCount++;
            }
            if (scene.preparedIndex == index) {
                scene.preparedIndex = -1;
                scene.preparedAnchor = null;
            }
        }

        int next = index + 1;
        if (scene.groupAnchors.containsKey(index) && next < scene.layout.groups.size() && scene.preparedIndex != next) {
            var upcoming = scene.layout.groups.get(next);
            long starts = scene.layout.tokens.get(upcoming.firstToken()).startMillis();
            if (starts < scene.wordsStopAt && starts - songTime <= 190) {
                try {
                    Anchor anchor = searchReadingAnchor(client, camera, scene, upcoming, songTime, preferences);
                    if (anchor != null) {
                        scene.preparedIndex = next;
                        scene.preparedAnchor = anchor;
                    }
                } catch (DeferredSearch deferred) { /* Try again while the current group is still readable. */ }
            }
        }
        return !scene.groupAnchors.isEmpty();
    }

    private static Anchor reuseReadingAnchor(Minecraft client, Vec3 camera, SceneLine scene) {
        if (scene.openSky) return null;
        if (reusableReadingAnchor(client, camera, scene.base)) return scene.base;
        for (var other : LINES.reversed().values()) {
            if (other != scene && other.fit != ReadingFit.FULL && reusableReadingAnchor(client, camera, other.base))
                return other.base;
        }
        return null;
    }

    private static boolean reusableReadingAnchor(Minecraft client, Vec3 camera, Anchor anchor) {
        if (anchor == null || !withinView(client, camera, anchor, true) || !physicallyFreePanel(client, anchor)) return false;
        try { return visuallyClearPanel(client, camera, anchor); }
        catch (DeferredSearch deferred) { return false; }
    }

    private static Anchor searchReadingAnchor(Minecraft client, Vec3 camera, SceneLine scene, WorldLyricsStage.Group group,
                                              long songTime, ClientPreferences preferences) {
        if (scene.searchWord != group.index()) {
            scene.searchWord = group.index();
            scene.searchCamera = null;
            scene.nextVisibilityCheck = 0;
        }
        if (frameClock < scene.nextVisibilityCheck) return null;
        var liveContext = context;
        if (scene.searchCamera == null || frameClock - scene.searchAt > 250_000_000L
                || scene.searchCamera.distanceTo(camera) > .75 || scene.searchView.dot(cameraDirection(client)) < .985) {
            scene.searchCamera = camera;
            scene.searchView = cameraDirection(client);
            scene.searchUp = WorldLyricsRenderHook.cameraUp(client);
            scene.searchContext = context;
            scene.searchAt = frameClock;
            scene.searchRays.clear();
        }
        var view = viewpoint(client, camera);
        placementOwner = scene;
        placementSongTime = songTime;
        searchView = scene.searchView;
        context = scene.searchContext;
        try {
            Anchor anchor = findReadingAnchor(client, scene, group, camera, view, preferences);
            scene.nextVisibilityCheck = frameClock + 25_000_000L;
            if (anchor != null) scene.searchCamera = null;
            return anchor;
        } finally {
            placementOwner = null;
            searchView = null;
            context = liveContext;
            if (scene.searchCamera == null) scene.searchRays.clear();
        }
    }

    private static boolean openSkyView(Minecraft client, Vec3 camera, ClientPreferences preferences) {
        Vec3 aim = cameraDirection(client);
        boolean skyPosition = preferences.getWorldLyricsPosition() == WorldLyricsSpace.Position.SKY;
        boolean flying = gliding(client) || context.mode() == WorldLyricsContext.Mode.FLIGHT && context.speed() >= 1.75;
        boolean seesSky = client.level.canSeeSky(BlockPos.containing(camera));
        if (!skyPosition && !flying && aim.y < -.10) return false;
        try {
            Vec3 ahead = camera.add(aim.scale(22));
            if (!clearSight(client, camera, ahead)) return false;
            Vec3 forward = new Vec3(aim.x, 0, aim.z).normalize();
            Vec3 side = new Vec3(-forward.z, 0, forward.x);
            boolean lookingUp = aim.y >= .12 && (seesSky || client.level.canSeeSky(BlockPos.containing(ahead)));
            if (skyPosition || flying || lookingUp) {
                if (roomFit == ReadingFit.FULL) return true;
                // The room estimate settles slowly after leaving a corridor. Check the actual opening.
                Vec3 opening = camera.add(aim.scale(9));
                Vec3 edge = side.scale(3);
                return clearSight(client, camera, opening.add(edge))
                        && clearSight(client, camera, opening.subtract(edge));
            }
            if (!seesSky || aim.y >= .12) return false;

            // A broad horizon can be open even when the player is standing on solid ground.
            side = side.scale(19);
            Vec3 horizon = camera.add(forward.scale(42));
            return clearSight(client, camera, horizon.add(side))
                    && clearSight(client, camera, horizon.subtract(side));
        } catch (DeferredSearch deferred) {
            return false;
        }
    }

    private static boolean isSkyFinalGroup(SceneLine scene, int index) {
        return scene.openSky && scene.layout.groups.size() > 1 && index == scene.layout.groups.size() - 1;
    }

    private static double skyTextSize(SceneLine scene, int index) {
        return !scene.openSky ? 1 : isSkyFinalGroup(scene, index) ? .91 : .82;
    }

    private static Anchor findOpenSkyAnchor(Minecraft client, SceneLine scene, WorldLyricsStage.Group group,
                                            Vec3 liveCamera, WorldLyricsSpatial.Camera view, ClientPreferences preferences) {
        var page = scene.layout.pages.get(group.index());
        var range = distanceRange(client, preferences);
        double textSize = skyTextSize(scene, group.index());
        double desired = .052 * preferences.getWorldLyricsSize() * textSize * Math.clamp(range.maximum() / 6, .35, 24);
        double width = (page.width() + 8) * desired * 1.25;
        double height = page.rows().size() * (client.font.lineHeight + 5) * desired * 1.25;
        int side = isSkyFinalGroup(scene, group.index()) ? 0 : WorldLyricsStage.readingSide(scene.cue, group.index(), true);

        Vec3 camera = scene.searchCamera;
        Vec3 forward = readingDirection(scene, searchDirection(client));
        Vec3 right = forward.cross(scene.searchUp).normalize();
        if (right.lengthSqr() < .001) return null;
        Vec3 up = right.cross(forward).normalize();
        Anchor fallback = null;
        try {
            for (var probe : WorldLyricsRecovery.skyProbes(range, width, height, side)) {
                Anchor anchor = floatingProbeAnchor(camera, forward, right, up, probe);
                if (anchor.distance < range.minimum() - .05 || anchor.distance > range.maximum() + .05
                        || !withinView(client, anchor, true, view)
                        || WorldLyricsSpatial.project(panel(anchor), view).textHeight() < .028
                        || !safeRunway(scene, group.index(), liveCamera, anchor, placementSongTime)) continue;
                boolean separated = separatedWord(scene, group.index(), anchor, view, true);
                if ((!separated && fallback != null) || !freePanel(client, liveCamera, anchor)) continue;
                if (separated) return anchor;
                fallback = anchor;
            }
        } catch (DeferredSearch deferred) {
            if (fallback == null) throw deferred;
        }
        return fallback;
    }

    private static Anchor findReadingAnchor(Minecraft client, SceneLine scene, WorldLyricsStage.Group group,
                                         Vec3 liveCamera, WorldLyricsSpatial.Camera view,
                                         ClientPreferences preferences) {
        if (scene.openSky) return findOpenSkyAnchor(client, scene, group, liveCamera, view, preferences);
        var page = scene.layout.pages.get(group.index());
        var range = distanceRange(client, preferences);
        double speed = scene.layoutContext.speed();
        // Use one distance for the line. A different word changes the slot, not the depth of the composition.
        double distance = range.clamp(Math.max(scene.requestedDistance, 3.8 + speed * .65));
        double desired = .052 * preferences.getWorldLyricsSize() * Math.clamp(distance / 6, .35, 43);
        double width = (page.width() + 8) * desired * 1.25;
        double height = page.rows().size() * (client.font.lineHeight + 5) * desired * 1.25;
        var probes = WorldLyricsRecovery.readingProbes(distance, width, height,
                WorldLyricsStage.readingSide(scene.cue, group.index(), false));

        Vec3 camera = scene.searchCamera;
        Vec3 forward = readingDirection(scene, searchDirection(client));
        Vec3 right = new Vec3(-forward.z, 0, forward.x).normalize();
        if (right.lengthSqr() < .001) right = new Vec3(-1, 0, 0);
        Vec3 up = right.cross(forward).normalize();
        Anchor fallback = null, shortRunway = null;
        boolean shortRunwaySeparated = false;
        int remainingAfterFallback = 20;
        try {
            for (var probe : probes) {
                if (fallback != null && remainingAfterFallback-- == 0) break;
                Anchor candidate = floatingProbeAnchor(camera, forward, right, up, probe);
                if (!withinView(client, candidate, true, view)) continue;
                boolean separated = separatedWord(scene, group.index(), candidate, view, true);
                if (!separated && (fallback != null || shortRunwaySeparated)) continue;
                if (!freePanel(client, liveCamera, candidate)) continue;
                if (safeRunway(scene, group.index(), liveCamera, candidate, placementSongTime)) {
                    if (separated) return candidate;
                    if (fallback == null) fallback = candidate;
                } else if ((shortRunway == null || separated && !shortRunwaySeparated) && liveCamera.distanceTo(new Vec3(candidate.x, candidate.y, candidate.z)) >= 1.4) {
                    shortRunway = candidate;
                    shortRunwaySeparated = separated;
                }
            }
        } catch (DeferredSearch deferred) {
            if (fallback == null && shortRunway == null) throw deferred;
        }
        if (wallPlacementAllowed(preferences)) {
            try {
                Anchor wall = findSurface(client, camera, 1, new PhraseBounds(width, height), distance,
                        preferences.isWorldLyricsCinematic(), Math.max(2.1, Math.min(range.minimum(), distance * .65)), group.index());
                if (wall != null && withinView(client, wall, true, view)
                        && safeRunway(scene, group.index(), liveCamera, wall, placementSongTime)
                        && freePanel(client, liveCamera, wall)
                        && (fallback == null && shortRunway == null
                                || separatedWord(scene, group.index(), wall, view, true))) return wall;
            } catch (DeferredSearch deferred) {
                if (fallback == null && shortRunway == null) throw deferred;
            }
        }
        return shortRunwaySeparated || fallback == null ? shortRunway : fallback;
    }

    private static Vec3 readingDirection(SceneLine scene, Vec3 aim) {
        if (scene.readingHeading == null || !WorldLyricsStage.keepReadingHeading(scene.readingHeading.dot(aim)))
            scene.readingHeading = aim;
        return scene.readingHeading;
    }

    private static boolean separatedWord(SceneLine owner, int index, Anchor candidate, WorldLyricsSpatial.Camera view,
                                         boolean avoidAim) {
        var candidatePanel = panel(candidate);
        var projected = WorldLyricsSpatial.project(candidatePanel, view);
        if (avoidAim && !projected.clearsAim()) return false;
        for (var scene : LINES.values()) for (var entry : scene.groupAnchors.entrySet()) {
            if (scene == owner && entry.getKey() == index) continue;
            var otherPanel = panel(entry.getValue());
            if (WorldLyricsSpatial.worldOverlap(candidatePanel, otherPanel, .12)
                    || WorldLyricsSpatial.overlap(projected, WorldLyricsSpatial.project(otherPanel, view), .18)) return false;
        }
        return true;
    }

    private static boolean wordCovered(SceneLine owner, int index, Anchor anchor, WorldLyricsSpatial.Camera view) {
        var projected = WorldLyricsSpatial.project(panel(anchor), view);
        for (var scene : LINES.values()) {
            if (scene != owner && !takesWordPriority(scene, owner)) continue;
            for (var entry : scene.groupAnchors.entrySet()) {
                if (scene == owner && entry.getKey() <= index) continue;
                if (WorldLyricsSpatial.overlap(projected, WorldLyricsSpatial.project(panel(entry.getValue()), view), .12))
                    return true;
            }
        }
        return false;
    }

    private static double wordCoverageAlpha(SceneLine scene, int index, boolean covered) {
        var previous = scene.wordCoverage.get(index);
        if (previous == null && !covered) return 1;
        double alpha = previous == null ? 1 : previous.alpha();
        if (previous != null) {
            double step = Math.clamp((frameClock - previous.at()) / 160_000_000.0, 0, 1);
            alpha = Math.clamp(alpha + (covered ? -step : step), 0, 1);
        }
        if (alpha == 1 && !covered) scene.wordCoverage.remove(index);
        else scene.wordCoverage.put(index, new WordCoverage(alpha, frameClock));
        return alpha;
    }

    private static boolean takesWordPriority(SceneLine candidate, SceneLine current) {
        long candidateStart = candidate.cue.source().line().startMillis();
        long currentStart = current.cue.source().line().startMillis();
        if (candidateStart != currentStart) return candidateStart > currentStart;
        if (candidate.cue.source().backing() != current.cue.source().backing())
            return !candidate.cue.source().backing();
        return earlierScene(candidate, current);
    }

    /** Find a smaller, ordered world-space phrase inside the visible free space near the camera. */
    private static Anchor findCompactAnchor(Minecraft client, Vec3 camera,
                                            SceneLine scene, ClientPreferences preferences) {
        var pages = scene.compactLayout.pages;
        double desired = .052 * preferences.getWorldLyricsSize() * Math.clamp(scene.requestedDistance / 6, .35, 43);
        double width = pages.stream().mapToDouble(page -> page.width() + 8).max().orElse(80) * desired * 1.25;
        double height = pages.stream().mapToInt(page -> page.rows().size()).max().orElse(1)
                * (client.font.lineHeight + 5) * desired * 1.25;
        Anchor backing = findBackingAnchor(client, camera, scene, new PhraseBounds(width, height), true);
        if (backing != null) return backing;
        var range = distanceRange(client, preferences);
        double desiredDistance = range.clamp(Math.max(scene.requestedDistance, context.speed() * .95));
        var probes = new ArrayList<>(WorldLyricsRecovery.compactProbes(desiredDistance, width, height, range.minimum()));
        for (var emergency : WorldLyricsRecovery.compactProbes(desiredDistance, width, height))
            if (emergency.distance() < range.minimum() - .05) probes.add(emergency);
        Vec3 forward = searchDirection(client);
        Vec3 right = new Vec3(-forward.z, 0, forward.x).normalize();
        if (right.lengthSqr() < .001) right = new Vec3(-1, 0, 0);
        Vec3 up = right.cross(forward).normalize();
        for (var probe : probes) {
            Anchor anchor = floatingProbeAnchor(camera, forward, right, up, probe);
            if (withinView(client, camera, anchor, true) && separated(anchor) && freePanel(client, camera, anchor)) return anchor;
        }
        return null;
    }

    private static Anchor floatingProbeAnchor(Vec3 camera, Vec3 forward, Vec3 right, Vec3 up,
                                             WorldLyricsRecovery.Probe probe) {
        Vec3 center = camera.add(forward.scale(probe.distance())).add(right.scale(probe.side())).add(up.scale(probe.up()));
        center = atDistance(camera, center, probe.distance());
        Vec3 normal = camera.subtract(center).normalize();
        double rotation = Math.atan2(normal.x, normal.z);
        Vec3 across = new Vec3(Math.cos(rotation), 0, -Math.sin(rotation));
        return new Anchor(center.x, center.y, center.z, normal.x, normal.y, normal.z, across.x, across.z,
                (float) rotation, false, probe.width(), probe.height(), 1, camera.distanceTo(center));
    }

    private static Anchor findBackingAnchor(Minecraft client, Vec3 camera, SceneLine scene,
                                            PhraseBounds bounds, boolean compact) {
        if (scene.lead == null || scene.lead.groupAnchors.isEmpty()) return null;
        var range = distanceRange(client, RelayLyricsClient.preferences());
        Anchor lead = scene.lead.groupAnchors.lastEntry().getValue();
        Vec3 leadCenter = new Vec3(lead.x, lead.y, lead.z);
        Vec3 forward = searchDirection(client);
        double distance = Math.max(.18, leadCenter.subtract(camera).dot(forward));
        double ratio = distance / Math.max(.8, scene.requestedDistance);
        double width = Math.min(bounds.width * ratio, distance * .58);
        double height = Math.min(bounds.height * ratio, distance * .30);
        Vec3 right = new Vec3(-forward.z, 0, forward.x).normalize();
        if (right.lengthSqr() < .001) right = new Vec3(-1, 0, 0);
        Vec3 up = right.cross(forward).normalize();
        for (int attempt = 0; attempt < 3; attempt++) {
            double panelHeight = attempt == 0 ? height : height * .78;
            double gap = attempt < 2 ? .57 : .88;
            Vec3 center = leadCenter.subtract(up.scale((lead.height + panelHeight) * gap));
            center = atDistance(camera, center, range.clamp(camera.distanceTo(center)));
            Anchor candidate = new Anchor(center.x, center.y, center.z, lead.normalX, lead.normalY, lead.normalZ,
                    lead.rightX, lead.rightZ, lead.rotationYaw, false, width, panelHeight, 1, camera.distanceTo(center));
            if (withinView(client, camera, candidate, compact) && separated(candidate) && freePanel(client, camera, candidate))
                return candidate;
        }
        return null;
    }

    private static Vec3 searchDirection(Minecraft client) { return searchView != null ? searchView : cameraDirection(client); }

    private static Vec3 cameraDirection(Minecraft client) { return WorldLyricsRenderHook.cameraForward(client); }

    /** Test the actual viewpoint, independently of the flight trajectory used for new placements. */
    private static boolean withinView(Minecraft client, Vec3 camera, Anchor anchor) {
        return withinView(client, camera, anchor, false);
    }

    private static boolean withinView(Minecraft client, Vec3 camera, Anchor anchor, boolean compact) {
        return withinView(client, anchor, compact, viewpoint(client, camera));
    }

    private static boolean withinView(Minecraft client, Anchor anchor, boolean compact, WorldLyricsSpatial.Camera view) {
        var range = distanceRange(client, RelayLyricsClient.preferences());
        if ((!compact && anchor.distance < range.minimum() - .05) || anchor.distance > range.maximum() + .05) return false;
        var geometry = panel(anchor);
        var projection = WorldLyricsSpatial.project(geometry, view);
        if (!(projection.visible() && !projection.nearClipped() && projection.depth() < 384
                && projection.minX() >= -.98 && projection.maxX() <= .98
                && projection.minY() >= -.98 && projection.maxY() <= .98
                && WorldLyricsSpatial.facingAlpha(geometry, view) > .55)) return false;
        if (!anchor.wall && !anchor.ground) return true;
        // A floor panel's thickness doesn't make the glyph face easier to read at a grazing angle.
        var face = new WorldLyricsSpatial.Panel(geometry.center(), geometry.basis(), anchor.width * .8, anchor.height * .7, .01, 0);
        return WorldLyricsSpatial.project(face, view).textHeight() >= .022;
    }

    private static WorldLyricsSpatial.Vector vector(Vec3 value) { return new WorldLyricsSpatial.Vector(value.x, value.y, value.z); }
    private static WorldLyricsSpatial.Panel panel(Anchor anchor) {
        var basis = WorldLyricsSpatial.Basis.facing(new WorldLyricsSpatial.Vector(anchor.normalX, anchor.normalY, anchor.normalZ),
                new WorldLyricsSpatial.Vector(anchor.rightX, anchor.rightY, anchor.rightZ));
        return new WorldLyricsSpatial.Panel(new WorldLyricsSpatial.Vector(anchor.x, anchor.y, anchor.z), basis,
                anchor.width, anchor.height, Math.clamp(RelayLyricsClient.preferences().getWorldLyricsDepth() * Math.max(.5, anchor.distance / 6), .025, .65) * 2 + .04, Math.min(anchor.height * .10, .16));
    }
    private static WorldLyricsSpatial.Camera viewpoint(Minecraft client, Vec3 camera) {
        return new WorldLyricsSpatial.Camera(vector(camera), vector(placementOwner == null ? cameraDirection(client) : searchDirection(client)),
                vector(placementOwner != null && placementOwner.searchUp != null ? placementOwner.searchUp : WorldLyricsRenderHook.cameraUp(client)),
                WorldLyricsRenderHook.cameraFov(client), client.getWindow().getScreenWidth() / (double) Math.max(1, client.getWindow().getScreenHeight()), .08);
    }
    private static boolean sung(SceneLine scene, int index, long now) {
        if (scene.compact) return scene.cue.source().line().startMillis() <= now && now < scene.cue.source().line().endMillis();
        for (int i = 0; i < scene.layout.groups.size(); i++) if (scene.layout.groups.get(i).index() == index) {
            var page = scene.layout.pages.get(i); return page.startMillis() <= now && now < page.endMillis();
        }
        return false;
    }
    private static int perspectivePriority(SceneLine scene, int index, long now) {
        return (sung(scene, index, now) ? 0 : 2) + (scene.cue.source().backing() ? 1 : 0);
    }

    private static boolean earlierScene(SceneLine first, SceneLine second) {
        for (var scene : LINES.values()) {
            if (scene == first) return true;
            if (scene == second) return false;
        }
        return false;
    }

    /** On a camera turn, fade lower-priority conflicting groups instead of dragging the sung words. */
    private static void updatePerspective(Minecraft client, Vec3 camera, SceneLine scene, long now) {
        var view = viewpoint(client, camera);
        for (var entry : scene.groupAnchors.entrySet()) {
            int rank = perspectivePriority(scene, entry.getKey(), now);
            var projected = WorldLyricsSpatial.project(panel(entry.getValue()), view);
            boolean conflict = false;
            for (var other : LINES.values()) for (var target : other.groupAnchors.entrySet()) {
                if (other == scene && target.getKey().equals(entry.getKey())) continue;
                int otherRank = perspectivePriority(other, target.getKey(), now);
                boolean before = other == scene ? target.getKey() < entry.getKey() : earlierScene(other, scene);
                if ((otherRank < rank || otherRank == rank && before) && WorldLyricsSpatial.overlap(projected,
                        WorldLyricsSpatial.project(panel(target.getValue()), view), .18)) conflict = true;
            }
            if (conflict) {
                if (!Objects.equals(scene.conflictRanks.put(entry.getKey(), rank), rank))
                    scene.conflictSince.remove(entry.getKey());
                scene.conflictSince.putIfAbsent(entry.getKey(), frameClock); scene.conflictRecoverySince.remove(entry.getKey()); perspectiveConflicts++;
            } else if (scene.conflictSince.remove(entry.getKey()) != null) {
                if (Objects.equals(scene.conflictRanks.remove(entry.getKey()), rank))
                    scene.conflictRecoverySince.put(entry.getKey(), frameClock);
            }
        }
    }

    private static WorldLyricsPresentation.Window readingWindow(SceneLine scene, long now, boolean animate, double speed) {
        return WorldLyricsPresentation.readingWindow(scene.layout.tokens, scene.layout.fitEnds, now, animate, scene.wordsStopAt, speed);
    }

    /** Fit the line to its first usable position before drawing it. */
    private static boolean limitSceneFit(SceneLine scene, ReadingFit limit, double distance, ClientPreferences preferences) {
        ReadingFit fit = scene.fit.limitTo(limit);
        if (fit == scene.fit) return false;
        scene.fit = fit;
        scene.requestedDistance = distance;
        scene.groupAnchors.clear();
        scene.conflictSince.clear();
        scene.conflictRanks.clear();
        scene.conflictRecoverySince.clear();
        scene.wordCoverage.clear();
        scene.base = null;
        scene.compact = false;
        scene.compactAnchor = null;
        scene.compactLayout = null;
        scene.searchCamera = null;
        scene.searchWord = -1;
        scene.preparedIndex = -1;
        scene.preparedAnchor = null;
        scene.nextVisibilityCheck = 0;
        scene.handoffAt = scene.repairFadeAt = 0;
        scene.layout = layout(scene.cue.source().line(), preferences, distance, scene.cue, false,
                scene.layoutContext, List.of(), fit, scene.openSky);
        return true;
    }

    private static void renderLine(PoseStack matrices, MultiBufferSource consumers, Vec3 cameraPos,
                                   SceneLine scene, long now,
                                   ClientPreferences preferences, WorldLyricsPlan.PlayerReaction reaction, boolean allowPlacement) {
        Minecraft client = Minecraft.getInstance();
        Font renderer = client.font;
        if (!leavingForPause && allowPlacement) {
            adaptFutureGroups(scene, now, preferences);
            if (!scene.fitLocked && scene.cue.composition() == WorldLyricsPresentation.Composition.COEXIST)
                limitSceneFit(scene, roomFit, scene.requestedDistance, preferences);
            if (!preparePlacement(client, cameraPos, scene, now, preferences)) return;
            if (!scene.fitLocked && scene.cue.composition() == WorldLyricsPresentation.Composition.COEXIST
                    && scene.base != null && now < scene.cue.source().line().endMillis()) {
                double actual = cameraPos.distanceTo(new Vec3(scene.base.x, scene.base.y, scene.base.z));
                if (limitSceneFit(scene, ReadingFit.atDistance(actual, scene.fit), actual, preferences)
                        && !preparePlacement(client, cameraPos, scene, now, preferences)) return;
                scene.fitLocked = scene.base != null;
            }
        }
        Layout layout = scene.compact ? scene.compactLayout : scene.layout;
        boolean animate = preferences.isLyricsAnimations();
        double yieldProgress = scene.yieldedAt == Long.MIN_VALUE ? 0 : Math.clamp((now - scene.yieldedAt) / 150.0, 0, 1);
        double lineExit = layout.fit != ReadingFit.FULL ? 0 : WorldLyricsPresentation.exitProgress(layout.cue, now);
        double exitProgress = animate ? Math.max(pauseProgress, Math.max(yieldProgress, lineExit)) : 0;
        boolean pairedBacking = scene.lead != null;
        double lineHeight = renderer.lineHeight + 5;
        double strength = WorldLyricsExperience.spicyStrength(preferences.getWorldLyricsStyle(),
                preferences.getWorldLyricsPresentationIntensity(), preferences.getWorldLyricsAnimationIntensity(), animate);
        boolean limited = layout.fit != ReadingFit.FULL;
        boolean wordOnly = layout.fit == ReadingFit.WORD;
        boolean squareOutline = preferences.getLyricsFont() == LyricsFont.MINECRAFT
                && (!preferences.isWorldLyricsGeminiFont() || layout.cue.look().font() == WorldLyricsPresentation.SongFont.DEFAULT);
        boolean shaderActive = CompatibilityManager.worldShaderActive();
        boolean fullPhrase = layout.cue.composition() == WorldLyricsPresentation.Composition.COEXIST;
        var window = limited ? readingWindow(scene, now, animate, preferences.getWorldLyricsAnimationSpeed()) : fullPhrase ? WorldLyricsPresentation.fullPhraseWindow(layout.tokens)
                : scene.compact && !wordOnly ? WorldLyricsRecovery.compactWindow(layout.cue, layout.tokens, now)
                : WorldLyricsPresentation.window(layout.cue, layout.tokens, now, animate, scene.wordsStopAt);
        Set<Integer> emittedWords = new HashSet<>();
        WorldLyricsLights.Point activeLight = null;
        var wordView = limited ? viewpoint(client, cameraPos) : null;
        int maxGroups = wordOnly ? WorldLyricsPresentation.MAX_VISIBLE_WORD_GROUPS : WorldLyricsPresentation.MAX_VISIBLE_CHUNKS;
        int firstPage = limited ? Math.max(0, window.newestGroup() - maxGroups + 1)
                : scene.compact ? WorldLyricsPlacement.sungPageIndex(layout.pages, now) : 0;
        // Timed page selection has a crossfade lead. Keep the old compact page until the next can draw.
        while (!limited && firstPage > 0 && !groupVisible(layout, layout.groups.get(firstPage), window, now, animate)) firstPage--;
        int lastPage = limited ? Math.min(window.newestGroup() + 1, layout.pages.size())
                : scene.compact ? Math.min(firstPage + 1, layout.pages.size()) : layout.pages.size();
        for (int pageOffset = 0; pageOffset < lastPage - firstPage; pageOffset++) {
            int pageIndex = limited ? lastPage - 1 - pageOffset : firstPage + pageOffset;
            var group = layout.groups.get(pageIndex);
            if (!groupVisible(layout, group, window, now, animate)) continue;
            var page = layout.pages.get(pageIndex);
            Anchor anchor = scene.compact ? scene.compactAnchor : scene.groupAnchors.get(group.index());
            if (anchor == null || !physicallyFreePanel(client, anchor)) continue;
            boolean covered = limited && wordCovered(scene, group.index(), anchor, wordView);
            if (covered && !animate) continue;
            double distance = cameraPos.distanceTo(new Vec3(anchor.x, anchor.y, anchor.z));
            if (distance > 384 || distance < (limited ? 1.05 : .08)) continue;
            double visibility = (pairedBacking ? .94 : 1)
                    * (scene.compact ? 1 : Math.clamp(distance / Math.max(1, anchor.distance * .35), 0, 1));
            if (limited && animate) visibility *= wordCoverageAlpha(scene, group.index(), covered);
            visibility *= 1 - LyricsAnimation.smoother(Math.max(yieldProgress, pauseProgress));
            visibility *= WorldLyricsSpatial.facingAlpha(panel(anchor), viewpoint(client, cameraPos));
            int rank = perspectivePriority(scene, scene.compact ? 0 : group.index(), now);
            if (animate && scene.repairFadeAt > 0 && rank >= 2)
                visibility *= 1 - LyricsAnimation.smoother((frameClock - scene.repairFadeAt) / 180_000_000.0);
            if (animate && scene.handoffAt > 0) visibility *= LyricsAnimation.smoother((frameClock - scene.handoffAt) / 240_000_000.0);
            int anchorIndex = scene.compact ? 0 : group.index();
            long conflictAt = Objects.equals(scene.conflictRanks.get(anchorIndex), rank)
                    ? scene.conflictSince.getOrDefault(anchorIndex, Long.MIN_VALUE) : Long.MIN_VALUE;
            if (conflictAt != Long.MIN_VALUE) visibility *= 1 - LyricsAnimation.smoother((frameClock - conflictAt) / 180_000_000.0);
            long recoveredAt = rank == 0 ? Long.MIN_VALUE : scene.conflictRecoverySince.getOrDefault(anchorIndex, Long.MIN_VALUE);
            if (recoveredAt != Long.MIN_VALUE) visibility *= LyricsAnimation.smoother((frameClock - recoveredAt) / 180_000_000.0);
            if (visibility < .005) continue;
            int piecesBefore = drawnPieces;
            if (animate && cinematicHandoffAt > 0)
                visibility *= LyricsAnimation.smoother((frameClock - cinematicHandoffAt) / 240_000_000.0);
            double panelTextWidth = page.width();
            double pixelScale = scene.compact
                    ? Math.min(anchor.width * .88 / Math.max(1, (panelTextWidth + 8) * 1.22),
                            anchor.height * .88 / Math.max(1, page.rows().size() * lineHeight * 1.22))
                    : contextualPixelScale(page, anchor, preferences.getWorldLyricsSize() * skyTextSize(scene, group.index()),
                            pairedBacking, lineHeight);
            if (!limited) {
                double accentFit = layout.cue.accents().stream().filter(accent -> accent.tokenIndex() >= group.firstToken()
                        && accent.tokenIndex() < group.endToken()).mapToDouble(WorldLyricsPresentation.Accent::scale).max().orElse(1);
                pixelScale *= group.scale() / Math.max(1, accentFit / (scene.compact ? 1 : 1.18));
            }
            double angle = limited ? 0 : Math.toRadians(group.rotationDegrees()), cos = Math.cos(angle), sin = Math.sin(angle);
            double extrude = Math.clamp(scene.composition.depth() * Math.clamp(anchor.distance / 6, .5, 43), 0, .65);
            int light = Math.clamp(client.level.getMaxLocalRawBrightness(BlockPos.containing(anchor.x, anchor.y, anchor.z)), 0, 15);
            int sideColor = materialSideColor(preferences, scene.composition, light);
            var drawPage = layout.drawn.get(pageIndex);
            for (int rowIndex = 0; rowIndex < drawPage.rows.size(); rowIndex++) {
                DrawRow row = drawPage.rows.get(rowIndex);
                double rowY = ((drawPage.rows.size() - 1) / 2.0 - rowIndex) * lineHeight * pixelScale;
                for (Piece piece : row.pieces) {
                    long entry = animate ? piece.entryStart : piece.token.startMillis();
                    double letterX = layout.phraseOffsets[piece.tokenIndex] + piece.fragmentX;
                    var appearance = limited
                            ? WorldLyricsPresentation.sampleReadingLetter(layout.cue, window, piece.tokenIndex, now, animate,
                                    shaderActive, layout.phraseOffsets, layout.phraseWidth, letterX)
                            : fullPhrase ? WorldLyricsPresentation.samplePhraseLetter(layout.cue, layout.tokens, window,
                                    piece.tokenIndex, now, animate, letterX / Math.max(1, layout.phraseWidth))
                            : WorldLyricsPresentation.sampleLetter(layout.cue, window, piece.tokenIndex,
                                    now, entry, piece.token.endMillis(), animate);
                    double pieceAlpha = visibility * appearance.alpha();
                    if (pieceAlpha <= .016) continue;
                    int word = layout.timed ? piece.tokenIndex : piece.wordIndex;
                    while (layout.timed && word > 0 && !layout.tokens.get(word).startsWord()) word--;
                    if (!emittedWords.contains(word)) {
                        if (!fullPhrase && visibleWords >= WorldLyricsPresentation.MAX_WORDS) continue;
                        emittedWords.add(word); visibleWords++;
                    }
                    piece.segmentMotion.sample(piece.token.startMillis(), piece.token.endMillis(), now, frameClock, animate && layout.timed);
                    if (piece.glyphMotion != null) piece.glyphMotion.sample(piece.glyphStart, piece.glyphEnd, now, frameClock, animate && layout.timed);
                    double segmentScale = 1 + (piece.segmentMotion.scale() - 1) * strength;
                    double glyphScale = 1 + ((piece.glyphMotion == null ? 1 : piece.glyphMotion.scale()) - 1) * strength;
                    // Timed syllables share one word. Their animation can lift and stretch
                    // vertically, but each fragment must stay inside its measured horizontal slot.
                    boolean splitWord = layout.timed && word + 1 < layout.tokens.size()
                            && !layout.tokens.get(word + 1).startsWord();
                    double center = (splitWord ? piece.x + piece.width / 2 - row.width / 2
                            : piece.segmentCenter - row.width / 2
                                    + (piece.x + piece.width / 2 - piece.segmentCenter) * segmentScale) * pixelScale;
                    double exitScale = 1, exitY = 0, exitDepth = 0;
                    if (exitProgress > 0) {
                        double position = (piece.x + piece.width / 2) / Math.max(1, row.width);
                        var exit = WorldLyricsPresentation.exitMotion(layout.cue, exitProgress, piece.rtl ? 1 - position : position);
                        exitScale = shaderActive ? WorldLyricsPresentation.shaderExitScale(exitProgress) : exit.scale();
                        exitY = exit.offsetY();
                        exitDepth = exit.offsetDepth();
                    }
                    float scale = (float) (pixelScale * segmentScale * glyphScale * appearance.scale() * exitScale);
                    float horizontalScale = splitWord ? (float) (pixelScale * exitScale * appearance.scale()) : scale;
                    double elevation = (piece.segmentMotion.elevation()
                            + (piece.glyphMotion == null ? 0 : piece.glyphMotion.elevation())) * renderer.lineHeight * pixelScale * strength;
                    double localSide = center * (limited ? appearance.scale() : 1)
                            + appearance.offsetX() * lineHeight * pixelScale;
                    // Independent blocks already express the staircase; retain only its entry movement locally.
                    double localOffset = appearance.offsetY() + exitY;
                    if (scene.cue.composition() == WorldLyricsPresentation.Composition.STAIRCASE)
                        localOffset += (WorldLyricsPresentation.groupIndex(scene.cue, piece.tokenIndex) % WorldLyricsPresentation.MAX_GROUPS) * .45;
                    double localUp = rowY + localOffset * lineHeight * pixelScale + renderer.lineHeight * scale / 2 + elevation;
                    double side = localSide * cos - localUp * sin;
                    double up = localSide * sin + localUp * cos;
                    matrices.pushPose();
                    double depthLimit = depthMotionLimit(anchor);
                    double depthOffset = Math.clamp((appearance.offsetDepth() + exitDepth) * lineHeight * pixelScale, -depthLimit, depthLimit);
                    place(matrices, cameraPos, anchor, side, up, depthOffset);
                    matrices.mulPose(new org.joml.Matrix4f().rotation(new Quaternionf().rotateZ((float) angle)));
                    matrices.scale(horizontalScale, -scale, scale);
                    double progress = LyricsAnimation.timedProgress(now, piece.token.startMillis(), piece.token.endMillis());
                    double boundary = (piece.rtl ? 1 - progress : progress) * piece.fragmentWidth - piece.fragmentX - piece.width / 2;
                    int face = appearance.color() < 0 ? preferences.getWorldLyricsFaceColor() : appearance.color();
                    double firstEdge = piece.rtl ? 1 - (piece.fragmentX + piece.width) / Math.max(1, piece.fragmentWidth)
                            : piece.fragmentX / Math.max(1, piece.fragmentWidth);
                    var sungColor = preferences.getSungTextColor();
                    if (progress > firstEdge && (layout.timed || wordOnly || sungColor.mode() != SungTextColor.Mode.DEFAULT)) {
                        double offset = layout.phraseOffsets[piece.tokenIndex], total = layout.phraseWidth;
                        if (sungColor.scope() == SungTextColor.Scope.WORD) {
                            var range = layout.wordRanges[piece.tokenIndex];
                            if (layout.timed) {
                                offset -= range.start;
                                total = range.width;
                            } else {
                                offset = piece.rtl ? piece.wordOffset + piece.wordWidth - piece.fragmentWidth : -piece.wordOffset;
                                total = piece.wordWidth;
                            }
                        }
                        face = preferences.sungGlyphColor(face, progress, piece.fragmentX, piece.width,
                                piece.fragmentWidth, piece.rtl, offset, total);
                    }
                    double pieceDepth = (!scene.compact || wordOnly) && (preferences.isWorldLyricsVolumetric() || appearance.volumetric())
                            ? extrude * exitScale : 0;
                    boolean mesh = pieceDepth > .001 && WorldTextMesh.drawBudgeted(piece.text,
                            matrices.last().pose(), consumers, (float) (-piece.width / 2), (float) (pieceDepth / scale),
                            face, sideColor, (float) (pieceAlpha * (layout.timed ? progress <= 0 ? .46 : progress >= 1 ? .82 : 1 : 1)),
                            layout.timed && progress > 0 && progress < 1 ? (float) boundary : null,
                            piece.rtl, preferences.getWorldLyricsStyle() == WorldLyricsExperience.Style.SCULPTED, frameBudget);
                    drawnPieces++; if (mesh) drawnMeshes++;
                    if (strength > 0 && (Math.abs(segmentScale - 1) > .015 || Math.abs(glyphScale - 1) > .015 || Math.abs(elevation) > .01)) spicyMotionPieces++;
                    matrices.translate(0, 0, pieceDepth / scale + .025);
                    if (WorldLyricsLights.available() && preferences.isWorldLyricsTerrainLight() && layout.timed
                            && now >= piece.glyphStart && now < piece.glyphEnd && pieceAlpha > .05) {
                        double illumination = preferences.getWorldLyricsLuminance() * Math.min(1, pieceAlpha);
                        activeLight = new WorldLyricsLights.Point(anchor.x + anchor.rightX * side + anchor.normalY * anchor.rightZ * up + anchor.normalX * pieceDepth,
                                anchor.y + (anchor.normalZ * anchor.rightX - anchor.normalX * anchor.rightZ) * up + anchor.normalY * pieceDepth,
                                anchor.z + anchor.rightZ * side - anchor.normalY * anchor.rightX * up + anchor.normalZ * pieceDepth, illumination);
                    }
                    if (preferences.isWorldLyricsOutline())
                        WorldLyricsOutline.draw(piece.text, matrices.last().pose(), consumers, (float) (-piece.width / 2),
                                preferences.getWorldLyricsOutlineColor(), pieceAlpha, squareOutline);
                    drawFace(renderer, piece, layout.timed && !wordOnly, progress, boundary, pieceAlpha, face, false, matrices, consumers);
                    matrices.popPose();
                }
            }
            if (drawnPieces != piecesBefore && visibility >= .15 && Boolean.getBoolean("lyrica.worldContextSelfTest")) {
                PROBE_SUBMITTED.add(anchor);
                if (limited && pageIndex == window.newestGroup() && now < scene.wordsStopAt
                        && now < scene.cue.source().line().endMillis()) newestGroupDrawn = true;
            }
        }
        if (activeLight != null) WorldLyricsLights.offer(activeLight);
    }

    private static double contextualPixelScale(WorldLyricsLayout.Page page, Anchor anchor, double size, boolean backing,
                                               double lineHeight) {
        double desired = .052 * size * Math.clamp(anchor.distance / 6, .35, 24) * (backing ? .88 : 1);
        // Candidate bounds already reserve animation space. Subtracting a fixed half-block here makes
        // a short lyric on a wall or floor collapse to a few pixels.
        double fitX = anchor.width / Math.max(1, (page.width() + 6) * 1.20);
        double fitY = anchor.height / Math.max(1, page.rows().size() * lineHeight * 1.20);
        return Math.min(desired, Math.min(fitX, fitY));
    }

    private static boolean groupVisible(Layout layout, WorldLyricsStage.Group group, WorldLyricsPresentation.Window window,
                                        long now, boolean animate) {
        for (int index = group.firstToken(); index < group.endToken(); index++)
            if ((layout.fit != ReadingFit.FULL ? WorldLyricsPresentation.sampleReading(layout.cue, window, index, now, animate)
                    : WorldLyricsPresentation.sample(layout.cue, layout.tokens, window, index, now, animate)).alpha() > .016) return true;
        return false;
    }

    private static void pruneGroupAnchors(SceneLine scene, long now, boolean animate, double speed) {
        if (scene.layout == null || scene.compact) return;
        var window = scene.fit != ReadingFit.FULL ? readingWindow(scene, now, animate, speed)
                : scene.layout.cue.composition() == WorldLyricsPresentation.Composition.COEXIST
                ? WorldLyricsPresentation.fullPhraseWindow(scene.layout.tokens)
                : WorldLyricsPresentation.window(scene.layout.cue, scene.layout.tokens, now, animate, scene.wordsStopAt);
        scene.groupAnchors.keySet().removeIf(index ->
                index >= scene.layout.groups.size() || !groupVisible(scene.layout, scene.layout.groups.get(index), window, now, animate));
        scene.wordCoverage.keySet().retainAll(scene.groupAnchors.keySet());
    }

    private static void drawFace(Font renderer, Piece piece, boolean timed, double progress,
                                 double boundary, double visibility, int rgb, boolean shadow,
                                 PoseStack matrices, MultiBufferSource consumers) {
        boolean partial = timed && progress > 0 && progress < 1;
        boolean intersects = boundary > -piece.width / 2 - 1 && boundary < piece.width / 2 + 1;
        int alpha = Math.clamp((int) Math.round(255 * visibility), 0, 255);
        int color = alpha << 24 | rgb & 0xFFFFFF;
        if (partial && intersects) {
            WorldTextFill.draw(renderer, piece.text, (float) (-piece.width / 2), color, shadow,
                    matrices.last().pose(), consumers, (float) boundary, piece.rtl);
            return;
        }
        boolean filled = piece.rtl ? boundary <= -piece.width / 2 : boundary >= piece.width / 2;
        double opacity = !timed ? 1 : progress >= 1 ? .82 : partial && filled ? 1 : .46;
        alpha = Math.clamp((int) Math.round(alpha * opacity), 0, 255);
        if (alpha >= 4) FontCompat.drawInBatch(renderer, piece.text, (float) (-piece.width / 2), 0, alpha << 24 | rgb & 0xFFFFFF,
                shadow, matrices.last().pose(), consumers, Font.DisplayMode.NORMAL,
                0, LightTexture.FULL_BRIGHT);
    }

    private static int materialSideColor(ClientPreferences preferences, WorldLyricsDirector.Composition composition,
                                         int worldLight) {
        int color = switch (preferences.getWorldLyricsMaterial()) {
            case STONE -> 0x817D76;
            case METAL -> 0x8A9BAC;
            case SOFT_LIGHT -> 0x86B9D1;
            case AUTO -> composition.preset() == WorldLyricsDirector.Preset.MONOLITH ? 0x817D76
                    : composition.preset() == WorldLyricsDirector.Preset.MURAL ? 0x8A9BAC
                    : preferences.getWorldLyricsSideColor();
        };
        if (!preferences.isWorldLyricsWorldLight()
                || preferences.getWorldLyricsMaterial() == WorldLyricsDirector.Material.SOFT_LIGHT) return color;
        double light = .52 + .48 * worldLight / 15.0;
        int r = (int) Math.round((color >> 16 & 255) * light);
        int g = (int) Math.round((color >> 8 & 255) * light);
        int b = (int) Math.round((color & 255) * light);
        return r << 16 | g << 8 | b;
    }

    private static void drawContactShadow(Font renderer, Piece piece, Vec3 camera, Anchor anchor,
                                          double side, float scale, double visibility,
                                          PoseStack matrices, MultiBufferSource consumers) {
        // The ground was measured once when this anchor was created. Keep the projected text just above it.
        double floor = anchor.y - anchor.height * .5 - .20;
        matrices.pushPose();
        matrices.translate(anchor.x + anchor.rightX * side - camera.x, floor + .025 - camera.y,
                anchor.z + anchor.rightZ * side - camera.z);
        matrices.mulPose(new org.joml.Matrix4f().rotation(new Quaternionf().rotateY(anchor.rotationYaw).rotateX((float) (-Math.PI / 2))));
        matrices.scale(scale, -scale, scale);
        int alpha = Math.clamp((int) Math.round(85 * visibility), 0, 140);
        FontCompat.drawInBatch(renderer, piece.text, (float) (-piece.width / 2), 0, alpha << 24 | 0x101015, false,
                matrices.last().pose(), consumers, Font.DisplayMode.NORMAL,
                0, LightTexture.FULL_BRIGHT);
        matrices.popPose();
    }

    private static void place(PoseStack matrices, Vec3 camera, Anchor anchor, double side, double up, double depth) {
        // Anchor axes are orthonormal. Avoid constructing projection envelopes for every drawn glyph.
        double upX = anchor.normalY * anchor.rightZ - anchor.normalZ * anchor.rightY;
        double upY = anchor.normalZ * anchor.rightX - anchor.normalX * anchor.rightZ;
        double upZ = anchor.normalX * anchor.rightY - anchor.normalY * anchor.rightX;
        matrices.translate(anchor.x + anchor.rightX * side + upX * up + anchor.normalX * depth - camera.x,
                anchor.y + anchor.rightY * side + upY * up + anchor.normalY * depth - camera.y,
                anchor.z + anchor.rightZ * side + upZ * up + anchor.normalZ * depth - camera.z);
        matrices.mulPose(new org.joml.Matrix4f().m00((float) anchor.rightX).m01((float) anchor.rightY).m02((float) anchor.rightZ)
                .m10((float) upX).m11((float) upY).m12((float) upZ)
                .m20((float) anchor.normalX).m21((float) anchor.normalY).m22((float) anchor.normalZ));
    }

    private static void renderInterlude(PoseStack matrices, MultiBufferSource consumers, Vec3 camera,
                                       WorldLyricsPlan.Interlude gap, long now, ClientPreferences preferences) {
        var renderer = Minecraft.getInstance().font;
        Anchor anchor = interludeAnchor;
        for (int i = 0; i < 3; i++) {
            double lift = preferences.isLyricsAnimations() ? LyricsAnimation.interludeDotLift(gap.progress(now), i) * .12 : 0;
            double offset = (i - 1) * .35;
            double alpha = gap.visibility(now) * (.34 + .66 * LyricsAnimation.interludeDotFill(gap.progress(now), i));
            if (Math.round(255 * alpha) < 4) continue;
            matrices.pushPose();
            place(matrices, camera, anchor, offset, .55 + lift, 0);
            matrices.scale(BASE_PIXEL_SCALE, -BASE_PIXEL_SCALE, BASE_PIXEL_SCALE);
            Component dot = styled("\u2022", preferences);
            FontCompat.drawInBatch(renderer, dot, -renderer.width(dot) / 2F, 0, (int) Math.round(255 * alpha) << 24 | 0xFFFFFF,
                    preferences.isLyricsTextShadow(), matrices.last().pose(), consumers,
                    Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
            matrices.popPose();
        }
    }

    private static Component styled(String value, ClientPreferences preferences) {
        LyricsFont font = preferences.getLyricsFont();
        LyricsFontWeight weight = preferences.getLyricsFontWeight();
        Component text = Component.literal(value);
        return weight.syntheticBold(font)
                ? PlatformTextStyles.withFontBold(text, font.id())
                : PlatformTextStyles.withFont(text, font.id());
    }

    private static Component styled(String value, ClientPreferences preferences, WorldLyricsPresentation.Cue cue) {
        var font = cue.look().font();
        if (!preferences.isWorldLyricsGeminiFont() || font == WorldLyricsPresentation.SongFont.DEFAULT)
            return styled(value, preferences);
        return PlatformTextStyles.withFont(Component.literal(value), net.minecraft.resources.Identifier.parse(font.resource));
    }

    /** Resolve a requested screen region once; the resulting anchor never follows the camera. */
    private static Anchor createDirectedAnchor(Minecraft client, Vec3 camera, ClientPreferences preferences,
                                               WorldLyricsDirector.Composition composition, double requestedDistance,
                                               PhraseBounds bounds, WorldLyricsPresentation.Cue cue, WorldLyricsStage.Group group,
                                               boolean reserveBackingSpace, boolean pairedBacking) {
        Vec3 forward = placementDirection(client);
        boolean canUseGround = surfacePlacementAllowed(preferences);
        if (canUseGround && forward.y < -.55) {
            Anchor ground = findGroundSurface(client, camera, 1, bounds, WorldLyricsDirector.Preset.SIGN, requestedDistance);
            if (ground != null) return ground;
        }
        if (wallPlacementAllowed(preferences)) {
            Anchor wall = findSurface(client, camera, 1, bounds, requestedDistance, preferences.isWorldLyricsCinematic(),
                    distanceRange(client, preferences).minimum(), -1);
            if (wall != null) return wall;
        }
        Vec3 right = new Vec3(-forward.z, 0, forward.x).normalize();
        if (right.lengthSqr() < .001) right = new Vec3(-1, 0, 0);
        Vec3 up = right.cross(forward).normalize();
        boolean focusAboveAim = group.region() == WorldLyricsPresentation.Region.CENTER && !preferences.isWorldLyricsCinematic()
                && cue.accents().stream().anyMatch(accent -> accent.center()
                    && accent.tokenIndex() >= group.firstToken() && accent.tokenIndex() < group.endToken());
        var region = focusAboveAim ? WorldLyricsPresentation.Region.TOP
                : WorldLyricsPresentation.safeRegion(group.region(), preferences.isWorldLyricsCinematic());
        if (region == WorldLyricsPresentation.Region.AUTO)
            region = (cue.id().hashCode() & 1) == 0 ? WorldLyricsPresentation.Region.LEFT : WorldLyricsPresentation.Region.RIGHT;

        var range = distanceRange(client, preferences);
        double distance = range.clamp(requestedDistance);
        var fallback = region == WorldLyricsPresentation.Region.LEFT ? WorldLyricsPresentation.Region.RIGHT : WorldLyricsPresentation.Region.LEFT;
        var lanes = pairedBacking ? List.of(WorldLyricsPresentation.Region.BOTTOM, region, fallback, WorldLyricsPresentation.Region.TOP)
                : List.of(region, fallback, WorldLyricsPresentation.Region.TOP, WorldLyricsPresentation.Region.RIGHT);
        for (int attempt = 0; attempt < 16; attempt++) {
            double ratio = distance / Math.max(.8, requestedDistance);
            double width = Math.clamp(bounds.width * ratio * 1.05, .35, distance * .58);
            double height = Math.clamp(bounds.height * ratio * 1.05, .25, distance * .30);
            var lane = lanes.get((attempt + attempt / 8) % lanes.size());
            double side = switch (lane) {
                case LEFT -> -(distance * .18 + width * .55);
                case RIGHT -> distance * .18 + width * .55;
                case TOP -> focusAboveAim && attempt % 4 == 0 ? 0 : WorldLyricsStage.topSideSign(group, attempt) * (distance * .28 + width * .25);
                default -> 0;
            };
            double vertical = switch (lane) {
                case TOP -> distance * .26 + height * .60;
                case BOTTOM -> -(distance * .16 + height * .55);
                case LEFT, RIGHT -> (((group.index() & 1) == 0 ? .06 : -.06)
                        + (reserveBackingSpace ? .38 : 0)) * distance;
                default -> 0;
            };
            // Eight world sectors are considered; projection rejects sectors outside the actual view.
            int sector = context.sector(cue.id(), group.index(), attempt);
            double nudge = attempt < 8 ? 0 : ((group.index() & 1) == 0 ? 1 : -1) * Math.PI / 8;
            double bearing = Math.atan2(context.dirX(), context.dirZ()) + sector * Math.PI / 4 + nudge;
            Vec3 sectorForward = new Vec3(Math.sin(bearing), Math.clamp(context.dirY(), -.35, .35), Math.cos(bearing)).normalize();
            if (context.speed() < .12) {
                double cameraBearing = Math.atan2(forward.x, forward.z);
                bearing = cameraBearing + (sector > 4 ? sector - 8 : sector) * Math.PI / 4 + nudge;
                sectorForward = new Vec3(Math.sin(bearing), forward.y, Math.cos(bearing)).normalize();
            }
            Vec3 center = camera.add(sectorForward.scale(distance)).add(right.scale(side * .45)).add(up.scale(vertical));
            center = atDistance(camera, center, distance);
            Vec3 towards = camera.subtract(center).normalize();
            double rotation = Math.atan2(towards.x, towards.z);
            double ny = Math.clamp(towards.y, -.75, .75), horizontal = Math.sqrt(1 - ny * ny);
            Vec3 normal = new Vec3(Math.sin(rotation) * horizontal, ny, Math.cos(rotation) * horizontal);
            Vec3 across = new Vec3(Math.cos(rotation), 0, -Math.sin(rotation));
            Anchor candidate = new Anchor(center.x, center.y, center.z, normal.x, normal.y, normal.z,
                    across.x, across.z, (float) rotation, false, width, height, 1, camera.distanceTo(center));
            if (withinView(client, camera, candidate) && separated(candidate) && freePanel(client, camera, candidate)) return candidate;

            if (attempt == 3) distance = range.clamp(requestedDistance * .68);
            if (attempt == 7) distance = range.clamp(Math.min(requestedDistance, 4));
            if (attempt == 11) distance = range.minimum();
        }
        return canUseGround && forward.y >= -.55
                ? findGroundSurface(client, camera, 1, bounds, WorldLyricsDirector.Preset.SIGN, requestedDistance)
                : null;
    }

    private static boolean separated(Anchor candidate) {
        var client = Minecraft.getInstance();
        var view = viewpoint(client, placementOwner != null && placementOwner.searchCamera != null ? placementOwner.searchCamera : WorldLyricsRenderHook.cameraPosition(client));
        var projected = WorldLyricsSpatial.project(panel(candidate), view);
        for (var scene : LINES.values()) {
            if (placementOwner != null && scene != placementOwner && scene.fit == ReadingFit.FULL
                    && placementOwner.cue.source().line().endMillis() > placementSongTime
                    && scene.cue.source().line().endMillis() <= placementSongTime) continue;
            for (Anchor other : scene.groupAnchors.values()) {
                if (WorldLyricsSpatial.worldOverlap(panel(candidate), panel(other), .12)
                        || WorldLyricsSpatial.overlap(projected, WorldLyricsSpatial.project(panel(other), view), .18)) return false;
            }
        }
        return true;
    }

    private static Anchor createAnchor(Minecraft client, Vec3 camera, int bands, ClientPreferences preferences,
                                       WorldLyricsDirector.Composition composition, double requestedDistance,
                                       PhraseBounds bounds) {
        return createSpatialAnchor(client,camera,bands,preferences,composition,requestedDistance,bounds,0);
    }

    private static Anchor createSpatialAnchor(Minecraft client, Vec3 camera, int bands, ClientPreferences preferences,
                                              WorldLyricsDirector.Composition composition, double requestedDistance,
                                              PhraseBounds bounds, int attempt) {
        double yaw=Math.toRadians(client.gameRenderer.mainCamera().yRot());
        Vec3 movement=travel(client);
        boolean gliding=gliding(client) && movement.length()>.05;
        Vec3 forward=placementDirection(client);
        Vec3 right=new Vec3(-forward.z,0,forward.x).normalize();
        if (right.lengthSqr()<.001) right=new Vec3(-Math.cos(yaw),0,-Math.sin(yaw));
        boolean outdoors=client.level.canSeeSky(BlockPos.containing(camera));
        var position=preferences.getWorldLyricsPosition();
        if (position==WorldLyricsSpace.Position.ADAPTIVE && bands>1) position=WorldLyricsSpace.Position.AROUND;
        var range = distanceRange(client, preferences);
        double distance = range.clamp(requestedDistance);
        double ratio=distance/Math.max(1,requestedDistance);
        double width=Math.clamp(bounds.width*ratio*1.15,1.2,distance*.62);
        double height=Math.clamp(bounds.height*ratio,Math.min(1.8,distance*.30),distance*(position==WorldLyricsSpace.Position.SKY || position==WorldLyricsSpace.Position.ADAPTIVE && outdoors ? .38 : .78));
        if (cinematicActive) { width = Math.min(width, distance * .42); height = Math.min(height, distance * .24); }
        Vec3 up = cinematicActive ? right.cross(forward).normalize() : new Vec3(0, 1, 0);
        for (int sign : new int[]{1,-1}) {
            var offset = cinematicActive ? WorldLyricsSpace.cinematicOffset(position, distance, width, sign)
                    : WorldLyricsSpace.offset(position,outdoors,gliding,distance,width,height,sign);
            Vec3 center=camera.add(forward.scale(offset.forward())).add(right.scale(offset.side())).add(up.scale(offset.up()));
            center = atDistance(camera, center, distance);
            Vec3 towards=camera.subtract(center).normalize();
            double rotation=Math.atan2(towards.x,towards.z)+Math.toRadians(offset.yawDegrees());
            double ny=Math.clamp(towards.y,-.75,.75), horizontal=Math.sqrt(1-ny*ny);
            Vec3 normal=new Vec3(Math.sin(rotation)*horizontal,ny,Math.cos(rotation)*horizontal);
            Vec3 across=new Vec3(Math.cos(rotation),0,-Math.sin(rotation));
            Anchor anchor=new Anchor(center.x,center.y,center.z,normal.x,normal.y,normal.z,across.x,across.z,
                    (float)rotation,false,width,height,bands,camera.distanceTo(center));
            if (freePanel(client,camera,anchor)) return anchor;
        }
        // Stay outside the crosshair corridor even indoors; never fall back to a panel in the player's face.
        if (attempt < 3 && distance > range.minimum())
            return createSpatialAnchor(client,camera,bands,preferences,composition,distance*.68,bounds,attempt+1);
        return null;
    }

    /** Check the panel's center and both ends before fixing its position in the world. */
    private static Vec3 liftAboveGround(Minecraft client, Vec3 center, Vec3 right,
                                         double distance, double height, double depth) {
        double halfHeight = height * .5;
        double margin = .45 + Math.max(0, depth) * Math.max(1, distance / 6);
        double highestGround = Double.NEGATIVE_INFINITY;
        double halfSpan = Math.min(distance * .32, 24);
        for (double side : new double[]{-halfSpan, 0, halfSpan}) {
            Vec3 point = center.add(right.scale(side));
            Vec3 start = new Vec3(point.x, point.y + halfHeight + 2, point.z);
            Vec3 end = new Vec3(point.x, point.y - halfHeight - margin - 3, point.z);
            BlockHitResult floor = ray(client, start, end);
            if (floor.getType() == HitResult.Type.BLOCK && floor.getDirection() == Direction.UP)
                highestGround = Math.max(highestGround, floor.getLocation().y);
        }
        if (highestGround == Double.NEGATIVE_INFINITY) return center;
        return new Vec3(center.x, Math.max(center.y, highestGround + halfHeight + margin), center.z);
    }

    static Anchor interpolateAnchor(Anchor from, Anchor to, double progress, double rise) {
        double t = Math.clamp(progress, 0, 1);
        if (t <= 0) return from;
        if (t >= 1) return to;
        double yaw = from.rotationYaw + Math.atan2(Math.sin(to.rotationYaw - from.rotationYaw),
                Math.cos(to.rotationYaw - from.rotationYaw)) * t;
        double pitch = Math.asin(Math.clamp(from.normalY, -1, 1))
                + (Math.asin(Math.clamp(to.normalY, -1, 1))
                - Math.asin(Math.clamp(from.normalY, -1, 1))) * t;
        double horizontal = Math.cos(pitch);
        return new Anchor(
                from.x + (to.x - from.x) * t,
                from.y + (to.y - from.y) * t + rise * Math.sin(Math.PI * t),
                from.z + (to.z - from.z) * t,
                Math.sin(yaw) * horizontal, Math.sin(pitch), Math.cos(yaw) * horizontal,
                Math.cos(yaw), -Math.sin(yaw), (float) yaw, to.wall,
                from.width + (to.width - from.width) * t,
                from.height + (to.height - from.height) * t, to.bands,
                from.distance + (to.distance - from.distance) * t, to.ground);
    }

    private static boolean freePanel(Minecraft client, Vec3 camera, Anchor anchor) {
        return physicallyFreePanel(client, anchor) && visuallyClearPanel(client, camera, anchor);
    }

    private static Vec3 point(Anchor anchor, double side, double up, double depth) {
        var geometry = panel(anchor);
        var basis = geometry.basis();
        var position = geometry.center().add(basis.right().scale(anchor.width * side))
                .add(basis.up().scale(anchor.height * up)).add(basis.normal().scale(depth));
        return new Vec3(position.x(), position.y(), position.z());
    }

    private static boolean visuallyClearPanel(Minecraft client, Vec3 camera, Anchor anchor) {
        for (double[] sample : new double[][]{{0,0},{-.48,-.48},{-.48,.48},{.48,-.48},{.48,.48}}) {
            Vec3 position = point(anchor, sample[0], sample[1], 0);
            if (!client.level.hasChunkAt(BlockPos.containing(position)) || !clearSight(client, camera, position)) return false;
        }
        return true;
    }

    private static double depthMotionLimit(Anchor anchor) { return anchor.wall || anchor.ground ? .025 : .08; }

    private static boolean physicallyFreePanel(Minecraft client, Anchor anchor) {
        return PHYSICAL_CLEARANCE.computeIfAbsent(anchor, key -> testPhysicalClearance(client, key));
    }

    private static boolean testPhysicalClearance(Minecraft client, Anchor anchor) {
        double extrusion = Math.clamp(Math.min(.3, RelayLyricsClient.preferences().getWorldLyricsDepth() * 2)
                * Math.max(.5, anchor.distance / 6), .025, .65);
        var surface = panel(anchor); var basis = surface.basis();
        double depthMargin = depthMotionLimit(anchor) + .015;
        // Include the glyph plane itself as well as movement and extrusion on either side of it.
        var occupancy = new WorldLyricsSpatial.Panel(surface.center().add(basis.normal().scale(extrusion * .5 + .025)), basis,
                anchor.width + surface.animationMargin() * 2, anchor.height + surface.animationMargin() * 2,
                extrusion + 2 * depthMargin + .05, 0);
        var corners = occupancy.corners();
        double minX = corners.stream().mapToDouble(WorldLyricsSpatial.Vector::x).min().orElse(anchor.x);
        double minY = corners.stream().mapToDouble(WorldLyricsSpatial.Vector::y).min().orElse(anchor.y);
        double minZ = corners.stream().mapToDouble(WorldLyricsSpatial.Vector::z).min().orElse(anchor.z);
        double maxX = corners.stream().mapToDouble(WorldLyricsSpatial.Vector::x).max().orElse(anchor.x);
        double maxY = corners.stream().mapToDouble(WorldLyricsSpatial.Vector::y).max().orElse(anchor.y);
        double maxZ = corners.stream().mapToDouble(WorldLyricsSpatial.Vector::z).max().orElse(anchor.z);
        var bounds = new net.minecraft.world.phys.AABB(minX, minY, minZ, maxX, maxY, maxZ);
        boolean large = (Math.ceil(maxX) - Math.floor(minX) + 1) * (Math.ceil(maxY) - Math.floor(minY) + 1)
                * (Math.ceil(maxZ) - Math.floor(minZ) + 1) > 4096;
        if (large) return clearLargePanel(client, occupancy, bounds);
        for (int x = (int) Math.floor(minX) >> 4; x <= (int) Math.floor(maxX) >> 4; x++)
            for (int z = (int) Math.floor(minZ) >> 4; z <= (int) Math.floor(maxZ) >> 4; z++)
                if (!client.level.hasChunkAt(new BlockPos(x << 4, (int) Math.floor(minY), z << 4))) return false;
        return clearPanelBlocks(client, occupancy, bounds);
    }

    /** Large panels may span many air blocks. Inspect only occupied sections that intersect the panel. */
    private static boolean clearLargePanel(Minecraft client, WorldLyricsSpatial.Panel occupancy, net.minecraft.world.phys.AABB bounds) {
        int minX = (int) Math.floor(bounds.minX) >> 4, maxX = (int) Math.floor(bounds.maxX) >> 4;
        int minY = (int) Math.floor(bounds.minY) >> 4, maxY = (int) Math.floor(bounds.maxY) >> 4;
        int minZ = (int) Math.floor(bounds.minZ) >> 4, maxZ = (int) Math.floor(bounds.maxZ) >> 4;
        int bottom = client.level.getMinSectionY();
        int occupied = 0;
        var axes = WorldLyricsSpatial.Basis.facing(new WorldLyricsSpatial.Vector(0, 0, 1), new WorldLyricsSpatial.Vector(1, 0, 0));
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
            if (!client.level.hasChunkAt(new BlockPos(x << 4, minY << 4, z << 4))) return false;
            var sections = client.level.getChunk(x, z).getSections();
            for (int y = Math.max(minY, bottom); y <= Math.min(maxY, bottom + sections.length - 1); y++) {
                if (sections[y - bottom].hasOnlyAir()) continue;
                int bx = x << 4, by = y << 4, bz = z << 4;
                var section = new WorldLyricsSpatial.Panel(new WorldLyricsSpatial.Vector(bx + 8, by + 8, bz + 8),
                        axes, 16, 16, 16, 0);
                if (!WorldLyricsSpatial.worldOverlap(occupancy, section, 0)) continue;
                if (++occupied > 6) return false;
                var slice = new net.minecraft.world.phys.AABB(Math.max(bounds.minX, bx), Math.max(bounds.minY, by), Math.max(bounds.minZ, bz),
                        Math.min(bounds.maxX, bx + 16), Math.min(bounds.maxY, by + 16), Math.min(bounds.maxZ, bz + 16));
                if (!clearPanelBlocks(client, occupancy, slice)) return false;
            }
        }
        return true;
    }

    private static boolean clearPanelBlocks(Minecraft client, WorldLyricsSpatial.Panel occupancy, net.minecraft.world.phys.AABB bounds) {
        int examined = 0;
        for (var collision : client.level.getBlockCollisions(null, bounds))
            for (var box : collision.toAabbs()) {
                if (++examined > 4096) return false;
                var obstacle = new WorldLyricsSpatial.Panel(new WorldLyricsSpatial.Vector((box.minX + box.maxX) * .5,
                        (box.minY + box.maxY) * .5, (box.minZ + box.maxZ) * .5),
                        WorldLyricsSpatial.Basis.facing(new WorldLyricsSpatial.Vector(0, 0, 1), new WorldLyricsSpatial.Vector(1, 0, 0)),
                        box.maxX - box.minX, box.maxY - box.minY, box.maxZ - box.minZ, 0);
                if (WorldLyricsSpatial.worldOverlap(occupancy, obstacle, 0)) return false;
            }
        return true;
    }

    private static void updateRoomClearance(Minecraft client, Vec3 eye) {
        if (frameClock < nextClearanceCheck) return;
        nextClearanceCheck = frameClock + 150_000_000L;
        ReadingFit measured;
        try {
            double left = opaqueDistance(client, eye, -2.4, 0, 0);
            double right = opaqueDistance(client, eye, 2.4, 0, 0);
            double north = opaqueDistance(client, eye, 0, 0, -2.4);
            double south = opaqueDistance(client, eye, 0, 0, 2.4);
            boolean lowCeiling = opaqueDistance(client, eye, 0, .65, 0) <= .65;
            double width = Math.min(left + right, north + south);
            double nearestWall = Math.min(Math.min(left, right), Math.min(north, south));
            double wordWidth = roomFit == ReadingFit.WORD ? 2.65 : 2.3;
            measured = width <= wordWidth || lowCeiling && nearestWall <= 1.15 ? ReadingFit.WORD
                    : width <= 4.8 || lowCeiling ? ReadingFit.SHORT : ReadingFit.FULL;
        } catch (DeferredSearch deferred) {
            return;
        }
        if (measured != candidateFit) {
            candidateFit = measured;
            clearanceChangeAt = frameClock;
        } else if (roomFit != measured) {
            long confirmation = measured.ordinal() < roomFit.ordinal() ? 400_000_000L : 1_100_000_000L;
            if (frameClock - clearanceChangeAt >= confirmation) {
                roomFit = measured;
                confined = roomFit == ReadingFit.WORD;
            }
        }
    }

    private static double opaqueDistance(Minecraft client, Vec3 eye, double x, double y, double z) {
        var hit = ray(client, eye, eye.add(x, y, z));
        return hit.getType() != HitResult.Type.MISS && material(client, hit) == WorldLyricsSpatial.Material.OPAQUE ? eye.distanceTo(hit.getLocation()) : Double.POSITIVE_INFINITY;
    }

    private static WorldLyricsSpatial.Material material(Minecraft client, BlockHitResult hit) {
        var state = client.level.getBlockState(hit.getBlockPos());
        String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if (id.contains("glass") || id.equals("minecraft:ice") || id.equals("minecraft:frosted_ice")) return WorldLyricsSpatial.Material.GLASS;
        if (id.contains("leaves") || id.endsWith(":vine") || id.endsWith("_vines") || id.endsWith(":short_grass") || id.endsWith(":tall_grass") || id.contains("fern")
                || id.contains("flower") || id.contains("sapling") || id.contains("bush")) return WorldLyricsSpatial.Material.FOLIAGE;
        return WorldLyricsSpatial.Material.OPAQUE;
    }

    private static boolean clearSight(Minecraft client, Vec3 start, Vec3 end) {
        var occlusion = new WorldLyricsSpatial.VisualOcclusion();
        Vec3 direction = end.subtract(start).normalize(), cursor = start;
        for (int layer = 0; layer < 128; layer++) {
            var hit = ray(client, cursor, end, true);
            if (hit.getType() != HitResult.Type.BLOCK) return true;
            var block = hit.getBlockPos();
            var boxes = new ArrayList<WorldLyricsSpatial.Box>();
            for (var box : client.level.getBlockState(block).getShape(client.level, block).toAabbs())
                boxes.add(new WorldLyricsSpatial.Box(new WorldLyricsSpatial.Vector(block.getX() + box.minX,
                        block.getY() + box.minY, block.getZ() + box.minZ), new WorldLyricsSpatial.Vector(block.getX() + box.maxX,
                        block.getY() + box.maxY, block.getZ() + box.maxZ)));
            var coverage = WorldLyricsSpatial.coverage(vector(cursor), vector(end), boxes);
            occlusion.add(material(client, hit), coverage.thickness());
            if (occlusion.blocked()) return false;
            cursor = cursor.add(end.subtract(cursor).scale(coverage.exitFraction())).add(direction.scale(.004));
            if (cursor.subtract(end).dot(direction) >= 0) return true;
        }
        // Unknown under a traversal limit must never be mistaken for a newly blocked existing anchor.
        throw DeferredSearch.INSTANCE;
    }

    private static BlockHitResult ray(Minecraft client, Vec3 start, Vec3 end) { return ray(client, start, end, false); }
    private static BlockHitResult ray(Minecraft client, Vec3 start, Vec3 end, boolean visual) {
        var key = new RayKey(start, end, visual);
        var cached = placementOwner == null ? RAY_CACHE.get(key) : placementOwner.searchRays.get(key);
        if (cached != null && (placementOwner != null || rayTick - cached.tick <= 4)) return cached.hit;
        cached = RAY_CACHE.get(key);
        if (cached != null && rayTick - cached.tick <= 4) {
            if (placementOwner != null) placementOwner.searchRays.put(key, cached);
            return cached.hit;
        }
        if (!RAYS.tryAcquire()) throw DeferredSearch.INSTANCE;
        peakRaycasts = Math.max(peakRaycasts, RAYS.used());
        var hit = client.level.clip(new ClipContext(start, end, visual ? ClipContext.Block.OUTLINE : ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, client.player));
        if (RAY_CACHE.size() > 4096) RAY_CACHE.remove(RAY_CACHE.keySet().iterator().next());
        var result = new CachedRay(hit, rayTick);
        RAY_CACHE.put(key, result);
        if (placementOwner != null) placementOwner.searchRays.put(key, result);
        return hit;
    }

    private static boolean surfacePlacementAllowed(ClientPreferences preferences) {
        return preferences.getWorldLyricsPosition() != WorldLyricsSpace.Position.SKY && context.speed() <= 9 && !cinematicActive;
    }

    private static boolean wallPlacementAllowed(ClientPreferences preferences) {
        return preferences.isWorldLyricsAutoWalls() && surfacePlacementAllowed(preferences);
    }

    private static Anchor findGroundSurface(Minecraft client, Vec3 camera, int bands,
                                            PhraseBounds bounds, WorldLyricsDirector.Preset preset, double desiredDistance) {
        Vec3 view = searchDirection(client);
        double bearing = Math.atan2(view.x, view.z);
        double distance = distanceRange(client, RelayLyricsClient.preferences()).clamp(Math.min(desiredDistance, 12));
        double width = Math.clamp(bounds.width, .7, Math.min(7.5, distance * .5)), height = Math.clamp(bounds.height, .5, 2.5);
        for (int sector : new int[]{-1,1,0,-2,2,3,-3,4}) {
            double angle = bearing + sector * Math.PI / 4;
            Vec3 forward = new Vec3(Math.sin(angle), 0, Math.cos(angle)), right = new Vec3(-forward.z, 0, forward.x);
            Vec3 center = camera.add(forward.scale(distance));
            double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
            boolean stable = true;
            for (double side : new double[]{-.48,0,.48}) {
                Vec3 sample = center.add(right.scale(width * side));
                var hit = ray(client, sample.add(0, 3, 0), sample.add(0, -8, 0));
                if (hit.getType() != HitResult.Type.BLOCK || hit.getDirection() != Direction.UP || !solidFace(client, hit)
                        || !client.level.getFluidState(hit.getBlockPos()).isEmpty()) { stable = false; break; }
                low = Math.min(low, hit.getLocation().y); high = Math.max(high, hit.getLocation().y);
            }
            if (!stable || high - low > .20) continue;
            Anchor flat = new Anchor(center.x, high + .065, center.z, 0, 1, 0, right.x, right.z,
                    (float) Math.atan2(-forward.x, -forward.z), false, width, height, bands, camera.distanceTo(new Vec3(center.x, high, center.z)), true);
            if (withinView(client, camera, flat) && separated(flat) && supportedFloor(client, flat) && freePanel(client, camera, flat)) return flat;
            Anchor standing = new Anchor(center.x, high + height * .5 + .24, center.z, -forward.x, 0, -forward.z,
                    right.x, right.z, (float) Math.atan2(-forward.x, -forward.z), false, width, height, bands,
                    camera.distanceTo(new Vec3(center.x, high + height * .5, center.z)), true);
            if (withinView(client, camera, standing) && separated(standing) && freePanel(client, camera, standing)) return standing;
        }
        return null;
    }

    private static boolean supportedFloor(Minecraft client, Anchor flat) {
        for (double x : new double[]{-.48, 0, .48}) for (double y : new double[]{-.48, 0, .48}) {
            Vec3 position = point(flat, x, y, 0);
            var support = ray(client, position.add(0, .2, 0), position.add(0, -.25, 0));
            if (support.getType() != HitResult.Type.BLOCK || support.getDirection() != Direction.UP || !solidFace(client, support)
                    || Math.abs(support.getLocation().y - (flat.y - .065)) > .1) return false;
        }
        return true;
    }

    private static Anchor findSurface(Minecraft client, Vec3 camera, int bands, PhraseBounds bounds,
                                      double requestedDistance, boolean centered, double minimumDistance, int readingGroup) {
        Vec3 view = searchDirection(client);
        double bearing = Math.atan2(view.x, view.z);
        var viewGeometry = viewpoint(client, camera);
        var candidates = new ArrayList<WallCandidate>();
        for (int sector : new int[]{-1,1,0,-2,2,3,-3,4}) {
            double angle = bearing + sector * Math.PI / 4;
            Vec3 direction = new Vec3(Math.sin(angle), Math.clamp(view.y, -.5, .5), Math.cos(angle)).normalize();
            var hit = ray(client, camera, camera.add(direction.scale(16)));
            if (hit.getType() != HitResult.Type.BLOCK || !hit.getDirection().getAxis().isHorizontal() || !solidFace(client, hit)) continue;
            if (camera.distanceTo(hit.getLocation()) < minimumDistance) continue;
            Direction face = hit.getDirection();
            Vec3 normal = new Vec3(face.getStepX(), 0, face.getStepZ());
            double facing = camera.subtract(hit.getLocation()).normalize().dot(normal);
            if (facing >= .38) candidates.add(new WallCandidate(hit, facing * 3 - Math.abs(sector) * .12));
        }
        candidates.sort(Comparator.comparingDouble(WallCandidate::score).reversed());
        for (var candidate : candidates.stream().limit(2).toList()) {
            var hit = candidate.hit; Direction face = hit.getDirection();
            Vec3 normal = new Vec3(face.getStepX(), 0, face.getStepZ()), right = new Vec3(normal.z, 0, -normal.x);
            double ratio = Math.clamp(camera.distanceTo(hit.getLocation()) / Math.max(.8, requestedDistance), .75, 4);
            // More than one lyric can use the same face. Try tangent offsets when the centered
            // footprint covers the aim, clips a floor, or overlaps another phrase on this wall.
            for (double fit : new double[]{1, .8}) {
                double width = Math.clamp(bounds.width * ratio, .7, 7.5) * fit;
                double height = Math.clamp(bounds.height * ratio, .5, 3.5) * fit;
                double rise = .37 + height * .65, sidestep = .44 + width * .58;
                double upperRow = .68 + height * 1.75, diagonal = .25 + width * .7, diagonalRise = .25 + height * .7;
                positions:
                for (double[] offset : new double[][]{{0, 0}, {0, rise}, {sidestep, 0}, {-sidestep, 0},
                        {0, upperRow}, {diagonal, diagonalRise}, {-diagonal, diagonalRise}, {0, -rise}}) {
                    Vec3 faceCenter = hit.getLocation().add(right.scale(offset[0])).add(0, offset[1], 0);
                    Vec3 center = faceCenter.add(normal.scale(.065));
                    Anchor anchor = new Anchor(center.x, center.y, center.z, normal.x, 0, normal.z, right.x, right.z,
                            (float) Math.atan2(normal.x, normal.z), true, width, height, bands, camera.distanceTo(center));
                    var footprint = WorldLyricsSpatial.project(panel(anchor), viewGeometry);
                    if (!centered && footprint.minX() < .045 && footprint.maxX() > -.045
                            && footprint.minY() < .045 && footprint.maxY() > -.045) {
                        tracePlacement("wall:aim", anchor); continue;
                    }
                    if (!withinView(client, camera, anchor, readingGroup >= 0)) { tracePlacement("wall:view", anchor); continue; }
                    if (readingGroup >= 0 ? !separatedWord(placementOwner, readingGroup, anchor, viewGeometry, false) : !separated(anchor)) {
                        tracePlacement("wall:overlap", anchor); continue;
                    }
                    for (double x : new double[]{-.48,0,.48}) for (double y : new double[]{-.48,0,.48}) {
                        Vec3 position = faceCenter.add(right.scale(x * width)).add(0, y * height, 0);
                        var support = ray(client, position.add(normal.scale(.2)), position.subtract(normal.scale(.2)));
                        if (support.getType() != HitResult.Type.BLOCK || support.getDirection() != face || !solidFace(client, support)
                                || support.getLocation().distanceTo(position) > .10) {
                            tracePlacement("wall:support", anchor); continue positions;
                        }
                    }
                    if (!freePanel(client, camera, anchor)) { tracePlacement("wall:blocked", anchor); continue; }
                    tracePlacement("wall:usable", anchor);
                    return anchor;
                }
            }
        }
        return null;
    }

    private static boolean solidFace(Minecraft client, BlockHitResult hit) {
        var position = hit.getBlockPos();
        if (!client.level.hasChunkAt(position) || material(client, hit) != WorldLyricsSpatial.Material.OPAQUE) return false;
        var local = hit.getLocation().subtract(position.getX(), position.getY(), position.getZ());
        // A slab's actual top face is valid support even though it is below the block's unit-cube top.
        for (var box : client.level.getBlockState(position).getCollisionShape(client.level, position).toAabbs()) {
            boolean x = local.x >= box.minX - .005 && local.x <= box.maxX + .005;
            boolean y = local.y >= box.minY - .005 && local.y <= box.maxY + .005;
            boolean z = local.z >= box.minZ - .005 && local.z <= box.maxZ + .005;
            boolean supported = switch (hit.getDirection()) {
                case UP -> x && z && Math.abs(local.y - box.maxY) < .025;
                case DOWN -> x && z && Math.abs(local.y - box.minY) < .025;
                case EAST -> y && z && Math.abs(local.x - box.maxX) < .025;
                case WEST -> y && z && Math.abs(local.x - box.minX) < .025;
                case SOUTH -> x && y && Math.abs(local.z - box.maxZ) < .025;
                case NORTH -> x && y && Math.abs(local.z - box.minZ) < .025;
            };
            if (supported) return true;
        }
        return false;
    }
    private static double findGround(Minecraft client, double x, double z, double cameraY) {
        var hit = ray(client, new Vec3(x, cameraY + 2, z), new Vec3(x, cameraY - 8, z));
        return hit.getType() == HitResult.Type.BLOCK ? hit.getLocation().y : cameraY - 2;
    }
    private record RayKey(Vec3 start, Vec3 end, boolean visual) { }
    private record CachedRay(BlockHitResult hit, long tick) { }
    private static final class DeferredSearch extends RuntimeException {
        static final DeferredSearch INSTANCE = new DeferredSearch();
        private DeferredSearch() { super(null, null, false, false); }
    }



    private record LineKey(LyricLine line, boolean backing) {
        @Override public int hashCode() { return System.identityHashCode(line) * 31 + (backing ? 1 : 0); }
        @Override public boolean equals(Object o) { return o instanceof LineKey k && line == k.line && backing == k.backing; }
    }
    private record Piece(Component text, WorldLyricsPlan.Token token, int tokenIndex, int wordIndex, double x, double width,
                         double fragmentX, double fragmentWidth, double wordOffset, double wordWidth, double fraction, boolean rtl, double segmentCenter,
                         WorldLyricsExperience.Motion segmentMotion, WorldLyricsExperience.Motion glyphMotion,
                         long glyphStart, long glyphEnd, long entryStart) { }
    private record DrawRow(List<Piece> pieces, double width, Component backdrop, double backdropWidth) { }
    private record DrawPage(List<DrawRow> rows) { }
    private record Layout(List<WorldLyricsLayout.Page> pages, List<DrawPage> drawn, boolean timed, List<WorldLyricsPlan.Token> tokens, WorldLyricsPresentation.Cue cue, List<WorldLyricsStage.Group> groups,
                          double[] phraseOffsets, GradientRange[] wordRanges, double phraseWidth,
                          ReadingFit fit, List<Integer> fitEnds) { }
    private record GradientRange(double start, double width) { }
    private record PhraseBounds(double width, double height) { }
    private record WordCoverage(double alpha, long at) { }

    static final class SceneLine {
        Anchor base;
        WorldLyricsPresentation.Cue cue;
        double requestedDistance;
        final LinkedHashMap<Integer, Anchor> groupAnchors = new LinkedHashMap<>();
        final Map<Integer, Long> conflictSince = new HashMap<>();
        final Map<Integer, Integer> conflictRanks = new HashMap<>();
        final Map<Integer, Long> conflictRecoverySince = new HashMap<>();
        final Map<Integer, WordCoverage> wordCoverage = new HashMap<>();
        final Map<RayKey, CachedRay> searchRays = new HashMap<>();
        Vec3 searchCamera, searchView, searchUp, readingHeading;
        WorldLyricsContext.Snapshot searchContext;
        long searchAt;
        SceneLine lead;
        final WorldLyricsRecovery.Monitor recovery = new WorldLyricsRecovery.Monitor();
        Layout compactLayout;
        Anchor compactAnchor;
        boolean compact, readable, placementFailed;
        long wordsStopAt = Long.MAX_VALUE;
        ReadingFit fit = ReadingFit.FULL;
        boolean fitLocked, openSky;
        int searchWord = -1, preparedIndex = -1;
        Anchor preparedAnchor;
        long yieldedAt = Long.MIN_VALUE;
        long nextVisibilityCheck;
        Set<Integer> observedGroups = Set.of();
        Anchor from;
        final int slot;
        final WorldLyricsDirector.Composition composition;
        final PhraseBounds bounds;
        int worldLight;
        Layout layout;
        WorldLyricsContext.Snapshot layoutContext = context;
        List<WorldLyricsStage.Group> frozenGroups = List.of();
        long anchoredAt;
        long handoffAt, repairFadeAt;
        long moveStartedAt, moveDuration;
        double moveRise;
        int activePage;
        WorldLyricsSpace.RelocationState relocation = new WorldLyricsSpace.RelocationState();
        SceneLine(Anchor base, int slot, WorldLyricsDirector.Composition composition, long now,
                  PhraseBounds bounds, int worldLight) {
            this.base = base; this.slot = slot; this.composition = composition;
            this.bounds = bounds; this.worldLight = worldLight;
            this.anchoredAt = now;
        }
        SceneLine(Anchor base, int slot, WorldLyricsDirector.Composition composition, long now) {
            this(base, slot, composition, now, new PhraseBounds(base.width, base.height), 15);
        }
        Anchor display(long now, boolean animate) {
            if (!animate || from == null) { from = null; return base; }
            double t = Math.clamp((now - moveStartedAt) / (double) moveDuration, 0, 1);
            if (t >= 1) { from = null; return base; }
            t = t * t * (3 - 2 * t);
            return interpolateAnchor(from, base, t, moveRise);
        }
    }
    private record WallCandidate(BlockHitResult hit, double score) { }
    static record Anchor(double x, double y, double z,
                          double normalX, double normalY, double normalZ, double rightX, double rightZ,
                          float rotationYaw, boolean wall, double width, double height, int bands, double distance,
                          boolean ground, double rightY) {
        Anchor(double x, double y, double z, double normalX, double normalY, double normalZ,
               double rightX, double rightZ, float rotationYaw, boolean wall,
               double width, double height, int bands, double distance, boolean ground) {
            this(x, y, z, normalX, normalY, normalZ, rightX, rightZ, rotationYaw, wall, width, height, bands, distance, ground, 0);
        }
        Anchor(double x, double y, double z, double normalX, double normalY, double normalZ,
               double rightX, double rightZ, float rotationYaw, boolean wall,
               double width, double height, int bands, double distance) {
            this(x, y, z, normalX, normalY, normalZ, rightX, rightZ, rotationYaw,
                    wall, width, height, bands, distance, false);
        }
    }
}
