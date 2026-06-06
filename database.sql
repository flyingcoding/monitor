/*
 运维监控系统 - 数据库 bootstrap 脚本

 用途：
 1. 可选地创建 monitor 数据库。
 2. 给手工 MySQL 初始化留一个非破坏性入口。

 注意：
 - 这不是应用 schema。
 - 不要用它初始化或重置业务表。
 - 真实 schema 由 monitor-server/src/main/resources/db/migration/ 下的 Flyway 迁移管理。
 - 本文件不包含 DROP TABLE，避免误运行破坏已有数据。
*/

SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS `monitor`
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
