package dev.fandroid.client.music;

import net.fabricmc.loader.api.FabricLoader;

import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Extracts the bundled browser companion so it can be loaded as an unpacked extension. */
public final class YouTubeMusicCompanion {
    private static final String[] FILES = {"manifest.json", "content.js", "background.js", "README.md"};

    private YouTubeMusicCompanion() { }

    public static Path install() throws IOException {
        Path directory = FabricLoader.getInstance().getConfigDir().resolve("youtube-music-extension");
        Files.createDirectories(directory);
        for (String name : FILES) {
            try (InputStream input = YouTubeMusicCompanion.class.getResourceAsStream(
                    "/youtube-music-extension/" + name)) {
                if (input == null) throw new IOException("Missing bundled YouTube Music companion file: " + name);
                Files.copy(input, directory.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return directory;
    }

    public static Path installAndOpen() throws IOException {
        Path directory = install();
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            try {
                Desktop.getDesktop().open(directory.toFile());
            } catch (IOException | SecurityException ignored) {
                // The copied path still lets the player select the folder manually.
            }
        }
        return directory;
    }
}
