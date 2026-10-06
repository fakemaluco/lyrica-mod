package dev.relaylyrics.client.ui;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.relaylyrics.client.ClientPreferences;
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
import org.joml.Quaternionf;

import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

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
        if (client.player == null || client.level == null) {
            previousPlayerPosition = null; travelWorld = null; playerTravelPerTick = Vec3.ZERO; return;
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
        return travelWorld == client.level && previousPlayerPosition != null ? playerTravelPerTick : client.player.getDeltaMovement();
    }

    public static void render(PoseStack matrices, MultiBufferSource consumers, Vec3 cameraPos) {
        Minecraft client = Minecraft.getInstance();
        ClientPreferences preferences = RelayLyricsClient.preferences();
        LyricsOverlay overlay = RelayLyricsClient.overlay();
        if (!preferences.isWorldLyrics() || overlay == null || client.player == null
                || client.level == null || matrices == null || consumers == null) {
            clear(); STATE.clear(); return;
        }
        if (!Objects.equals(wallMode, preferences.isWorldLyricsAutoWalls())) {
            clear(); wallMode = preferences.isWorldLyricsAutoWalls();
        }
        var playback = overlay.worldPlayback();
        long elapsedPreview = (System.nanoTime() - previewStartedNanos) / 1_000_000;
        boolean preview = previewStartedNanos != 0 && elapsedPreview >= 0 && elapsedPreview <= 15_450;
        long now = preview ? elapsedPreview : playback.positionMillis();
        String track = preview ? "world-preview" : playback.track() + "|seek=" + playback.revision();
        boolean playing = preview || playback.playing();
        List<LyricLine> sourceLines = preview ? List.of(PREVIEW_LINE)
                : overlay.document() == null ? List.of() : overlay.document().lines();
        boolean reactive = preferences.isLyricsAnimations() && preferences.isWorldLyricsPlayerReactions();
        Vec3 velocity = travel(client);
        if (STATE.update(client.level, preview ? PREVIEW_LINE : overlay.document(), track, now, playing, System.nanoTime(),
                client.player.onGround(), client.player.getAbilities().flying || WorldLyricsRenderHook.gliding(client.player),
                velocity.length(), reactive)) clear();
        WorldLyricsDirector.Settings director = directorSettings(preferences);
        String anchors = preferences.isWorldLyricsAutomatic() + "|" + preferences.getWorldLyricsPreset()
                + "|" + preferences.getWorldLyricsVariation() + "|" + preferences.getEnabledWorldLyricsPresets()
                + "|" + preferences.getWorldLyricsMinimumDistance() + "|" + preferences.getWorldLyricsMaximumDistance()
                + "|" + preferences.getWorldLyricsDistanceSensitivity() + "|" + preferences.getWorldLyricsArcRadius()
                + "|" + preferences.getWorldLyricsCenterGap() + "|" + preferences.getWorldLyricsSurfacePreference();
        if (!Objects.equals(anchors, anchorStyle)) { clear(); anchorStyle = anchors; }
        String style = preferences.getLyricsFont().id() + "|" + preferences.getGoogleFontFamily()
                + "|" + preferences.getLyricsFontWeight() + "|" + preferences.isLyricsRomanization()
                + "|" + overlay.fontLayoutRevision() + "|" + preferences.getWorldLyricsSize();
        if (!Objects.equals(style, layoutStyle) || fontStorage != client.font) {
            // Font changes invalidate geometry, but never move an existing world anchor.
            LINES.values().forEach(value -> value.layout = null);
            WorldTextMesh.clear();
            layoutStyle = style; fontStorage = client.font;
        }
        List<WorldLyricsPlan.WorldLine> visible = preview
                ? List.of(new WorldLyricsPlan.WorldLine(PREVIEW_LINE, LyricAlignment.NORMAL, false))
                : WorldLyricsPlan.visibleLines(overlay.document(), now);
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
                    PhraseBounds bounds = measureGroup(sourceLines, planned.line(),
                            preferences, dynamicDistance, bands);
                    base = createAnchor(client, cameraPos, bands, preferences, composition, dynamicDistance, bounds);
                    if (base == null) continue;
                    selected = compatibleComposition(base, cameraPos,
                            base.wall ? composition(planned.line(), planned.backing(), true, director, lastPreset) : composition);
                }
                int slot = 0;
                Set<Integer> occupied = new HashSet<>();
                for (var existing : LINES.values()) {
                    if (existing.base == base) occupied.add(existing.slot);
                }
                while (occupied.contains(slot)) slot++;
                if (slot >= base.bands) {
                    // A chain of overlapping singers may outlive the original band's allocation.
                    WorldLyricsDirector.Composition composition = composition(planned.line(), planned.backing(), false, director, lastPreset);
                    double dynamicDistance = readingDistance(planned.line(), now, composition, director);
                    base = createAnchor(client, cameraPos, 3, preferences, composition, dynamicDistance,
                            measureGroup(sourceLines, planned.line(), preferences, dynamicDistance, 3));
                    if (base == null) continue;
                    selected = compatibleComposition(base, cameraPos,
                            base.wall ? composition(planned.line(), planned.backing(), true, director, lastPreset) : composition);
                    slot = 0;
                }
                lastPreset = selected.preset();
                scene = new SceneLine(base, slot, selected, now,
                        measureGroup(sourceLines, planned.line(), preferences, base.distance, base.bands),
                        Math.clamp(client.level.getMaxLocalRawBrightness(BlockPos.containing(base.x, base.y, base.z)), 0, 15));
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
                scene.pageAnchors.clear();
                scene.pageAnchors.put(scene.activePage, scene.base);
            }
            if (playing && !scene.layout.pages.isEmpty()) {
                advancePageAnchor(client, cameraPos, scene, planned.line(), now, director, preferences);
                keepReadingHorizon(client, cameraPos, scene, planned.line(), now, director,
                        preferences.isLyricsAnimations());
            }
            renderLine(matrices, consumers, cameraPos, scene, planned, now, preferences, STATE.reaction(reactive));
        }
        var gap = preview ? null : WorldLyricsPlan.interlude(overlay.document(), now);
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
        LINES.clear(); currentInterlude = null; interludeAnchor = null; lastPreset = null;
    }
    private static LineKey key(WorldLyricsPlan.WorldLine value) {
        return new LineKey(value.line(), value.backing());
    }

    private static WorldLyricsDirector.Settings directorSettings(ClientPreferences p) {
        double minimum = p.getWorldLyricsMinimumDistance();
        double maximum = p.getWorldLyricsMaximumDistance();
        return new WorldLyricsDirector.Settings(p.isWorldLyricsAutomatic(), p.getWorldLyricsPreset(),
                p.getEnabledWorldLyricsPresets(), p.getWorldLyricsVariation(), minimum,
                maximum, p.getWorldLyricsDistanceSensitivity(), p.getWorldLyricsArcRadius(),
                p.getWorldLyricsCenterGap(), p.getWorldLyricsDepth(), p.getWorldLyricsAnimationIntensity(),
                p.getWorldLyricsAnimationSpeed(), p.isWorldLyricsTrails(), p.getWorldLyricsQuality());
    }

    private static double readingDistance(LyricLine line, long now, WorldLyricsDirector.Composition composition,
                                          WorldLyricsDirector.Settings settings) {
        double speed = Math.max(STATE.blocksPerSecond(), travel(Minecraft.getInstance()).length() * 20);
        return WorldLyricsDirector.readingDistance(speed, Math.max(0, line.endMillis() - now) / 1000.0,
                STATE.flying(), composition.preset() == WorldLyricsDirector.Preset.LANDSCAPE, settings);
    }

    /** A long flying phrase receives its next fixed anchor at a timed page boundary. */
    private static void advancePageAnchor(Minecraft client, Vec3 camera, SceneLine scene, LyricLine line,
                                          long now, WorldLyricsDirector.Settings settings, ClientPreferences preferences) {
        int next = WorldLyricsPlacement.pageIndex(scene.layout.pages, now);
        if (next == scene.activePage) return;
        if (next < scene.activePage) {
            scene.activePage = next;
            scene.base = scene.pageAnchors.getOrDefault(next, scene.base);
            scene.from = null;
            scene.anchoredAt = now;
            return;
        }
        Anchor previous = scene.base;
        scene.pageAnchors.put(scene.activePage, scene.display(now, preferences.isLyricsAnimations()));
        if (!previous.wall && !previous.ground && (STATE.flying() || STATE.blocksPerSecond() > 5.1)) {
            double speed = Math.max(STATE.blocksPerSecond(), travel(client).length() * 20);
            double remaining = Math.max(0, scene.layout.pages.get(next).endMillis() - now) / 1000.0;
            double target = WorldLyricsPlacement.readingDistance(speed, remaining, STATE.flying(),
                    scene.composition.preset() == WorldLyricsDirector.Preset.LANDSCAPE, settings);
            Anchor replacement = createAnchor(client, camera, previous.bands, preferences,
                    scene.composition, target, scene.bounds);
            double oldDistance = camera.distanceTo(new Vec3(previous.x, previous.y, previous.z));
            if (replacement != null && replacement.distance > oldDistance + Math.max(6, speed * .35)) {
                Anchor displayed = scene.display(now, preferences.isLyricsAnimations());
                double rise = preferences.isLyricsAnimations()
                        ? relocationRise(client, displayed, replacement, scene.composition.depth()) : 0;
                if (!preferences.isLyricsAnimations() || clearRelocationPath(client, displayed, replacement, rise)) {
                    scene.from = preferences.isLyricsAnimations() ? displayed : null;
                    scene.base = replacement;
                    scene.moveStartedAt = now;
                    scene.moveDuration = Math.round(Math.clamp(300 + oldDistance * 4, 350, 850));
                    scene.moveRise = rise;
                    scene.anchoredAt = now;
                    scene.worldLight = Math.clamp(client.level.getMaxLocalRawBrightness(
                            BlockPos.containing(replacement.x, replacement.y, replacement.z)), 0, 15);
                }
            }
        }
        scene.activePage = next;
        scene.pageAnchors.put(next, scene.base);
    }

    private static void keepReadingHorizon(Minecraft client, Vec3 camera, SceneLine scene,
                                           LyricLine line, long now, WorldLyricsDirector.Settings settings,
                                           boolean animate) {
        long pageEnd = Math.min(line.endMillis(), scene.layout.pages.get(scene.activePage).endMillis());
        double remaining = (pageEnd - now) / 1000.0;
        if (scene.base.wall || scene.base.ground) return;
        Anchor previous = scene.base;
        Vec3 toAnchor = new Vec3(previous.x, previous.y, previous.z).subtract(camera);
        double ahead = toAnchor.length();
        double closingSpeed = ahead > 0 ? travel(client).dot(toAnchor.scale(1 / ahead)) * 20 : 0;
        if (!WorldLyricsDirector.shouldRelocate(ahead, previous.distance, closingSpeed, remaining,
                (now - scene.anchoredAt) / 1000.0, STATE.flying())) return;
        double speed = Math.max(STATE.blocksPerSecond(), travel(client).length() * 20);
        double target = WorldLyricsPlacement.readingDistance(speed, remaining, STATE.flying(),
                scene.composition.preset() == WorldLyricsDirector.Preset.LANDSCAPE, settings);
        // Move only when the current reading runway is nearly exhausted.
        Anchor replacement = createAnchor(client, camera, previous.bands, RelayLyricsClient.preferences(),
                scene.composition, target, scene.bounds);
        if (replacement == null) { scene.anchoredAt = now; return; }
        if (replacement.distance <= ahead + Math.max(8, closingSpeed * .45)) {
            scene.anchoredAt = now;
            return;
        }
        Anchor displayed = scene.display(now, animate);
        double span = new Vec3(displayed.x, displayed.y, displayed.z)
                .distanceTo(new Vec3(replacement.x, replacement.y, replacement.z));
        long duration = Math.round(Math.clamp(Math.min(Math.min(300 + span * (STATE.flying() ? 5 : 12),
                (pageEnd - now) * .55), ahead / closingSpeed * 800),
                300, STATE.flying() ? 850 : 1100));
        double rise = animate ? relocationRise(client, displayed, replacement, scene.composition.depth()) : 0;
        if (animate && !clearRelocationPath(client, displayed, replacement, rise)) {
            for (SceneLine sibling : LINES.values()) if (sibling.base == previous) sibling.anchoredAt = now;
            return;
        }
        for (SceneLine sibling : LINES.values()) if (sibling.base == previous) {
            sibling.from = animate ? sibling.display(now, true) : null;
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
        int words = Math.max(1, line.text().strip().split("\\s+").length);
        return WorldLyricsDirector.choose(line.startMillis(), line.text(), words,
                Math.max(0, line.endMillis() - line.startMillis()), backing, wall,
                Math.max(Math.clamp((STATE.blocksPerSecond() - 3.5) / 2.5, 0, 1),
                        STATE.flying() ? 1 : 0), settings, previous);
    }

    private static WorldLyricsDirector.Composition compatibleComposition(Anchor anchor, Vec3 camera,
                                                                          WorldLyricsDirector.Composition selected) {
        var preset = selected.preset();
        if (!anchor.wall && (preset == WorldLyricsDirector.Preset.MURAL
                || preset == WorldLyricsDirector.Preset.ENGRAVED))
            return new WorldLyricsDirector.Composition(WorldLyricsDirector.Preset.LANDSCAPE,
                    WorldLyricsDirector.Placement.FRONT, WorldLyricsDirector.Entrance.SLIDE,
                    WorldLyricsDirector.Motion.BREATHE, 0, 0, selected.depth(), selected.trails(),
                    selected.depthLayers(), selected.trailCopies());
        if (anchor.wall && preset == WorldLyricsDirector.Preset.ENGRAVED) {
            double facing = camera.subtract(new Vec3(anchor.x, anchor.y, anchor.z)).normalize()
                    .dot(new Vec3(anchor.normalX, anchor.normalY, anchor.normalZ));
            if (facing < .78) return new WorldLyricsDirector.Composition(WorldLyricsDirector.Preset.MURAL,
                    WorldLyricsDirector.Placement.FRONT, WorldLyricsDirector.Entrance.WALL_SLIDE,
                    WorldLyricsDirector.Motion.BREATHE, 0, 0, selected.depth(), selected.trails(),
                    selected.depthLayers(), selected.trailCopies());
        }
        return selected;
    }

    private static Layout layout(LyricLine line, ClientPreferences preferences, Anchor anchor) {
        Font renderer = Minecraft.getInstance().font;
        boolean romanized = preferences.isLyricsRomanization() && LyricsRenderText.hasRomanization(line);
        boolean timed = WorldLyricsPlan.hasWordTiming(line, romanized);
        var tokens = WorldLyricsPlan.tokens(line, romanized);
        String displayed = tokens.stream().map(WorldLyricsPlan.Token::text).reduce("", (a, b) -> a + " " + b);
        boolean rtl = LyricsAnimation.isRightToLeft(displayed);
        double desiredScale = .067 * preferences.getWorldLyricsSize() * Math.clamp(anchor.distance / 6, .8, 43);
        double wrapWidth = Math.clamp((anchor.width - .6 * Math.clamp(anchor.distance / 6, .8, 43)) / (desiredScale * 1.16), 110, 160);
        var pages = WorldLyricsLayout.plan(tokens, timed, rtl, wrapWidth,
                value -> renderer.width(styled(value, preferences)));
        List<DrawPage> drawn = new ArrayList<>();
        int space = Math.max(1, renderer.width(styled(" ", preferences)));
        for (var page : pages) {
            List<DrawRow> rows = new ArrayList<>();
            for (var row : page.rows()) {
                List<Piece> pieces = new ArrayList<>();
                for (var span : row.spans()) {
                    var token = span.token();
                    if (!timed || span.rtl() || !LyricsAnimation.usesPerGlyphMotion(token.durationMillis())) {
                        pieces.add(new Piece(styled(span.text(), preferences), token, span.x(), span.width(),
                                span.sourceX(), span.sourceWidth(), .5, span.rtl()));
                    } else {
                        for (var glyph : LyricsRenderText.timedGraphemes(span.text(), token.startMillis(), token.endMillis())) {
                            double start = renderer.width(styled(span.text().substring(0, glyph.startIndex()), preferences));
                            double end = renderer.width(styled(span.text().substring(0, glyph.endIndex()), preferences));
                            pieces.add(new Piece(styled(glyph.text(), preferences), token, span.x() + start, end - start,
                                    span.sourceX() + start, span.sourceWidth(),
                                    (span.sourceX() + (start + end) / 2) / Math.max(1, span.sourceWidth()), false));
                        }
                    }
                }
                Component backdrop = styled(" ".repeat((int) Math.ceil(row.width() / space)), preferences);
                rows.add(new DrawRow(List.copyOf(pieces), row.width(), backdrop, renderer.width(backdrop)));
            }
            drawn.add(new DrawPage(List.copyOf(rows)));
        }
        return new Layout(pages, List.copyOf(drawn), timed);
    }

    /** Measure the shaped words before probing the terrain. The drawing layout uses the same planner. */
    private static PhraseBounds measure(LyricLine line, ClientPreferences preferences, double distance, int bands) {
        Font renderer = Minecraft.getInstance().font;
        boolean romanized = preferences.isLyricsRomanization() && LyricsRenderText.hasRomanization(line);
        var tokens = WorldLyricsPlan.tokens(line, romanized);
        String displayed = tokens.stream().map(WorldLyricsPlan.Token::text).reduce("", (a, b) -> a + " " + b);
        double scale = .067 * preferences.getWorldLyricsSize() * Math.clamp(distance / 6, .8, 43);
        double available = Math.clamp(distance * .92 / (scale * 1.16), 110, 160);
        var pages = WorldLyricsLayout.plan(tokens, WorldLyricsPlan.hasWordTiming(line, romanized),
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

    private static void renderLine(PoseStack matrices, MultiBufferSource consumers, Vec3 cameraPos,
                                   SceneLine scene, WorldLyricsPlan.WorldLine planned, long now,
                                   ClientPreferences preferences, WorldLyricsPlan.PlayerReaction reaction) {
        Font renderer = Minecraft.getInstance().font;
        Layout layout = scene.layout;
        Anchor activeAnchor = scene.display(now, preferences.isLyricsAnimations());
        WorldLyricsDirector.Composition composition = scene.composition;
        int sideColor = materialSideColor(preferences, composition, scene.worldLight);
        boolean animate = preferences.isLyricsAnimations();
        boolean cinematic = preferences.isWorldLyricsCinematic();
        double lineAlpha = (animate ? WorldLyricsPlan.lineVisibility(planned.line(), now) : 1)
                * (planned.backing() ? .78 : 1);
        // A new phrase can reuse the same wall. Recede the already-finished phrase during its tail.
        if (now > planned.line().endMillis()) {
            for (var entry : LINES.entrySet()) {
                if (entry.getKey().line().startMillis() >= planned.line().endMillis()
                        && entry.getKey().line().startMillis() <= now
                        && new Vec3(activeAnchor.x, activeAnchor.y, activeAnchor.z).distanceToSqr(
                        new Vec3(entry.getValue().base.x, entry.getValue().base.y, entry.getValue().base.z)) < 4) {
                    lineAlpha *= .3; break;
                }
            }
        }
        double lineHeight = renderer.lineHeight + 6;
        for (int pageIndex = 0; pageIndex < layout.pages.size(); pageIndex++) {
            Anchor anchor = pageIndex == scene.activePage ? activeAnchor
                    : scene.pageAnchors.getOrDefault(pageIndex, activeAnchor);
            double bandHeight = anchor.height / anchor.bands;
            double bandY = (anchor.bands - 1) * bandHeight / 2 - scene.slot * bandHeight;
            double distance = cameraPos.distanceTo(new Vec3(anchor.x, anchor.y + bandY, anchor.z));
            var page = layout.pages.get(pageIndex);
            double visibility = lineAlpha * WorldLyricsLayout.pageVisibility(layout.pages, pageIndex, now, animate);
            if (visibility <= .016) continue;
            var entrance = preferences.getWorldLyricsEntranceOverride() == null
                    ? composition.entrance() : preferences.getWorldLyricsEntranceOverride();
            if (anchor.ground && preferences.getWorldLyricsEntranceOverride() == null
                    && composition.preset() != WorldLyricsDirector.Preset.MONOLITH) entrance = WorldLyricsDirector.Entrance.RISE;
            var pose = WorldLyricsMotion.page(entrance, page.startMillis(), page.endMillis(), now,
                    anchor.height, preferences.getWorldLyricsAnimationIntensity(),
                    preferences.getWorldLyricsAnimationSpeed(), cinematic && animate);
            double zoom = pose.zoom(), slide = pose.vertical(), slideX = pose.horizontal();
            double rotation = pose.rotation(), transitionDepth = pose.depth();
            double pixelScale = WorldLyricsLayout.pixelScale(page.width(), page.rows().size(), anchor.width,
                    bandHeight, anchor.distance, preferences.getWorldLyricsSize(), planned.backing(), lineHeight) * zoom;
            var drawPage = layout.drawn.get(pageIndex);
            for (int rowIndex = 0; rowIndex < drawPage.rows.size(); rowIndex++) {
                DrawRow row = drawPage.rows.get(rowIndex);
                double cascade = composition.entrance() == WorldLyricsDirector.Entrance.CASCADE
                        ? slide * (rowIndex + 1.0) / drawPage.rows.size() : slide;
                double rowY = bandY + ((drawPage.rows.size() - 1) / 2.0 - rowIndex) * lineHeight * pixelScale + cascade;
                if (cinematic) {
                    // One subtle contrast ribbon per row, behind the text and still depth-tested by the world.
                    matrices.pushPose();
                    place(matrices, cameraPos, anchor, 0, rowY + renderer.lineHeight * pixelScale / 2, -.018);
                    matrices.scale((float) pixelScale, (float) -pixelScale, (float) pixelScale);
                    int background = (int) Math.round(24 * visibility) << 24;
                    FontCompat.drawInBatch(renderer, row.backdrop, (float) (-row.backdropWidth / 2), 0, 0xFFFFFFFF, false,
                            matrices.last().pose(), consumers, Font.DisplayMode.NORMAL,
                            background, LightTexture.FULL_BRIGHT);
                    matrices.popPose();
                }
                for (Piece piece : row.pieces) {
                    var motion = WorldLyricsPlan.motion(planned.line(), piece.token, now, piece.fraction,
                            layout.timed, animate, preferences.getWorldLyricsIntensity().multiplier
                                    * preferences.getWorldLyricsAnimationIntensity(),
                            distance, reaction, anchor.wall);
                    // Wall text stays bonded to the surface instead of floating off it on held notes.
                    double pulse = animate && composition.depth() > 0 && composition.motion() == WorldLyricsDirector.Motion.DEPTH_PULSE
                            ? piece.token.emphasis(now) * .045 : 0;
                    double extrude = Math.max(0, composition.depth() + pulse) * (anchor.wall ? 1 : Math.max(1, anchor.distance / 6));
                    if (composition.preset() == WorldLyricsDirector.Preset.ENGRAVED) extrude = Math.min(extrude, .025);
                    double depth = (anchor.wall ? Math.min(.015, motion.depth()) : motion.depth()) + transitionDepth;
                    if (anchor.wall) depth = Math.max(0, depth);
                    double lift = anchor.wall ? Math.clamp(motion.y(), -.1, .1) : motion.y();
                    float scale = (float) (pixelScale * Math.min(1.1, motion.scale() + reaction.flight() * .04));
                    double center = (piece.x + piece.width / 2 - row.width / 2) * pixelScale;
                    double extraTilt = 0;
                    if (animate) {
                        double emphasis = piece.token.emphasis(now) * preferences.getWorldLyricsAnimationIntensity();
                        switch (composition.motion()) {
                            case WAVE -> lift += Math.sin(now / 180.0 - piece.fraction * 3) * emphasis * .025;
                            case WORD_JUMP -> lift += emphasis * .06;
                            case BREATHE -> scale *= (float) (1 + .025 * Math.sin(now / 330.0 + piece.fraction * 2));
                            case TILT_SEQUENCE -> extraTilt = Math.sin(now / 260.0 - piece.fraction * 2.5) * emphasis * 3;
                            case DEPTH_PULSE -> { }
                        }
                    }
                    if (anchor.ground && preferences.isWorldLyricsContactShadow()
                            && preferences.getWorldLyricsQuality() != WorldLyricsDirector.Quality.LOW) {
                        drawContactShadow(renderer, piece, cameraPos, anchor, center + motion.x() + slideX,
                                scale, visibility, matrices, consumers);
                    }
                    matrices.pushPose();
                    double pieceUp = rowY + renderer.lineHeight * scale / 2 + lift + composition.verticalOffset();
                    if (anchor.ground) pieceUp = Math.max(pieceUp,
                            -anchor.height * .5 + renderer.lineHeight * scale / 2 + .23);
                    place(matrices, cameraPos, anchor, center + motion.x() + slideX,
                            pieceUp, depth);
                    matrices.mulPose(new Quaternionf().rotateZ((float) Math.toRadians(
                            anchor.wall ? Math.clamp(motion.tilt() + rotation + extraTilt, -2, 2)
                                    : motion.tilt() + rotation + extraTilt)));
                    matrices.scale(scale, -scale, scale);
                    double progress = LyricsAnimation.timedProgress(now, piece.token.startMillis(), piece.token.endMillis());
                    double boundary = (piece.rtl ? 1 - progress : progress) * piece.fragmentWidth
                            - piece.fragmentX - piece.width / 2;
                    if (composition.trailCopies() > 0 && animate) {
                        for (int trail = composition.trailCopies(); trail >= 1; trail--) {
                            matrices.pushPose();
                            matrices.translate(-motion.x() * trail * 2.5 / scale, motion.y() * trail * 1.5 / scale, -.006 * trail / scale);
                            drawFace(renderer, piece, layout.timed, progress, boundary, visibility * .16 / trail,
                                    preferences.getWorldLyricsSideColor(), false, matrices, consumers);
                            matrices.popPose();
                        }
                    }
                    boolean meshDrawn = extrude > .001 && WorldTextMesh.draw(piece.text,
                            matrices.last().pose(), consumers, (float) (-piece.width / 2),
                            (float) (extrude / scale), preferences.getWorldLyricsFaceColor(), sideColor,
                            (float) (visibility * (layout.timed && progress >= 1 ? .82 : 1)),
                            layout.timed && progress < 1 ? (float) boundary : null, piece.rtl(),
                            preferences.isWorldLyricsBevel()
                                    && preferences.getWorldLyricsQuality() != WorldLyricsDirector.Quality.LOW
                                    && (composition.preset() == WorldLyricsDirector.Preset.MONOLITH
                                    || preferences.getWorldLyricsMaterial() == WorldLyricsDirector.Material.METAL),
                            (float) Math.min(1.2, preferences.getWorldLyricsBevelWidth()
                                    * (composition.preset() == WorldLyricsDirector.Preset.MONOLITH ? 1.4 : 1)),
                            switch (preferences.getWorldLyricsQuality()) {
                                case LOW -> 4_096; case MEDIUM -> 8_192; case HIGH -> 16_384;
                            });
                    if (meshDrawn) {
                        // The actual font renderer owns shaping and antialiasing. Keep its crisp
                        // face in front of the cached volume, including accents and RTL glyphs.
                        matrices.pushPose();
                        matrices.translate(0, 0, extrude / scale + .025);
                        drawFace(renderer, piece, layout.timed, progress, boundary, visibility,
                                preferences.getWorldLyricsFaceColor(), false, matrices, consumers);
                        matrices.popPose();
                    } else drawFace(renderer, piece, layout.timed, progress, boundary, visibility,
                            preferences.getWorldLyricsFaceColor(), preferences.isLyricsTextShadow(), matrices, consumers);
                    matrices.popPose();
                }
            }
        }
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

    private static Anchor createAnchor(Minecraft client, Vec3 camera, int bands, ClientPreferences preferences,
                                       WorldLyricsDirector.Composition composition, double requestedDistance,
                                       PhraseBounds bounds) {
        var preference = preferences.getWorldLyricsSurfacePreference();
        if (WorldLyricsDirector.seekSurface(composition.preset(), preference,
                STATE.blocksPerSecond(), STATE.flying(), preferences.isWorldLyricsAutomatic())) {
            boolean wallFirst = composition.preset() == WorldLyricsDirector.Preset.MURAL
                    || composition.preset() == WorldLyricsDirector.Preset.ENGRAVED
                    || Math.floorMod(composition.preset().ordinal() + (int) Math.round(requestedDistance), 3) == 0;
            Anchor surface = null;
            if (wallFirst && preferences.isWorldLyricsAutoWalls()) surface = findSurface(client, camera, bands, bounds);
            if (surface == null && composition.preset() != WorldLyricsDirector.Preset.MURAL
                    && composition.preset() != WorldLyricsDirector.Preset.ENGRAVED)
                surface = findGroundSurface(client, camera, bands, bounds, composition.preset(), requestedDistance);
            if (surface == null && !wallFirst && preferences.isWorldLyricsAutoWalls()
                    && composition.preset() != WorldLyricsDirector.Preset.SIGN
                    && composition.preset() != WorldLyricsDirector.Preset.MONOLITH)
                surface = findSurface(client, camera, bands, bounds);
            if (surface != null) return surface;
        }
        // Close walls cannot provide the reading horizon needed during running or gliding.
        double offset = composition.yawOffsetDegrees();
        if (STATE.blocksPerSecond() > 5.1) offset *= .25;
        double yaw = Math.toRadians(client.gameRenderer.mainCamera().yRot() + offset);
        double pitch = Math.toRadians(client.gameRenderer.mainCamera().xRot());
        boolean groundedStyle = composition.preset() == WorldLyricsDirector.Preset.SIGN && !STATE.flying();
        if (groundedStyle) pitch = 0;
        Vec3 direction = new Vec3(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
        Vec3 travelDirection = travel(client).normalize();
        if (STATE.blocksPerSecond() > 5.1 && direction.dot(travelDirection) > .4) {
            double trajectoryWeight = STATE.flying() ? .48 : .35;
            direction = direction.scale(1 - trajectoryWeight)
                    .add(travelDirection.scale(trajectoryWeight)).normalize();
        }
        double distance = Math.max(.75, Math.min(groundedStyle ? Math.min(requestedDistance, 18) : requestedDistance,
                Math.max(24, client.options.renderDistance().get() * 14)));
        // Keep the fallback in front of obstacles too; never create a panel beyond the first block.
        Vec3 hitTarget = camera.add(direction.scale(distance));
        var obstacle = ray(client, camera, hitTarget);
        if (obstacle.getType() == HitResult.Type.BLOCK) distance = Math.max(.75, camera.distanceTo(obstacle.getLocation()) - .5);
        Vec3 center = camera.add(direction.scale(distance));
        double height = 2.25 * bands * Math.max(.5, distance / 6);
        if (groundedStyle) height = Math.min(height, 3 * bands);
        Vec3 normal = direction.reverse();
        Vec3 right = new Vec3(normal.z, 0, -normal.x).normalize();
        if (right.lengthSqr() < .001) right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
        if (groundedStyle) {
            Vec3 start = center.add(0, 5, 0);
            BlockHitResult floor = ray(client, start, center.add(0, -14, 0));
            if (floor.getType() == HitResult.Type.BLOCK && floor.getDirection() == Direction.UP)
                center = new Vec3(center.x, floor.getLocation().y + height * .5 + .35, center.z);
        }
        center = liftAboveGround(client, center, right, distance, height, composition.depth());
        Anchor candidate = new Anchor(center.x, center.y, center.z, normal.x, normal.y, normal.z,
                right.x, right.z, (float) Math.atan2(normal.x, normal.z), false,
                Math.max(1.5, distance * (Math.abs(offset) > 1 ? .55 : .95)), height, bands, distance);
        if (!freePanel(client, camera, candidate)) {
            if (Math.abs(composition.yawOffsetDegrees()) > .01) {
                var front = new WorldLyricsDirector.Composition(composition.preset(), WorldLyricsDirector.Placement.FRONT,
                        composition.entrance(), composition.motion(), 0, 0, composition.depth(),
                        composition.trails(), composition.depthLayers(), composition.trailCopies());
                return createAnchor(client, camera, bands, preferences, front, requestedDistance, bounds);
            }
            if (requestedDistance > 4) return createAnchor(client, camera, bands, preferences,
                    composition, Math.max(3, requestedDistance * .7), bounds);
        }
        return freePanel(client, camera, candidate) ? candidate : null;
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
        // Check the planned rectangle once, before it becomes a fixed world anchor.
        for (double side : new double[]{-.5, 0, .5}) {
            for (double up : new double[]{-.5, 0, .5}) {
                Vec3 point = new Vec3(anchor.x + anchor.rightX * anchor.width * side + anchor.normalY * anchor.rightZ * anchor.height * up,
                        anchor.y + (anchor.normalZ * anchor.rightX - anchor.normalX * anchor.rightZ) * anchor.height * up,
                        anchor.z + anchor.rightZ * anchor.width * side - anchor.normalY * anchor.rightX * anchor.height * up);
                if (!client.level.hasChunkAt(BlockPos.containing(point))) return false;
                var hit = ray(client, camera, point);
                if (hit.getType() == HitResult.Type.BLOCK && hit.getLocation().distanceTo(point) > .3) return false;
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
                Vec3 normal = forward.reverse();
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

    private record LineKey(LyricLine line, boolean backing) { }
    private record Piece(Component text, WorldLyricsPlan.Token token, double x, double width,
                         double fragmentX, double fragmentWidth, double fraction, boolean rtl) { }
    private record DrawRow(List<Piece> pieces, double width, Component backdrop, double backdropWidth) { }
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
        long moveStartedAt, moveDuration;
        double moveRise;
        int activePage;
        final Map<Integer, Anchor> pageAnchors = new HashMap<>();
        SceneLine(Anchor base, int slot, WorldLyricsDirector.Composition composition, long now,
                  PhraseBounds bounds, int worldLight) {
            this.base = base; this.slot = slot; this.composition = composition;
            this.bounds = bounds; this.worldLight = worldLight;
            this.anchoredAt = now;
            this.pageAnchors.put(0, base);
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
