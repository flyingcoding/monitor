package com.example.integration.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 数据库清理器：除了 IntegrationTestBase 上挂的 @Sql AFTER_TEST_METHOD（声明式）以外，
 * 提供一个 imperative 入口给少数测试方法在中途 reset 状态（如循环执行场景）。
 *
 * <p>多数情况下不需要直接用本类，IntegrationTestBase 的 @Sql 已经接管 cleanup；
 * 仅在测试方法内部需要中途清表时（如压测多轮注册同一客户端 ID），通过 @Autowired 注入。
 */
@Component
public class DatabaseCleaner {

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public DatabaseCleaner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Truncate 全部业务表并复刻 Flyway V1 预置的 admin 行。等价 cleanup-after-test.sql 的语义，
     * 但用 Java imperative 接口便于在测试方法中途调用。
     */
    public void resetAll() {
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");
        for (String table : new String[]{
                "account_oidc_binding", "oidc_provider", "api_token", "status_page_config",
                "alert_history", "alert_rule", "notification_channel",
                "probe_history", "probe_task",
                "client_ssh", "client_detail", "client", "account"
        }) {
            jdbcTemplate.execute("TRUNCATE TABLE `" + table + "`");
        }
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");
        jdbcTemplate.update(
                "INSERT INTO `account` (`id`, `username`, `email`, `password`, `role`, `register_time`) "
                        + "VALUES (1, 'admin', 'admin@monitor.local', "
                        + "'$2a$10$WMFjOMHaHqIVJCzJ16xOH.HByBDlCLz2LlNHxYHlP83FcIKfWsyDW', 'admin', NOW())"
        );
    }
}
