package org.monitorclient.system;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 默认 {@link CommandExecutor}：用 {@link ProcessBuilder} 启动子进程。
 * <p>
 * 使用 Virtual Threads 并行读取 stdout / stderr 避免缓冲区满导致子进程阻塞。
 * 超时未结束时强制 {@code destroyForcibly}。
 */
@Slf4j
public class ProcessCommandExecutor implements CommandExecutor {

    @Override
    public CommandResult execute(List<String> command, Duration timeout) {
        Process process = null;
        AtomicReference<String> stdoutRef = new AtomicReference<>("");
        AtomicReference<String> stderrRef = new AtomicReference<>("");
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            process = pb.start();

            Process current = process;
            var stdoutThread = Thread.ofVirtual().start(
                    () -> stdoutRef.set(drainStream(current.getInputStream(), "stdout")));
            var stderrThread = Thread.ofVirtual().start(
                    () -> stderrRef.set(drainStream(current.getErrorStream(), "stderr")));

            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                stdoutThread.join();
                stderrThread.join();
                return new CommandResult(-1, stdoutRef.get(),
                        "command timeout: " + String.join(" ", command), true);
            }
            stdoutThread.join();
            stderrThread.join();
            return new CommandResult(process.exitValue(), stdoutRef.get(), stderrRef.get(), false);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            return new CommandResult(-1, stdoutRef.get(),
                    "command exec error: " + e.getMessage(), false);
        }
    }

    @Override
    public boolean isAvailable(String command) {
        try {
            CommandResult result = execute(List.of(command, "--version"), Duration.ofSeconds(3));
            // 部分工具（如 smartctl）以 exit code != 0 但 stdout 非空的方式输出版本号
            return result.success() || (result.exitCode() >= 0 && !result.stdout().isBlank());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 同步读取流到 String，避免子进程因缓冲区满而阻塞。
     *
     * @param is 输入流
     * @param label 标签（仅用于日志）
     * @return 累积的文本
     */
    private String drainStream(java.io.InputStream is, String label) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (IOException e) {
            log.debug("子进程 {} 读取异常：{}", label, e.getMessage());
        }
        return sb.toString();
    }
}
