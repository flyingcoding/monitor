package org.monitorclient.system;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Polls both process pipes with bounded buffers; never allocates per-command reader threads. */
public class ProcessCommandExecutor implements CommandExecutor {
    private Process unfinished;
    private static final int MAX_OUTPUT_BYTES = 262144;
    private static final int DRAIN_BUDGET_BYTES = 16384;

    /** Executes a local command with a monotonic deadline and independently capped output streams. */
    @Override
    public synchronized CommandResult execute(List<String> command, Duration timeout) {
        if (unfinished != null && unfinished.isAlive()) return failure("Previous command has not terminated", false);
        unfinished = null;
        if (Thread.currentThread().isInterrupted()) return failure("Command interrupted", false);
        if (command == null || command.isEmpty() || timeout == null || timeout.isNegative() || timeout.isZero()) {
            return failure("Invalid command or timeout", false);
        }
        Process process = null;
        long deadline = System.nanoTime() + Math.min(timeout.toNanos(), TimeUnit.SECONDS.toNanos(30));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        try {
            process = new ProcessBuilder(command).redirectErrorStream(false).start();
            process.getOutputStream().close();
            while (true) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                if (System.nanoTime() - deadline >= 0) return failure("Command deadline exceeded", true);
                drainAvailable(process.getInputStream(), stdout, buffer);
                drainAvailable(process.getErrorStream(), stderr, buffer);
                if (!process.isAlive()) {
                    // Finite draining also handles descendants that inherited but did not close the pipes.
                    for (int i = 0; i < 32; i++) {
                        int drained = drainAvailable(process.getInputStream(), stdout, buffer)
                                + drainAvailable(process.getErrorStream(), stderr, buffer);
                        if (drained == 0) break;
                    }
                    return new CommandResult(process.exitValue(), text(stdout), text(stderr), false);
                }
                process.waitFor(10, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return failure("Command interrupted", false);
        } catch (IOException | RuntimeException error) {
            return failure("Command failed: " + error.getClass().getSimpleName(), false);
        } finally {
            if (process != null) {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    unfinished = process;
                }
                close(process.getInputStream());
                close(process.getErrorStream());
                try { process.getOutputStream().close(); } catch (IOException ignored) { }
            }
        }
    }

    /** Checks tool availability using the same bounded command path. */
    @Override
    public boolean isAvailable(String command) {
        CommandResult result = execute(Arrays.asList(command, "--version"), Duration.ofSeconds(3));
        return result.success() || (result.exitCode() >= 0 && !result.stdout().trim().isEmpty());
    }

    /** Reads only immediately available bytes, bounding both per-poll work and retained output. */
    private int drainAvailable(InputStream input, ByteArrayOutputStream output, byte[] buffer) throws IOException {
        int drained = 0;
        while (drained < DRAIN_BUDGET_BYTES) {
            int available = input.available();
            if (available <= 0) break;
            int size = input.read(buffer, 0, Math.min(buffer.length, Math.min(available, DRAIN_BUDGET_BYTES - drained)));
            if (size <= 0) break;
            if (output.size() + size > MAX_OUTPUT_BYTES) throw new IOException("Command output exceeds 256 KiB");
            output.write(buffer, 0, size);
            drained += size;
        }
        return drained;
    }

    /** Converts one capped output buffer without retaining the underlying process. */
    private String text(ByteArrayOutputStream output) { return new String(output.toByteArray(), StandardCharsets.UTF_8); }
    /** Produces an empty-output failure so callers cannot mistake truncation for a valid snapshot. */
    private CommandResult failure(String message, boolean timedOut) { return new CommandResult(-1, "", message, timedOut); }
    /** Closes a process pipe after its sole polling owner is finished. */
    private void close(InputStream input) { try { input.close(); } catch (IOException ignored) { } }
}
