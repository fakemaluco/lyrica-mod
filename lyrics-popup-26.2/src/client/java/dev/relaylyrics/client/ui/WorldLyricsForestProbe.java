package dev.relaylyrics.client.ui;

import dev.relaylyrics.FanDroidMod;
import dev.relaylyrics.client.GoogleFontManager;
import dev.relaylyrics.client.RelayLyricsClient;
import dev.relaylyrics.client.SungTextColor;
import dev.relaylyrics.client.WorldLyricsRenderHook;
import dev.relaylyrics.client.music.WorldLyricsPresentation;
import dev.relaylyrics.protocol.lyrics.LyricLine;
import dev.relaylyrics.protocol.lyrics.LyricsDocument;
import dev.relaylyrics.protocol.lyrics.TimedWord;
import dev.relaylyrics.protocol.music.MusicCapabilities;
import dev.relaylyrics.protocol.music.PlaybackSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Runs the renderer in a disposable naturally generated forest and captures what the camera sees. */
final class WorldLyricsForestProbe {
    private record Stage(String name, String font, boolean gradient, double speed) {}
    private record Route(List<Vec3> points, int yaw, int x, int z) {}
    private static final List<Stage> STAGES = List.of(
            new Stage("poppins-phrase-gradient", "Poppins", true, 0),
            new Stage("poppins-walk-solid", "Poppins", false, 4.2),
            new Stage("inter-sprint-gradient", "Inter", true, 5.8),
            new Stage("inter-phrase-solid", "Inter", false, 0));
    private static List<Route> routes;
    private static int stage, ticks, timeout, visibleFrames, relocated;
    private static boolean loading, ready, capturing;
    private static String lastAnchor;
    private static double minimum = Double.POSITIVE_INFINITY;

    private WorldLyricsForestProbe() {}

