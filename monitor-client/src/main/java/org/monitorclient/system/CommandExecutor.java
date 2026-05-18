package org.monitorclient.system;

import java.time.Duration;
import java.util.List;

/**
 * 命令执行抽象层。
 * <p>
 * v1.3 GpuCollector / SmartCollector / SystemdCollector 通过本接口执行 {@code nvidia-smi} /
 * {@code smartctl} / {@code systemctl}，便于单测注入命令输出 fixture。
 */
public interface CommandExecutor {

    /**
     * 执行命令并返回结果（stdout + stderr + exit code）。
     *
     * @param command 命令片段列表（如 {@code ["nvidia-smi","--query-gpu=index","--format=csv,noheader"]}）
     * @param timeout 超时；超时强制 destroy
     * @return 命令执行结果；执行失败（命令不存在 / 超时 / 异常）也返回结果，由调用方根据 exit code 判断
     */
    CommandResult execute(List<String> command, Duration timeout);

    /**
     * 探测命令是否可用（等价于执行 {@code <cmd> --version} 并判断 exit code）。
     *
     * @param command 探测命令（如 {@code "nvidia-smi"}）
     * @return 可用返回 true
     */
    boolean isAvailable(String command);

    /**
     * 命令执行结果。
     *
     * @param exitCode 退出码；-1 表示未启动 / 异常
     * @param stdout 标准输出
     * @param stderr 标准错误输出
     * @param timedOut 是否超时强制终止
     */
    record CommandResult(int exitCode, String stdout, String stderr, boolean timedOut) {
        /**
         * 判断命令是否成功（exit code 为 0）。
         *
         * @return 成功返回 true
         */
        public boolean success() {
            return exitCode == 0;
        }
    }
}
