-- v1.1 告警体系：阈值规则、告警历史、通知通道
-- 通道与规则使用 alert_rule.channel_ids JSON 数组关联（不引入独立关联表）

CREATE TABLE IF NOT EXISTS `alert_rule` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(64) NOT NULL,
  `client_id` INT NULL,
  `metric` VARCHAR(32) NOT NULL,
  `operator` VARCHAR(8) NOT NULL,
  `threshold` DOUBLE NOT NULL,
  `duration_sec` INT NOT NULL DEFAULT 60,
  `level` VARCHAR(16) NOT NULL DEFAULT 'warning',
  `enabled` TINYINT(1) NOT NULL DEFAULT 1,
  `channel_ids` JSON NULL,
  `silence_until` DATETIME NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_client_enabled` (`client_id`, `enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `alert_history` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `rule_id` BIGINT NOT NULL,
  `client_id` INT NOT NULL,
  `fired_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `resolved_at` DATETIME NULL,
  `status` VARCHAR(16) NOT NULL DEFAULT 'firing',
  `level` VARCHAR(16) NOT NULL,
  `current_value` DOUBLE NULL,
  `message` VARCHAR(512) NULL,
  `acked_by` INT NULL,
  `acked_at` DATETIME NULL,
  PRIMARY KEY (`id`),
  KEY `idx_rule_fired` (`rule_id`, `fired_at` DESC),
  KEY `idx_client_status` (`client_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `notification_channel` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(64) NOT NULL,
  `type` VARCHAR(16) NOT NULL,
  `config` JSON NOT NULL,
  `enabled` TINYINT(1) NOT NULL DEFAULT 1,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
