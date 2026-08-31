package org.monitorclient;

import ch.qos.logback.classic.*;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import ch.qos.logback.core.status.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** Loads the shipped production configuration into an isolated logger context. */
class LogbackConfigurationTest {
    @TempDir Path directory;

    /** Validates that logging actually starts with a bounded, nonblocking queue and rolling policy. */
    @Test
    void shouldStartBoundedRollingFileLogging() throws Exception {
        LoggerContext context = new LoggerContext();
        context.putProperty("MONITOR_LOG_DIR", directory.toString());
        try {
            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);
            configurator.doConfigure(getClass().getClassLoader().getResource("logback.xml"));
            AsyncAppender async = (AsyncAppender) context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("ASYNC");
            assertNotNull(async);
            assertTrue(async.isStarted());
            assertEquals(256, async.getQueueSize());
            assertTrue(async.isNeverBlock());
            RollingFileAppender<?> file = (RollingFileAppender<?>) async.getAppender("FILE");
            assertTrue(file.isStarted());
            SizeAndTimeBasedRollingPolicy<?> policy = (SizeAndTimeBasedRollingPolicy<?>) file.getRollingPolicy();
            assertEquals(7, policy.getMaxHistory());
            assertTrue(policy.isCleanHistoryOnStart());
            assertFalse(context.getStatusManager().getCopyOfStatusList().stream().anyMatch(s -> s.getLevel() == Status.ERROR));
        } finally { context.stop(); }
    }
    /** Exercises an actual size-triggered archive with small test-only thresholds. */
    @Test
    void shouldCreateArchiveWhenActiveLogReachesSizeLimit() throws Exception {
        LoggerContext context = new LoggerContext();
        context.putProperty("MONITOR_LOG_DIR", directory.toString());
        try {
            String xml;
            try (java.io.InputStream input = getClass().getClassLoader().getResourceAsStream("logback.xml")) {
                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int size;
                while ((size = input.read(buffer)) != -1) output.write(buffer, 0, size);
                xml = new String(output.toByteArray(), java.nio.charset.StandardCharsets.UTF_8)
                        .replace("10MB", "4KB").replace("50MB", "16KB")
                        .replace(".log.gz", ".log");
            }
            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);
            configurator.doConfigure(new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            AsyncAppender async = (AsyncAppender) context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("ASYNC");
            @SuppressWarnings("unchecked")
            RollingFileAppender<ch.qos.logback.classic.spi.ILoggingEvent> file =
                    (RollingFileAppender<ch.qos.logback.classic.spi.ILoggingEvent>) async.getAppender("FILE");
            String message = String.join("", java.util.Collections.nCopies(2048, "x"));
            for (int i = 0; i < 80; i++) {
                ch.qos.logback.classic.spi.LoggingEvent event = new ch.qos.logback.classic.spi.LoggingEvent();
                event.setTimeStamp(System.currentTimeMillis());
                event.setLevel(Level.INFO);
                event.setLoggerName("rotation-test");
                event.setThreadName("test");
                event.setMessage(message);
                event.setMDCPropertyMap(java.util.Collections.emptyMap());
                file.doAppend(event);
                Thread.sleep(20);
            }
            assertFalse(context.getStatusManager().getCopyOfStatusList().stream().anyMatch(status -> status.getLevel() == Status.ERROR),
                    () -> context.getStatusManager().getCopyOfStatusList().toString());
            assertTrue(java.nio.file.Files.isDirectory(directory.resolve("archive")));
            try (java.util.stream.Stream<Path> archives = java.nio.file.Files.list(directory.resolve("archive"))) {
                assertTrue(archives.anyMatch(java.nio.file.Files::isRegularFile));
            }
        } finally { context.stop(); }
    }

}
