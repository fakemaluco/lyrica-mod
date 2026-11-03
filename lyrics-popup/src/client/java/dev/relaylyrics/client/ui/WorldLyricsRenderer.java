package dev.relaylyrics.client.ui;

import dev.relaylyrics.client.ClientPreferences;
import dev.relaylyrics.client.CompatibilityManager;
import dev.relaylyrics.client.music.WorldLyricsExperience;
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
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
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
    private static boolean cinematicSuspended;
    static int probeCourseChanges() { return courseChanges; }
    static boolean probeCinematicSuspended() { return cinematicSuspended && LINES.isEmpty() && drawnPieces == 0; }
    private static Vec3d probeVelocity;
    private static boolean probeGliding;
    static void probeFlight(Vec3d velocity, boolean gliding) {
        if (!Boolean.getBoolean("lyrica.worldExperienceSelfTest")) throw new IllegalStateException("Opt-in probe only");
        probeVelocity=velocity; probeGliding=gliding;
    }
    private static boolean gliding(MinecraftClient client) {
        return Boolean.getBoolean("lyrica.worldExperienceSelfTest") && probeGliding || WorldLyricsRenderHook.gliding(client.player);
    }
    static double probeAnchorDistance() { return LINES.isEmpty() ? 0 : LINES.values().iterator().next().base.distance; }
    static double probeTravelAlignment() {
        var client=MinecraftClient.getInstance();
        if (LINES.isEmpty()) return -1;
        Anchor anchor=LINES.values().iterator().next().base;
        return new Vec3d(anchor.x,anchor.y,anchor.z).subtract(WorldLyricsRenderHook.cameraPosition(client)).normalize().dotProduct(travel(client).normalize());
    }
    static boolean probeHasVolume() { return drawnMeshes>0 && drawnHalos==0; }
    static boolean probeFontMatchesPopup() {
        var expected=PlatformTextStyles.withFont(Text.literal("X"),RelayLyricsClient.preferences().getLyricsFont().id()).getStyle().getFont();
        return LINES.values().stream().filter(line -> line.layout!=null).flatMap(line -> line.layout.drawn.stream())
                .flatMap(page -> page.rows.stream()).flatMap(row -> row.pieces.stream()).allMatch(piece -> piece.text.getStyle().getFont().equals(expected));
    }
    private static int drawnPieces, drawnMeshes, drawnHalos;
    static boolean verifyProbe(int expectedLines, boolean safe) { return LINES.size() >= expectedLines && drawnPieces > 0 && (!safe || drawnMeshes == 0); }
    static boolean probeWall() { return LINES.values().stream().anyMatch(scene -> scene.base.wall); }
    static String probeSummary() { return "wall=" + probeWall() + ", lines=" + LINES.size() + ", pieces=" + drawnPieces + ", meshes=" + drawnMeshes + ", halos=" + drawnHalos + ", safe=" + safeRendering; }


    private static Object travelWorld;
    private static Vec3d previousPlayerPosition;
    private static Vec3d playerTravelPerTick = Vec3d.ZERO;
    private static final LyricLine PREVIEW_LINE = new LyricLine(0, 15_000,
            "Letras fazem parte do mundo",
            List.of(new TimedWord(0, 3_000, "Letras"), new TimedWord(3_000, 6_000, "fazem"),
                    new TimedWord(6_000, 9_000, "parte"), new TimedWord(9_000, 12_000, "do"),
                    new TimedWord(12_000, 15_000, "mundo")), null, false, null, null);
    private static long previewStartedNanos;

    private WorldLyricsRenderer() { }

    /** Shows a timed sample in the current world even when no music is playing. */
    public static void previewCurrentPhrase() { clear(); previewStartedNanos = System.nanoTime(); }

    public static void tickPlayerMotion(MinecraftClient client) {
        if (client.player == null || client.world == null || !RelayLyricsClient.preferences().isWorldLyrics()) {
            clear(); STATE.clear(); timelineDocument=null; timeline=null; visibleCache=List.of();
            previousPlayerPosition = null; travelWorld = null; playerTravelPerTick = Vec3d.ZERO; return;
        }
        Vec3d position = new Vec3d(client.player.getX(), client.player.getY(), client.player.getZ());
        if (travelWorld == client.world && previousPlayerPosition != null) {
            Vec3d delta = position.subtract(previousPlayerPosition);
            if (delta.lengthSquared() > 64) { clear(); STATE.clear(); playerTravelPerTick = client.player.getVelocity(); }
            else playerTravelPerTick = delta;
        } else playerTravelPerTick = client.player.getVelocity();
        previousPlayerPosition = position; travelWorld = client.world;
    }

    private static Vec3d travel(MinecraftClient client) {
        if (Boolean.getBoolean("lyrica.worldExperienceSelfTest") && probeVelocity!=null) return probeVelocity;
        return travelWorld == client.world && previousPlayerPosition != null ? playerTravelPerTick : client.player.getVelocity();
    }

    public static void render(MatrixStack matrices, VertexConsumerProvider consumers, Vec3d cameraPos) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPreferences preferences = RelayLyricsClient.preferences();
        LyricsOverlay overlay = RelayLyricsClient.overlay();
        // Auxiliary shader passes must not advance the scene from a light's camera.
        if (CompatibilityManager.worldShadowPass()) return;
        if (!preferences.isLyricsVisible()) return;
        if (!preferences.isWorldLyrics() || overlay == null || client.player == null
                || client.world == null || matrices == null || consumers == null) {
            clear(); STATE.clear(); return;
        }
        if (!Objects.equals(wallMode, preferences.isWorldLyricsAutoWalls())) {
            clear(); wallMode = preferences.isWorldLyricsAutoWalls();
        }
        frameClock=System.nanoTime(); WorldLyricsLights.begin(client.world,frameClock);
        // Ji moves and cuts its own camera. Its caption owns the lyrics until control returns.
        if (CompatibilityManager.jiAfkCinematicActive()) {
            if (!cinematicSuspended) { clear(); STATE.clear(); }
            cinematicSuspended = true;
            drawnPieces = drawnMeshes = drawnHalos = 0;
            return;
        }
        cinematicSuspended = false;
        var playback = overlay.worldPlayback();
        long elapsedPreview = (System.nanoTime() - previewStartedNanos) / 1_000_000;
        boolean preview = previewStartedNanos != 0 && elapsedPreview >= 0 && elapsedPreview <= 15_450;
        // Untimed text belongs in the scrollable popup; it cannot be placed at a musical moment.
        if (!preview && (overlay.document() == null || overlay.document().metadata().syncMode()
                == dev.relaylyrics.protocol.lyrics.LyricsSyncMode.STATIC)) {
            clear(); STATE.clear(); return;
        }
        long now = preview ? elapsedPreview : playback.positionMillis();
        if (!Objects.equals(trackIdentity,playback.track()) || trackRevision != playback.revision()) {
            trackIdentity = playback.track(); trackRevision = playback.revision();
            trackCache = playback.track() + "|seek=" + playback.revision();
        }
        String track = preview ? "world-preview" : trackCache;
        boolean playing = preview || playback.playing();
        List<LyricLine> sourceLines = preview ? List.of(PREVIEW_LINE)
                : overlay.document() == null ? List.of() : overlay.document().lines();
        boolean reactive = false;
        Vec3d velocity = travel(client);
        if (STATE.update(client.world, preview ? PREVIEW_LINE : overlay.document(), track, now, playing, System.nanoTime(),
                client.player.isOnGround(), velocity.length() > .05 && (client.player.getAbilities().flying || gliding(client)),
                velocity.length(), reactive)) clear();
        WorldLyricsDirector.Settings director = directorSettings(preferences);
        long configureNow = System.nanoTime();
        if (configureNow - configureAt >= 50_000_000L) {
            configureAt = configureNow;
            String anchors = preferences.getWorldLyricsStyle() + "|" + preferences.getWorldLyricsPosition()
                    + "|" + preferences.getWorldLyricsDepth() + "|" + preferences.getWorldLyricsQuality();
            if (!Objects.equals(anchors, anchorStyle)) { clear(); anchorStyle = anchors; }
            String style = preferences.getLyricsFont().id() + "|" + preferences.getGoogleFontFamily()
                    + "|" + preferences.getLyricsFontWeight() + "|" + preferences.isLyricsRomanization()
                    + "|" + overlay.fontLayoutRevision() + "|" + preferences.getWorldLyricsSize();
            if (!Objects.equals(style, layoutStyle) || fontStorage != client.textRenderer) {
                LINES.values().forEach(value -> value.layout = null);
                WorldTextMesh.clear(); layoutStyle = style; fontStorage = client.textRenderer;
            }
            safeRendering = false;
        }
        if (timelineDocument != overlay.document() || timeline == null) {
            timelineDocument = overlay.document(); timeline = new WorldLyricsExperience.Timeline(overlay.document());
            visibleAt = Long.MIN_VALUE;
        }
        if (visibleAt == Long.MIN_VALUE || now < visibleAt || now - visibleAt >= 25) {
            visibleAt = now; visibleCache = timeline.visible(now);
        }
        List<WorldLyricsPlan.WorldLine> visible = preview
                ? List.of(new WorldLyricsPlan.WorldLine(PREVIEW_LINE, LyricAlignment.NORMAL, false)) : visibleCache;
        drawnPieces = drawnMeshes = drawnHalos = 0;
        frameBudget = new WorldLyricsExperience.Budget(preferences.getWorldLyricsQuality(), safeRendering);
        Set<LineKey> keys = new HashSet<>();
        for (var line : visible) keys.add(key(line));
        LINES.keySet().removeIf(key -> !keys.contains(key));
        for (var planned : visible) {
            LineKey key = key(planned);
            SceneLine scene = LINES.get(key);
            if (scene == null) {
                Anchor base = null;
                WorldLyricsDirector.Composition selected = null;
                SceneLine shared = null;
                for (var existing : LINES.entrySet()) {
                    LyricLine other = existing.getKey().line();
                    if (other.startMillis() < planned.line().endMillis()
                            && planned.line().startMillis() < other.endMillis()) {
                        base = existing.getValue().base;
                        selected = existing.getValue().composition;
                        shared = existing.getValue();
                        break;
                    }
                }
                if (base == null) {
                    int bands = (int) Math.clamp(sourceLines.stream().filter(other ->
                            other.startMillis() < planned.line().endMillis()
                                    && planned.line().startMillis() < other.endMillis()).count(), 1, 3);
                    WorldLyricsDirector.Composition composition = composition(planned.line(), planned.backing(), false, director, lastPreset);
                    double dynamicDistance = readingDistance(planned.line(), now, composition, director);
                    for (LyricLine other : sourceLines) if (WorldLyricsSpace.overlapping(other.startMillis(),other.endMillis(),planned.line().startMillis(),planned.line().endMillis()))
                        dynamicDistance=Math.max(dynamicDistance,readingDistance(other,now,composition,director));
                    PhraseBounds bounds = measureGroup(sourceLines, planned.line(),
                            preferences, dynamicDistance, bands);
                    if (stageAnchor != null && stageAnchor.bands >= bands && reusable(client, cameraPos, stageAnchor)) base = stageAnchor;
                    else base = createAnchor(client, cameraPos, bands, preferences, composition, dynamicDistance,
                            new PhraseBounds(Math.max(4, bounds.width), Math.max(3, bounds.height)));
                    if (base == null) continue;
                    stageAnchor = base;
                    selected = compatibleComposition(base, cameraPos,
                            base.wall ? composition(planned.line(), planned.backing(), true, director, lastPreset) : composition);
                }
                int slot = 0;
                Set<Integer> occupied = new HashSet<>();
                for (var existing : LINES.entrySet()) {
                    if (existing.getValue().base == base && existing.getKey().line().startMillis() < planned.line().endMillis()
                            && planned.line().startMillis() < existing.getKey().line().endMillis()) occupied.add(existing.getValue().slot);
                }
                while (occupied.contains(slot)) slot++;
                if (slot >= base.bands) {
                    // A chain of overlapping singers may outlive the original band's allocation.
                    WorldLyricsDirector.Composition composition = composition(planned.line(), planned.backing(), false, director, lastPreset);
                    double dynamicDistance = readingDistance(planned.line(), now, composition, director);
                    for (LyricLine other : sourceLines) if (WorldLyricsSpace.overlapping(other.startMillis(),other.endMillis(),planned.line().startMillis(),planned.line().endMillis()))
                        dynamicDistance=Math.max(dynamicDistance,readingDistance(other,now,composition,director));
                    base = createAnchor(client, cameraPos, 3, preferences, composition, dynamicDistance,
                            measureGroup(sourceLines, planned.line(), preferences, dynamicDistance, 3));
                    if (base == null) continue;
                    selected = compatibleComposition(base, cameraPos,
                            base.wall ? composition(planned.line(), planned.backing(), true, director, lastPreset) : composition);
                    slot = 0;
                }
                // A new phrase can reuse a stage that is still completing its previous move.
                if (shared == null) for (SceneLine existing : LINES.values()) {
                    if (existing.base == base) { shared = existing; break; }
                }
                lastPreset = selected.preset();
                scene = new SceneLine(base, slot, selected, now,
                        measureGroup(sourceLines, planned.line(), preferences, base.distance, base.bands),
                        Math.clamp(client.world.getLightLevel(BlockPos.ofFloored(base.x, base.y, base.z)), 0, 15));
                if (shared != null && shared.base == base) {
                    scene.relocation = shared.relocation;
                    scene.anchoredAt = shared.anchoredAt;
                }
                if (shared != null && shared.base == base && shared.from != null
                        && now - shared.moveStartedAt < shared.moveDuration) {
                    scene.from = shared.from;
                    scene.moveStartedAt = shared.moveStartedAt;
                    scene.moveDuration = shared.moveDuration;
                    scene.moveRise = shared.moveRise;
                }
                LINES.put(key, scene);
            }
            if (scene.layout == null) {
                scene.layout = layout(planned.line(), preferences, scene.base);
                scene.activePage = WorldLyricsPlacement.pageIndex(scene.layout.pages, now);
            }
            if (playing && !scene.layout.pages.isEmpty()) {
                // Timed pages share the phrase's placement; only an exhausted runway can move it.
                scene.activePage = WorldLyricsPlacement.pageIndex(scene.layout.pages, now);
                keepReadingHorizon(client, cameraPos, scene, planned.line(), now, director,
                        preferences.isLyricsAnimations());
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
        MinecraftClient client=MinecraftClient.getInstance();
        double speed=Math.max(STATE.blocksPerSecond(),travel(client).length()*20);
        return WorldLyricsSpace.readingDistance(speed,Math.max(0,line.endMillis()-now)/1000.0,
                line.text().codePointCount(0,line.text().length()),gliding(client),
                Math.max(24,client.options.getViewDistance().getValue()*14));
    }

    private static Vec3d placementDirection(MinecraftClient client) {
        double yaw = Math.toRadians(client.gameRenderer.getCamera().getYaw());
        double pitch = Math.toRadians(client.gameRenderer.getCamera().getPitch());
        Vec3d look = new Vec3d(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
        Vec3d movement = travel(client);
        if (gliding(client) && movement.length() > .05) return movement.normalize();
        if (movement.length() > .1 && look.dotProduct(movement.normalize()) > .4)
            return look.multiply(.7).add(movement.normalize().multiply(.3)).normalize();
        return look;
    }

    private static boolean reusable(MinecraftClient client, Vec3d camera, Anchor anchor) {
        Vec3d to = new Vec3d(anchor.x, anchor.y, anchor.z).subtract(camera);
        return to.length() >= Math.max(2.5, anchor.distance * .45) && to.length() <= anchor.distance * 1.8
                && to.normalize().dotProduct(placementDirection(client)) > .55 && freePanel(client, camera, anchor);
    }

    private static void keepReadingHorizon(MinecraftClient client, Vec3d camera, SceneLine scene,
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
        Vec3d toAnchor = new Vec3d(previous.x, previous.y, previous.z).subtract(camera);
        double ahead = toAnchor.length();
        double closingSpeed = ahead > 0 ? travel(client).dotProduct(toAnchor.multiply(1 / ahead)) * 20 : 0;
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
        double span = new Vec3d(displayed.x, displayed.y, displayed.z)
                .distanceTo(new Vec3d(replacement.x, replacement.y, replacement.z));
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
            sibling.worldLight = Math.clamp(client.world.getLightLevel(
                    BlockPos.ofFloored(replacement.x, replacement.y, replacement.z)), 0, 15);
        }
    }

    /** A few terrain checks at relocation time keep the moving phrase above intervening ground. */
    private static double relocationRise(MinecraftClient client, Anchor from, Anchor to, double depth) {
        double rise = 0;
        for (double t : new double[]{.25, .5, .75}) {
            Anchor sample = interpolateAnchor(from, to, t, 0);
            Vec3d point = new Vec3d(sample.x, sample.y, sample.z);
            Vec3d right = new Vec3d(sample.rightX, 0, sample.rightZ);
            Vec3d safe = liftAboveGround(client, point, right, sample.distance, sample.height, depth);
            rise = Math.max(rise, (safe.y - point.y) / Math.sin(Math.PI * t));
        }
        return rise;
    }

    private static boolean clearRelocationPath(MinecraftClient client, Anchor from, Anchor to, double rise) {
        // Probe once per attempted move, including both edges. Never move the text through a wall.
        for (double side : new double[]{-.35, 0, .35}) {
            Vec3d previous = null;
            for (int step = 0; step <= 6; step++) {
                double t = step / 6.0;
                Anchor sample = interpolateAnchor(from, to, t, rise);
                Vec3d point = new Vec3d(sample.x + sample.rightX * sample.width * side + sample.normalX * .25,
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

    private static WorldLyricsDirector.Composition compatibleComposition(Anchor anchor, Vec3d camera,
                                                                          WorldLyricsDirector.Composition selected) {
        return selected;
    }

    private static Layout layout(LyricLine line, ClientPreferences preferences, Anchor anchor) {
        TextRenderer renderer = MinecraftClient.getInstance().textRenderer;
        boolean romanized = preferences.isLyricsRomanization() && LyricsRenderText.hasRomanization(line);
        boolean timed = WorldLyricsPlan.hasWordTiming(line, romanized);
        var tokens = WorldLyricsPlan.tokens(line, romanized);
        String displayed = tokens.stream().map(WorldLyricsPlan.Token::text).reduce("", (a,b) -> a+" "+b);
        double desiredScale = .052 * preferences.getWorldLyricsSize() * Math.clamp(anchor.distance/6,.35,43);
        double wrapWidth = Math.clamp((anchor.width-.3*Math.max(.5,anchor.distance/6))/(desiredScale*1.16),60,220);
        var pages = WorldLyricsLayout.plan(tokens,timed,LyricsAnimation.isRightToLeft(displayed),wrapWidth,
                value -> renderer.getWidth(styled(value,preferences)));
        List<DrawPage> drawn = new ArrayList<>();
        for (var page : pages) {
            List<DrawRow> rows = new ArrayList<>();
            for (var row : page.rows()) {
                List<Piece> pieces = new ArrayList<>();
                for (var span : row.spans()) {
                    var token = span.token();
                    var segment = new WorldLyricsExperience.Motion(false);
                    double motionCenter=span.x()+span.width()/2;
                    boolean glyphMotion = timed && !span.rtl() && LyricsAnimation.usesPerGlyphMotion(token.durationMillis());
                    if (glyphMotion) {
                        for (var glyph : LyricsRenderText.timedGraphemes(span.text(),token.startMillis(),token.endMillis())) {
                            double begin = renderer.getWidth(styled(span.text().substring(0,glyph.startIndex()),preferences));
                            double end = renderer.getWidth(styled(span.text().substring(0,glyph.endIndex()),preferences));
                            pieces.add(new Piece(styled(glyph.text(),preferences),token,span.x()+begin,end-begin,
                                    span.sourceX()+begin,span.sourceWidth(),(begin+end)/2/Math.max(1,span.width()),false,
                                    motionCenter,segment,new WorldLyricsExperience.Motion(true),glyph.startMillis(),glyph.endMillis()));
                        }
                    } else pieces.add(new Piece(styled(span.text(),preferences),token,span.x(),span.width(),
                            span.sourceX(),span.sourceWidth(),.5,span.rtl(),motionCenter,segment,null,
                            token.startMillis(),token.endMillis()));
                }
                rows.add(new DrawRow(List.copyOf(pieces),row.width(),null,0));
            }
            drawn.add(new DrawPage(List.copyOf(rows)));
        }
        return new Layout(pages,List.copyOf(drawn),timed);
    }

    /** Measure the shaped words before probing the terrain. The drawing layout uses the same planner. */
    private static PhraseBounds measure(LyricLine line, ClientPreferences preferences, double distance, int bands) {
        TextRenderer renderer = MinecraftClient.getInstance().textRenderer;
        boolean romanized = preferences.isLyricsRomanization() && LyricsRenderText.hasRomanization(line);
        var tokens = WorldLyricsPlan.tokens(line, romanized);
        String displayed = tokens.stream().map(WorldLyricsPlan.Token::text).reduce("", (a, b) -> a + " " + b);
        double scale = .052 * preferences.getWorldLyricsSize() * Math.clamp(distance / 6, .8, 43);
        double available = Math.clamp(distance * .92 / (scale * 1.16), 100, 220);
        var pages = WorldLyricsLayout.plan(tokens, WorldLyricsPlan.hasWordTiming(line, romanized),
                LyricsAnimation.isRightToLeft(displayed), available,
                value -> renderer.getWidth(styled(value, preferences)));
        double width = 1.5, height = 1;
        for (var page : pages) {
            width = Math.max(width, page.width() * scale * 1.08);
            height = Math.max(height, page.rows().size() * (renderer.fontHeight + 6) * scale);
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

    private static void renderLine(MatrixStack matrices, VertexConsumerProvider consumers, Vec3d cameraPos,
                                   SceneLine scene, WorldLyricsPlan.WorldLine planned, long now,
                                   ClientPreferences preferences, WorldLyricsPlan.PlayerReaction reaction) {
        TextRenderer renderer = MinecraftClient.getInstance().textRenderer;
        Layout layout = scene.layout;
        Anchor anchor = scene.display(now, preferences.isLyricsAnimations());
        double distance = cameraPos.distanceTo(new Vec3d(anchor.x, anchor.y, anchor.z));
        if (distance > 384 || distance < .6) return;
        boolean animate = preferences.isLyricsAnimations();
        boolean main = !planned.backing();
        double visibility = (animate ? WorldLyricsPlan.lineVisibility(planned.line(), now) : 1)
                * (main ? 1 : .65) * Math.clamp(distance / Math.max(1, anchor.distance * .35), 0, 1);
        if (now > planned.line().endMillis()) visibility *= .35;
        if (animate && scene.handoffAt>0) visibility*=LyricsAnimation.smoother((frameClock-scene.handoffAt)/240_000_000.0);
        double lineHeight = renderer.fontHeight + 5;
        double bandHeight = anchor.height / anchor.bands;
        double bandY = (anchor.bands - 1) * bandHeight / 2 - scene.slot * bandHeight;
        int sideColor = materialSideColor(preferences, scene.composition, scene.worldLight);
        WorldLyricsLights.Point activeLight=null;
        for (int pageIndex = Math.max(0, scene.activePage - 1); pageIndex < Math.min(layout.pages.size(), scene.activePage + 2); pageIndex++) {
            var page = layout.pages.get(pageIndex);
            double alpha = visibility * WorldLyricsLayout.pageVisibility(layout.pages, pageIndex, now, animate);
            if (alpha <= .016) continue;
            double pixelScale = WorldLyricsLayout.pixelScale(page.width(), page.rows().size(), anchor.width,
                    bandHeight, anchor.distance, preferences.getWorldLyricsSize(), planned.backing(), lineHeight);
            for (var sibling : LINES.entrySet()) {
                SceneLine other = sibling.getValue();
                if (sibling.getKey().backing() || other.base != scene.base || other.layout == null || other.layout.pages.isEmpty()) continue;
                var otherPage = other.layout.pages.get(other.activePage);
                double shared = WorldLyricsLayout.pixelScale(otherPage.width(), otherPage.rows().size(), anchor.width,
                        bandHeight, anchor.distance, preferences.getWorldLyricsSize(), false, lineHeight);
                pixelScale = Math.min(pixelScale, shared * (main ? 1 : .76));
            }
            double entrance = WorldLyricsExperience.entrance(page.startMillis(), now, animate);
            double rise = -.16 * Math.max(.5, anchor.distance / 6) * entrance;
            double extrude = scene.composition.depth() * Math.clamp(anchor.distance / 6, .5, 43);
            var drawPage = layout.drawn.get(pageIndex);
            for (int rowIndex = 0; rowIndex < drawPage.rows.size(); rowIndex++) {
                DrawRow row = drawPage.rows.get(rowIndex);
                double rowY = bandY + ((drawPage.rows.size() - 1) / 2.0 - rowIndex) * lineHeight * pixelScale + rise;
                double alignment = planned.alignment() == LyricAlignment.OPPOSITE ? (anchor.width - row.width * pixelScale) * .18
                        : -(anchor.width - row.width * pixelScale) * .18;
                for (int i = 0; i < row.pieces.size(); i++) {
                    Piece piece = row.pieces.get(i);
                    piece.segmentMotion.sample(piece.token.startMillis(),piece.token.endMillis(),now,frameClock,animate && layout.timed);
                    if (piece.glyphMotion!=null) piece.glyphMotion.sample(piece.glyphStart,piece.glyphEnd,now,frameClock,animate && layout.timed);
                    double segmentScale=piece.segmentMotion.scale();
                    double glyphScale=piece.glyphMotion==null ? 1 : piece.glyphMotion.scale();
                    double strength=preferences.getWorldLyricsAnimationIntensity();
                    segmentScale=1+(segmentScale-1)*strength;
                    glyphScale=1+(glyphScale-1)*strength;
                    double center=((piece.segmentCenter-row.width/2)+(piece.x+piece.width/2-piece.segmentCenter)*segmentScale)*pixelScale;
                    float scale=(float)(pixelScale*segmentScale*glyphScale);
                    double elevation=(piece.segmentMotion.elevation()+(piece.glyphMotion==null ? 0 : piece.glyphMotion.elevation()))*renderer.fontHeight*pixelScale*strength;
                    double curvedDepth=anchor.wall ? 0 : -center*center/Math.max(1,anchor.distance*3);
                    matrices.push();
                    double side=center+alignment;
                    double up=rowY+renderer.fontHeight*scale/2+elevation;
                    place(matrices,cameraPos,anchor,side,up,curvedDepth);
                    if (!anchor.wall) matrices.multiply(new Quaternionf().rotateY((float)(-center/Math.max(1,anchor.distance*1.5))));
                    matrices.scale(scale,-scale,scale);
                    double progress = LyricsAnimation.timedProgress(now, piece.token.startMillis(), piece.token.endMillis());
                    double boundary = (piece.rtl ? 1 - progress : progress) * piece.fragmentWidth - piece.fragmentX - piece.width / 2;
                    int face = preferences.getWorldLyricsFaceColor();
                    int litSide = sideColor;
                    boolean mesh = extrude > .001 && WorldTextMesh.drawBudgeted(piece.text,
                            matrices.peek().getPositionMatrix(), consumers, (float) (-piece.width / 2), (float) (extrude / scale),
                            face, litSide, (float) (alpha * (layout.timed ? progress <= 0 ? .46 : progress >= 1 ? .82 : 1 : 1)),
                            layout.timed && progress > 0 && progress < 1 ? (float) boundary : null,
                            piece.rtl, preferences.getWorldLyricsStyle() == WorldLyricsExperience.Style.SCULPTED,
                            frameBudget);
                    drawnPieces++; if (mesh) drawnMeshes++;
                    matrices.translate(0,0,extrude/scale+.025);
                    if (WorldLyricsLights.available() && preferences.isWorldLyricsTerrainLight() && layout.timed && now>=piece.glyphStart && now<piece.glyphEnd && alpha>.05) {
                        double illumination=preferences.getWorldLyricsLuminance()*Math.min(1,alpha)*(.85+.15*LyricsAnimation.activeGlowEnvelope(
                                LyricsAnimation.timedProgress(now,piece.glyphStart,piece.glyphEnd)));
                        activeLight=new WorldLyricsLights.Point(anchor.x+anchor.rightX*side+anchor.normalY*anchor.rightZ*up+anchor.normalX*(curvedDepth+extrude),
                                anchor.y+(anchor.normalZ*anchor.rightX-anchor.normalX*anchor.rightZ)*up+anchor.normalY*(curvedDepth+extrude),
                                anchor.z+anchor.rightZ*side-anchor.normalY*anchor.rightX*up+anchor.normalZ*(curvedDepth+extrude),illumination);
                    }
                    drawFace(renderer, piece, layout.timed, progress, boundary, alpha, face,
                            false, matrices, consumers);
                    matrices.pop();
                }
            }
        }
        if (activeLight!=null) WorldLyricsLights.offer(activeLight);
    }

    private static void drawFace(TextRenderer renderer, Piece piece, boolean timed, double progress,
                                 double boundary, double visibility, int rgb, boolean shadow,
                                 MatrixStack matrices, VertexConsumerProvider consumers) {
        boolean partial = timed && progress > 0 && progress < 1;
        boolean intersects = boundary > -piece.width / 2 - 1 && boundary < piece.width / 2 + 1;
        int alpha = Math.clamp((int) Math.round(255 * visibility), 0, 255);
        int color = alpha << 24 | rgb & 0xFFFFFF;
        if (partial && intersects) {
            WorldTextFill.draw(renderer, piece.text, (float) (-piece.width / 2), color, shadow,
                    matrices.peek().getPositionMatrix(), consumers, (float) boundary, piece.rtl);
            return;
        }
        boolean filled = piece.rtl ? boundary <= -piece.width / 2 : boundary >= piece.width / 2;
        double opacity = !timed ? 1 : progress >= 1 ? .82 : partial && filled ? 1 : .46;
        alpha = Math.clamp((int) Math.round(alpha * opacity), 0, 255);
        if (alpha >= 4) renderer.draw(piece.text, (float) (-piece.width / 2), 0, alpha << 24 | rgb & 0xFFFFFF,
                shadow, matrices.peek().getPositionMatrix(), consumers, TextRenderer.TextLayerType.NORMAL,
                0, LightmapTextureManager.MAX_LIGHT_COORDINATE);
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

    private static void drawContactShadow(TextRenderer renderer, Piece piece, Vec3d camera, Anchor anchor,
                                          double side, float scale, double visibility,
                                          MatrixStack matrices, VertexConsumerProvider consumers) {
        // The ground was measured once when this anchor was created. Keep the projected text just above it.
        double floor = anchor.y - anchor.height * .5 - .20;
        matrices.push();
        matrices.translate(anchor.x + anchor.rightX * side - camera.x, floor + .025 - camera.y,
                anchor.z + anchor.rightZ * side - camera.z);
        matrices.multiply(new Quaternionf().rotateY(anchor.rotationYaw).rotateX((float) (-Math.PI / 2)));
        matrices.scale(scale, -scale, scale);
        int alpha = Math.clamp((int) Math.round(85 * visibility), 0, 140);
        renderer.draw(piece.text, (float) (-piece.width / 2), 0, alpha << 24 | 0x101015, false,
                matrices.peek().getPositionMatrix(), consumers, TextRenderer.TextLayerType.NORMAL,
                0, LightmapTextureManager.MAX_LIGHT_COORDINATE);
        matrices.pop();
    }

    private static void place(MatrixStack matrices, Vec3d camera, Anchor anchor, double side, double up, double depth) {
        matrices.translate(anchor.x + anchor.rightX * side + anchor.normalY * anchor.rightZ * up + anchor.normalX * depth - camera.x,
                anchor.y + (anchor.normalZ * anchor.rightX - anchor.normalX * anchor.rightZ) * up + anchor.normalY * depth - camera.y,
                anchor.z + anchor.rightZ * side - anchor.normalY * anchor.rightX * up + anchor.normalZ * depth - camera.z);
        matrices.multiply(new Quaternionf().rotateY(anchor.rotationYaw).rotateX((float) -Math.asin(anchor.normalY)));
    }

    private static void renderInterlude(MatrixStack matrices, VertexConsumerProvider consumers, Vec3d camera,
                                       WorldLyricsPlan.Interlude gap, long now, ClientPreferences preferences) {
        var renderer = MinecraftClient.getInstance().textRenderer;
        Anchor anchor = interludeAnchor;
        for (int i = 0; i < 3; i++) {
            double lift = preferences.isLyricsAnimations() ? LyricsAnimation.interludeDotLift(gap.progress(now), i) * .12 : 0;
            double offset = (i - 1) * .35;
            double alpha = gap.visibility(now) * (.34 + .66 * LyricsAnimation.interludeDotFill(gap.progress(now), i));
            if (Math.round(255 * alpha) < 4) continue;
            matrices.push();
            place(matrices, camera, anchor, offset, .55 + lift, 0);
            matrices.scale(BASE_PIXEL_SCALE, -BASE_PIXEL_SCALE, BASE_PIXEL_SCALE);
            Text dot = styled("•", preferences);
            renderer.draw(dot, -renderer.getWidth(dot) / 2F, 0, (int) Math.round(255 * alpha) << 24 | 0xFFFFFF,
                    preferences.isLyricsTextShadow(), matrices.peek().getPositionMatrix(), consumers,
                    TextRenderer.TextLayerType.NORMAL, 0, LightmapTextureManager.MAX_LIGHT_COORDINATE);
            matrices.pop();
        }
    }

    private static Text styled(String value, ClientPreferences preferences) {
        LyricsFont font = preferences.getLyricsFont();
        LyricsFontWeight weight = preferences.getLyricsFontWeight();
        Text text = Text.literal(value);
        return weight.syntheticBold(font)
                ? PlatformTextStyles.withFontBold(text, font.id())
                : PlatformTextStyles.withFont(text, font.id());
    }

    private static Anchor createAnchor(MinecraftClient client, Vec3d camera, int bands, ClientPreferences preferences,
                                       WorldLyricsDirector.Composition composition, double requestedDistance,
                                       PhraseBounds bounds) {
        return createSpatialAnchor(client,camera,bands,preferences,composition,requestedDistance,bounds,0);
    }

    private static Anchor createSpatialAnchor(MinecraftClient client, Vec3d camera, int bands, ClientPreferences preferences,
                                              WorldLyricsDirector.Composition composition, double requestedDistance,
                                              PhraseBounds bounds, int attempt) {
        double yaw=Math.toRadians(client.gameRenderer.getCamera().getYaw());
        Vec3d movement=travel(client);
        boolean gliding=gliding(client) && movement.length()>.05;
        Vec3d forward=placementDirection(client);
        Vec3d right=new Vec3d(-forward.z,0,forward.x).normalize();
        if (right.lengthSquared()<.001) right=new Vec3d(-Math.cos(yaw),0,-Math.sin(yaw));
        boolean outdoors=client.world.isSkyVisible(BlockPos.ofFloored(camera));
        var position=preferences.getWorldLyricsPosition();
        if (position==WorldLyricsSpace.Position.ADAPTIVE && bands>1) position=WorldLyricsSpace.Position.AROUND;
        double distance=Math.max(3,Math.min(requestedDistance,Math.max(24,client.options.getViewDistance().getValue()*14)));
        double ratio=distance/Math.max(1,requestedDistance);
        double width=Math.clamp(bounds.width*ratio*1.15,1.2,distance*.62);
        double height=Math.clamp(bounds.height*ratio,Math.min(1.8,distance*.30),distance*(position==WorldLyricsSpace.Position.SKY || position==WorldLyricsSpace.Position.ADAPTIVE && outdoors ? .38 : .78));
        for (int sign : new int[]{1,-1}) {
            var offset=WorldLyricsSpace.offset(position,outdoors,gliding,distance,width,height,sign);
            Vec3d center=camera.add(forward.multiply(offset.forward())).add(right.multiply(offset.side())).add(0,offset.up(),0);
            Vec3d towards=camera.subtract(center).normalize();
            double rotation=Math.atan2(towards.x,towards.z)+Math.toRadians(offset.yawDegrees());
            double ny=Math.clamp(towards.y,-.75,.75), horizontal=Math.sqrt(1-ny*ny);
            Vec3d normal=new Vec3d(Math.sin(rotation)*horizontal,ny,Math.cos(rotation)*horizontal);
            Vec3d across=new Vec3d(Math.cos(rotation),0,-Math.sin(rotation));
            Anchor anchor=new Anchor(center.x,center.y,center.z,normal.x,normal.y,normal.z,across.x,across.z,
                    (float)rotation,false,width,height,bands,camera.distanceTo(center));
            if (freePanel(client,camera,anchor)) {
                return anchor;
            }
        }
        // Stay outside the crosshair corridor even indoors; never fall back to a panel in the player's face.
        if (attempt<3 && requestedDistance>4)
            return createSpatialAnchor(client,camera,bands,preferences,composition,requestedDistance*.68,bounds,attempt+1);
        return null;
    }

    /** Check the panel's center and both ends before fixing its position in the world. */
    private static Vec3d liftAboveGround(MinecraftClient client, Vec3d center, Vec3d right,
                                         double distance, double height, double depth) {
        double halfHeight = height * .5;
        double margin = .45 + Math.max(0, depth) * Math.max(1, distance / 6);
        double highestGround = Double.NEGATIVE_INFINITY;
        double halfSpan = Math.min(distance * .32, 24);
        for (double side : new double[]{-halfSpan, 0, halfSpan}) {
            Vec3d point = center.add(right.multiply(side));
            Vec3d start = new Vec3d(point.x, point.y + halfHeight + 2, point.z);
            Vec3d end = new Vec3d(point.x, point.y - halfHeight - margin - 3, point.z);
            BlockHitResult floor = ray(client, start, end);
            if (floor.getType() == HitResult.Type.BLOCK && floor.getSide() == Direction.UP)
                highestGround = Math.max(highestGround, floor.getPos().y);
        }
        if (highestGround == Double.NEGATIVE_INFINITY) return center;
        return new Vec3d(center.x, Math.max(center.y, highestGround + halfHeight + margin), center.z);
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

    private static boolean freePanel(MinecraftClient client, Vec3d camera, Anchor anchor) {
        // Check the planned rectangle once, before it becomes a fixed world anchor.
        for (double side : new double[]{-.5, 0, .5}) {
            for (double up : new double[]{-.5, 0, .5}) {
                Vec3d point = new Vec3d(anchor.x + anchor.rightX * anchor.width * side + anchor.normalY * anchor.rightZ * anchor.height * up,
                        anchor.y + (anchor.normalZ * anchor.rightX - anchor.normalX * anchor.rightZ) * anchor.height * up,
                        anchor.z + anchor.rightZ * anchor.width * side - anchor.normalY * anchor.rightX * anchor.height * up);
                if (!client.world.isChunkLoaded(BlockPos.ofFloored(point))) return false;
                var hit = ray(client, camera, point);
                if (hit.getType() == HitResult.Type.BLOCK && hit.getPos().distanceTo(point) > .3) return false;
            }
        }
        return true;
    }

    private static BlockHitResult ray(MinecraftClient client, Vec3d start, Vec3d end) {
        return client.world.raycast(new RaycastContext(start, end, RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, client.player));
    }

    /** A sign stands only where its whole lower edge has stable, nearly level support. */
    private static Anchor findGroundSurface(MinecraftClient client, Vec3d camera, int bands,
                                            PhraseBounds bounds, WorldLyricsDirector.Preset preset, double desiredDistance) {
        double yaw = Math.toRadians(client.gameRenderer.getCamera().getYaw());
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
                Vec3d forward = new Vec3d(-Math.sin(angle), 0, Math.cos(angle));
                Vec3d right = new Vec3d(-Math.cos(angle), 0, -Math.sin(angle));
                Vec3d center = camera.add(forward.multiply(distance));
                double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
                boolean safe = true;
                for (double side : new double[]{-.5, 0, .5}) for (double front : new double[]{-.18, .18}) {
                    Vec3d sample = center.add(right.multiply(side * width)).add(forward.multiply(front));
                    BlockHitResult hit = ray(client, sample.add(0, 3, 0), sample.add(0, -8, 0));
                    if (hit.getType() != HitResult.Type.BLOCK || hit.getSide() != Direction.UP
                            || !client.world.isChunkLoaded(hit.getBlockPos())
                            || !client.world.getFluidState(hit.getBlockPos()).isEmpty()
                            || !client.world.getFluidState(BlockPos.ofFloored(hit.getPos().add(0, .05, 0))).isEmpty()
                            || !solidFace(client, hit)) { safe = false; break; }
                    low = Math.min(low, hit.getPos().y);
                    high = Math.max(high, hit.getPos().y);
                }
                if (!safe || high - low > .25 || Math.abs(high - camera.y) > 5) continue;
                Vec3d panel = new Vec3d(center.x, high + height * .5 + .20, center.z);
                Vec3d normal = forward.negate();
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

    private static Anchor findSurface(MinecraftClient client, Vec3d camera, int bands, PhraseBounds bounds) {
        List<WallCandidate> candidates = new ArrayList<>();
        double yaw = client.gameRenderer.getCamera().getYaw();
        double pitch = client.gameRenderer.getCamera().getPitch();
        // A bounded fan around the camera finds walls even when the crosshair points at a doorway or the floor.
        for (int yawOffset : new int[]{0, -18, 18, -35, 35}) {
            for (int pitchOffset : new int[]{0, -14, 14}) {
                double ry = Math.toRadians(yaw + yawOffset);
                double rp = Math.toRadians(Math.clamp(pitch + pitchOffset, -35, 35));
                Vec3d look = new Vec3d(-Math.sin(ry) * Math.cos(rp), -Math.sin(rp), Math.cos(ry) * Math.cos(rp));
                BlockHitResult hit = ray(client, camera, camera.add(look.multiply(12)));
                if (hit.getType() != HitResult.Type.BLOCK || !hit.getSide().getAxis().isHorizontal()) continue;
                double distance = camera.distanceTo(hit.getPos());
                if (distance < 1.5 || !solidFace(client, hit)) continue;
                Direction face = hit.getSide();
                Vec3d normal = new Vec3d(face.getOffsetX(), 0, face.getOffsetZ());
                double facing = camera.subtract(hit.getPos()).normalize().dotProduct(normal);
                if (facing < .4) continue;
                double score = facing * 4 - Math.abs(yawOffset) / 25.0 - Math.abs(pitchOffset) / 35.0
                        - distance * .08 - (Math.abs(yawOffset) < 10 ? .9 : 0);
                candidates.add(new WallCandidate(hit, score));
            }
        }
        candidates.sort(Comparator.comparingDouble(WallCandidate::score).reversed());
        List<WallCandidate> probes = new ArrayList<>();
        for (var candidate : candidates) {
            boolean duplicate = probes.stream().anyMatch(previous -> previous.hit.getSide() == candidate.hit.getSide()
                    && previous.hit.getPos().squaredDistanceTo(candidate.hit.getPos()) < 2.25);
            if (!duplicate) probes.add(candidate);
            if (probes.size() == 2) break;
        }
        Anchor best = null; double bestScore = -Double.MAX_VALUE;
        // At most two 11x7 grids: all probing happens at anchor creation, never per glyph/frame.
        for (var candidate : probes) {
            var hit = candidate.hit;
            Direction face = hit.getSide();
            double nx = face.getOffsetX(), nz = face.getOffsetZ();
            Vec3d normal = new Vec3d(nx, 0, nz), right = new Vec3d(nz, 0, -nx);
            boolean[][] support = new boolean[7][11];
            for (int row = 0; row < 7; row++) {
                for (int column = 0; column < 11; column++) {
                    Vec3d point = hit.getPos().add(right.multiply((column - 5) * .75)).add(0, (row - 3) * .75, 0);
                    var probe = ray(client, point.add(normal.multiply(.2)), point.subtract(normal.multiply(.2)));
                    support[row][column] = probe.getType() == HitResult.Type.BLOCK && probe.getSide() == face
                            && client.world.isChunkLoaded(probe.getBlockPos())
                            && client.world.getFluidState(probe.getBlockPos()).isEmpty()
                            && probe.getPos().squaredDistanceTo(point) < .003 && solidFace(client, probe);
                }
            }
            var rectangle = WorldLyricsLayout.wallRectangle(support);
            if (rectangle == null) continue;
            double width = (rectangle.right() - rectangle.left()) * .75 - .1;
            double height = (rectangle.top() - rectangle.bottom()) * .75 - .1;
            if (width < Math.max(2, bounds.width * .85) || height < Math.max(1.1 * bands, bounds.height * .85)) continue;
            Vec3d center = hit.getPos().add(right.multiply(((rectangle.left() + rectangle.right()) / 2.0 - 5) * .75))
                    .add(0, ((rectangle.bottom() + rectangle.top()) / 2.0 - 3) * .75, 0).add(normal.multiply(.065));
            // The final rectangle center must also be visible from the actual camera (e.g. around doorframes).
            var sight = ray(client, camera, center);
            if (sight.getType() == HitResult.Type.BLOCK && sight.getPos().distanceTo(center) > .12) continue;
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

    private static boolean solidFace(MinecraftClient client, BlockHitResult hit) {
        return client.world.getBlockState(hit.getBlockPos()).isSideSolidFullSquare(client.world, hit.getBlockPos(), hit.getSide());
    }

    private static double findGround(MinecraftClient client, double x, double z, double cameraY) {
        var hit = ray(client, new Vec3d(x, cameraY + 2, z), new Vec3d(x, cameraY - 8, z));
        return hit.getType() == HitResult.Type.BLOCK ? hit.getPos().y : cameraY - 2;
    }

    private record LineKey(LyricLine line, boolean backing) {
        @Override public int hashCode() { return System.identityHashCode(line) * 31 + (backing ? 1 : 0); }
        @Override public boolean equals(Object o) { return o instanceof LineKey k && line == k.line && backing == k.backing; }
    }
    private record Piece(Text text, WorldLyricsPlan.Token token, double x, double width,
                         double fragmentX, double fragmentWidth, double fraction, boolean rtl, double segmentCenter,
                         WorldLyricsExperience.Motion segmentMotion, WorldLyricsExperience.Motion glyphMotion,
                         long glyphStart, long glyphEnd) { }
    private record DrawRow(List<Piece> pieces, double width, Text backdrop, double backdropWidth) { }
    private record DrawPage(List<DrawRow> rows) { }
    private record Layout(List<WorldLyricsLayout.Page> pages, List<DrawPage> drawn, boolean timed) { }
    private record PhraseBounds(double width, double height) { }
    static final class SceneLine {
        Anchor base;
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
