package org.monitorclient.utils;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

@Slf4j
public class RetryUtils {

    private static final int MAX_RETRIES = 5;
    private static final long INITIAL_DELAY_MS = 1000;
    private static final long MAX_DELAY_MS = 8000;

    public static <T> T retryWithBackoff(Supplier<T> action, String actionName) {
        int attempt = 0;
        while (attempt < MAX_RETRIES) {
            try {
                return action.get();
            } catch (Exception e) {
                attempt++;
                if (attempt >= MAX_RETRIES) {
                    log.error("{}在{}次重试后仍然失败: {}", actionName, MAX_RETRIES, e.getMessage());
                    throw e;
                }
                long delay = Math.min(INITIAL_DELAY_MS * (1L << (attempt - 1)), MAX_DELAY_MS);
                long jitter = ThreadLocalRandom.current().nextLong(delay / 4);
                delay = delay + jitter;
                log.warn("{}失败，第{}次重试，等待{}ms: {}", actionName, attempt, delay, e.getMessage());
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(ie);
                }
            }
        }
        throw new RuntimeException(actionName + "重试次数已用尽");
    }
}
