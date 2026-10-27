package gg.playit.minecraft;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Install a PINNED official Playit daemon binary for the current Linux platform.
 *
 * The release, artifact and SHA-256 must be updated together after reviewing
 * the upstream release. No GitHub "latest" or unverified executable is trusted.
 * Always run this off the Bukkit main thread.
 */
public final class PlayitNativeInstaller {
    static final String RELEASE = "v1.0.12";
    private static final long MAX_BYTES = 24L * 1024 * 1024;
    private static final String RELEASE_BASE =
            "https://github.com/playit-cloud/playit-agent/releases/download/" + RELEASE + "/";

    private PlayitNativeInstaller() {}

    public static final class Artifact {
        public final String file;
        public final String sha256;
        Artifact(String file, String sha256) {
            this.file = file;
            this.sha256 = sha256;
        }
    }

    public static Artifact forPlatform(String os, String arch) throws IOException {
        if (!os.toLowerCase(Locale.ROOT).contains("linux")) {
            throw new IOException("No automatic Playit daemon download for " + os
                    + ". Set native-binary to a compatible official executable.");
        }

        return switch (arch.toLowerCase(Locale.ROOT)) {
            case "x86_64", "amd64" -> new Artifact("playit-linux-amd64",
                    "4ad5aa85670e056b2d2f5f47f9f992b81db34bedc89bbe755c6f8cc0c8206c78");
            case "aarch64", "arm64" -> new Artifact("playit-linux-aarch64",
                    "01ed32ab8f9bd15120a9adfbba866346687f7874398023458f8035997bd4e617");
            case "armv7l", "armv7" -> new Artifact("playit-linux-armv7",
                    "6b9e02ef92cebff5220cd7293cc3b807de8b32e6de66c5781d873025dc13514b");
            case "x86", "i386", "i686" -> new Artifact("playit-linux-i686",
                    "1e1311b31f09f0456aff463f2badc7b3d368e5a5ea1936224e92ff3cd57290d0");
            default -> throw new IOException("Unsupported Playit daemon CPU architecture: "
                    + arch + ". Set native-binary to an official executable.");
        };
    }

    public static Path ensure(Path dataDirectory, Logger log) throws IOException {
        Artifact artifact = forPlatform(System.getProperty("os.name", ""),
                System.getProperty("os.arch", ""));
        Path folder = dataDirectory.toAbsolutePath().normalize().resolve("bin");
        Files.createDirectories(folder);
        Path executable = folder.resolve("playitd-" + RELEASE + "-" + artifact.file);
        if (Files.isSymbolicLink(executable)) {
            throw new IOException("Refusing to run symlinked native executable: " + executable);
        }

        if (Files.isRegularFile(executable) && verify(executable, artifact.sha256)) {
            makeExecutable(executable);
            return executable;
        }

        log.info("[playit.gg] Installing official " + RELEASE + " " + artifact.file
                + " into " + folder + " (SHA-256 verification enabled).");
        Path temporary = Files.createTempFile(folder, ".playitd-", ".download");
        try {
            URI source = URI.create(RELEASE_BASE + artifact.file);
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(12))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(source)
                    .timeout(Duration.ofSeconds(60))
                    .header("User-Agent", "VoxelHorizons-PlayitMinecraftPlugin")
                    .GET().build();
            HttpResponse<InputStream> response;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("Native agent download interrupted", error);
            }
            try (InputStream stream = response.body()) {
                if (response.statusCode() != 200) {
                    throw new IOException("GitHub returned HTTP " + response.statusCode()
                            + " when downloading " + artifact.file);
                }
                if (!"https".equalsIgnoreCase(response.uri().getScheme())) {
                    throw new IOException("Native agent download redirected off HTTPS");
                }
                long copied = 0;
                byte[] buffer = new byte[16384];
                try (OutputStream out = Files.newOutputStream(temporary)) {
                    int count;
                    while ((count = stream.read(buffer)) != -1) {
                        copied += count;
                        if (copied > MAX_BYTES) {
                            throw new IOException("Native agent download exceeded 24 MiB limit");
                        }
                        out.write(buffer, 0, count);
                    }
                }
            }

            if (!verify(temporary, artifact.sha256)) {
                throw new IOException("SHA-256 mismatch for " + artifact.file
                        + "; refusing to run downloaded executable");
            }
            makeExecutable(temporary);
            try {
                Files.move(temporary, executable, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temporary, executable, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("[playit.gg] Verified official Playit daemon " + RELEASE
                    + " (" + artifact.file + ").");
            return executable;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static boolean verify(Path file, String expectedHex) throws IOException {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            try (InputStream stream = Files.newInputStream(file)) {
                byte[] buffer = new byte[16384];
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    hash.update(buffer, 0, count);
                }
            }
            return MessageDigest.isEqual(hash.digest(), java.util.HexFormat.of()
                    .parseHex(expectedHex.toLowerCase(Locale.ROOT)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 unavailable", impossible);
        }
    }

    private static void makeExecutable(Path file) throws IOException {
        try {
            Set<PosixFilePermission> permissions = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(file, permissions);
        } catch (UnsupportedOperationException exception) {
            if (!file.toFile().setExecutable(true, true)) {
                throw new IOException("Cannot mark native agent as executable: " + file);
            }
        }
    }
}
