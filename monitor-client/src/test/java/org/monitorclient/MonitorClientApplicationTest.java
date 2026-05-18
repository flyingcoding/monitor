package org.monitorclient;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link MonitorClientApplication} 配置加载测试。
 */
class MonitorClientApplicationTest {

    @TempDir
    Path tempDir;

    /**
     * 外部 application.properties 应优先于 classpath，便于 java -jar 部署后调整 collector 配置。
     */
    @Test
    void loadCollectorPropertiesShouldReadExternalFileFirst() throws IOException {
        Path external = tempDir.resolve("application.properties");
        Files.writeString(external, "monitor.collect.gpu.enabled=true\n");
        ClassLoader emptyClassLoader = new ClassLoader(null) {
            @Override
            public java.io.InputStream getResourceAsStream(String name) {
                return null;
            }
        };

        Properties props = MonitorClientApplication.loadCollectorProperties(List.of(external), emptyClassLoader);

        assertEquals("true", props.getProperty("monitor.collect.gpu.enabled"));
    }

    /**
     * 外部配置缺失时应回退到 classpath application.properties。
     */
    @Test
    void loadCollectorPropertiesShouldFallbackToClasspath() throws IOException {
        Path classesDir = tempDir.resolve("classes");
        Files.createDirectories(classesDir);
        Files.writeString(classesDir.resolve("application.properties"),
                "monitor.collect.smart.devices=/dev/nvme0n1\n");
        try (URLClassLoader loader = new URLClassLoader(new URL[]{classesDir.toUri().toURL()}, null)) {
            Properties props = MonitorClientApplication.loadCollectorProperties(
                    List.of(tempDir.resolve("missing.properties")), loader);

            assertEquals("/dev/nvme0n1", props.getProperty("monitor.collect.smart.devices"));
        }
    }
}