    static void tick(Minecraft client) {
        if (client.level == null || client.player == null || client.gui.overlay() != null || capturing) return;
        if (++timeout > 1100) throw new IllegalStateException("Forest fixture timed out at " + stage);
        client.options.pauseOnLostFocus = false;
        if (client.gui.screen() != null) client.gui.setScreen(null);
        client.gui.hud.getChat().clearMessages(false);
        var server = client.getSingleplayerServer();
        if (server == null || !server.getWorldData().getLevelName().equals("LyricaForestQA"))
            throw new IllegalStateException("Use the disposable LyricaForestQA world");
        if (stage >= STAGES.size()) return;
        var fixture = STAGES.get(stage);
        if (!loading) {
            loading = true;
            server.execute(() -> {
                var found = routes == null ? findRoutes(server.overworld()) : routes;
                GoogleFontManager.install(fixture.font()).thenAccept(result -> client.execute(() -> {
                    if (!result.success()) throw new IllegalStateException(result.message());
                    routes = found;
                    configure(fixture);
                    ready = true;
                }));
            });
        }
        if (!ready) return;
        var route = routes.get(stage % routes.size());
        double travelled = Math.min(route.points().size() - 1.001, Math.max(0, ticks - 26) * fixture.speed() / 20);
        int step = (int) travelled;
        var position = route.points().get(step).lerp(route.points().get(step + 1), travelled - step);
        client.player.setPos(position.x, position.y, position.z);
        client.player.setYRot(route.yaw());
        client.player.setXRot(-2);
        client.player.setOldPosAndRot();
        var velocity = new Vec3(route.x() * fixture.speed() / 20, 0, route.z() * fixture.speed() / 20);
        if (ticks < 26) velocity = Vec3.ZERO;
        client.player.setDeltaMovement(velocity);
        client.player.getAbilities().flying = false;
        client.player.setOnGround(true);
        client.player.setSprinting(fixture.speed() >= 5);
        WorldLyricsRenderer.probeFlight(velocity, false);
        if (++ticks == 8) lyrics(fixture);
        if (ticks >= 30 && WorldLyricsRenderer.probeWordCount() > 0) {
            visibleFrames++;
            var eye = WorldLyricsRenderHook.cameraPosition(client);
            for (var anchor : WorldLyricsRenderer.probePlacedAnchors())
                minimum = Math.min(minimum, eye.distanceTo(new Vec3(anchor.x(), anchor.y(), anchor.z())));
            String anchor = WorldLyricsRenderer.probeAnchors();
            if (lastAnchor != null && !lastAnchor.equals(anchor)) relocated++;
            lastAnchor = anchor;
        }
        if (ticks < 63 || WorldLyricsRenderer.probeWordCount() == 0 && ticks < 77) return;
        if (visibleFrames < 8 || WorldLyricsRenderer.probeWordCount() == 0 || !WorldLyricsRenderer.probeFontMatchesPopup())
            throw new IllegalStateException("Forest lyrics were unreadable in " + fixture.name() + ": " + WorldLyricsRenderer.probeSummary() + ", visible ticks=" + visibleFrames
                    + ", relocations=" + relocated + ", anchors=" + WorldLyricsRenderer.probeAnchors());
        if (fixture.speed() > 0 && minimum < .95)
            throw new IllegalStateException("Forest lyrics moved too close: " + minimum);
        capturing = true;
        int frames = visibleFrames, moves = relocated;
        double closest = minimum;
        int words = WorldLyricsRenderer.probeWordCount();
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                var path = Path.of(System.getProperty("lyrica.worldProbeOutput"));
                Files.createDirectories(path);
                image.writeToFile(path.resolve(fixture.name() + ".png"));
                client.execute(() -> {
                    FanDroidMod.LOGGER.info("Forest {}: distance travelled={}, visible ticks={}, relocations={}, closest={}, words={}",
                            fixture.name(), travelled, frames, moves, closest, words);
                    ticks = visibleFrames = relocated = 0;
                    lastAnchor = null;
                    minimum = Double.POSITIVE_INFINITY;
                    loading = ready = capturing = false;
                    if (++stage == STAGES.size()) client.stop();
                });
            } catch (Exception failure) { throw new IllegalStateException("Cannot save forest screenshot", failure); }
        });
    }

    private static List<Route> findRoutes(net.minecraft.server.level.ServerLevel world) {
        List<Route> result = new ArrayList<>();
        for (int[] direction : new int[][]{{0,1,0},{1,0,-90},{0,-1,180},{-1,0,90}}) {
            boolean found = false;
            for (int x = -18; x <= 18 && !found; x += 2) for (int z = -18; z <= 18 && !found; z += 2) {
                List<Vec3> points = new ArrayList<>();
                for (int step = 0; step < 17; step++) {
                    int xx = x + direction[0] * step, zz = z + direction[1] * step;
                    var feet = world.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(xx, 0, zz));
                    var ground = world.getBlockState(feet.below());
                    if (!(ground.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK)
                            || ground.is(net.minecraft.world.level.block.Blocks.DIRT))
                            || !world.getBlockState(feet).getCollisionShape(world, feet).isEmpty()
                            || !world.getBlockState(feet.above()).getCollisionShape(world, feet.above()).isEmpty()
                            || !points.isEmpty() && Math.abs(points.getLast().y - feet.getY()) > 1) break;
                    points.add(new Vec3(xx + .5, feet.getY(), zz + .5));
                }
                if (points.size() >= 13) { result.add(new Route(points, direction[2], direction[0], direction[1])); found = true; }
            }
        }
        if (result.isEmpty()) throw new IllegalStateException("No walkable forest route found");
        return List.copyOf(result);
    }

    private static void configure(Stage fixture) {
        var p = RelayLyricsClient.preferences();
        p.setGoogleFontFamily(fixture.font());
        p.setWorldLyrics(true);
        p.setLyricsVisible(true);
        p.setWorldLyricsReposition(true);
        p.setLyricsAnimations(true);
        p.setWorldLyricsOutline(true);
        p.setWorldLyricsOutlineColor(0x080A0C);
        p.setWorldLyricsSize(1.3);
        p.setWorldLyricsReveal(fixture.speed() > 0 ? WorldLyricsPresentation.Reveal.WORD_BY_WORD : WorldLyricsPresentation.Reveal.PHRASE_AND_WORD);
        p.setSungTextColor(new SungTextColor(fixture.gradient() ? SungTextColor.Mode.GRADIENT : SungTextColor.Mode.SOLID,
                0x2EEDFF, List.of(new SungTextColor.Stop(0, 0xFF4D78), new SungTextColor.Stop(500, 0xFFEB39),
                new SungTextColor.Stop(1000, 0x3BFFB4))));
    }

    private static void lyrics(Stage fixture) {
        String text = fixture.speed() > 0 ? "Caminhando pela floresta iluminada" :
                "Aqui entre folhas e colinas cada palavra da frase continua aparecendo até o final inteiro";
        List<TimedWord> timed = fixture.speed() > 0 ? List.of(new TimedWord(0, 900, "Caminhando"),
                new TimedWord(900, 1700, " pela"), new TimedWord(1700, 2900, " floresta"),
                new TimedWord(2900, 12000, " iluminada")) : List.of(new TimedWord(0, 450, "Aqui"), new TimedWord(450, 800, " entre"));
        var overlay = RelayLyricsClient.overlay();
        overlay.setLyrics(new LyricsDocument(List.of(new LyricLine(0, 12000, text, timed, "lead", false, null, null))));
        overlay.setPlayback(new PlaybackSnapshot("Forest fixture", "Lyrica", "", null, "fixture:forest:" + stage,
                "Local fixture", fixture.speed() > 0 ? 2400 : 10400, 60000, false, .65, false,
                "off", false, 0, 0, 0, 0, new double[0], Instant.now(), MusicCapabilities.unavailable()));
    }
}
