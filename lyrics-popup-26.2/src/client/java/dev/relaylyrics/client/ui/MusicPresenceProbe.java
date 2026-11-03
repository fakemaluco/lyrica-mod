package dev.relaylyrics.client.ui;

import dev.relaylyrics.FanDroidMod;
import dev.relaylyrics.client.*;
import dev.relaylyrics.client.presence.*;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.multiplayer.PlayerInfo;
import com.mojang.blaze3d.platform.NativeImage;

import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Opt-in native fixture; no music account or presence backend is contacted. */
public final class MusicPresenceProbe {
    private static final UUID ID = UUID.nameUUIDFromBytes("Lyrica presence fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    private static final String[] STAGES = {"before-dwell", "card", "follow-player", "tab", "command", "paused", "blocked-side", "rear-view", "cleared"};
    private static RemotePlayer friend;
    private static PlayerInfo entry;
    private static int stage, ticks, total;
    private static boolean pending;
    private static double initialAnchor;
    private MusicPresenceProbe() { }
    @SuppressWarnings("unchecked")
    public static void tick() {
        if (!Boolean.getBoolean("lyrica.musicPresenceSelfTest")) return;
        var c = Minecraft.getInstance();
        if (++total > 1600) throw new IllegalStateException("Presence probe timed out: " + STAGES[stage]);
        if (c.level == null || c.player == null || c.gui.overlay() != null || pending) return;
        if (friend == null) {
            c.gui.setScreen(null);
            var p = RelayLyricsClient.preferences(); p.setWorldLyrics(false); p.setLyricsVisible(false); p.setMusicPresenceEnabled(true);
            if (c.gui.hud.isHidden()) c.gui.hud.toggle();
            var server = c.getSingleplayerServer();
            server.execute(() -> { for (String command : List.of("gamemode spectator @a", "time set day", "weather clear")) server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command); });
            var profile = new GameProfile(ID, "PresenceFriend");
            friend = new RemotePlayer(c.level, profile); friend.setId(1500000);
            friend.setPos(0, 81, 4); c.level.addEntity(friend);
            entry = new PlayerInfo(profile, false);
            try {
                var field = c.getConnection().getClass().getDeclaredField("playerInfoMap"); field.setAccessible(true);
                ((Map<UUID, PlayerInfo>)field.get(c.getConnection())).put(ID, entry);
                var listed = c.getConnection().getClass().getDeclaredField("listedPlayers"); listed.setAccessible(true);
                ((Set<PlayerInfo>)listed.get(c.getConnection())).add(entry);
            } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
            MusicPresence.probeTracks(Map.of(ID, new PresenceTrack(ID, "PresenceFriend", "A song shared with friends", "Lyrica Music Presence",
                    PresenceArtwork.prepare(LyricsSettingsDemo.artwork()), System.nanoTime() + 120_000_000_000L)));
        }
        c.player.setPos(0, 81, 0); c.player.setYRot(stage == 2 ? -15 : 0); c.player.setXRot(stage == 2 ? 4 : 0);
        c.player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        if (stage == 7) { c.player.setPos(0,81,8); c.player.setYRot(180); c.player.setXRot(0); }
        friend.setPos(stage >= 2 && stage <= 4 ? .8 : 0, 81, stage == 2 ? 7 : 4);
        friend.yBodyRot = 0;
        if (stage != 4) c.gui.hud.getChat().clearMessages(false);
        MusicPresence.updateGaze(PresencePlatform.lookingAt());
        if (ticks++ == 0) {
            c.options.keyPlayerList.setDown(stage == 3);
            if (stage == 4) {
                try {
                    var dispatcher = net.fabricmc.fabric.api.client.command.v2.ClientCommands.getActiveDispatcher();
                    int result = dispatcher.execute("music PresenceFriend", (net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource)c.getConnection().getSuggestionsProvider());
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
            c.level.setBlock(new net.minecraft.core.BlockPos(1, 82, 4), (stage == 6 ? net.minecraft.world.level.block.Blocks.STONE : net.minecraft.world.level.block.Blocks.AIR).defaultBlockState(), 3);
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
        String tab = c.gui.hud.getTabList().getNameForDisplay(entry).getString();
        if (stage == 3 && (!tab.contains("♫") || tab.contains("A song shared"))) throw new IllegalStateException("Tab must show just the music symbol beside the name");
        if ((stage == 5 || stage == 8) && (MusicPresence.track(ID) != null || tab.contains("♫"))) throw new IllegalStateException("Paused/disconnected presence remained visible");
        pending = true;
        Consumer<NativeImage> writer = image -> {
            try (image) {
                String version = FabricLoader.getInstance().getModContainer("minecraft").orElseThrow().getMetadata().getVersion().getFriendlyString();
                Path folder = Path.of(System.getProperty("lyrica.presenceProbeOutput", "build/presence-qa")).toAbsolutePath(); Files.createDirectories(folder);
                Path output = folder.resolve("presence-" + version + "-" + STAGES[stage] + ".png"); image.writeToFile(output);
                FanDroidMod.LOGGER.info("Presence native stage {} passed: {}", STAGES[stage], output);
                if (++stage == STAGES.length) { FanDroidMod.LOGGER.info("Music Presence native probe passed"); c.stop(); return; }
                ticks = 0; pending = false;
            } catch (Exception error) { throw new IllegalStateException(error); }
        };
        net.minecraft.client.Screenshot.takeScreenshot(c.gameRenderer.mainRenderTarget(), writer);
    }
}
