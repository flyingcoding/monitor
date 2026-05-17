-- v1.2 安全与差异化护城河：OIDC/SSO Provider、账号绑定、API Token、公开状态页配置
-- 子模块业务实现由后续 trellis-implement Agent A/B/C/D 完成，本迁移仅落地共享 schema。

CREATE TABLE IF NOT EXISTS `oidc_provider` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(64) NOT NULL,
  `display_name` VARCHAR(128) DEFAULT NULL,
  `icon_url` VARCHAR(255) DEFAULT NULL,
  `issuer_url` VARCHAR(255) NOT NULL,
  `client_id` VARCHAR(255) NOT NULL,
  `client_secret_enc` VARCHAR(512) NOT NULL,
  `scopes` VARCHAR(255) NOT NULL DEFAULT 'openid,profile,email',
  `enabled` TINYINT(1) NOT NULL DEFAULT 1,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `unique_provider_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `account_oidc_binding` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `account_id` INT NOT NULL,
  `provider_name` VARCHAR(64) NOT NULL,
  `subject` VARCHAR(255) NOT NULL,
  `email` VARCHAR(255) DEFAULT NULL,
  `bound_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_provider_subject` (`provider_name`, `subject`),
  KEY `idx_account` (`account_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `api_token` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `account_id` INT NOT NULL,
  `name` VARCHAR(128) NOT NULL,
  `token_hash` VARCHAR(128) NOT NULL,
  `prefix_tail` VARCHAR(32) NOT NULL,
  `scope` VARCHAR(32) NOT NULL DEFAULT 'readonly',
  `expires_at` DATETIME DEFAULT NULL,
  `last_used_at` DATETIME DEFAULT NULL,
  `last_used_ip` VARCHAR(64) DEFAULT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `unique_token_hash` (`token_hash`),
  KEY `idx_account` (`account_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `status_page_config` (
  `id` INT NOT NULL,
  `title` VARCHAR(128) NOT NULL DEFAULT '',
  `subtitle` VARCHAR(255) DEFAULT NULL,
  `brand_color` VARCHAR(32) DEFAULT NULL,
  `logo_url` VARCHAR(255) DEFAULT NULL,
  `client_ids` TEXT,
  `enabled` TINYINT(1) NOT NULL DEFAULT 1,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- P2-3 修复：种子行默认 enabled=0 且 client_ids='' (明确清空)。
--   research/status-page-design.md §default-deny 要求 admin 必须主动通过 /status/config 开启
--   并选择可见客户端，避免迁移后立即对未登录访客暴露全量客户端列表。
INSERT IGNORE INTO `status_page_config` (`id`, `title`, `enabled`, `client_ids`, `updated_at`)
VALUES (1, 'Service Status', 0, '', NOW());

-- 账号启用/禁用标志：API Token 校验、未来管理后台禁用账号都将依赖此字段。
ALTER TABLE `account` ADD COLUMN `enabled` TINYINT(1) NOT NULL DEFAULT 1;

-- 公开状态页展示用别名：与内部 `client.name` 解耦，避免泄露内部命名。
ALTER TABLE `client` ADD COLUMN `display_name` VARCHAR(255) DEFAULT NULL;
