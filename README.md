# 运维监控系统

这是一个中文友好的自托管轻量监控平台，包含服务端、主机 Agent 和 Vue 管理端。当前实现已经进入 v2.0 阶段：支持主机指标采集、告警、通知通道、公开状态页、OIDC/API Token、OTLP 接收、InfluxDB/VictoriaMetrics 双时序后端、Web SSH 多 Tab、SFTP MVP、集成测试与 E2E 测试。

## 模块

| 模块 | 说明 |
| --- | --- |
| `monitor-server/` | Spring Boot 3.5.10 + Java 21 后端，提供 REST API、WebSocket/SSE、告警、OIDC、API Token、状态页、探测任务、Flyway 迁移和 TSDB 适配层。 |
| `monitor-client/` | Java 21 主机 Agent，使用 OSHI 采集指标，maven-shade 打包为单 jar。短期断网补报使用进程内队列，重启后不会保留。 |
| `monitor-web/` | Vue 3 + Vite + Element Plus 前端，包含 Dashboard、主机详情、告警、状态页配置、安全设置、Web SSH 多 Tab 和 SFTP 面板。 |

## 当前能力

- 主机指标：CPU、内存、磁盘、网络、磁盘 IO，支持批量上报和 TSDB 历史查询。
- 可选采集：进程、NVIDIA GPU、SMART、systemd 服务，能力通过 `client_detail.capabilities_json` 上报。
- 实时通道：SSE 推送主机列表、runtime、告警和可选采集快照；WebSocket 提供 SSH shell 与 SFTP。
- 告警体系：阈值规则、告警历史、确认/恢复、邮件/Webhook/钉钉/飞书通知。
- 安全：JWT、管理员/子账户权限、API Token、OIDC/SSO、密码策略、请求日志脱敏、SSH/OIDC/探测敏感字段加密；SSH 设置查询不回传密码。
- 状态页：公开 `/status` 页面，默认关闭，管理员必须显式选择公开的客户端。
- 时序后端：默认 InfluxDB 2.7，可切换 VictoriaMetrics，支持 JSONL 缓冲和重放。
- 测试：后端 Surefire 单测、Failsafe + Testcontainers 集成测试、前端 Vitest、Playwright 三浏览器 E2E。

## 快速启动

初始化环境变量：

```bash
cp .env.example .env
bash scripts/generate-env.sh
```

启动完整本地依赖和应用容器：

```bash
docker compose up -d --build
```

开发模式常用命令：

```bash
make dev-server
cd monitor-client && mvn spring-boot:run
cd monitor-web && pnpm install && pnpm run dev
```

直接运行 `monitor-server` 前需先在仓库根目录加载 `.env`，例如 `set -a; source .env; set +a`。dev profile 的 MySQL、Redis、RabbitMQ 和 InfluxDB 密码默认值为空，本地 Docker 依赖使用 `scripts/generate-env.sh` 生成的 `.env` 密码。

Makefile 快捷命令：

```bash
make init
make up
make dev-server
make up-client
make up-vm
make migrate-influx-to-vm
make clean
```

`make dev-server` 会加载根目录 `.env` 后启动后端；`make up-client` 需要 `.env` 中配置 `MONITOR_TOKEN`，该值来自管理端的客户端注册令牌。

## 配置要点

`.env.example` 使用偏安全默认值：

- `CORS_ORIGIN=`：生产环境应填写精确 origin，例如 `https://monitor.example.com`。本地联调如需开放可显式设为 `*`。
- `PASSWORD_POLICY=basic`：生产默认要求至少 8 位且包含字母和数字。本地调试如需放宽可设为 `none`。
- `JWT_KEY`、`API_TOKEN_HMAC_KEY`、`SSH_ENCRYPT_KEY` 必须使用彼此不同的强随机密钥。生产启动会拒绝空值、模板值、弱 JWT 和非 32 字节的 API/SSH 密钥。
- `MAIL_USERNAME` / `MAIL_PASSWORD` 是通知与验证码邮件凭证，不应提交真实值。
- `MONITOR_TSDB_PROVIDER=influxdb|victoria-metrics` 控制时序后端。

`monitor-server/src/main/resources/application-dev.yml` 只保留本地 Docker 默认或空占位；真实生产配置应通过环境变量注入，生产 profile 不依赖硬编码凭证。

## 数据库

应用 schema 的唯一事实源是 Flyway：

```text
monitor-server/src/main/resources/db/migration/
├── V1__init.sql
├── V2__alert.sql
├── V3__v1-2-security.sql
├── V4__v1-3-monitoring.sql
└── V5__v2-0-performance-indexes.sql
```

根目录 `database.sql` 只用于可选地创建 `monitor` 数据库，不包含业务表，也不包含 `DROP TABLE`。不要把它当成应用 schema 初始化脚本；服务启动时由 Flyway 自动建表/迁移。

## 删除主机合同

管理端删除主机是 MySQL 侧硬删除：

- 删除 `client`、`client_detail`、`client_ssh`。
- 删除绑定该主机的 `alert_rule` 和 `alert_history`。
- 从 `status_page_config.client_ids` 和子账户 `account.clients` 中移除该主机 ID。
- 清理服务端本地 runtime/heartbeat/client token 缓存并推送主机列表刷新。
- 保留 InfluxDB/VictoriaMetrics 中的 TSDB 历史数据；如需物理清理时序历史，应另开带保留策略和回滚方案的任务。

## 测试与验证

后端：

```bash
cd monitor-server && mvn test
cd monitor-server && mvn verify
```

`mvn verify` 会运行 Failsafe 集成测试和 JaCoCo 检查。无 Docker 时 Testcontainers 集成测试会按配置跳过或失败，具体看测试类标注。

客户端：

```bash
cd monitor-client && mvn test
```

前端：

```bash
cd monitor-web && pnpm run lint
cd monitor-web && pnpm run test -- --run
cd monitor-web && pnpm run build
cd monitor-web && pnpm run e2e
```

## 文档索引

- `EVOLUTION.md`：当前路线图、已交付能力和剩余任务。
- `docs/engineering.md`：版本化工程规范和跨模块合同。
- `docs/v2.0-alpha-otlp.md`：OTLP 接收端点与 TSDB 适配层。
- `docs/v2.0-beta-vm.md`：VictoriaMetrics 切换与 vmctl 迁移。
- `docs/v2.0-tests.md`：集成测试与 E2E 测试体系。
- `EVOLUTION.md` 的审查与演进章节：已处理问题和拆分后的后续任务。
