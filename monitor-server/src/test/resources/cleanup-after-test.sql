-- v2.0-tests 集成测试 @AfterEach cleanup 脚本。
-- 设计目标：跨测试方法重置数据库到「仅 Flyway V1 预置 admin」状态，保证测试间状态隔离。
-- 与 D4 决策对齐：运行时建+销，不在 Flyway migration 中预置除 admin 之外的种子数据。
SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE `account_oidc_binding`;
TRUNCATE TABLE `oidc_provider`;
TRUNCATE TABLE `api_token`;
TRUNCATE TABLE `status_page_config`;
TRUNCATE TABLE `alert_history`;
TRUNCATE TABLE `alert_rule`;
TRUNCATE TABLE `notification_channel`;
TRUNCATE TABLE `probe_history`;
TRUNCATE TABLE `probe_task`;
TRUNCATE TABLE `client_ssh`;
TRUNCATE TABLE `client_detail`;
TRUNCATE TABLE `client`;
TRUNCATE TABLE `account`;
SET FOREIGN_KEY_CHECKS = 1;
-- 复刻 Flyway V1__init.sql 第 14-15 行：BCrypt 哈希对应明文 `admin123`
INSERT INTO `account` (`id`, `username`, `email`, `password`, `role`, `register_time`)
VALUES (1, 'admin', 'admin@monitor.local',
        '$2a$10$WMFjOMHaHqIVJCzJ16xOH.HByBDlCLz2LlNHxYHlP83FcIKfWsyDW',
        'admin', NOW());
