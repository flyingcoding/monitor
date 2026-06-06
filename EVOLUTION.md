# 项目演进文档

更新时间：2026-06-06

本文档是当前路线图事实源。历史实施细节保留在 `.trellis/tasks/archive/`、`docs/` 和 Git 历史中；这里不再重复旧版“缺口”描述，避免把已交付能力重新列为待规划事项。

## 当前定位

本项目定位为“中文友好的自托管轻量监控 + Web 终端平台”。它面向个人、小团队和小规模运维场景，不与 Datadog、Netdata Cloud、Coroot 等云原生全栈可观测平台正面竞争。

当前差异化能力：

- 浏览器内 SSH 多 Tab 与 SFTP MVP。
- 管理员/子账户权限、OIDC/SSO、API Token。
- 告警规则、告警历史和多通知通道。
- 公开状态页，默认关闭且 default-deny。
- Java 21 主机 Agent，保留轻量 HTTP+JSON 上报，同时开放 OTLP HTTP 接收入口。
- InfluxDB 默认时序后端，VictoriaMetrics 可选切换。

明确不追求：

- eBPF 内核级采集。
- 大型 SaaS 多租户平台。
- AIOps/LLM 自动诊断。
- K8s 原生监控作为主赛道。
- 用 gRPC 或完整 OpenTelemetry SDK 替换现有轻量客户端协议。

## 当前技术栈

| 层级 | 当前实现 |
| --- | --- |
| Server | Spring Boot 3.5.10, Java 21, Spring Security, MyBatis-Plus 3.5.16, Flyway, MapStruct 1.6.3, Resilience4j, sshj, Knife4j |
| Client | Java 21, OSHI, fastjson2, SLF4J/Logback, maven-shade 单 jar |
| Web | Vue 3.5, Vite 6, Element Plus, Pinia, ECharts, xterm.js, Vitest, Playwright |
| Storage | MySQL 8, Redis 7, RabbitMQ, InfluxDB 2.7, optional VictoriaMetrics |

## 已交付里程碑

| 阶段 | 状态 | 交付内容 |
| --- | --- | --- |
| v1.0 | 完成 | 基础主机注册、指标采集、InfluxDB 历史、Web Dashboard、SSH 终端、子账户权限、Docker Compose。 |
| v1.1 | 完成 | 告警规则、告警历史、通知通道、通知中心。 |
| v1.2 | 完成 | OIDC/SSO、API Token、公开状态页、安全加固、密码策略、请求日志脱敏。 |
| v1.3 | 完成 | 服务探测、进程、GPU、SMART、systemd、客户端能力上报、服务端/客户端单测覆盖启动。 |
| v2.0-alpha | 完成 | OTLP HTTP 接收端点、`TimeSeriesAdapter`、InfluxDB provider 收编。 |
| v2.0-beta | 完成 | VictoriaMetrics provider、vmctl 迁移、共享 TSDB JSONL 缓冲。 |
| v2.0-frontend-ux | 完成 | Dashboard 时间范围、CSV 导出、浏览器通知设置。 |
| v2.0-tests | 完成 | Surefire/Failsafe/Testcontainers/Playwright 三层测试。 |
| v2.0-terminal-tabs | 完成 | Web SSH 多会话 Tab。 |
| v2.0-sftp-mvp | 完成 | 独立 SFTP WebSocket、文件浏览、下载、上传、新建目录、删除空目录。 |
| v2.0-performance-frontend | 完成 | Manage 首屏拆包、RuntimeHistory Web Worker 下采样。 |
| v2.0-performance-tsdb | 完成 | `/monitor/runtime/batch`、TSDB 批量写入、JSONL 批量缓冲。 |

## 当前剩余计划

### P4 剩余：Redis 缓存与查询索引

这是当前 v2.0 仍未收口的性能档。拆成两个独立任务执行：

1. `client list/details cache`
   - 目标：为主机列表、主机详情和状态页候选数据建立 Redis 读模型缓存。
   - 边界：Redis 是跨进程缓存，Caffeine 继续保留为短 TTL 进程内缓存；两者必须定义清晰失效点。
   - 失效点：主机注册、删除、重命名、节点变更、`client_detail` 更新、状态页配置更新、子账户权限变更。
   - 不在同一任务里做 TSDB 历史缓存。

2. `query/index audit`
   - 目标：从真实列表/筛选/鉴权查询入口反推索引，而不是凭表名泛加索引。
   - 当前已补齐的索引见 Flyway `V5__v2-0-performance-indexes.sql`。
   - 后续新增列表型查询必须同步检查索引和分页策略。

### P5：部署体验

- 一键安装脚本。
- 更完整的 `.env` 生成和密钥轮换说明。
- 可选 Helm Chart，但不作为主线。
- 发布产物与版本文档。

### P6：长期能力

- 多租户隔离。
- 操作审计日志。
- 更细粒度 API Token scope。
- SFTP 大文件分片、断点续传、进度条和 E2E。

## 当前技术债务

| 项目 | 当前状态 | 下一步 |
| --- | --- | --- |
| Redis 缓存层 | Redis 已用于验证码、限流和 token 状态，尚未作为主机读模型缓存。 | 建立 `client list/details cache` 任务。 |
| 查询索引 | 基础索引已存在，仍需随真实查询入口持续审计。 | 已新增 v2.0 索引迁移，后续按查询变更补充。 |
| `ClientServiceImpl` 职责偏宽 | 仍集中管理注册 token、缓存、runtime、heartbeat、TSDB、SSE、告警、SSH、删除合同。 | 跟随后续任务渐进拆分 `ClientDeletionService`、`ClientReadModelService`、`ClientSshService`，不做一次性大重构。 |
| monitor-client 离线补报 | 进程内 `LinkedBlockingDeque`，最大 1000 条，重启丢失。 | 若需要可靠补报，单独引入 JSONL 磁盘队列；当前文档明确为短期补报。 |
| WebSocket 复用 | Terminal/SFTP 已抽出 URL、创建和关闭 helper，但重连策略仍主要在终端组件内。 | 后续扩展 WebSocket 时继续复用 `createManagedWebSocket`。 |

## 数据库事实源

业务 schema 只由 Flyway 管理：

- `V1__init.sql`：`account`、`client`、`client_detail`、`client_ssh`。
- `V2__alert.sql`：告警规则、告警历史、通知通道。
- `V3__v1-2-security.sql`：OIDC、API Token、公开状态页、账号启用、状态页展示别名。
- `V4__v1-3-monitoring.sql`：探测任务、探测历史、客户端能力 JSON。
- `V5__v2-0-performance-indexes.sql`：v2.0 查询/索引专项。

根目录 `database.sql` 只创建数据库，不再包含早期表结构或 `DROP TABLE`。

## 运行与测试口径

- 后端单测：`cd monitor-server && mvn test`
- 后端集成与覆盖率：`cd monitor-server && mvn verify`
- 客户端单测：`cd monitor-client && mvn test`
- 前端：`cd monitor-web && pnpm run lint && pnpm run test -- --run && pnpm run build`
- E2E：`cd monitor-web && pnpm run e2e`

报告验证结果时必须区分：

- 已运行并通过。
- 静态检查通过。
- 因 Docker/外部服务/网络限制未运行。

不得把静态验证描述为线上或全栈已验证。
