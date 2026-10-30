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
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Screenshot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Opt-in real Minecraft GUI fixture. Never connects accounts or writes user preferences. */
public final class LyricsConfigProbe {
    private static final String[] STAGES = Boolean.getBoolean("lyrica.configResponsiveSelfTest")
            ? new String[]{"general", "source_priority", "priority_choices", "compact", "compact_priority", "compact_tiny"}
            : Boolean.getBoolean("lyrica.configArtistOnlySelfTest")
            ? new String[]{"appearance", "artist", "artist_fallback"}
            : Boolean.getBoolean("lyrica.configRenderingSelfTest")
            ? new String[]{"general", "appearance", "quality_choices", "compact", "compact_choices", "kawarp", "kawarp_later", "artist", "artist_fallback", "static", "paused"}
            : Boolean.getBoolean("lyrica.configInteractionSelfTest")
            ? new String[]{"ui_toggle_0", "ui_toggle_20", "ui_toggle_40", "ui_toggle_80", "ui_toggle_140", "ui_toggle_220",
            "ui_hover_0", "ui_hover_50", "ui_hover_150", "ui_press_60", "ui_pane_0", "ui_pane_30", "ui_pane_70", "ui_pane_140", "ui_open_0", "ui_open_30", "ui_open_70", "ui_open_140", "ui_open_220", "ui_peek", "compact", "compact_choices"}
            : Boolean.getBoolean("lyrica.configCompactOnlySelfTest")
            ? new String[]{"compact", "compact_choices", "search"}
            : Boolean.getBoolean("lyrica.configModalOnlySelfTest")
            ? new String[]{"general", "appearance", "choices", "search", "sources_end", "motion_artwork", "world", "compact", "compact_choices"}
            : Boolean.getBoolean("lyrica.configMotionOnlySelfTest")
            ? new String[]{"appearance", "motion_artwork"}
            : Boolean.getBoolean("lyrica.configSourcesOnlySelfTest")
            ? new String[]{"sources", "sources_end"}
            : Boolean.getBoolean("lyrica.configNoticeOnlySelfTest")
            ? new String[]{"unavailable_notice", "unavailable_faded", "lyrics_return"}
            : new String[]{"general", "appearance", "connection", "custom", "advanced",
            "experimental", "world", "world_fixed", "effects", "choices", "editor",
            "unavailable_notice", "unavailable_faded", "lyrics_return", "spotify_guide", "youtube_guide",
            "general", "appearance", "custom", "advanced", "world", "categories", "editor",
            "spotify_guide", "youtube_guide"};
    private static int stage, ticks, backdropFrame;
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
            if (capturePending || client.gui.overlay() != null) return;
            if (++ticks < (Boolean.getBoolean("lyrica.configInteractionSelfTest") ? 4 : 30)) return;
            if (Boolean.getBoolean("lyrica.configRenderingSelfTest") && STAGES[stage].equals("artist") && !ARTWORK.artistReady()) {
                if (ticks < 300) return;
                throw new IllegalStateException("Artist photo was not decoded and installed");
            }
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
        Minecraft client = Minecraft.getInstance();
        client.options.guiScale().set((Boolean.getBoolean("lyrica.configResponsiveSelfTest") || Boolean.getBoolean("lyrica.configModalOnlySelfTest") || Boolean.getBoolean("lyrica.configCompactOnlySelfTest") || Boolean.getBoolean("lyrica.configInteractionSelfTest") || Boolean.getBoolean("lyrica.configRenderingSelfTest")) ? (STAGES[stage].startsWith("compact") ? 3 : 1) : stage >= 16 ? 3 : 1); client.resizeGui();
        String name = STAGES[stage];
        if (Boolean.getBoolean("lyrica.configResponsiveSelfTest")) {
            PREFERENCES.setLyricsWidth(name.equals("compact_tiny") ? 96 : 420);
            PREFERENCES.setLyricsHeight(name.equals("compact_tiny") ? 64 : 260);
        }
        if (Boolean.getBoolean("lyrica.configRenderingSelfTest")) {
            restoreCurrentSong(!name.equals("paused"));
            PREFERENCES.setLyricsAnimations(!name.equals("static"));
            PREFERENCES.setLyricsDynamicBackground(true);
            PREFERENCES.setLyricsArtistBackground(name.startsWith("artist"));
            PREFERENCES.setLyricsWidth(840); PREFERENCES.setLyricsHeight(540);
            PREFERENCES.setLyricsX(40); PREFERENCES.setLyricsY(52);
            ARTWORK.configureArtist(name.equals("artist") ? dev.relaylyrics.client.LyricsSettingsDemo.artistArtwork() : null);
            ARTWORK.setMotionPlaying(!name.equals("paused"));
        }
        screen = null; spotifyScreen = null; youtubeScreen = null;
        if (name.equals("unavailable_notice")) {
            RelayLyricsClient.overlay().clearLyrics();
            RelayLyricsClient.overlay().markUnavailable("No lyrics");
            unavailableNanos = System.nanoTime();
            unavailableBounds = RelayLyricsClient.overlay().popupBoundsSnapshot(1280, 720);
        } else if (name.equals("lyrics_return")) restoreCurrentSong();
        if (name.equals("spotify_guide")) {
            spotifyScreen = new SpotifyAccountScreen(null, PREFERENCES, null);
            client.gui.setScreen(spotifyScreen);
        } else if (name.equals("youtube_guide")) {
            youtubeScreen = new YouTubeMusicConnectionScreen(null, PREFERENCES, null);
            client.gui.setScreen(youtubeScreen);
        } else {
            screen = new LyricsConfigScreen(null, PREFERENCES);
            client.gui.setScreen(screen);
            screen.configureProbe(name.startsWith("unavailable_") || name.equals("lyrics_return") ? "editor" : name);
        }
        if (screen != null) screen.prepareInteractionProbe(name);
        ticks = 0; backdropFrame = 0; capturePending = false;
    }
    private static void captured() {
        if (Boolean.getBoolean("lyrica.configRenderingSelfTest") && STAGES[stage].equals("kawarp") && ++backdropFrame < 32) {
            ticks = 28; capturePending = false; return;
        }
        var client = Minecraft.getInstance();
        if (++stage < STAGES.length) { client.execute(LyricsConfigProbe::showStage); return; }
        if (!portuguese) {
            portuguese = true; stage = 0;
            client.getLanguageManager().setSelected("pt_br");
            client.reloadResourcePacks().thenRun(() -> client.execute(LyricsConfigProbe::showStage)); return;
        }
        FanDroidMod.LOGGER.info(Boolean.getBoolean("lyrica.configRenderingSelfTest")
                ? "Rendering GUI probe passed: sharp fonts/borders at GUI scales 1/3, Kawarp, artist image/fallback and static/paused states"
                : Boolean.getBoolean("lyrica.configInteractionSelfTest")
                ? "Settings interaction GUI probe passed: English/Portuguese, intermediate toggle/hover/press/pane/open frames and unchanged live popup bounds"
                : Boolean.getBoolean("lyrica.configModalOnlySelfTest")
                ? "Centered settings GUI probe passed: English/Portuguese, navigation, search, dropdowns, sources, videos and compact GUI scale"
                : Boolean.getBoolean("lyrica.configMotionOnlySelfTest")
                ? "Motion settings GUI probe passed: English/Portuguese, video controls and scrolling"
                : Boolean.getBoolean("lyrica.configSourcesOnlySelfTest")
                ? "Sources GUI probe passed: English/Portuguese, full source list and scrolling"
                : Boolean.getBoolean("lyrica.configNoticeOnlySelfTest")
                ? "Notice GUI probe passed: English/Portuguese, 10s notice fade, unchanged popup bounds, lyrics return"
                : "Settings GUI probe passed: English/Portuguese, inline choices, custom settings, live-song preview, 10s unavailable fade/fixed bounds/return, connection guides and GUI scales 1/3");
        client.stop();
    }
    private static void restoreCurrentSong() { restoreCurrentSong(true); }
    private static void restoreCurrentSong(boolean playing) {
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
                2_000, 400_000, playing, .65, false, "off", true, .4, 0, 110, .5, new double[0],
                Instant.now(), MusicCapabilities.unavailable()));
        RelayLyricsClient.overlay().setLyrics(fixtureLyrics, "LYRICS");
    }
    private static void capture() {
        var client = Minecraft.getInstance();
        Consumer<NativeImage> writer = image -> {
            try (image) {
                String version = FabricLoader.getInstance().getModContainer("minecraft").orElseThrow().getMetadata().getVersion().getFriendlyString();
                Path directory = Path.of(System.getProperty("lyrica.configProbeOutput", "build/config-qa")).toAbsolutePath().normalize();
                Files.createDirectories(directory);
                Path output = directory.resolve("config-" + version + "-" + (portuguese ? "pt" : "en") + "-" + stage + "-" + STAGES[stage] + ".png");
                if (Boolean.getBoolean("lyrica.configRenderingSelfTest") && STAGES[stage].equals("kawarp")) {
                    Path frame = directory.resolve("kawarp-" + version + "-" + (portuguese ? "pt" : "en") + "-" + String.format(java.util.Locale.ROOT, "%02d", backdropFrame) + ".png");
                    image.writeToFile(frame);
                    if (backdropFrame == 0) image.writeToFile(output);
                } else image.writeToFile(output);
                FanDroidMod.LOGGER.info("Settings screenshot: {}", output);
                captured();
            } catch (Exception failure) { throw new IllegalStateException("Settings screenshot failed", failure); }
        };
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), writer);
    }
}
