package dev.relaylyrics.client.ui;

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
import dev.relaylyrics.protocol.lyrics.LyricLine;
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
    private static Object travelWorld;
    private static Vec3d previousPlayerPosition;
    private static Vec3d playerTravelPerTick = Vec3d.ZERO;

    private WorldLyricsRenderer() { }

    /** Rebuilds only the local presentation so the configuration screen can preview the current phrase. */
    public static void previewCurrentPhrase() { clear(); }

    public static void tickPlayerMotion(MinecraftClient client) {
        if (client.player == null || client.world == null) {
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
        return travelWorld == client.world && previousPlayerPosition != null ? playerTravelPerTick : client.player.getVelocity();
    }

    public static void render(MatrixStack matrices, VertexConsumerProvider consumers, Vec3d cameraPos) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPreferences preferences = RelayLyricsClient.preferences();
        LyricsOverlay overlay = RelayLyricsClient.overlay();
        if (!preferences.isWorldLyrics() || overlay == null || client.player == null
                || client.world == null || matrices == null || consumers == null) {
            clear(); STATE.clear(); return;
        }
        if (!Objects.equals(wallMode, preferences.isWorldLyricsAutoWalls())) {
            clear(); wallMode = preferences.isWorldLyricsAutoWalls();
        }
        var playback = overlay.worldPlayback();
        long now = playback.positionMillis();
        String track = playback.track() + "|seek=" + playback.revision();
        boolean playing = playback.playing();
        boolean reactive = preferences.isLyricsAnimations() && preferences.isWorldLyricsPlayerReactions();
        Vec3d velocity = travel(client);
        if (STATE.update(client.world, overlay.document(), track, now, playing, System.nanoTime(),
                client.player.isOnGround(), client.player.getAbilities().flying || WorldLyricsRenderHook.gliding(client.player),
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
        if (!Objects.equals(style, layoutStyle) || fontStorage != client.textRenderer) {
            // Font changes invalidate geometry, but never move an existing world anchor.
            LINES.values().forEach(value -> value.layout = null);
            WorldTextMesh.clear();
            layoutStyle = style; fontStorage = client.textRenderer;
        }
        List<WorldLyricsPlan.WorldLine> visible = WorldLyricsPlan.visibleLines(overlay.document(), now);
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
                    int bands = (int) Math.clamp(overlay.document().lines().stream().filter(other ->
                            other.startMillis() < planned.line().endMillis()
                                    && planned.line().startMillis() < other.endMillis()).count(), 1, 3);
                    WorldLyricsDirector.Composition composition = composition(planned.line(), planned.backing(), false, director, lastPreset);
                    double dynamicDistance = readingDistance(planned.line(), now, composition, director);
                    PhraseBounds bounds = measureGroup(overlay.document().lines(), planned.line(),
                            preferences, dynamicDistance, bands);
                    base = createAnchor(client, cameraPos, bands, preferences, composition, dynamicDistance, bounds);
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
                            measureGroup(overlay.document().lines(), planned.line(), preferences, dynamicDistance, 3));
                    selected = compatibleComposition(base, cameraPos,
                            base.wall ? composition(planned.line(), planned.backing(), true, director, lastPreset) : composition);
                    slot = 0;
                }
                lastPreset = selected.preset();
                scene = new SceneLine(base, slot, selected, now,
                        measureGroup(overlay.document().lines(), planned.line(), preferences, base.distance, base.bands),
                        Math.clamp(client.world.getLightLevel(BlockPos.ofFloored(base.x, base.y, base.z)), 0, 15));
                if (shared != null && shared.base == base && shared.from != null
                        && now - shared.moveStartedAt < shared.moveDuration) {
                    scene.from = shared.from;
                    scene.moveStartedAt = shared.moveStartedAt;
                    scene.moveDuration = shared.moveDuration;
                    scene.moveRise = shared.moveRise;
                }
                LINES.put(key, scene);
            }
            if (playing) keepReadingHorizon(client, cameraPos, scene, planned.line(), now, director,
                    preferences.isLyricsAnimations());
            if (scene.layout == null) scene.layout = layout(planned.line(), preferences, scene.base);
            renderLine(matrices, consumers, cameraPos, scene, planned, now, preferences, STATE.reaction(reactive));
        }
        var gap = WorldLyricsPlan.interlude(overlay.document(), now);
        if (visible.isEmpty() && gap != null && gap.visibility(now) > .01) {
            if (!gap.equals(currentInterlude) || interludeAnchor == null) {
                currentInterlude = gap;
                var calm = new WorldLyricsDirector.Composition(WorldLyricsDirector.Preset.CLASSIC,
                        WorldLyricsDirector.Placement.FRONT, WorldLyricsDirector.Entrance.DISSOLVE,
                        WorldLyricsDirector.Motion.BREATHE, 0, 0, 0, false, 1, 0);
                interludeAnchor = createAnchor(client, cameraPos, 1, preferences, calm,
                        WorldLyricsDirector.readingDistance(STATE.blocksPerSecond(), 2, STATE.flying(), false, director),
                        new PhraseBounds(1.5, 1));
            }
            renderInterlude(matrices, consumers, cameraPos, gap, now, preferences);
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
        double speed = Math.max(STATE.blocksPerSecond(), travel(MinecraftClient.getInstance()).length() * 20);
        return WorldLyricsDirector.readingDistance(speed, Math.max(0, line.endMillis() - now) / 1000.0,
                STATE.flying(), composition.preset() == WorldLyricsDirector.Preset.LANDSCAPE, settings);
    }

    private static void keepReadingHorizon(MinecraftClient client, Vec3d camera, SceneLine scene,
                                           LyricLine line, long now, WorldLyricsDirector.Settings settings,
                                           boolean animate) {
        double remaining = (line.endMillis() - now) / 1000.0;
        if (remaining < .75 || now - scene.anchoredAt < 800) return;
        Anchor previous = scene.base;
        Vec3d normal = new Vec3d(previous.normalX, previous.normalY, previous.normalZ);
        double closingSpeed = -travel(client).dotProduct(normal) * 20;
        if (closingSpeed < 4) return;
        double ahead = camera.subtract(new Vec3d(previous.x, previous.y, previous.z)).dotProduct(normal);
        if (ahead > closingSpeed * (remaining + .5)) return;
        double target = readingDistance(line, now, scene.composition, settings);
        // A new fixed anchor only when acceleration would make the player overtake the phrase.
        Anchor replacement = createAnchor(client, camera, previous.bands, RelayLyricsClient.preferences(),
                scene.composition, target, scene.bounds);
        if (replacement.distance <= Math.max(2, ahead) * 1.2) {
            scene.anchoredAt = now;
            return;
        }
        Anchor displayed = scene.display(now, animate);
        double span = new Vec3d(displayed.x, displayed.y, displayed.z)
                .distanceTo(new Vec3d(replacement.x, replacement.y, replacement.z));
        long duration = Math.round(Math.clamp(Math.min(300 + span * 12,
                (line.endMillis() - now) * .75), 300, 1100));
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
        int words = Math.max(1, line.text().strip().split("\\s+").length);
        return WorldLyricsDirector.choose(line.startMillis(), line.text(), words,
                Math.max(0, line.endMillis() - line.startMillis()), backing, wall,
                Math.max(Math.clamp((STATE.blocksPerSecond() - 3.5) / 2.5, 0, 1),
                        STATE.flying() ? 1 : 0), settings, previous);
    }

    private static WorldLyricsDirector.Composition compatibleComposition(Anchor anchor, Vec3d camera,
                                                                          WorldLyricsDirector.Composition selected) {
        var preset = selected.preset();
        if (!anchor.wall && (preset == WorldLyricsDirector.Preset.MURAL
                || preset == WorldLyricsDirector.Preset.ENGRAVED))
            return new WorldLyricsDirector.Composition(WorldLyricsDirector.Preset.CINEMA,
                    WorldLyricsDirector.Placement.FRONT, WorldLyricsDirector.Entrance.SLIDE,
                    WorldLyricsDirector.Motion.BREATHE, 0, 0, selected.depth(), selected.trails(),
                    selected.depthLayers(), selected.trailCopies());
        if (anchor.wall && preset == WorldLyricsDirector.Preset.ENGRAVED) {
            double facing = camera.subtract(new Vec3d(anchor.x, anchor.y, anchor.z)).normalize()
                    .dotProduct(new Vec3d(anchor.normalX, anchor.normalY, anchor.normalZ));
            if (facing < .78) return new WorldLyricsDirector.Composition(WorldLyricsDirector.Preset.MURAL,
                    WorldLyricsDirector.Placement.FRONT, WorldLyricsDirector.Entrance.WALL_SLIDE,
                    WorldLyricsDirector.Motion.BREATHE, 0, 0, selected.depth(), selected.trails(),
                    selected.depthLayers(), selected.trailCopies());
        }
        return selected;
    }

    private static Layout layout(LyricLine line, ClientPreferences preferences, Anchor anchor) {
        TextRenderer renderer = MinecraftClient.getInstance().textRenderer;
        boolean romanized = preferences.isLyricsRomanization() && LyricsRenderText.hasRomanization(line);
        boolean timed = WorldLyricsPlan.hasWordTiming(line, romanized);
        var tokens = WorldLyricsPlan.tokens(line, romanized);
        String displayed = tokens.stream().map(WorldLyricsPlan.Token::text).reduce("", (a, b) -> a + " " + b);
        boolean rtl = LyricsAnimation.isRightToLeft(displayed);
        double desiredScale = .067 * preferences.getWorldLyricsSize() * Math.clamp(anchor.distance / 6, .8, 43);
        double wrapWidth = Math.clamp((anchor.width - .6 * Math.clamp(anchor.distance / 6, .8, 43)) / (desiredScale * 1.16), 110, 160);
        var pages = WorldLyricsLayout.plan(tokens, timed, rtl, wrapWidth,
                value -> renderer.getWidth(styled(value, preferences)));
        List<DrawPage> drawn = new ArrayList<>();
        int space = Math.max(1, renderer.getWidth(styled(" ", preferences)));
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
                            double start = renderer.getWidth(styled(span.text().substring(0, glyph.startIndex()), preferences));
                            double end = renderer.getWidth(styled(span.text().substring(0, glyph.endIndex()), preferences));
                            pieces.add(new Piece(styled(glyph.text(), preferences), token, span.x() + start, end - start,
                                    span.sourceX() + start, span.sourceWidth(),
                                    (span.sourceX() + (start + end) / 2) / Math.max(1, span.sourceWidth()), false));
                        }
                    }
                }
                Text backdrop = styled(" ".repeat((int) Math.ceil(row.width() / space)), preferences);
                rows.add(new DrawRow(List.copyOf(pieces), row.width(), backdrop, renderer.getWidth(backdrop)));
            }
            drawn.add(new DrawPage(List.copyOf(rows)));
        }
        return new Layout(pages, List.copyOf(drawn), timed);
    }

    /** Measure the shaped words before probing the terrain. The drawing layout uses the same planner. */
    private static PhraseBounds measure(LyricLine line, ClientPreferences preferences, double distance, int bands) {
        TextRenderer renderer = MinecraftClient.getInstance().textRenderer;
        boolean romanized = preferences.isLyricsRomanization() && LyricsRenderText.hasRomanization(line);
        var tokens = WorldLyricsPlan.tokens(line, romanized);
        String displayed = tokens.stream().map(WorldLyricsPlan.Token::text).reduce("", (a, b) -> a + " " + b);
        double scale = .067 * preferences.getWorldLyricsSize() * Math.clamp(distance / 6, .8, 43);
        double available = Math.clamp(distance * .92 / (scale * 1.16), 110, 160);
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
                        && new Vec3d(anchor.x, anchor.y, anchor.z).squaredDistanceTo(
                        new Vec3d(entry.getValue().base.x, entry.getValue().base.y, entry.getValue().base.z)) < 4) {
                    lineAlpha *= .3; break;
                }
            }
        }
        double bandHeight = anchor.height / anchor.bands;
        double bandY = (anchor.bands - 1) * bandHeight / 2 - scene.slot * bandHeight;
        double distance = cameraPos.distanceTo(new Vec3d(anchor.x, anchor.y + bandY, anchor.z));
        double lineHeight = renderer.fontHeight + 6;
        for (int pageIndex = 0; pageIndex < layout.pages.size(); pageIndex++) {
            var page = layout.pages.get(pageIndex);
            double visibility = lineAlpha * WorldLyricsLayout.pageVisibility(layout.pages, pageIndex, now, animate);
            if (visibility <= .016) continue;
            double zoom = 1;
            double slide = 0;
            double slideX = 0;
            double rotation = 0;
            double transitionDepth = 0;
            if (cinematic && animate) {
                double animationSpeed = preferences.getWorldLyricsAnimationSpeed();
                double enter = LyricsAnimation.smoother((now - page.startMillis() + 180) / (360.0 / animationSpeed));
                double exit = LyricsAnimation.smoother((now - page.endMillis()) / (450.0 / animationSpeed));
                double hold = LyricsAnimation.activeGlowEnvelope(LyricsAnimation.timedProgress(now, page.startMillis(), page.endMillis()));
                zoom = (.94 + .06 * enter) * (1 - .04 * exit) * (1 + .025 * hold);
                double phase = (1 - enter - exit) * preferences.getWorldLyricsAnimationIntensity();
                var entrance = preferences.getWorldLyricsEntranceOverride() == null
                        ? composition.entrance() : preferences.getWorldLyricsEntranceOverride();
                if (anchor.ground && preferences.getWorldLyricsEntranceOverride() == null
                        && composition.preset() != WorldLyricsDirector.Preset.INTRO) entrance = WorldLyricsDirector.Entrance.RISE;
                switch (entrance) {
                    case SLIDE -> slideX = phase * .22;
                    case CASCADE -> slide = phase * .26;
                    case ROTATE -> rotation = phase * 7;
                    case DEPTH -> transitionDepth = phase * -.12;
                    case FALL -> slide = phase * .60;
                    case POP -> { zoom *= 1 - .22 * phase; transitionDepth = phase * -.10; }
                    case RISE -> { slide = -phase * .06; zoom *= 1 - .55 * Math.clamp(phase, 0, 1); }
                    case LAND -> slide = phase * Math.min(.5, anchor.height * .2);
                    case WALL_SLIDE -> slideX = phase * .40;
                    case BUILD -> { transitionDepth = -phase * .14; zoom *= 1 - .10 * phase; }
                    case DISSOLVE -> { }
                }
            }
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
                    matrices.push();
                    place(matrices, cameraPos, anchor, 0, rowY + renderer.fontHeight * pixelScale / 2, -.018);
                    matrices.scale((float) pixelScale, (float) -pixelScale, (float) pixelScale);
                    int background = (int) Math.round(88 * visibility) << 24;
                    renderer.draw(row.backdrop, (float) (-row.backdropWidth / 2), 0, 0xFFFFFFFF, false,
                            matrices.peek().getPositionMatrix(), consumers, TextRenderer.TextLayerType.NORMAL,
                            background, LightmapTextureManager.MAX_LIGHT_COORDINATE);
                    matrices.pop();
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
                    matrices.push();
                    double pieceUp = rowY + renderer.fontHeight * scale / 2 + lift + composition.verticalOffset();
                    if (anchor.ground) pieceUp = Math.max(pieceUp,
                            -anchor.height * .5 + renderer.fontHeight * scale / 2 + .23);
                    place(matrices, cameraPos, anchor, center + motion.x() + slideX,
                            pieceUp, depth);
                    matrices.multiply(new Quaternionf().rotateZ((float) Math.toRadians(
                            anchor.wall ? Math.clamp(motion.tilt() + rotation + extraTilt, -2, 2)
                                    : motion.tilt() + rotation + extraTilt)));
                    matrices.scale(scale, -scale, scale);
                    double progress = LyricsAnimation.timedProgress(now, piece.token.startMillis(), piece.token.endMillis());
                    double boundary = (piece.rtl ? 1 - progress : progress) * piece.fragmentWidth
                            - piece.fragmentX - piece.width / 2;
                    if (composition.trailCopies() > 0 && animate) {
                        for (int trail = composition.trailCopies(); trail >= 1; trail--) {
                            matrices.push();
                            matrices.translate(-motion.x() * trail * 2.5 / scale, motion.y() * trail * 1.5 / scale, -.006 * trail / scale);
                            drawFace(renderer, piece, layout.timed, progress, boundary, visibility * .16 / trail,
                                    preferences.getWorldLyricsSideColor(), false, matrices, consumers);
                            matrices.pop();
                        }
                    }
                    boolean meshDrawn = extrude > .001 && WorldTextMesh.draw(piece.text,
                            matrices.peek().getPositionMatrix(), consumers, (float) (-piece.width / 2),
                            (float) (extrude / scale), preferences.getWorldLyricsFaceColor(), sideColor,
                            (float) (visibility * (layout.timed && progress >= 1 ? .82 : 1)),
                            layout.timed && progress < 1 ? (float) boundary : null, piece.rtl(),
                            preferences.isWorldLyricsBevel()
                                    && preferences.getWorldLyricsQuality() != WorldLyricsDirector.Quality.LOW
                                    && (composition.preset() == WorldLyricsDirector.Preset.INTRO
                                    || composition.preset() == WorldLyricsDirector.Preset.SCULPTURE
                                    || preferences.getWorldLyricsMaterial() == WorldLyricsDirector.Material.METAL),
                            (float) Math.min(1.2, preferences.getWorldLyricsBevelWidth()
                                    * (composition.preset() == WorldLyricsDirector.Preset.INTRO ? 1.4 : 1)));
                    if (!meshDrawn) drawFace(renderer, piece, layout.timed, progress, boundary, visibility,
                            preferences.getWorldLyricsFaceColor(), preferences.isLyricsTextShadow(), matrices, consumers);
                    matrices.pop();
                }
            }
        }
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
            case AUTO -> composition.preset() == WorldLyricsDirector.Preset.INTRO ? 0x817D76
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
                surface = findGroundSurface(client, camera, bands, bounds, composition.preset());
            if (surface == null && !wallFirst && preferences.isWorldLyricsAutoWalls()
                    && composition.preset() != WorldLyricsDirector.Preset.GROUND
                    && composition.preset() != WorldLyricsDirector.Preset.INTRO)
                surface = findSurface(client, camera, bands, bounds);
            if (surface != null) return surface;
        }
        if (preference == WorldLyricsDirector.SurfacePreference.HORIZON)
            requestedDistance = Math.min(preferences.getWorldLyricsMaximumDistance(), Math.max(32, requestedDistance));
        // Close walls cannot provide the reading horizon needed during running or gliding.
        double offset = composition.yawOffsetDegrees();
        if (STATE.blocksPerSecond() > 5.1) offset *= .25;
        double yaw = Math.toRadians(client.gameRenderer.getCamera().getYaw() + offset);
        double pitch = Math.toRadians(client.gameRenderer.getCamera().getPitch());
        boolean groundedStyle = composition.preset() == WorldLyricsDirector.Preset.GROUND && !STATE.flying();
        if (groundedStyle) pitch = 0;
        Vec3d direction = new Vec3d(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
        Vec3d travelDirection = travel(client).normalize();
        if (STATE.blocksPerSecond() > 5.1 && direction.dotProduct(travelDirection) > .4)
            direction = direction.multiply(.65).add(travelDirection.multiply(.35)).normalize();
        double distance = Math.max(.75, Math.min(groundedStyle ? Math.min(requestedDistance, 18) : requestedDistance,
                Math.max(24, client.options.getViewDistance().getValue() * 14)));
        // Keep the fallback in front of obstacles too; never create a panel beyond the first block.
        Vec3d hitTarget = camera.add(direction.multiply(distance));
        var obstacle = ray(client, camera, hitTarget);
        if (obstacle.getType() == HitResult.Type.BLOCK) distance = Math.max(.75, camera.distanceTo(obstacle.getPos()) - .5);
        Vec3d center = camera.add(direction.multiply(distance));
        double height = 2.25 * bands * Math.max(.5, distance / 6);
        if (groundedStyle) height = Math.min(height, 3 * bands);
        Vec3d normal = direction.negate();
        Vec3d right = new Vec3d(normal.z, 0, -normal.x).normalize();
        if (right.lengthSquared() < .001) right = new Vec3d(-Math.cos(yaw), 0, -Math.sin(yaw));
        if (groundedStyle) {
            Vec3d start = center.add(0, 5, 0);
            BlockHitResult floor = ray(client, start, center.add(0, -14, 0));
            if (floor.getType() == HitResult.Type.BLOCK && floor.getSide() == Direction.UP)
                center = new Vec3d(center.x, floor.getPos().y + height * .5 + .35, center.z);
        }
        center = liftAboveGround(client, center, right, distance, height, composition.depth());
        Anchor candidate = new Anchor(center.x, center.y, center.z, normal.x, normal.y, normal.z,
                right.x, right.z, (float) Math.atan2(normal.x, normal.z), false,
                Math.max(1.5, distance * (Math.abs(offset) > 1 ? .55 : .95)), height, bands, distance);
        if (Math.abs(composition.yawOffsetDegrees()) > .01 && !freePanel(client, camera, candidate)) {
            var front = new WorldLyricsDirector.Composition(composition.preset(), WorldLyricsDirector.Placement.FRONT,
                    composition.entrance(), composition.motion(), 0, 0, composition.depth(),
                    composition.trails(), composition.depthLayers(), composition.trailCopies());
            return createAnchor(client, camera, bands, preferences, front, requestedDistance, bounds);
        }
        return candidate;
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
                                            PhraseBounds bounds, WorldLyricsDirector.Preset preset) {
        double yaw = Math.toRadians(client.gameRenderer.getCamera().getYaw());
        double width = Math.clamp(bounds.width, 1.5, 7.5);
        double height = Math.clamp(bounds.height, 1.1 * bands, 3.2 * bands);
        int[] sides = (preset.ordinal() & 1) == 0 ? new int[]{-1, 1} : new int[]{1, -1};
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
                // The ordered candidates preserve variety while keeping the usual flat-ground probe short.
                return candidate;
            }
        }
        return null;
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
            if (score > bestScore) {
                bestScore = score;
                best = new Anchor(center.x, center.y, center.z, nx, 0, nz, nz, -nx,
                        (float) Math.atan2(nx, nz), true, width, height, bands, camera.distanceTo(center));
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

    private record LineKey(LyricLine line, boolean backing) { }
    private record Piece(Text text, WorldLyricsPlan.Token token, double x, double width,
                         double fragmentX, double fragmentWidth, double fraction, boolean rtl) { }
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
        long moveStartedAt, moveDuration;
        double moveRise;
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
