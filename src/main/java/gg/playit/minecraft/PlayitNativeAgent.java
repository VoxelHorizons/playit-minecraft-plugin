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
    private Process process;
    private boolean suspended;
    private long lastLaunchAttempt;

    public PlayitNativeAgent(Logger log, Path dataDir, String executable) {
        this.log = log;
        this.dataDir = dataDir.toAbsolutePath().normalize();
        this.executable = executable == null || executable.isBlank() ? "playitd" : executable;
    }

    public synchronized boolean isRunning() {
        return process != null && process.isAlive();
    }

    public synchronized String status() {
        if (isRunning()) {
            return "native playitd process running (check /playit tunnels for tunnel assignments)";
        }
        return suspended ? "stopped by administrator" : "native playitd process offline";
    }

    /**
     * The plugin owns the daemon process in this mode. Do not run a second
     * playit daemon with the same agent secret simultaneously.
     */
    public synchronized boolean start(String secret) {
        if (isRunning()) {
            return true;
        }
        if (secret == null || secret.length() < 32
                || secret.length() % 2 != 0 || !secret.matches("[0-9a-fA-F]+")) {
            log.warning("[playit.gg] Native backend needs a valid agent-secret. "
                    + "Claim an agent using the legacy backend first, then switch to native.");
            return false;
        }
        lastLaunchAttempt = System.currentTimeMillis();
        try {
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
                    executable, "--secret-path", secretFile.toString(),
                    "--socket-path", dataDir.resolve("playitd.sock").toString());
            builder.directory(dataDir.toFile());
            // Never pipe (or print) the agent's secret or unbounded output.
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            process = builder.start();
            suspended = false;
            log.info("[playit.gg] Started native playitd (PID " + process.pid() + "). "
                    + "It forwards Java TCP, Bedrock UDP, and voice UDP tunnels configured for this agent.");
            return true;
        } catch (IOException error) {
            log.warning("[playit.gg] Unable to launch native playitd executable '"
                    + executable + "': " + error.getMessage());
            return false;
        }
    }

    public synchronized boolean keepAlive(String secret) {
        if (suspended || isRunning()) {
            return isRunning();
        }
        if (System.currentTimeMillis() - lastLaunchAttempt < 30_000L) {
            return false;
        }
        log.warning("[playit.gg] Native agent exited. Attempting to restart it.");
        return start(secret);
    }

    public synchronized boolean restart(String secret) {
        stopProcess();
        suspended = false;
        return start(secret);
    }

    public synchronized void stop() {
        suspended = true;
        stopProcess();
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
