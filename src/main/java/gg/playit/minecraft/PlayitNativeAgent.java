package gg.playit.minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Supervise the official playitd agent. Unlike the Java-only legacy backend,
 * playitd forwards TCP AND UDP, including Geyser and Simple Voice Chat.
 *
 * No shell is involved and secrets never appear in process arguments.
 */
public final class PlayitNativeAgent {
    private final Logger log;
    private final Path dataDir;
    private final String executable;
    private final boolean autoInstall;
    private Process process;
    private volatile boolean suspended;
    private volatile boolean installing;
    private volatile long lastLaunchAttempt;
    private volatile String lastFailure;

    public PlayitNativeAgent(Logger log, Path dataDir, String executable, boolean autoInstall) {
        this.log = log;
        this.dataDir = dataDir.toAbsolutePath().normalize();
        this.executable = executable == null || executable.isBlank() ? "auto" : executable;
        this.autoInstall = autoInstall;
    }

    public synchronized boolean isRunning() {
        return process != null && process.isAlive();
    }

    public synchronized String status() {
        if (isRunning()) {
            return "native playitd process running (check /playit tunnels for tunnel assignments)";
        }
        if (installing) {
            return "installing/starting verified Playit daemon (see server logs)";
        }
        if (suspended) {
            return "stopped by administrator";
        }
        return lastFailure != null ? "offline: " + lastFailure : "native playitd process offline";
    }

    /**
     * The plugin owns the daemon process in this mode. Do not run a second
     * playit daemon with the same agent secret simultaneously.
     */
    /**
     * May download a pinned official binary. Call only from an asynchronous
     * Bukkit task, never on the server's main tick thread.
     */
    public boolean start(String secret) {
        synchronized (this) {
            if (suspended) return false;
            if (isRunning()) return true;
            if (installing) return false;
            installing = true;
            lastLaunchAttempt = System.currentTimeMillis();
        }

        try {
            if (secret == null || secret.length() < 32
                    || secret.length() % 2 != 0 || !secret.matches("[0-9a-fA-F]+")) {
                lastFailure = "agent-secret missing or invalid";
                log.warning("[playit.gg] Native backend needs a valid agent-secret. "
                        + "Claim an agent using the legacy backend first, then switch to native.");
                return false;
            }

            Path executablePath;
            if (autoInstall && ("auto".equalsIgnoreCase(executable)
                    || "playitd".equalsIgnoreCase(executable))) {
                executablePath = PlayitNativeInstaller.ensure(dataDir, log);
            } else if ("auto".equalsIgnoreCase(executable)) {
                lastFailure = "native-auto-install disabled; specify native-binary";
                log.warning("[playit.gg] " + lastFailure);
                return false;
            } else {
                executablePath = Path.of(executable);
            }

            synchronized (this) {
                // Shutdown may have occurred during the download.
                if (suspended) return false;
                if (isRunning()) return true;

                Files.createDirectories(dataDir);
                Path secretFile = dataDir.resolve("native-agent-secret.txt");
                Path temporary = Files.createTempFile(dataDir, "native-key-", ".tmp");
                try {
                    restrictToOwner(temporary);
                    Files.writeString(temporary, secret + System.lineSeparator(), StandardCharsets.UTF_8);
                    try {
                        Files.move(temporary, secretFile, StandardCopyOption.REPLACE_EXISTING,
                                StandardCopyOption.ATOMIC_MOVE);
                    } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                        Files.move(temporary, secretFile, StandardCopyOption.REPLACE_EXISTING);
                    }
                    restrictToOwner(secretFile);
                } finally {
                    Files.deleteIfExists(temporary);
                }

                ProcessBuilder builder = new ProcessBuilder(
                        executablePath.toString(), "--secret-path", secretFile.toString(),
                        "--socket-path", dataDir.resolve("playitd.sock").toString());
                builder.directory(dataDir.toFile());
                Path nativeLog = dataDir.resolve("native-agent.log");
                if (Files.exists(nativeLog) && Files.size(nativeLog) > 2L * 1024 * 1024) {
                    Files.move(nativeLog, dataDir.resolve("native-agent.previous.log"),
                            StandardCopyOption.REPLACE_EXISTING);
                }
                if (Files.notExists(nativeLog)) {
                    Files.createFile(nativeLog);
                }
                restrictToOwner(nativeLog);
                builder.redirectErrorStream(true);
                builder.redirectOutput(ProcessBuilder.Redirect.appendTo(nativeLog.toFile()));
                process = builder.start();
                lastFailure = null;
                log.info("[playit.gg] Started native playitd (PID " + process.pid()
                        + ", executable: " + executablePath + "). "
                        + "Java TCP, Bedrock UDP and voice UDP will use the same Playit agent.");
                return true;
            }
        } catch (IOException | SecurityException error) {
            lastFailure = error.getMessage();
            log.warning("[playit.gg] Native agent startup failed: " + error.getMessage()
                    + ". The Minecraft server will remain online. See plugins/playit-gg/native-agent.log.");
            return false;
        } finally {
            installing = false;
        }
    }

    public boolean keepAlive(String secret) {
        synchronized (this) {
            if (suspended || installing || isRunning()) return isRunning();
            if (System.currentTimeMillis() - lastLaunchAttempt < 30_000L) return false;
        }
        log.warning("[playit.gg] Native agent offline; attempting to restart it.");
        return start(secret);
    }

    public boolean restart(String secret) {
        synchronized (this) {
            stopProcess();
            suspended = false;
        }
        return start(secret);
    }

    public synchronized void stop() {
        suspended = true;
        stopProcess();
    }

    /** Ensure agent reset does not leave the old key in a sidecar file. */
    public synchronized void reset() throws IOException {
        stop();
        Files.deleteIfExists(dataDir.resolve("native-agent-secret.txt"));
    }

    private void stopProcess() {
        Process current = process;
        process = null;
        if (current == null || !current.isAlive()) {
            return;
        }
        current.destroy();
        try {
            if (!current.waitFor(2, TimeUnit.SECONDS)) {
                current.destroyForcibly();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            current.destroyForcibly();
        }
    }

    private static void restrictToOwner(Path path) throws IOException {
        try {
            Set<PosixFilePermission> ownerOnly = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(path, ownerOnly);
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystem (e.g. Windows). The OS owns file ACLs.
        }
    }
}
