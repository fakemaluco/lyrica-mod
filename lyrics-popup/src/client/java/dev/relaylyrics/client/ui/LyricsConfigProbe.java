package dev.relaylyrics.client.ui;

import dev.relaylyrics.FanDroidMod;
import dev.relaylyrics.client.ClientPreferences;
import dev.relaylyrics.client.LyricsSettingsDemo;
import dev.relaylyrics.client.RelayLyricsClient;
import dev.relaylyrics.client.music.CoverArtTexture;
import dev.relaylyrics.protocol.lyrics.LyricLine;
import dev.relaylyrics.protocol.lyrics.LyricsDocument;
import dev.relaylyrics.protocol.lyrics.TimedWord;
import dev.relaylyrics.protocol.music.MusicCapabilities;
import dev.relaylyrics.protocol.music.PlaybackSnapshot;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Opt-in real Minecraft GUI fixture. Never connects accounts or writes user preferences. */
public final class LyricsConfigProbe {
    private static final String[] STAGES = Boolean.getBoolean("lyrica.configNoticeOnlySelfTest")
            ? new String[]{"unavailable_notice", "unavailable_faded", "lyrics_return"}
            : new String[]{"general", "appearance", "connection", "custom", "advanced",
            "experimental", "world", "world_fixed", "effects", "choices", "editor",
            "unavailable_notice", "unavailable_faded", "lyrics_return", "spotify_guide", "youtube_guide",
            "general", "appearance", "custom", "advanced", "world", "categories", "editor",
            "spotify_guide", "youtube_guide"};
    private static int stage, ticks;
    private static boolean capturePending, portuguese;
    private static LyricsConfigScreen screen;
    private static SpotifyAccountScreen spotifyScreen;
    private static YouTubeMusicConnectionScreen youtubeScreen;
    private static final ClientPreferences PREFERENCES = new ClientPreferences();
    // One-shot fixtures live until process shutdown, including queued 26.2 GUI draws.
    private static final CoverArtTexture ARTWORK = new CoverArtTexture();
    private static LyricsDocument fixtureLyrics;
    private static long unavailableNanos;
    private static LyricsOverlay.PopupBounds unavailableBounds;
    private LyricsConfigProbe() { }
    public static void start() {
        PREFERENCES.setModernMode(true);
        ARTWORK.update(LyricsSettingsDemo.artwork());
        RelayLyricsClient.overlay().setArtworkTexture(ARTWORK);
        RelayLyricsClient.overlay().setArtworkAccent(0xFF8266C4);
        restoreCurrentSong();
        showStage();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (capturePending || client.getOverlay() != null) return;
            if (++ticks < 30) return;
            if (STAGES[stage].equals("unavailable_faded")
                    && System.nanoTime() - unavailableNanos < 13_000_000_000L) return;
            if (STAGES[stage].equals("unavailable_faded")) {
                var bounds = RelayLyricsClient.overlay().popupBoundsSnapshot(1280, 720);
                if (!bounds.equals(unavailableBounds)) throw new IllegalStateException("Unavailable notice resized or moved the popup");
            }
            capturePending = true;
            if (screen != null) screen.verifyLayoutForProbe();
            if (spotifyScreen != null) spotifyScreen.verifyLayoutForProbe();
            if (youtubeScreen != null) youtubeScreen.verifyLayoutForProbe();
            capture();
        });
    }
    private static void showStage() {
        MinecraftClient client = MinecraftClient.getInstance();
        client.options.getGuiScale().setValue(stage >= 16 ? 3 : 1); client.onResolutionChanged();
        String name = STAGES[stage];
        screen = null; spotifyScreen = null; youtubeScreen = null;
        if (name.equals("unavailable_notice")) {
            RelayLyricsClient.overlay().clearLyrics();
            RelayLyricsClient.overlay().markUnavailable("No lyrics");
            unavailableNanos = System.nanoTime();
            unavailableBounds = RelayLyricsClient.overlay().popupBoundsSnapshot(1280, 720);
        } else if (name.equals("lyrics_return")) restoreCurrentSong();
        if (name.equals("spotify_guide")) {
            spotifyScreen = new SpotifyAccountScreen(null, PREFERENCES, null);
            client.setScreen(spotifyScreen);
        } else if (name.equals("youtube_guide")) {
            youtubeScreen = new YouTubeMusicConnectionScreen(null, PREFERENCES, null);
            client.setScreen(youtubeScreen);
        } else {
            screen = new LyricsConfigScreen(null, PREFERENCES);
            client.setScreen(screen);
            screen.configureProbe(name.startsWith("unavailable_") || name.equals("lyrics_return") ? "editor" : name);
        }
        ticks = 0; capturePending = false;
    }
    private static void captured() {
        var client = MinecraftClient.getInstance();
        if (++stage < STAGES.length) { client.execute(LyricsConfigProbe::showStage); return; }
        if (!portuguese) {
            portuguese = true; stage = 0;
            client.getLanguageManager().setLanguage("pt_br");
            client.reloadResources().thenRun(() -> client.execute(LyricsConfigProbe::showStage)); return;
        }
        FanDroidMod.LOGGER.info(Boolean.getBoolean("lyrica.configNoticeOnlySelfTest")
                ? "Notice GUI probe passed: English/Portuguese, 10s notice fade, unchanged popup bounds, lyrics return"
                : "Settings GUI probe passed: English/Portuguese, inline choices, custom settings, live-song preview, 10s unavailable fade/fixed bounds/return, connection guides and GUI scales 1/3");
        client.scheduleStop();
    }
    private static void restoreCurrentSong() {
        if (fixtureLyrics == null) {
            List<LyricLine> lines = new ArrayList<>();
            String[] phrases = {"Let the words follow the music", "Every moment has a rhythm", "A luz acompanha a canção"};
            for (int index = 0; index < 40; index++) {
                long start = index * 10_000L;
                String phrase = phrases[index % phrases.length];
                String[] pieces = phrase.split(" ");
                List<TimedWord> words = new ArrayList<>();
                for (int word = 0; word < pieces.length; word++)
                    words.add(new TimedWord(start + word * 1_100L, start + (word + 1) * 1_100L, pieces[word]));
                lines.add(new LyricLine(start, start + 8_500, phrase, words, "lead", false,
                        "As palavras acompanham a música", null));
            }
            fixtureLyrics = new LyricsDocument(lines);
        }
        RelayLyricsClient.overlay().setPlayback(new PlaybackSnapshot("Current song • Música atual",
                "Lyrica visual test", "Current playback", null, "spotify:track:configFixture", "Native GUI fixture",
                2_000, 400_000, true, .65, false, "off", true, .4, 0, 110, .5, new double[0],
                Instant.now(), MusicCapabilities.unavailable()));
        RelayLyricsClient.overlay().setLyrics(fixtureLyrics, "LYRICS");
    }
    private static void capture() {
        var client = MinecraftClient.getInstance();
        Consumer<NativeImage> writer = image -> {
            try (image) {
                String version = FabricLoader.getInstance().getModContainer("minecraft").orElseThrow().getMetadata().getVersion().getFriendlyString();
                Path directory = Path.of(System.getProperty("lyrica.configProbeOutput", "build/config-qa")).toAbsolutePath().normalize();
                Files.createDirectories(directory);
                Path output = directory.resolve("config-" + version + "-" + (portuguese ? "pt" : "en") + "-" + stage + "-" + STAGES[stage] + ".png");
                image.writeTo(output);
                FanDroidMod.LOGGER.info("Settings screenshot: {}", output);
                captured();
            } catch (Exception failure) { throw new IllegalStateException("Settings screenshot failed", failure); }
        };
        try {
            try {
                var synchronous = ScreenshotRecorder.class.getMethod("takeScreenshot", net.minecraft.client.gl.Framebuffer.class);
                writer.accept((NativeImage) synchronous.invoke(null, client.getFramebuffer()));
            } catch (NoSuchMethodException callbackApi) {
                var asynchronous = ScreenshotRecorder.class.getMethod("takeScreenshot", net.minecraft.client.gl.Framebuffer.class, Consumer.class);
                asynchronous.invoke(null, client.getFramebuffer(), writer);
            }
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Settings framebuffer capture failed", failure); }
    }
}
