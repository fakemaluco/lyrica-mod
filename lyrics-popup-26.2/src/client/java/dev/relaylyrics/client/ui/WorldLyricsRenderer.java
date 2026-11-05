package dev.relaylyrics.client.ui;

import dev.relaylyrics.client.ClientPreferences;
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
import dev.relaylyrics.client.music.WorldLyricsDirector;
import dev.relaylyrics.client.music.WorldLyricsPlacement;
import dev.relaylyrics.client.music.WorldLyricsMotion;
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
    private static final Map<LineKey, SceneLine> LINES = new LinkedHashMap<>();
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
    private static long placementSongTime;
    static boolean probeCompact() { return LINES.values().stream().anyMatch(scene -> scene.compact); }
    static int probeRecoveryCount() { return recoveryCount; }
    static boolean probeReadable() { return !LINES.isEmpty() && LINES.values().stream().allMatch(scene -> scene.readable); }
    private static Map<String, WorldLyricsPresentation.Cue> lastOverrides;
    private static WorldLyricsPresentation.Intensity lastIntensity;
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
    static boolean probeFlat() { return drawnPieces > 0 && drawnMeshes == 0; }
    static String probeAnchors() { return LINES.values().stream().flatMap(scene -> scene.groupAnchors.values().stream()).map(Object::toString).sorted().toList().toString(); }
    static boolean probeHasVolume() { return drawnMeshes>0 && drawnHalos==0; }
    static boolean probeFontMatchesPopup() {
        var expected=PlatformTextStyles.withFont(Component.literal("X"),RelayLyricsClient.preferences().getLyricsFont().id()).getStyle().getFont();
        return LINES.values().stream().filter(line -> line.layout!=null).flatMap(line -> line.layout.drawn.stream())
                .flatMap(page -> page.rows.stream()).flatMap(row -> row.pieces.stream()).allMatch(piece -> piece.text.getStyle().getFont().equals(expected));
    }

    private static int drawnPieces, drawnMeshes, drawnHalos, spicyMotionPieces;
    static int probeSpicyMotion() { return spicyMotionPieces; }
    static int probeGroups() { return LINES.values().stream().mapToInt(scene -> scene.groupAnchors.size()).sum(); }
    static boolean probeRotation() { return LINES.values().stream().filter(scene -> scene.layout != null).flatMap(scene -> scene.layout.groups.stream()).anyMatch(group -> Math.abs(group.rotationDegrees()) > 1); }
    static boolean verifyProbe(int expectedLines, boolean safe) { return LINES.size() >= expectedLines && drawnPieces > 0 && (!safe || drawnMeshes == 0); }
    static boolean probeWall() { return LINES.values().stream().anyMatch(scene -> scene.base != null && scene.base.wall); }
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
            previousPlayerPosition = null; travelWorld = null; playerTravelPerTick = Vec3.ZERO;
            CINEMATIC_CAMERA.reset(); cinematicActive = false; cinematicHandoffAt = 0; return;
        }
        Vec3 position = new Vec3(client.player.getX(), client.player.getY(), client.player.getZ());
        if (travelWorld == client.level && previousPlayerPosition != null) {
            Vec3 delta = position.subtract(previousPlayerPosition);
            if (delta.lengthSqr() > 64) { clear(); STATE.clear(); playerTravelPerTick = client.player.getDeltaMovement(); }
            else playerTravelPerTick = delta;
        } else playerTravelPerTick = client.player.getDeltaMovement();
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
        if (!preferences.isLyricsVisible()) return;
        if (!preferences.isWorldLyrics() || overlay == null || client.player == null
                || client.level == null || matrices == null || consumers == null) {
            clear(); STATE.clear(); return;
        }
        if (!Objects.equals(wallMode, preferences.isWorldLyricsAutoWalls())) {
            clear(); wallMode = preferences.isWorldLyricsAutoWalls();
        }
        frameClock=System.nanoTime(); WorldLyricsLights.begin(client.level,frameClock);
        // Ji owns the viewpoint. Reframe on entry, camera cuts/pans, and return to the player.
        cinematicActive = CompatibilityManager.jiAfkCinematicActive();
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
        WorldLyricsDirector.Settings director = directorSettings(preferences);
        long configureNow = System.nanoTime();
        if (configureNow - configureAt >= 50_000_000L) {
            configureAt = configureNow;
            String anchors = preferences.getWorldLyricsStyle() + "|" + preferences.getWorldLyricsPosition()
                    + "|" + preferences.getWorldLyricsDepth() + "|" + preferences.getWorldLyricsQuality()
                    + "|" + preferences.isWorldLyricsCinematic() + "|" + preferences.getWorldLyricsPresentationIntensity();
            if (!Objects.equals(anchors, anchorStyle)) { clear(); anchorStyle = anchors; }
            String style = preferences.getLyricsFont().id() + "|" + preferences.getGoogleFontFamily()
                    + "|" + preferences.getLyricsFontWeight() + "|" + preferences.isLyricsRomanization()
                    + "|" + overlay.fontLayoutRevision() + "|" + preferences.getWorldLyricsSize()
                    + "|" + preferences.isWorldLyricsGeminiFont();
            if (!Objects.equals(style, layoutStyle) || fontStorage != client.font) {
                LINES.values().forEach(value -> { value.layout = null; value.compactLayout = null; value.nextVisibilityCheck = 0; });
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
            visibleCues = List.of(cue);
        }
        List<WorldLyricsPlan.WorldLine> visible = visibleCues.stream().map(WorldLyricsPresentation.Cue::source).toList();
        visibleWords = 0;
        drawnPieces = drawnMeshes = drawnHalos = spicyMotionPieces = 0;
        frameBudget = new WorldLyricsExperience.Budget(preferences.getWorldLyricsQuality(), safeRendering);
        Set<LineKey> keys = new HashSet<>();
        for (var line : visible) keys.add(key(line));
        LINES.keySet().removeIf(key -> !keys.contains(key));
        for (var scene : LINES.values()) pruneGroupAnchors(scene, now, preferences.isLyricsAnimations());
        var priority = Comparator.comparingInt((WorldLyricsPresentation.Cue cue) -> cue.source().line().endMillis() <= now
                ? 2 : cue.source().backing() ? 1 : 0);
        for (var cue : visibleCues.stream().sorted(priority).toList()) {
            var planned = cue.source();
            LineKey key = key(planned);
            SceneLine scene = LINES.get(key);
            if (scene == null) {
                var selected = composition(planned.line(), planned.backing(), false, director, lastPreset);
                double distance = readingDistance(planned.line(), now, selected, director);
                scene = new SceneLine(null, 0, selected, now, new PhraseBounds(1, 1), 15);
                scene.requestedDistance = distance;
                scene.cue = cue; // Existing phrases keep their direction when a remote plan arrives.
                LINES.put(key, scene);
            }
            if (scene.layout == null) {
                scene.layout = layout(planned.line(), preferences, scene.requestedDistance, scene.cue);
                scene.activePage = WorldLyricsPlacement.pageIndex(scene.layout.pages, now);
            }
            if (playing && !scene.layout.pages.isEmpty()) {
                // Timed pages share the phrase placement until its current view becomes unreadable.
                scene.activePage = WorldLyricsPlacement.pageIndex(scene.layout.pages, now);
                // Anchors stay fixed until this phrase leaves the scene.
            }
            renderLine(matrices, consumers, cameraPos, scene, planned, now, preferences, STATE.reaction(reactive));
        }
        var gap = preview || !visible.isEmpty() ? null : timeline.interlude(now);
        if (visible.isEmpty() && gap != null && gap.visibility(now) > .01) {
            if (!gap.equals(currentInterlude) || interludeAnchor == null) {
                currentInterlude = gap;
                var calm = new WorldLyricsDirector.Composition(WorldLyricsDirector.Preset.LANDSCAPE,
                        WorldLyricsDirector.Placement.FRONT, WorldLyricsDirector.Entrance.DISSOLVE,
                        WorldLyricsDirector.Motion.BREATHE, 0, 0, 0, false, 1, 0);
                interludeAnchor = createAnchor(client, cameraPos, 1, preferences, calm,
                        WorldLyricsDirector.readingDistance(STATE.blocksPerSecond(), 2, STATE.flying(), false, director),
                        new PhraseBounds(1.5, 1));
            }
            if (interludeAnchor != null) renderInterlude(matrices, consumers, cameraPos, gap, now, preferences);
        } else { currentInterlude = null; interludeAnchor = null; }
        if (cinematicActive && drawnPieces > 0) cinematicWorldFrames++;
    }

    private static void clear() {
        LINES.clear(); currentInterlude = null; interludeAnchor = null; lastPreset = null; stageAnchor = null; visibleAt = Long.MIN_VALUE;
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
        Minecraft client=Minecraft.getInstance();
        double speed=Math.max(STATE.blocksPerSecond(),travel(client).length()*20);
        return WorldLyricsSpace.readingDistance(speed,Math.max(0,line.endMillis()-now)/1000.0,
                line.text().codePointCount(0,line.text().length()),gliding(client),
                Math.max(24,client.options.renderDistance().get()*14));
    }

    private static Vec3 placementDirection(Minecraft client) {
        Vec3 look = cameraDirection(client);
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
        Font renderer = Minecraft.getInstance().font;
        boolean romanized = preferences.isLyricsRomanization() && LyricsRenderText.hasRomanization(line);
        boolean timed = WorldLyricsPlan.hasWordTiming(line, romanized);
        var tokens = WorldLyricsPlan.tokens(line, romanized);
        // Only use token-index directives if romanization preserved the original timed segments.
        if (romanized && !cue.accents().isEmpty()) {
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
        var tokenIndices = new IdentityHashMap<WorldLyricsPlan.Token, Integer>();
        for (int index = 0; index < tokens.size(); index++) tokenIndices.put(tokens.get(index), index);
        String displayed = tokens.stream().map(WorldLyricsPlan.Token::text).reduce("", (a,b) -> a+" "+b);
        double desiredScale = .052 * preferences.getWorldLyricsSize() * Math.clamp(distance / 6, .35, 43);
        double wrapWidth = Math.clamp(distance * .65 / (desiredScale * 1.16), 80, 240);
        List<WorldLyricsStage.Group> groups;
        List<WorldLyricsLayout.Page> pages;
        if (compact) {
            pages = WorldLyricsLayout.plan(tokens, timed, LyricsAnimation.isRightToLeft(displayed), 190,
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
            groups = WorldLyricsStage.groups(cue, tokens, preferences.getWorldLyricsPresentationIntensity());
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
                    boolean glyphMotion = timed && !span.rtl() && LyricsAnimation.usesPerGlyphMotion(token.durationMillis());
                    if (glyphMotion) {
                        for (var glyph : LyricsRenderText.timedGraphemes(span.text(),token.startMillis(),token.endMillis())) {
                            double begin = renderer.width(styled(span.text().substring(0,glyph.startIndex()),preferences,styleCue));
                            double end = renderer.width(styled(span.text().substring(0,glyph.endIndex()),preferences,styleCue));
                            pieces.add(new Piece(styled(glyph.text(),preferences,styleCue),token,tokenIndices.get(token),ordinal,span.x()+begin,end-begin,
                                    span.sourceX()+begin,span.sourceWidth(),(begin+end)/2/Math.max(1,span.width()),false,
                                    span.x()+span.width()/2,segment,new WorldLyricsExperience.Motion(true),glyph.startMillis(),glyph.endMillis()));
                        }
                    } else pieces.add(new Piece(styled(span.text(),preferences,styleCue),token,tokenIndices.get(token),ordinal,span.x(),span.width(),
                            span.sourceX(),span.sourceWidth(),.5,span.rtl(),span.x()+span.width()/2,segment,null,
                            token.startMillis(),token.endMillis()));
                }
                rows.add(new DrawRow(List.copyOf(pieces),row.width(),null,0));
            }
            drawn.add(new DrawPage(List.copyOf(rows)));
        }
        return new Layout(List.copyOf(pages),List.copyOf(drawn),timed,List.copyOf(tokens),cue,groups);
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

    /** Revalidate a whole phrase on the real clock, and commit replacements as one transaction. */
    private static boolean preparePlacement(Minecraft client, Vec3 camera, SceneLine scene, long songTime,
                                            ClientPreferences preferences) {
        if (scene.suppressed && scene.cue.source().line().endMillis() <= songTime) { scene.readable = true; return false; }
        if (scene.compact && scene.compactLayout == null)
            scene.compactLayout = layout(scene.cue.source().line(), preferences, scene.requestedDistance, scene.cue, true);
        var window = WorldLyricsPresentation.window(scene.layout.cue, scene.layout.tokens, songTime);
        var active = new LinkedHashSet<Integer>();
        for (var group : scene.layout.groups)
            if (groupVisible(scene.layout, group, window, songTime, preferences.isLyricsAnimations())) active.add(group.index());
        if (active.isEmpty()) { scene.readable = true; return false; }
        boolean reposition = preferences.isWorldLyricsReposition();
        boolean changed = !active.equals(scene.observedGroups);
        if (changed) scene.observedGroups = Set.copyOf(active);
        boolean complete = scene.compact ? scene.compactAnchor != null : scene.groupAnchors.keySet().containsAll(active);
        if (!reposition && complete) { scene.readable = true; return true; }
        if (!reposition && !changed) return !scene.groupAnchors.isEmpty();
        if (!changed && frameClock < scene.nextVisibilityCheck) return complete && !scene.placementFailed;
        scene.nextVisibilityCheck = frameClock + 120_000_000L;
        boolean clear = complete, onScreen = complete;
        if (complete) for (Anchor anchor : scene.groupAnchors.values()) {
            clear &= freePanel(client, camera, anchor, scene.compact);
            onScreen &= withinView(client, camera, anchor);
        }
        scene.readable = complete && clear && onScreen;
        boolean repair = scene.recovery.evaluate(frameClock, complete && clear, scene.readable);
        if (scene.readable) {
            scene.placementFailed = false;
            return true;
        }
        if ((!changed || scene.placementFailed) && !repair) return complete && !scene.placementFailed;
        scene.recovery.attempted(frameClock);
        var previous = new LinkedHashMap<>(scene.groupAnchors);
        scene.groupAnchors.clear();
        placementOwner = scene; placementSongTime = songTime;
        try {
            if (!scene.compact) {
                // Reserve healthy anchors before searching, so a replacement cannot overlap a surviving group.
                for (var entry : previous.entrySet()) {
                    Anchor anchor = entry.getValue();
                    if (active.contains(entry.getKey()) && (!reposition || freePanel(client, camera, anchor) && withinView(client, camera, anchor)))
                        scene.groupAnchors.put(entry.getKey(), anchor);
                }
                boolean success = true;
                for (int index = 0; index < scene.layout.groups.size(); index++) {
                    var group = scene.layout.groups.get(index);
                    if (!active.contains(group.index()) || scene.groupAnchors.containsKey(group.index())) continue;
                    var page = scene.layout.pages.get(index);
                    double desired = .052 * preferences.getWorldLyricsSize() * Math.clamp(scene.requestedDistance / 6, .35, 43);
                    double width = (page.width() + 6) * desired * 1.35;
                    double height = (page.rows().size() * (client.font.lineHeight + 2) + 4) * desired * 1.35;
                    double angle = Math.toRadians(group.rotationDegrees());
                    var bounds = new PhraseBounds(width * Math.abs(Math.cos(angle)) + height * Math.abs(Math.sin(angle)),
                            height * Math.abs(Math.cos(angle)) + width * Math.abs(Math.sin(angle)));
                    Anchor anchor = createDirectedAnchor(client, camera, preferences, scene.composition,
                            scene.requestedDistance, bounds, scene.cue, group);
                    if (anchor == null) { success = false; break; }
                    scene.groupAnchors.put(group.index(), anchor);
                }
                if (success) {
                    scene.base = scene.groupAnchors.values().iterator().next();
                    scene.placementFailed = false; scene.readable = true; recoveryCount++;
                    yieldRetainedPhrases(scene, songTime);
                    return true;
                }
                if (!reposition && !previous.isEmpty()) {
                    scene.readable = false;
                    return !scene.groupAnchors.isEmpty();
                }
                scene.groupAnchors.clear();
            }
            if (scene.compactLayout == null)
                scene.compactLayout = layout(scene.cue.source().line(), preferences, scene.requestedDistance, scene.cue, true);
            Anchor compact = findCompactAnchor(client, camera, scene, preferences);
            if (compact != null) {
                scene.compact = true; scene.compactAnchor = compact; scene.base = compact;
                scene.groupAnchors.put(0, compact);
                scene.placementFailed = false; scene.readable = true; recoveryCount++;
                yieldRetainedPhrases(scene, songTime);
                return true;
            }
            // Never draw a partial replacement and silently lose the remaining words.
            scene.groupAnchors.putAll(previous); scene.placementFailed = true; scene.readable = false;
            return false;
        } finally { placementOwner = null; }
    }

    private static void yieldRetainedPhrases(SceneLine owner, long songTime) {
        if (owner.cue.source().line().endMillis() <= songTime) return;
        for (var other : LINES.values()) {
            if (other == owner || other.cue.source().line().endMillis() > songTime) continue;
            boolean overlaps = owner.compact;
            for (var a : owner.groupAnchors.values()) for (var b : other.groupAnchors.values())
                overlaps |= Math.hypot(a.x - b.x, a.z - b.z) < (a.width + b.width) * .55
                        && Math.abs(a.y - b.y) < (a.height + b.height) * .55;
            if (overlaps) { other.suppressed = true; other.groupAnchors.clear(); other.readable = true; }
        }
    }

    /** Find a smaller, ordered world-space phrase inside the visible free space near the camera. */
    private static Anchor findCompactAnchor(Minecraft client, Vec3 camera, SceneLine scene, ClientPreferences preferences) {
        var pages = scene.compactLayout.pages;
        double desired = .052 * preferences.getWorldLyricsSize() * Math.clamp(scene.requestedDistance / 6, .35, 43);
        double width = pages.stream().mapToDouble(page -> page.width() + 8).max().orElse(80) * desired * 1.25;
        double height = pages.stream().mapToInt(page -> page.rows().size()).max().orElse(1)
                * (client.font.lineHeight + 5) * desired * 1.25;
        var probes = WorldLyricsRecovery.compactProbes(scene.requestedDistance, width, height);
        Vec3 forward = cameraDirection(client);
        Vec3 right = new Vec3(-forward.z, 0, forward.x).normalize();
        if (right.lengthSqr() < .001) right = new Vec3(-1, 0, 0);
        Vec3 up = right.cross(forward).normalize();
        for (var probe : probes) {
            Vec3 center = camera.add(forward.scale(probe.distance())).add(right.scale(probe.side())).add(up.scale(probe.up()));
            Vec3 normal = camera.subtract(center).normalize();
            double rotation = Math.atan2(normal.x, normal.z);
            Vec3 across = new Vec3(Math.cos(rotation), 0, -Math.sin(rotation));
            Anchor anchor = new Anchor(center.x, center.y, center.z, normal.x, normal.y, normal.z, across.x, across.z,
                    (float) rotation, false, probe.width(), probe.height(), 1, camera.distanceTo(center));
            if (freePanel(client, camera, anchor, true) && withinView(client, camera, anchor) && separated(anchor)) return anchor;
        }
        return null;
    }

    private static Vec3 cameraDirection(Minecraft client) {
        double yaw = Math.toRadians(client.gameRenderer.mainCamera().yRot());
        double pitch = Math.toRadians(client.gameRenderer.mainCamera().xRot());
        return new Vec3(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
    }

    /** Test the actual viewpoint, independently of the flight trajectory used for new placements. */
    private static boolean withinView(Minecraft client, Vec3 camera, Anchor anchor) {
        Vec3 forward = cameraDirection(client), to = new Vec3(anchor.x, anchor.y, anchor.z).subtract(camera);
        double ahead = to.dot(forward);
        if (ahead < .1 || ahead > 384) return false;
        double facing = -(anchor.normalX * to.x + anchor.normalY * to.y + anchor.normalZ * to.z) / to.length();
        if (facing < .35) return false;
        Vec3 right = new Vec3(-forward.z, 0, forward.x).normalize();
        if (right.lengthSqr() < .001) right = new Vec3(-1, 0, 0);
        Vec3 up = right.cross(forward).normalize();
        double fov = Math.clamp(client.options.fov().get(), 30, 110);
        double vertical = ahead * Math.tan(Math.toRadians(fov * .5)) * .94;
        double aspect = client.getWindow().getScreenWidth() / (double) Math.max(1, client.getWindow().getScreenHeight());
        return Math.abs(to.dot(right)) + anchor.width * .45 <= vertical * aspect
                && Math.abs(to.dot(up)) + anchor.height * .45 <= vertical;
    }

    private static void renderLine(PoseStack matrices, MultiBufferSource consumers, Vec3 cameraPos,
                                   SceneLine scene, WorldLyricsPlan.WorldLine planned, long now,
                                   ClientPreferences preferences, WorldLyricsPlan.PlayerReaction reaction) {
        Minecraft client = Minecraft.getInstance();
        Font renderer = client.font;
        if (!preparePlacement(client, cameraPos, scene, now, preferences)) return;
        Layout layout = scene.compact ? scene.compactLayout : scene.layout;
        boolean animate = preferences.isLyricsAnimations();
        double lineHeight = renderer.lineHeight + 5;
        double strength = WorldLyricsExperience.spicyStrength(preferences.getWorldLyricsStyle(),
                preferences.getWorldLyricsPresentationIntensity(), preferences.getWorldLyricsAnimationIntensity(), animate);
        var window = scene.compact ? WorldLyricsRecovery.compactWindow(layout.cue, layout.tokens, now)
                : WorldLyricsPresentation.window(layout.cue, layout.tokens, now);
        Set<Integer> emittedWords = new HashSet<>();
        WorldLyricsLights.Point activeLight = null;
        int firstPage = scene.compact ? WorldLyricsPlacement.pageIndex(layout.pages, now) : 0;
        // Timed page selection has a crossfade lead. Keep the old compact page until the next can draw.
        while (firstPage > 0 && !groupVisible(layout, layout.groups.get(firstPage), window, now, animate)) firstPage--;
        int lastPage = scene.compact ? Math.min(firstPage + 1, layout.pages.size()) : layout.pages.size();
        for (int pageIndex = firstPage; pageIndex < lastPage; pageIndex++) {
            var group = layout.groups.get(pageIndex);
            if (!groupVisible(layout, group, window, now, animate)) continue;
            var page = layout.pages.get(pageIndex);
            Anchor anchor = scene.compact ? scene.compactAnchor : scene.groupAnchors.get(group.index());
            if (anchor == null) continue;
            double distance = cameraPos.distanceTo(new Vec3(anchor.x, anchor.y, anchor.z));
            if (distance > 384 || distance < .08) continue;
            double visibility = (planned.backing() ? .65 : 1)
                    * (scene.compact ? 1 : Math.clamp(distance / Math.max(1, anchor.distance * .35), 0, 1));
            if (animate && cinematicHandoffAt > 0)
                visibility *= LyricsAnimation.smoother((frameClock - cinematicHandoffAt) / 240_000_000.0);
            double pixelScale = scene.compact
                    ? Math.min(anchor.width * .88 / Math.max(1, (page.width() + 8) * 1.22),
                            anchor.height * .88 / Math.max(1, page.rows().size() * lineHeight * 1.22))
                    : WorldLyricsLayout.pixelScale(page.width(), page.rows().size(), anchor.width,
                            anchor.height, anchor.distance, preferences.getWorldLyricsSize(), planned.backing(), lineHeight);
            double accentFit = layout.cue.accents().stream().filter(accent -> accent.tokenIndex() >= group.firstToken()
                    && accent.tokenIndex() < group.endToken()).mapToDouble(WorldLyricsPresentation.Accent::scale).max().orElse(1);
            pixelScale /= Math.max(1, accentFit / (scene.compact ? 1 : 1.18));
            pixelScale *= group.scale();
            double angle = Math.toRadians(group.rotationDegrees()), cos = Math.cos(angle), sin = Math.sin(angle);
            double extrude = scene.composition.depth() * Math.clamp(anchor.distance / 6, .5, 43);
            int light = Math.clamp(client.level.getMaxLocalRawBrightness(BlockPos.containing(anchor.x, anchor.y, anchor.z)), 0, 15);
            int sideColor = materialSideColor(preferences, scene.composition, light);
            var drawPage = layout.drawn.get(pageIndex);
            for (int rowIndex = 0; rowIndex < drawPage.rows.size(); rowIndex++) {
                DrawRow row = drawPage.rows.get(rowIndex);
                double rowY = ((drawPage.rows.size() - 1) / 2.0 - rowIndex) * lineHeight * pixelScale;
                for (Piece piece : row.pieces) {
                    var appearance = WorldLyricsPresentation.sample(layout.cue, layout.tokens, window, piece.tokenIndex, now, animate);
                    if (appearance.alpha() <= .016) continue;
                    int word = layout.timed ? piece.tokenIndex : piece.wordIndex;
                    while (layout.timed && word > 0 && !layout.tokens.get(word).startsWord()) word--;
                    if (!emittedWords.contains(word)) {
                        if (visibleWords >= WorldLyricsPresentation.MAX_WORDS) continue;
                        emittedWords.add(word); visibleWords++;
                    }
                    double pieceAlpha = visibility * appearance.alpha();
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
                    float scale = (float) (pixelScale * segmentScale * glyphScale * appearance.scale());
                    float horizontalScale = splitWord ? (float) pixelScale : scale;
                    double elevation = (piece.segmentMotion.elevation()
                            + (piece.glyphMotion == null ? 0 : piece.glyphMotion.elevation())) * renderer.lineHeight * pixelScale * strength;
                    double localSide = center + appearance.offsetX() * lineHeight * pixelScale;
                    // Independent blocks already express the staircase; retain only its entry movement locally.
                    double localOffset = appearance.offsetY();
                    if (scene.cue.composition() == WorldLyricsPresentation.Composition.STAIRCASE)
                        localOffset += (WorldLyricsPresentation.groupIndex(scene.cue, piece.tokenIndex) % WorldLyricsPresentation.MAX_GROUPS) * .45;
                    double localUp = rowY + localOffset * lineHeight * pixelScale + renderer.lineHeight * scale / 2 + elevation;
                    double side = localSide * cos - localUp * sin;
                    double up = localSide * sin + localUp * cos;
                    matrices.pushPose();
                    place(matrices, cameraPos, anchor, side, up, 0);
                    matrices.mulPose(new Quaternionf().rotateZ((float) angle));
                    matrices.scale(horizontalScale, -scale, scale);
                    double progress = LyricsAnimation.timedProgress(now, piece.token.startMillis(), piece.token.endMillis());
                    double boundary = (piece.rtl ? 1 - progress : progress) * piece.fragmentWidth - piece.fragmentX - piece.width / 2;
                    int face = appearance.color() < 0 ? preferences.getWorldLyricsFaceColor() : appearance.color();
                    double pieceDepth = !scene.compact && (preferences.isWorldLyricsVolumetric() || appearance.volumetric()) ? extrude : 0;
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
                    drawFace(renderer, piece, layout.timed, progress, boundary, pieceAlpha, face, false, matrices, consumers);
                    matrices.popPose();
                }
            }
        }
        if (activeLight != null) WorldLyricsLights.offer(activeLight);
    }

    private static boolean groupVisible(Layout layout, WorldLyricsStage.Group group, WorldLyricsPresentation.Window window,
                                        long now, boolean animate) {
        for (int index = group.firstToken(); index < group.endToken(); index++)
            if (WorldLyricsPresentation.sample(layout.cue, layout.tokens, window, index, now, animate).alpha() > .016) return true;
        return false;
    }

    private static void pruneGroupAnchors(SceneLine scene, long now, boolean animate) {
        if (scene.layout == null || scene.compact) return;
        var window = WorldLyricsPresentation.window(scene.layout.cue, scene.layout.tokens, now);
        var live = new HashSet<Integer>();
        for (var group : scene.layout.groups) if (groupVisible(scene.layout, group, window, now, animate)) live.add(group.index());
        scene.groupAnchors.keySet().retainAll(live);
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
        matrices.mulPose(new Quaternionf().rotateY(anchor.rotationYaw).rotateX((float) (-Math.PI / 2)));
        matrices.scale(scale, -scale, scale);
        int alpha = Math.clamp((int) Math.round(85 * visibility), 0, 140);
        FontCompat.drawInBatch(renderer, piece.text, (float) (-piece.width / 2), 0, alpha << 24 | 0x101015, false,
                matrices.last().pose(), consumers, Font.DisplayMode.NORMAL,
                0, LightTexture.FULL_BRIGHT);
        matrices.popPose();
    }

    private static void place(PoseStack matrices, Vec3 camera, Anchor anchor, double side, double up, double depth) {
        matrices.translate(anchor.x + anchor.rightX * side + anchor.normalY * anchor.rightZ * up + anchor.normalX * depth - camera.x,
                anchor.y + (anchor.normalZ * anchor.rightX - anchor.normalX * anchor.rightZ) * up + anchor.normalY * depth - camera.y,
                anchor.z + anchor.rightZ * side - anchor.normalY * anchor.rightX * up + anchor.normalZ * depth - camera.z);
        matrices.mulPose(new Quaternionf().rotateY(anchor.rotationYaw).rotateX((float) -Math.asin(anchor.normalY)));
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
            Component dot = styled("•", preferences);
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
                                               PhraseBounds bounds, WorldLyricsPresentation.Cue cue, WorldLyricsStage.Group group) {
        Vec3 forward = placementDirection(client);
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

        double distance = Math.clamp(requestedDistance, 3, Math.max(24, client.options.renderDistance().get() * 14));
        var fallback = region == WorldLyricsPresentation.Region.LEFT ? WorldLyricsPresentation.Region.RIGHT : WorldLyricsPresentation.Region.LEFT;
        var lanes = List.of(region, fallback, WorldLyricsPresentation.Region.TOP, WorldLyricsPresentation.Region.RIGHT);
        for (int attempt = 0; attempt < 16; attempt++) {
            double ratio = distance / Math.max(.8, requestedDistance);
            double width = Math.clamp(bounds.width * ratio * 1.05, .35, distance * .58);
            double height = Math.clamp(bounds.height * ratio * 1.05, .25, distance * .30);
            var lane = lanes.get(attempt % lanes.size());
            double side = switch (lane) {
                case LEFT -> -(distance * .18 + width * .55);
                case RIGHT -> distance * .18 + width * .55;
                case TOP -> focusAboveAim && attempt % 4 == 0 ? 0 : WorldLyricsStage.topSideSign(group, attempt) * (distance * .28 + width * .25);
                default -> 0;
            };
            double vertical = switch (lane) {
                case TOP -> distance * .26 + height * .60;
                case BOTTOM -> -(distance * .16 + height * .55);
                case LEFT, RIGHT -> ((group.index() & 1) == 0 ? .06 : -.06) * distance;
                default -> 0;
            };
            Vec3 center = camera.add(forward.scale(distance)).add(right.scale(side)).add(up.scale(vertical));
            Vec3 towards = camera.subtract(center).normalize();
            double rotation = Math.atan2(towards.x, towards.z);
            double ny = Math.clamp(towards.y, -.75, .75), horizontal = Math.sqrt(1 - ny * ny);
            Vec3 normal = new Vec3(Math.sin(rotation) * horizontal, ny, Math.cos(rotation) * horizontal);
            Vec3 across = new Vec3(Math.cos(rotation), 0, -Math.sin(rotation));
            Anchor candidate = new Anchor(center.x, center.y, center.z, normal.x, normal.y, normal.z,
                    across.x, across.z, (float) rotation, false, width, height, 1, camera.distanceTo(center));
            boolean clear = freePanel(client, camera, candidate), separate = separated(candidate);
            if (clear && separate && withinView(client, camera, candidate)) return candidate;

            if (attempt == 3) distance = Math.max(1.5, requestedDistance * .68);
            if (attempt == 7) distance = Math.min(requestedDistance, 4);
            if (attempt == 11) distance = Math.min(requestedDistance, 2.5);
        }
        return null;
    }

    private static boolean separated(Anchor candidate) {
        for (var scene : LINES.values()) {
            if (placementOwner != null && scene != placementOwner
                    && placementOwner.cue.source().line().endMillis() > placementSongTime
                    && scene.cue.source().line().endMillis() <= placementSongTime) continue;
            for (Anchor other : scene.groupAnchors.values()) {
            double dx = other.x - candidate.x, dz = other.z - candidate.z;
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            if (horizontal < (other.width + candidate.width) * .55
                    && Math.abs(other.y - candidate.y) < (other.height + candidate.height) * .55) return false;
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
        double distance=Math.max(3,Math.min(requestedDistance,Math.max(24,client.options.renderDistance().get()*14)));
        double ratio=distance/Math.max(1,requestedDistance);
        double width=Math.clamp(bounds.width*ratio*1.15,1.2,distance*.62);
        double height=Math.clamp(bounds.height*ratio,Math.min(1.8,distance*.30),distance*(position==WorldLyricsSpace.Position.SKY || position==WorldLyricsSpace.Position.ADAPTIVE && outdoors ? .38 : .78));
        if (cinematicActive) { width = Math.min(width, distance * .42); height = Math.min(height, distance * .24); }
        Vec3 up = cinematicActive ? right.cross(forward).normalize() : new Vec3(0, 1, 0);
        for (int sign : new int[]{1,-1}) {
            var offset = cinematicActive ? WorldLyricsSpace.cinematicOffset(position, distance, width, sign)
                    : WorldLyricsSpace.offset(position,outdoors,gliding,distance,width,height,sign);
            Vec3 center=camera.add(forward.scale(offset.forward())).add(right.scale(offset.side())).add(up.scale(offset.up()));
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
        if (attempt<3 && requestedDistance>4)
            return createSpatialAnchor(client,camera,bands,preferences,composition,requestedDistance*.68,bounds,attempt+1);
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
        return freePanel(client, camera, anchor, false);
    }

    private static boolean freePanel(Minecraft client, Vec3 camera, Anchor anchor, boolean strict) {
        // Check the planned rectangle once, before it becomes a fixed world anchor.
        for (double side : new double[]{-.5, 0, .5}) {
            for (double up : new double[]{-.5, 0, .5}) {
                Vec3 point = new Vec3(anchor.x + anchor.rightX * anchor.width * side + anchor.normalY * anchor.rightZ * anchor.height * up,
                        anchor.y + (anchor.normalZ * anchor.rightX - anchor.normalX * anchor.rightZ) * anchor.height * up,
                        anchor.z + anchor.rightZ * anchor.width * side - anchor.normalY * anchor.rightX * anchor.height * up);
                if (!client.level.hasChunkAt(BlockPos.containing(point))) return false;
                var hit = ray(client, camera, point);
                if (hit.getType() == HitResult.Type.BLOCK
                        && (strict || hit.getLocation().distanceTo(point) > .08)) return false;
            }
        }
        return true;
    }

    private static BlockHitResult ray(Minecraft client, Vec3 start, Vec3 end) {
        return client.level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, client.player));
    }

    /** A sign stands only where its whole lower edge has stable, nearly level support. */
    private static Anchor findGroundSurface(Minecraft client, Vec3 camera, int bands,
                                            PhraseBounds bounds, WorldLyricsDirector.Preset preset, double desiredDistance) {
        double yaw = Math.toRadians(client.gameRenderer.mainCamera().yRot());
        double width = Math.clamp(bounds.width, 1.5, 7.5);
        double height = Math.clamp(bounds.height, 1.1 * bands, 3.2 * bands);
        int[] sides = (preset.ordinal() & 1) == 0 ? new int[]{-1, 1} : new int[]{1, -1};
        Anchor best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int sideDirection : sides) {
            for (double distance : new double[]{5.5, 8, 11, 14, 18}) {
                double halfAngle = Math.toDegrees(Math.atan2(width * .5, distance));
                double offset = sideDirection * Math.max(18, halfAngle + 9);
                if (Math.abs(offset) + halfAngle > 34) continue;
                double angle = yaw + Math.toRadians(offset);
                Vec3 forward = new Vec3(-Math.sin(angle), 0, Math.cos(angle));
                Vec3 right = new Vec3(-Math.cos(angle), 0, -Math.sin(angle));
                Vec3 center = camera.add(forward.scale(distance));
                double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
                boolean safe = true;
                for (double side : new double[]{-.5, 0, .5}) for (double front : new double[]{-.18, .18}) {
                    Vec3 sample = center.add(right.scale(side * width)).add(forward.scale(front));
                    BlockHitResult hit = ray(client, sample.add(0, 3, 0), sample.add(0, -8, 0));
                    if (hit.getType() != HitResult.Type.BLOCK || hit.getDirection() != Direction.UP
                            || !client.level.hasChunkAt(hit.getBlockPos())
                            || !client.level.getFluidState(hit.getBlockPos()).isEmpty()
                            || !client.level.getFluidState(BlockPos.containing(hit.getLocation().add(0, .05, 0))).isEmpty()
                            || !solidFace(client, hit)) { safe = false; break; }
                    low = Math.min(low, hit.getLocation().y);
                    high = Math.max(high, hit.getLocation().y);
                }
                if (!safe || high - low > .25 || Math.abs(high - camera.y) > 5) continue;
                Vec3 panel = new Vec3(center.x, high + height * .5 + .20, center.z);
                Vec3 normal = forward.scale(-1);
                Anchor candidate = new Anchor(panel.x, panel.y, panel.z, normal.x, 0, normal.z,
                        right.x, right.z, (float) Math.atan2(normal.x, normal.z), false,
                        width, height, bands, camera.distanceTo(panel), true);
                if (!freePanel(client, camera, candidate)) continue;
                double score = WorldLyricsPlacement.score(new WorldLyricsPlacement.Candidate(
                        WorldLyricsPlacement.Surface.GROUND, candidate.distance,
                        Math.min(desiredDistance, 18), offset, 1, high - low,
                        true, true, true, true));
                if (score > bestScore) { best = candidate; bestScore = score; }
            }
        }
        return best;
    }

    private static Anchor findSurface(Minecraft client, Vec3 camera, int bands, PhraseBounds bounds) {
        List<WallCandidate> candidates = new ArrayList<>();
        double yaw = client.gameRenderer.mainCamera().yRot();
        double pitch = client.gameRenderer.mainCamera().xRot();
        // A bounded fan around the camera finds walls even when the crosshair points at a doorway or the floor.
        for (int yawOffset : new int[]{0, -18, 18, -35, 35}) {
            for (int pitchOffset : new int[]{0, -14, 14}) {
                double ry = Math.toRadians(yaw + yawOffset);
                double rp = Math.toRadians(Math.clamp(pitch + pitchOffset, -35, 35));
                Vec3 look = new Vec3(-Math.sin(ry) * Math.cos(rp), -Math.sin(rp), Math.cos(ry) * Math.cos(rp));
                BlockHitResult hit = ray(client, camera, camera.add(look.scale(12)));
                if (hit.getType() != HitResult.Type.BLOCK || !hit.getDirection().getAxis().isHorizontal()) continue;
                double distance = camera.distanceTo(hit.getLocation());
                if (distance < 1.5 || !solidFace(client, hit)) continue;
                Direction face = hit.getDirection();
                Vec3 normal = new Vec3(face.getStepX(), 0, face.getStepZ());
                double facing = camera.subtract(hit.getLocation()).normalize().dot(normal);
                if (facing < .4) continue;
                double score = facing * 4 - Math.abs(yawOffset) / 25.0 - Math.abs(pitchOffset) / 35.0
                        - distance * .08 - (Math.abs(yawOffset) < 10 ? .9 : 0);
                candidates.add(new WallCandidate(hit, score));
            }
        }
        candidates.sort(Comparator.comparingDouble(WallCandidate::score).reversed());
        List<WallCandidate> probes = new ArrayList<>();
        for (var candidate : candidates) {
            boolean duplicate = probes.stream().anyMatch(previous -> previous.hit.getDirection() == candidate.hit.getDirection()
                    && previous.hit.getLocation().distanceToSqr(candidate.hit.getLocation()) < 2.25);
            if (!duplicate) probes.add(candidate);
            if (probes.size() == 2) break;
        }
        Anchor best = null; double bestScore = -Double.MAX_VALUE;
        // At most two 11x7 grids: all probing happens at anchor creation, never per glyph/frame.
        for (var candidate : probes) {
            var hit = candidate.hit;
            Direction face = hit.getDirection();
            double nx = face.getStepX(), nz = face.getStepZ();
            Vec3 normal = new Vec3(nx, 0, nz), right = new Vec3(nz, 0, -nx);
            boolean[][] support = new boolean[7][11];
            for (int row = 0; row < 7; row++) {
                for (int column = 0; column < 11; column++) {
                    Vec3 point = hit.getLocation().add(right.scale((column - 5) * .75)).add(0, (row - 3) * .75, 0);
                    var probe = ray(client, point.add(normal.scale(.2)), point.subtract(normal.scale(.2)));
                    support[row][column] = probe.getType() == HitResult.Type.BLOCK && probe.getDirection() == face
                            && client.level.hasChunkAt(probe.getBlockPos())
                            && client.level.getFluidState(probe.getBlockPos()).isEmpty()
                            && probe.getLocation().distanceToSqr(point) < .003 && solidFace(client, probe);
                }
            }
            var rectangle = WorldLyricsLayout.wallRectangle(support);
            if (rectangle == null) continue;
            double width = (rectangle.right() - rectangle.left()) * .75 - .1;
            double height = (rectangle.top() - rectangle.bottom()) * .75 - .1;
            if (width < Math.max(2, bounds.width * .85) || height < Math.max(1.1 * bands, bounds.height * .85)) continue;
            Vec3 center = hit.getLocation().add(right.scale(((rectangle.left() + rectangle.right()) / 2.0 - 5) * .75))
                    .add(0, ((rectangle.bottom() + rectangle.top()) / 2.0 - 3) * .75, 0).add(normal.scale(.065));
            // The final rectangle center must also be visible from the actual camera (e.g. around doorframes).
            var sight = ray(client, camera, center);
            if (sight.getType() == HitResult.Type.BLOCK && sight.getLocation().distanceTo(center) > .12) continue;
            double score = candidate.score + Math.min(12, width * height) * .2;
            Anchor panel = new Anchor(center.x, center.y, center.z, nx, 0, nz, nz, -nx,
                    (float) Math.atan2(nx, nz), true, width, height, bands, camera.distanceTo(center));
            if (score > bestScore && freePanel(client, camera, panel)) {
                bestScore = score;
                best = panel;
            }
        }
        return best;
    }

    private static boolean solidFace(Minecraft client, BlockHitResult hit) {
        return client.level.getBlockState(hit.getBlockPos()).isFaceSturdy(client.level, hit.getBlockPos(), hit.getDirection());
    }

    private static double findGround(Minecraft client, double x, double z, double cameraY) {
        var hit = ray(client, new Vec3(x, cameraY + 2, z), new Vec3(x, cameraY - 8, z));
        return hit.getType() == HitResult.Type.BLOCK ? hit.getLocation().y : cameraY - 2;
    }

    private record LineKey(LyricLine line, boolean backing) {
        @Override public int hashCode() { return System.identityHashCode(line) * 31 + (backing ? 1 : 0); }
        @Override public boolean equals(Object o) { return o instanceof LineKey k && line == k.line && backing == k.backing; }
    }
    private record Piece(Component text, WorldLyricsPlan.Token token, int tokenIndex, int wordIndex, double x, double width,
                         double fragmentX, double fragmentWidth, double fraction, boolean rtl, double segmentCenter,
                         WorldLyricsExperience.Motion segmentMotion, WorldLyricsExperience.Motion glyphMotion,
                         long glyphStart, long glyphEnd) { }
    private record DrawRow(List<Piece> pieces, double width, Component backdrop, double backdropWidth) { }
    private record DrawPage(List<DrawRow> rows) { }
    private record Layout(List<WorldLyricsLayout.Page> pages, List<DrawPage> drawn, boolean timed, List<WorldLyricsPlan.Token> tokens, WorldLyricsPresentation.Cue cue, List<WorldLyricsStage.Group> groups) { }
    private record PhraseBounds(double width, double height) { }
    static final class SceneLine {
        Anchor base;
        WorldLyricsPresentation.Cue cue;
        double requestedDistance;
        final Map<Integer, Anchor> groupAnchors = new LinkedHashMap<>();
        final WorldLyricsRecovery.Monitor recovery = new WorldLyricsRecovery.Monitor();
        Layout compactLayout;
        Anchor compactAnchor;
        boolean compact, readable, suppressed, placementFailed;
        long nextVisibilityCheck;
        Set<Integer> observedGroups = Set.of();
        Anchor from;
        final int slot;
        final WorldLyricsDirector.Composition composition;
        final PhraseBounds bounds;
        int worldLight;
        Layout layout;
        long anchoredAt;
        long handoffAt;
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
                          boolean ground) {
        Anchor(double x, double y, double z, double normalX, double normalY, double normalZ,
               double rightX, double rightZ, float rotationYaw, boolean wall,
               double width, double height, int bands, double distance) {
            this(x, y, z, normalX, normalY, normalZ, rightX, rightZ, rotationYaw,
                    wall, width, height, bands, distance, false);
        }
    }
}
