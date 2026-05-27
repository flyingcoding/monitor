package com.example.container;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class DatabaseContainerIT {

    @Container
    private static final MySQLContainer<?> MYSQL_CONTAINER = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("monitor")
            .withUsername("test")
            .withPassword("test");

    /**
     * 验证MySQL测试容器可以正常启动。
     */
    @Test
    void mysqlContainerShouldStart() {
        Assertions.assertTrue(MYSQL_CONTAINER.isRunning());
    }
}
