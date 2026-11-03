package dev.relaylyrics.client.ui;

import dev.relaylyrics.FanDroidMod;
import dev.relaylyrics.client.*;
import dev.relaylyrics.client.presence.*;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;
import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Opt-in native fixture; no music account or presence backend is contacted. */
public final class MusicPresenceProbe {
    private static final UUID ID = UUID.nameUUIDFromBytes("Lyrica presence fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    private static final String[] STAGES = {"before-dwell", "card", "follow-player", "tab", "command", "paused", "blocked-side", "rear-view", "cleared"};
    private static OtherClientPlayerEntity friend;
    private static PlayerListEntry entry;
    private static int stage, ticks, total;
    private static boolean pending;
    private static double initialAnchor;
    private MusicPresenceProbe() { }
    @SuppressWarnings("unchecked")
    public static void tick() {
        if (!Boolean.getBoolean("lyrica.musicPresenceSelfTest")) return;
        var c = MinecraftClient.getInstance();
        if (++total > 1600) throw new IllegalStateException("Presence probe timed out: " + STAGES[stage]);
        if (c.world == null || c.player == null || c.getOverlay() != null || pending) return;
        if (friend == null) {
            c.setScreen(null);
            var p = RelayLyricsClient.preferences(); p.setWorldLyrics(false); p.setLyricsVisible(false); p.setMusicPresenceEnabled(true);
            c.options.hudHidden = false;
            var server = c.getServer();
            server.execute(() -> { for (String command : List.of("gamemode spectator @a", "time set day", "weather clear")) WorldLyricsRenderHook.probeCommand(server, command); });
            var profile = new GameProfile(ID, "PresenceFriend");
            friend = new OtherClientPlayerEntity(c.world, profile); friend.setId(1500000);
            friend.setPosition(0, 81, 4); c.world.addEntity(friend);
            entry = new PlayerListEntry(profile, false);
            try {
                var field = c.getNetworkHandler().getClass().getDeclaredField("playerListEntries"); field.setAccessible(true);
                ((Map<UUID, PlayerListEntry>)field.get(c.getNetworkHandler())).put(ID, entry);
                var listed = c.getNetworkHandler().getClass().getDeclaredField("listedPlayerListEntries"); listed.setAccessible(true);
                ((Set<PlayerListEntry>)listed.get(c.getNetworkHandler())).add(entry);
            } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
            MusicPresence.probeTracks(Map.of(ID, new PresenceTrack(ID, "PresenceFriend", "A song shared with friends", "Lyrica Music Presence",
                    PresenceArtwork.prepare(LyricsSettingsDemo.artwork()), System.nanoTime() + 120_000_000_000L)));
        }
        c.player.setPosition(0, 81, 0); c.player.setYaw(stage == 2 ? -15 : 0); c.player.setPitch(stage == 2 ? 4 : 0);
        c.player.setVelocity(net.minecraft.util.math.Vec3d.ZERO);
        if (stage == 7) { c.player.setPosition(0,81,8); c.player.setYaw(180); c.player.setPitch(0); }
        friend.setPosition(stage >= 2 && stage <= 4 ? .8 : 0, 81, stage == 2 ? 7 : 4);
        friend.bodyYaw = 0;
        if (stage != 4) c.inGameHud.getChatHud().clear(false);
        MusicPresence.updateGaze(PresencePlatform.lookingAt());
        if (ticks++ == 0) {
            c.options.playerListKey.setPressed(stage == 3);
            if (stage == 4) {
                try {
                    var dispatcher = net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.getActiveDispatcher();
                    int result = dispatcher.execute("music PresenceFriend", (net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource)c.getNetworkHandler().getCommandSource());
                    if (result != 1) throw new IllegalStateException("/music did not find the listening player");
                } catch (com.mojang.brigadier.exceptions.CommandSyntaxException error) { throw new IllegalStateException(error); }
            }
            if (stage == 5) MusicPresence.probeTracks(Map.of());
            if (stage == 6) {
                MusicPresence.probeTracks(Map.of(ID, new PresenceTrack(ID, "PresenceFriend", "A song shared with friends", "Lyrica Music Presence",
                        PresenceArtwork.prepare(LyricsSettingsDemo.artwork()), System.nanoTime() + 120_000_000_000L)));
            }
            if (stage == 8) MusicPresence.close();
        }
        if (stage >= 6) {
            c.world.setBlockState(new net.minecraft.util.math.BlockPos(1, 82, 4), (stage == 6 ? net.minecraft.block.Blocks.STONE : net.minecraft.block.Blocks.AIR).getDefaultState(), 3);
        }
        int wait = stage == 6 ? 90 : stage == 0 ? 25 : stage == 1 ? 50 : stage == 2 ? 55 : 16;
        if (ticks < wait) return;
        if (stage == 0 && MusicPresence.pinned() != null) throw new IllegalStateException("Card opened before the dwell threshold");
        if (stage == 1) {
            if (!ID.equals(MusicPresence.pinned()) || !Double.isFinite(MusicPresenceRenderer.probeAnchorX()) || !MusicPresenceRenderer.probeReady()) return;
            initialAnchor = MusicPresenceRenderer.probeAnchorX();
        }
        if (stage == 2 && Math.abs(MusicPresenceRenderer.probeAnchorX() - initialAnchor) < 15) throw new IllegalStateException("Card did not follow player movement");
        if (stage == 2 && !ID.equals(MusicPresence.pinned())) throw new IllegalStateException("Looking at the card did not retain presence");
        if (stage == 6 && (!ID.equals(MusicPresence.pinned()) || MusicPresenceRenderer.probeSide() != -1)) throw new IllegalStateException("Card did not switch away from the obstructing block");
        if (stage == 7 && !MusicPresenceRenderer.probeBackFacing()) throw new IllegalStateException("Rear view must use the readable front face");
        String tab = c.inGameHud.getPlayerListHud().getPlayerName(entry).getString();
        if (stage == 3 && (!tab.contains("♫") || tab.contains("A song shared"))) throw new IllegalStateException("Tab must show just the music symbol beside the name");
        if ((stage == 5 || stage == 8) && (MusicPresence.track(ID) != null || tab.contains("♫"))) throw new IllegalStateException("Paused/disconnected presence remained visible");
        pending = true;
        Consumer<NativeImage> writer = image -> {
            try (image) {
                String version = FabricLoader.getInstance().getModContainer("minecraft").orElseThrow().getMetadata().getVersion().getFriendlyString();
                Path folder = Path.of(System.getProperty("lyrica.presenceProbeOutput", "build/presence-qa")).toAbsolutePath(); Files.createDirectories(folder);
                Path output = folder.resolve("presence-" + version + "-" + STAGES[stage] + ".png"); image.writeTo(output);
                FanDroidMod.LOGGER.info("Presence native stage {} passed: {}", STAGES[stage], output);
                if (++stage == STAGES.length) { FanDroidMod.LOGGER.info("Music Presence native probe passed"); c.scheduleStop(); return; }
                ticks = 0; pending = false;
            } catch (Exception error) { throw new IllegalStateException(error); }
        };
        try {
            try { var method = ScreenshotRecorder.class.getMethod("takeScreenshot", net.minecraft.client.gl.Framebuffer.class); writer.accept((NativeImage)method.invoke(null, c.getFramebuffer())); }
            catch (NoSuchMethodException async) { ScreenshotRecorder.class.getMethod("takeScreenshot", net.minecraft.client.gl.Framebuffer.class, Consumer.class).invoke(null, c.getFramebuffer(), writer); }
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
}
