package dev.lyrica.engine;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.IOException;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class LyricaUpdateInstallerTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void swapsTheCheckedFileAndKeepsTheOldJar() throws Exception {
        var target = temporary.newFile("installed.jar").toPath();
        var staged = temporary.newFile("download.jar").toPath();
        Files.writeString(target, "old version");
        Files.writeString(staged, "new version");
        var oldHash = LyricaUpdateInstaller.hash(target);
        LyricaUpdateInstaller.install(staged, target, LyricaUpdateInstaller.hash(staged), oldHash);
        assertEquals("new version", Files.readString(target));
        assertEquals("old version", Files.readString(staged.resolveSibling("previous.jar")));
    }

    @Test public void rejectsStaleTargetsAndDamagedDownloads() throws Exception {
        var target = temporary.newFile("installed.jar").toPath();
        var staged = temporary.newFile("download.jar").toPath();
        Files.writeString(target, "current version");
        Files.writeString(staged, "new version");
        var oldHash = LyricaUpdateInstaller.hash(target);
        var newHash = LyricaUpdateInstaller.hash(staged);
        assertThrows(IOException.class, () -> LyricaUpdateInstaller.install(staged, target, "bad", oldHash));
        assertThrows(IOException.class, () -> LyricaUpdateInstaller.install(staged, target, newHash, "bad"));
        assertEquals("current version", Files.readString(target));
        assertEquals("new version", Files.readString(staged));
    }
}
