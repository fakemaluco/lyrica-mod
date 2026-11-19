package dev.relaylyrics.client.ui;

import dev.relaylyrics.FanDroidMod;
import dev.relaylyrics.client.ClientPreferences;
import dev.relaylyrics.client.RelayLyricsClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import java.nio.file.Files;
import java.nio.file.Path;

/** Opt-in screenshots in the disposable launcher world. Never requests a real update. */
public final class UpdateIslandProbe {
    private static int ticks;
    private static long ignoredAt;
    private static boolean queued;
    private static UpdateIslandScreen screen;

    private UpdateIslandProbe() { }

    public static void tick(Minecraft client) {
        if (client.player == null || client.level == null || client.gui.overlay() != null) return;
        switch (++ticks) {
            case 10 -> {
                var preferences = RelayLyricsClient.preferences();
                preferences.setLyricsVisible(true);
                preferences.setLyricsPinned(true);
                preferences.setPopupEdgeDocking(true);
                preferences.setPopupDocked(true);
            }
            case 32 -> {
                var overlay = RelayLyricsClient.overlay();
                overlay.setPlayback(dev.relaylyrics.protocol.music.PlaybackSnapshot.unavailable());
                if (!overlay.isHudVisible())
                    throw new IllegalStateException("The docked island must be visible before playback");
                capture(client, "00-ilha-sem-musica.png");
            }
            case 40 -> screen = UpdateIslandScreen.preview(() -> queued ? "queued" : "ready", () -> queued = true,
                    () -> RelayLyricsClient.music().support().dismissUpdate("0.9.5"));
            case 48 -> capture(client, "01-aviso-na-ilha.png");
            case 55 -> {
                client.gui.setScreen(screen);
                screen.pointerForPreview();
            }
            case 62 -> capture(client, "02-atualizacao-disponivel.png");
            case 70 -> {
                if (!screen.clickForPreview(false) || !queued) throw new IllegalStateException("Schedule button did not activate");
            }
            case 77 -> capture(client, "03-atualizacao-agendada.png");
            case 90 -> {
                if (!screen.clickForPreview(true) || !ClientPreferences.load().isUpdateDismissed("0.9.5"))
                    throw new IllegalStateException("Dismissal did not persist");
                FanDroidMod.LOGGER.info("Update island dismissal persisted after reloading client preferences");
            }
            case 100 -> {
                if (UpdateIslandScreen.hasNotice() || client.gui.screen() == screen)
                    throw new IllegalStateException("Dismissed update remained visible");
                queued = false;
                UpdateIslandScreen.preview(() -> "ready", () -> { }, () -> { });
                ignoredAt = System.nanoTime();
            }
        }
        if (ignoredAt != 0 && System.nanoTime() - ignoredAt >= 15_400_000_000L) {
            if (UpdateIslandScreen.hasNotice()) throw new IllegalStateException("Ignored update did not expire");
            FanDroidMod.LOGGER.info("Ignored update expired after 15 seconds");
            client.stop();
        }
    }

    private static void capture(Minecraft client, String name) {
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                Path output = Path.of(System.getProperty("lyrica.updateIslandProbeOutput"));
                Files.createDirectories(output);
                image.writeToFile(output.resolve(name));
                FanDroidMod.LOGGER.info("Update island screenshot saved: {}", name);
            } catch (Exception failure) {
                throw new IllegalStateException("Update island screenshot failed", failure);
            }
        });
    }
}
