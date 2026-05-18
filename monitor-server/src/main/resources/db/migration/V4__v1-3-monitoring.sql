-- v1.3 监控增强：服务可用性探测 + 客户端能力上报
-- 新增 4 类聚合指标走 AlertMetric 枚举扩展（无需新表），具体详情走 Caffeine 缓存（无需新表）。
-- 仅服务探测与客户端能力上报需要持久化层支持。

-- 服务探测任务定义。
-- HTTP/TCP/ICMP 三类探测，HTTP 支持 Custom Headers（headers_enc）与 Basic Auth（basic_auth_password_enc）。
-- channel_ids 与 alert_rule.channel_ids 同款 JSON 数组语义，告警时由 NotificationQueueListener 路由。
-- 探测告警独立于 alert_rule：连续 N 次失败 / SSL 即将过期由 ProbeScheduler 直接投递 notification 队列。
CREATE TABLE IF NOT EXISTS `probe_task` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(128) NOT NULL,
  `type` VARCHAR(16) NOT NULL,
  `target` VARCHAR(512) NOT NULL,
  `interval_sec` INT NOT NULL DEFAULT 60,
  `timeout_sec` INT NOT NULL DEFAULT 10,
  `expected_status_code` INT DEFAULT NULL,
  `expected_body_pattern` VARCHAR(512) DEFAULT NULL,
  `headers_enc` TEXT DEFAULT NULL,
  `basic_auth_username` VARCHAR(128) DEFAULT NULL,
  `basic_auth_password_enc` VARCHAR(512) DEFAULT NULL,
  `ssl_warn_days` INT DEFAULT 30,
  `consecutive_failures_threshold` INT NOT NULL DEFAULT 2,
  `channel_ids` VARCHAR(255) DEFAULT NULL,
  `enabled` TINYINT(1) NOT NULL DEFAULT 1,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_probe_task_name` (`name`),
  KEY `idx_enabled_type` (`enabled`, `type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 服务探测历史。
-- 单事件行存：success 0/1、延迟 ms、HTTP 状态码、SSL 剩余天、失败原因。
-- 30 天滚动清理由 ProbeHistoryCleanupJob 完成（@Scheduled 每日凌晨执行）。
CREATE TABLE IF NOT EXISTS `probe_history` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `task_id` BIGINT NOT NULL,
  `executed_at` DATETIME NOT NULL,
  `success` TINYINT(1) NOT NULL,
  `latency_ms` INT DEFAULT NULL,
  `status_code` INT DEFAULT NULL,
  `ssl_days_remaining` INT DEFAULT NULL,
  `error_message` VARCHAR(1024) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_task_executed` (`task_id`, `executed_at` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 客户端能力上报（D7 决策）。
-- JSON 内容：{ gpu: {enabled, available, deviceCount}, smart: {...}, systemd: {...}, process: {...} }
-- 与硬件静态信息属于同一更新流程（updateClientDetail），故落在 client_detail 表。
-- admin 在 Manage 页面通过 capabilities_json 显示能力徽章（available / enabled）。
ALTER TABLE `client_detail` ADD COLUMN `capabilities_json` TEXT DEFAULT NULL;
