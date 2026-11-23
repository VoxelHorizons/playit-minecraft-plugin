package gg.playit.minecraft;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PlayitNativeInstallerTest {
    @TempDir Path dir;

    @Test
    void selectsOfficialLinuxArtifacts() throws Exception {
        assertEquals("playit-linux-amd64",
                PlayitNativeInstaller.forPlatform("Linux", "amd64").file);
        assertEquals("playit-linux-aarch64",
                PlayitNativeInstaller.forPlatform("Linux", "arm64").file);
        assertEquals("playit-linux-armv7",
                PlayitNativeInstaller.forPlatform("Linux", "armv7l").file);
        assertEquals("playit-linux-i686",
                PlayitNativeInstaller.forPlatform("Linux", "i686").file);
        assertThrows(IOException.class, () ->
                PlayitNativeInstaller.forPlatform("Mac OS X", "amd64"));
    }

    @Test
    void refusesChangedExecutable() throws Exception {
        Path file = dir.resolve("agent");
        Files.writeString(file, "abc");
        String digest = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        assertTrue(PlayitNativeInstaller.verify(file, digest));
        Files.writeString(file, "changed");
        assertFalse(PlayitNativeInstaller.verify(file, digest));
    }
}
