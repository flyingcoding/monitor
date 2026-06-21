# 运维监控系统当前仓库审查与后续演进方案

> 生成日期：2026-06-21  
> 仓库：`flyingcoding/monitor`  
> 分支/版本依据：当前 `main-v2` 仓库内容、项目文档、配置文件、核心代码与 CI/部署文件。  
> 审查口径：基于静态代码、配置、文档与 CI/部署文件审查；未实际执行 `mvn verify`、`pnpm run build`、`docker compose up` 或压测，因此本文不把结论表述为运行验证结果。

---

## 1. 一页结论

当前项目已经不是普通 demo，而是接近 v2.0 交付形态的“轻量自托管监控 + Web 终端平台”。

仓库自身定位比较清晰：面向个人、小团队、小规模运维场景，不正面竞争 Datadog、Netdata Cloud、Coroot 等全栈云原生平台。当前差异化能力集中在：

- 中文友好的自托管体验。
- Web SSH 多 Tab 与 SFTP MVP。
- 管理员/子账户权限、OIDC/SSO、API Token。
- 阈值告警、告警历史、多通知通道。
- 公开状态页，默认关闭、default-deny。
- Java 21 主机 Agent，保留轻量 HTTP + JSON 上报，同时开放 OTLP HTTP 接收入口。
- InfluxDB 默认时序后端，VictoriaMetrics 可选切换。

核心建议：

**不要把项目重构成“大而全云原生可观测平台”。** 当前项目不应把 eBPF、K8s 原生监控、大型 SaaS 多租户、AIOps/LLM 自动诊断、完整 OpenTelemetry SDK/gRPC 替换轻量客户端协议作为主线。这个边界是合理的。

**后续主线应是：先把 v2.0 做稳，再通过标准协议接入主流生态。**  
也就是保留现有轻量 Agent Push 模式与 Web 运维体验，同时增强 OTLP、Prometheus remote_write、VictoriaMetrics、Collector/Alloy 配置示例、部署安全和 HA-lite 能力。

当前最高优先级风险不是功能缺失，而是：

1. **单机状态过重**：心跳、当前运行时、注册 token、SSE 连接、告警窗口等大量状态在 JVM 内存中，限制水平扩展。
2. **读模型与列表查询性能**：`ClientServiceImpl.listClients()` 对每台主机查一次 `client_detail`，属于典型 N+1 查询。
3. **安全与 REST 语义细节**：`GET /api/monitor/delete` 是变更操作；SSE/WS token 放 query；SSH 密码可解密回显；prod 中 MyBatis stdout SQL 日志仍开启。
4. **部署生产化不足**：compose 默认暴露 MySQL/Redis/RabbitMQ/InfluxDB 等基础设施端口，更适合开发环境而非生产环境。
5. **数据模型后续扩展压力**：`account.clients`、`status_page_config.client_ids`、`alert_rule.channel_ids` 使用 JSON/text 字段，MVP 简单，但多租户、细粒度权限、索引审计会越来越困难。

---

## 2. 当前架构画像

| 层级 | 当前实现 | 评价 |
|---|---|---|
| Server | Spring Boot 3.5.10、Java 21、Spring Security、MyBatis-Plus、Flyway、MapStruct、Resilience4j、sshj、Knife4j | 技术栈现代，工程纪律较好。 |
| Agent | Java 21、OSHI、fastjson2、SLF4J/Logback、shade 单 jar | 适合轻量自托管场景，部署门槛低。 |
| Web | Vue 3.5、Vite 6、Element Plus、Pinia、ECharts、xterm.js、Vitest、Playwright | 前端工程化基础完整，已经有测试与 E2E。 |
| 存储 | MySQL 8、Redis 7、RabbitMQ、InfluxDB 2.7、可选 VictoriaMetrics | 架构边界清楚，但 Redis 还没有承担核心读模型/分布式状态。 |
| 测试 | Surefire/Failsafe/Testcontainers/Playwright 三层测试 | 明显强于同类个人项目。CI 中 server/web/e2e 分层清楚。 |
| 部署 | Docker Compose、可选 VictoriaMetrics、vmctl 迁移 profile | 开发/小规模部署可用，生产安全隔离还需拆分。 |

仓库已经建立了一些较好的工程契约：

- 业务 schema 只由 Flyway 管理。
- TSDB 写入必须经过 `TimeSeriesAdapter`。
- 敏感字段通过 `CryptoUtils` 加密。
- 前端 REST、SSE、WebSocket 有统一 helper。
- SSE/WS/worker/ECharts/xterm 实例需要在组件卸载时释放资源。
- 用户侧 UI copy 以中文为主。
- 生产代码不应保留 `console.log`。

---

## 3. 与主流技术路线的对照

| 主流路线 | 外部趋势 | 对本项目的建议 |
|---|---|---|
| OpenTelemetry Collector / Agent-Gateway | OTel Collector 常见部署模式包括 Agent 与 Gateway；Agent-to-Gateway 适合把单机采集与中心化处理分离。 | 不建议重写现有 Agent。建议提供可选 OTel Collector / Grafana Alloy 配置，把项目变成“轻量协议 + 标准协议桥接”。 |
| Grafana Alloy / 混合 Collector | Grafana Alloy 是 OpenTelemetry Collector 发行版，内置 Prometheus pipeline，支持 metrics、logs、traces、profiles 等多信号采集。 | 可以提供 `alloy.example.yaml`：采集 host metrics、接入 OTLP、转发到本系统或 VictoriaMetrics。不要自己实现完整日志/链路采集栈。 |
| Prometheus remote_write + 长期 TSDB | Prometheus 本地存储是单节点模型，remote storage/remote write 用于对接远端长期存储；remote write 1.0 已标准化。 | 当前 VictoriaMetrics provider 是正确方向。后续建议增加 Prometheus remote_write ingest/export，而不是只保留自定义 `/monitor/runtime`。 |
| VictoriaMetrics / vmagent | vmagent 可抓取 Prometheus-compatible targets，并通过 Prometheus remote_write 或 VM remote_write 写入远端存储。 | 新安装文档中可把 VictoriaMetrics 从“可选”提升为“推荐生产 TSDB 之一”，但保留 InfluxDB 兼容与迁移路径。 |
| Alertmanager 风格告警 | Alertmanager 的核心能力是去重、分组、路由、静默、抑制与 HA。 | 当前告警已能触发、确认、恢复、通知。下一阶段不必复制完整 Alertmanager，但应补“分组、抑制、通知降噪、重复通知策略”。 |
| 轻量自托管 + 运维入口 | 小团队场景更看重安装简单、中文 UI、SSH/SFTP、状态页和告警闭环。 | 这是本项目的产品护城河，应继续强化，而不是追逐 eBPF/K8s/AIOps。 |

外部参考：

- OpenTelemetry Collector Deployment: https://opentelemetry.io/docs/collector/deployment/
- OpenTelemetry Agent to Gateway: https://opentelemetry.io/docs/collector/deploy/other/agent-to-gateway/
- Grafana Alloy: https://grafana.com/oss/alloy-opentelemetry-collector/
- Prometheus Storage / Remote Storage: https://prometheus.io/docs/prometheus/latest/storage/
- VictoriaMetrics vmagent: https://docs.victoriametrics.com/victoriametrics/vmagent/
- Prometheus Alertmanager: https://prometheus.io/docs/alerting/latest/alertmanager/

---

## 4. 代码审查结论

### 4.1 架构强项

#### 4.1.1 TSDB 抽象做得正确

`TimeSeriesAdapter` 把运行时写入、批量写入、OTLP 指标写入、历史查询、可用率查询统一在接口层，避免业务服务直接依赖 InfluxDB/VictoriaMetrics 客户端。

当前设计的价值：

- Server 业务逻辑不直接绑定 InfluxDB 查询/写入 API。
- InfluxDB 与 VictoriaMetrics 可以通过 provider 切换。
- 后续接入 Prometheus remote_write、VictoriaMetrics 原生写入、OpenTelemetry Collector 转发时，不需要大面积改 Controller/Service。

建议继续坚持该边界：**任何 TSDB 读写都不得绕过 `TimeSeriesAdapter`。**

#### 4.1.2 TSDB 降级设计具备生产意识

`InfluxDbProvider` 已包含：

- Resilience4j circuit breaker。
- 本地 JSONL 缓冲。
- 定时重放。
- 重放成功归档。
- 旧缓冲目录迁移。
- 批量写入路径。

这是当前项目中较强的可靠性设计。下一步不应推翻，而应补齐边界条件：

- 缓冲目录最大容量。
- archive 清理策略。
- buffer backlog 指标。
- replay 失败次数与最后失败原因指标。
- provider 切换时的 fail-fast 策略。

#### 4.1.3 测试体系较完整

当前测试体系已经从“纯单测”推进到三层结构：

```text
E2E：Playwright 黄金路径，CI 跑 docker-compose 全栈
集成测试：Spring Boot @SpringBootTest + Testcontainers
单元测试：Surefire 单测、MockMvc slice、接口桩
```

该设计对个人/小团队项目来说已经比较成熟。建议后续所有关键演进都必须补测试，不要回到“功能先行、测试滞后”的节奏。

#### 4.1.4 安全能力不是空白

项目已有：

- Spring Security。
- JWT。
- API Token。
- OIDC/SSO。
- 密码策略。
- 请求日志脱敏。
- SSH/OIDC/探测敏感字段加密。
- 公开状态页默认关闭。
- API Token readonly scope 限制。

但仍有几个高风险细节需要尽快修正，见下一节。

#### 4.1.5 前端网络层抽象清楚

前端已有：

- REST helper：`src/net/index.js`
- SSE helper：`src/net/sse.js`
- WebSocket helper：`src/net/ws.js`
- query 构造 helper
- SSE 指数退避重连
- WS URL 构造与关闭 helper

这使后续把 query token 改成短期 channel ticket、扩展 SFTP 断点续传、统一 WS 重连策略时有较好的改造入口。

---

## 5. 高优先级问题

### P0-1. 变更操作使用 GET

当前删除主机接口是：

```java
@GetMapping("/delete")
public RestBean<Void> deleteClient(@RequestParam int clientId, ...)
```

问题：

- 删除是变更操作，不应使用 GET。
- GET 可能被浏览器预取、缓存、代理、安全扫描误触发。
- 对外 API 语义不规范，后续开放 API Token 或 SDK 时会扩大风险。

建议改为：

```http
DELETE /api/v1/monitor/clients/{clientId}
```

短期兼容方案：

1. 新增标准 DELETE 接口。
2. 老 `GET /delete` 保留一个版本，返回 warning header 或日志提示 deprecated。
3. 前端立即切到 DELETE。
4. E2E 增加删除链路测试。
5. 下个小版本移除或默认禁用旧接口。

---

### P0-2. SSE/WS token 放在 query 中

当前后端允许 SSE、terminal、sftp 从 query 参数读取 token；前端也会把 token 拼到 URL query 中。

问题：

- token 可能进入浏览器历史。
- token 可能进入反向代理 access log。
- token 可能出现在错误截图、监控日志、Referer 相关链路中。
- WebSocket/SSE 的浏览器限制可以解释这种做法，但不应长期保留主 JWT 直接裸奔在 URL 中。

建议改为：

```text
JWT → 请求一次短期 channel ticket → SSE/WS 使用 ticket → ticket 单用途/短 TTL/绑定 userId + clientId + purpose
```

建议 ticket 语义：

| 字段 | 含义 |
|---|---|
| `ticket` | 随机 token，只显示一次 |
| `purpose` | `sse:clients` / `sse:runtime` / `ws:terminal` / `ws:sftp` |
| `userId` | 当前用户 |
| `clientId` | 可选，绑定目标主机 |
| `expiresAt` | 例如 30 秒 |
| `used` | 是否已使用 |
| `ip/userAgent hash` | 可选绑定，降低泄漏风险 |

---

### P0-3. SSH 密码可解密回显给前端

当前 `getSshSetting` 会将 `client_ssh.password` 解密后返回给前端。

问题：

- 任意能访问该接口的用户都能拿到 SSH 明文密码。
- Web 终端功能天然敏感，凭据回显会扩大泄漏面。
- 一旦浏览器、插件、XSS、前端日志、截图泄露，就直接泄露服务器凭据。

建议：

- 查询 SSH 设置时只返回 `passwordConfigured=true`，不返回明文。
- 修改 SSH 设置时：
  - 密码字段为空：保持原密码不变。
  - 密码字段非空：替换密码。
- 优先支持私钥、临时凭据、堡垒机模式。
- 所有 SSH 连接、SFTP 下载/上传/删除、SSH 配置变更写入操作审计日志。

---

### P0-4. prod SQL stdout 日志应关闭

`application-prod.yml` 中仍配置：

```yaml
mybatis-plus:
  configuration:
    log-impl: org.apache.ibatis.logging.stdout.StdOutImpl
```

问题：

- 生产 SQL 输出到 stdout，容易造成日志噪声。
- 参数、业务 ID、部分敏感上下文可能进入日志系统。
- 性能上也有不必要开销。

建议：

- 生产环境删除该项。
- 开发 profile 保留即可。
- 生产调试 SQL 通过 logger level、短期动态开关、采样与脱敏完成。

---

### P0-5. 生产密钥应 fail-fast

当前 `.env.example` 已说明 `JWT_KEY`、`API_TOKEN_HMAC_KEY`、`SSH_ENCRYPT_KEY` 必须使用不同强随机密钥，但 prod 中部分配置仍允许空值或示例值路径。

建议启动时增加配置校验：

- `prod` 下 `JWT_KEY` 必填。
- `prod` 下 `API_TOKEN_HMAC_KEY` 必填。
- `prod` 下 `SSH_ENCRYPT_KEY` 必填。
- `API_TOKEN_HMAC_KEY != JWT_KEY`。
- `SSH_ENCRYPT_KEY` 必须是 Base64 32 字节。
- 不允许使用 `.env.example` 中的示例值。
- 校验失败时应用启动失败，不允许降级继续运行。

---

## 6. 性能与可扩展性问题

### P1-1. `ClientServiceImpl` 职责过宽

当前 `ClientServiceImpl` 同时承担：

- 注册 token。
- 主机注册。
- client cache。
- token cache。
- runtime cache。
- heartbeat。
- TSDB 写入。
- SSE 推送。
- 告警触发。
- SSH 配置。
- 删除合同。
- 状态页引用清理。
- 子账户权限引用清理。

这会导致：

- 后续改动容易产生回归。
- 单测 mock 成本高。
- 读写逻辑边界不清。
- 缓存失效点分散。
- HA-lite 改造困难。

建议渐进拆分，不做一次性大重构：

| 新服务 | 负责范围 |
|---|---|
| `ClientRegistryService` | 注册 token、主机注册、token 查询 |
| `ClientRuntimeService` | runtime 上报、heartbeat、当前运行时状态 |
| `ClientReadModelService` | 主机列表、详情、状态页候选数据 |
| `ClientDeletionService` | 删除合同、关联清理、缓存失效 |
| `ClientSshService` | SSH/SFTP 配置、加密、权限与审计 |

第一阶段只抽 `ClientReadModelService` 与 `ClientDeletionService`，收益最大、风险最小。

---

### P1-2. 主机列表存在 N+1 查询

`listClients()` 从本地 client cache 遍历主机，然后每台主机查询一次 `client_detail`。`listSimpleClients()` 也有类似模式。

问题：

- 主机数量增加后，列表页响应时间线性变差。
- SSE `publishClientList()` 也会触发列表构造，会放大问题。
- 状态页、权限过滤、列表刷新都可能重复触发该路径。

建议 v2.1 处理：

1. 用 `selectBatchIds(clientIds)` 一次取所有 `client_detail`。
2. 建 Redis read model：
   - `client:list:{permissionHash}`
   - `client:detail:{clientId}`
   - `status:candidates`
3. 明确失效点：
   - 主机注册。
   - 主机删除。
   - 主机重命名。
   - 节点/区域变更。
   - `client_detail` 更新。
   - 状态页配置更新。
   - 子账户权限变更。
4. 保留 Caffeine 作为极短 TTL 进程内缓存，但不要让它成为跨实例事实源。

---

### P1-3. 随机 clientId 有碰撞与不可追踪风险

当前 `randomClientId()` 使用 `new Random()` 生成 8 位 ID。

问题：

- 存在碰撞风险。
- 碰撞后 `save(client)` 失败，注册流程没有重试逻辑。
- `Random` 不适合生成需要稳定唯一性的业务 ID。
- 8 位数字虽然适合展示，但不适合做内部主键。

建议：

- 最简单：改成数据库自增 ID。
- 如果要保留 8 位展示 ID：单独建 `display_id`，唯一索引 + 重试。
- 或使用 Snowflake/ULID 作为内部 ID，短 ID 只用于展示。

---

### P1-4. 注册 token 是进程内状态

当前注册 token 是 `ClientServiceImpl` 字段，应用启动时生成，注册成功后轮换。

问题：

- 多实例部署时，每个 server 都持有不同注册 token。
- 重启后 token 丢失。
- 注册 token 生命周期、使用次数、审计不可追踪。

建议改为 DB/Redis 管理的一次性 token：

```sql
CREATE TABLE registration_token (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  token_hash VARCHAR(128) NOT NULL,
  created_by INT NULL,
  expires_at DATETIME NOT NULL,
  used_at DATETIME NULL,
  max_uses INT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

---

## 7. 单机状态与 HA-lite

当前 SSE 是单机内存实现，`SseEventBus` 默认实现为本机推送。`SseController` 中所有 emitter 都存在当前 JVM 内存中。

这意味着：

- 多实例部署时，A 实例收到 runtime，B 实例上的前端 SSE 订阅者收不到。
- 心跳、当前 runtime、告警窗口、注册 token 都有类似问题。
- ProbeScheduler、AlertEvaluator 如果多实例同时执行，可能出现重复探测或重复告警。

建议 v2.2 目标不是完整分布式系统，而是 **HA-lite**：

| 能力 | 当前 | v2.2 建议 |
|---|---|---|
| SSE | 本机 emitter | Redis Pub/Sub bridge；每实例只推本机连接 |
| 心跳/current runtime | JVM Map/Caffeine | Redis hash/zset + 本地短 TTL |
| 告警窗口 | JVM 内存窗口 | Redis 状态或按 clientId 分片；至少加分布式锁 |
| 探测调度 | 单机调度假设 | ShedLock/Redis lock/MySQL lock，避免多实例重复执行 |
| 注册 token | JVM 字段 | DB/Redis token |
| 主机读模型 | 本地 cache + DB | Redis read model |

---

## 8. 演进路线建议

### 8.1 v2.1：稳定化与性能收口

目标：把当前 v2.0 从“功能完整”推进到“可放心部署”。

必做任务：

1. **安全修复**
   - 删除接口改为 DELETE。
   - SSH 密码不再明文回显。
   - prod 关闭 MyBatis stdout SQL 日志。
   - prod 密钥 fail-fast。
   - `/actuator/**` 改成只公开 `/actuator/health`，未来非 health 端点必须认证。
   - SSE/WS 增加短期 channel ticket。

2. **Redis 读模型**
   - 完成 `client list/details cache`。
   - 消除 `listClients()` / `listSimpleClients()` N+1。
   - 为列表接口增加缓存命中率、回源耗时、失效次数指标。

3. **ClientServiceImpl 拆分**
   - 第一阶段只抽 `ClientReadModelService` 和 `ClientDeletionService`。
   - 不改外部 API，先降低单类复杂度。

4. **TSDB 缓冲增强**
   - JSONL buffer 增加最大磁盘占用。
   - archive 增加清理策略。
   - `replayBatchSize` 明确是“文件数”还是“记录数”，避免配置语义误导。
   - unknown TSDB provider 在 prod 下建议 fail-fast，而不是静默 fallback 到 InfluxDB。

交付标准：

- `mvn verify` 通过。
- 前端 lint/test/build 通过。
- Playwright E2E 通过。
- 新增安全回归测试覆盖删除、SSH 密码、token ticket、prod 配置校验。

---

### 8.2 v2.2：可靠性与 HA-lite

目标：允许小团队以 2 个 server 实例部署，至少不丢关键推送、不重复关键任务。

任务：

1. **RedisSseEventBus**
   - 新增 `redisSseEventBus` bean。
   - 业务事件发布到 Redis channel。
   - 每个 server 订阅 channel 后只推本机 emitter。
   - 保留 `LocalSseEventBus` 作为单机 fallback。

2. **心跳与当前运行时迁移 Redis**
   - `client:heartbeat:{id}` 或 zset。
   - `client:runtime:{id}` hash/string，短 TTL。
   - Caffeine 只做本地读穿透缓存。

3. **告警与探测防重复**
   - 告警 `(ruleId, clientId)` 加分布式锁或分片归属。
   - 探测任务基于 Redis/MySQL lock 抢占。
   - 告警通知增加去重键：`ruleId + clientId + firingWindow`。

4. **Agent 离线可靠补报**
   - 当前 monitor-client 离线补报是进程内队列，最大 1000 条，重启丢失。
   - v2.2 可新增 agent-side JSONL queue：
     - 最大文件数/磁盘占用。
     - 成功 ACK 后删除。
     - 防重复 timestamp + sequence。

---

### 8.3 v2.3：开放协议与生态接入

目标：让项目不是封闭监控系统，而是能进入主流观测链路。

任务：

1. **OTLP 能力扩展**
   - 当前已有 `POST /v1/metrics`，支持 Protobuf/JSON，并用 `X-Monitor-Token` 鉴权。
   - 后续应扩展 metric 映射，不只处理 `monitor.client.*` 白名单。
   - 增加 OpenTelemetry Collector 示例配置。

2. **Prometheus remote_write**
   - 新增 remote_write ingest endpoint 或通过 vmagent/Alloy 转接。
   - 内部统一写入 `TimeSeriesAdapter`。
   - 如果引入 PromQL/MetricsQL 查询，优先依赖 VictoriaMetrics，不自己实现查询语言。

3. **VictoriaMetrics 生产路线**
   - 现有 docker-compose 已有 VictoriaMetrics optional profile 与 vmctl migration profile。
   - 建议新文档中提供：
     - InfluxDB 默认兼容路线。
     - VictoriaMetrics 推荐生产路线。
     - InfluxDB → VM 迁移 runbook。
     - 备份/恢复/retention 策略。

4. **服务自身可观测性**
   - 暴露 server 自身指标：
     - 请求耗时。
     - TSDB 写失败。
     - buffer backlog。
     - SSE 连接数。
     - 告警评估耗时。
     - RabbitMQ 投递失败。
   - 可选 Micrometer Prometheus endpoint，但默认不公开到公网。

---

### 8.4 v3.0：产品化与权限模型升级

目标：从“小团队自用”进入“多团队可管理”的边界，但仍不做大型 SaaS。

任务：

1. **多租户与数据模型规范化**
   - 当前 `account.clients` 是 JSON 字符串。
   - `status_page_config.client_ids` 是 TEXT。
   - `alert_rule.channel_ids` 是 JSON。
   - 建议改为：
     - `tenant`
     - `tenant_member`
     - `client_permission`
     - `status_page_client`
     - `alert_rule_channel`
   - JSON 字段可以保留为兼容读，但新写入走关联表。

2. **API Token 细粒度 scope**
   - 当前 API Token 主要区分 readonly 与写权限。
   - 建议升级为：
     - `monitor:read`
     - `monitor:write`
     - `alert:read`
     - `alert:write`
     - `status:write`
     - `terminal:connect`
     - `sftp:read`
     - `sftp:write`

3. **操作审计**
   - 审计事件至少覆盖：
     - 登录/登出/OIDC 绑定。
     - API Token 创建、撤销、使用。
     - SSH 配置变更。
     - 终端连接、SFTP 上传/下载/删除。
     - 主机删除。
     - 告警规则与通知通道变更。
   - 审计表单独建模，不依赖普通请求日志。

4. **告警降噪**
   - 当前告警已支持 firing、acknowledged、resolved、RabbitMQ 通知与 SSE。
   - 下一步建议参考 Alertmanager 的分组、抑制、静默、路由树，但实现轻量版即可。

---

## 9. 推荐优先级清单

| 优先级 | 任务 | 原因 |
|---|---|---|
| P0 | 删除接口 GET → DELETE | 安全与 HTTP 语义问题，影响最大，改动可控 |
| P0 | SSH 密码不回显 | 凭据安全风险高 |
| P0 | prod 关闭 SQL stdout 日志 | 避免生产日志泄露与噪声 |
| P0 | prod 密钥 fail-fast | 防止误用示例密钥或空密钥 |
| P1 | Redis read model + 消除 N+1 | 直接改善列表页、状态页、权限过滤性能 |
| P1 | 拆 `ClientServiceImpl` | 降低后续改动风险 |
| P1 | 注册 token 持久化 | 为 HA-lite 铺路 |
| P2 | Redis Pub/Sub SSE | 支持多实例部署 |
| P2 | 告警/探测分布式防重复 | 避免 HA 后重复通知/重复探测 |
| P3 | OTLP/Prometheus remote_write 增强 | 与主流生态接轨 |
| P3 | VictoriaMetrics 生产 runbook | 降低部署与迁移成本 |
| P4 | 多租户、细粒度 scope、审计 | 产品化长期能力 |

---

## 10. 建议任务拆解

### 10.1 第一批 PR：安全与接口语义

建议标题：

```text
chore(security): harden prod config and replace monitor delete GET with DELETE
```

范围：

- 新增 `DELETE /api/v1/monitor/clients/{clientId}`。
- 前端删除操作切换到 DELETE。
- 保留旧 GET 入口并标记 deprecated。
- prod 删除 MyBatis stdout SQL。
- 增加 prod 密钥校验。
- 增加对应单测/E2E。

### 10.2 第二批 PR：SSH 凭据安全

建议标题：

```text
fix(ssh): stop returning decrypted ssh passwords to frontend
```

范围：

- `SshSettingsVO` 改为 `passwordConfigured`。
- 保存 SSH 设置支持空密码不覆盖。
- 前端 SSH 表单调整。
- 增加回归测试：查询 SSH 设置不含明文密码。

### 10.3 第三批 PR：ClientReadModelService

建议标题：

```text
perf(client): add client read model service and remove list N+1 queries
```

范围：

- 抽 `ClientReadModelService`。
- `client_detail` 批量查询。
- 预留 Redis 缓存接口。
- `listClients()` 与 `listSimpleClients()` 性能测试或单测覆盖。

### 10.4 第四批 PR：Redis read model

建议标题：

```text
perf(cache): add redis-backed client list and detail read models
```

范围：

- Redis cache key 设计。
- 缓存失效点覆盖。
- 本地 Caffeine 只作为短 TTL 二级缓存。
- 增加缓存命中/失效日志或指标。

### 10.5 第五批 PR：HA-lite SSE

建议标题：

```text
feat(sse): add redis pubsub event bus for multi-instance deployments
```

范围：

- 新增 `RedisSseEventBus`。
- 事件序列化协议。
- 本地 emitter 只负责当前实例连接。
- 配置开关：`monitor.sse.bus=local|redis`。
- 增加集成测试或最小 mock 测试。

---

## 11. 最终路线判断

**v2.1 不要继续堆新功能。**  
先做安全、读模型、REST 语义、部署硬化和 `ClientServiceImpl` 拆分。

**v2.2 解决“单机可用但多实例不稳”的问题。**  
用 Redis 承接读模型、心跳、SSE 分发和分布式协调，但不要一次性引入复杂微服务化。

**v2.3 做标准协议桥接。**  
强化 OTLP、Prometheus remote_write、VictoriaMetrics、Collector/Alloy 示例，使项目能进入主流观测链路。

**v3.0 再做多租户、审计、细粒度权限。**  
这时再改 JSON/text 权限字段为规范化关联表，成本更合理。

---

## 12. 仓库依据索引

| 依据 | 文件路径 |
|---|---|
| 项目定位、模块、当前能力、数据库事实源 | `README.md` |
| 路线图、当前技术栈、已交付里程碑、剩余计划、技术债务 | `EVOLUTION.md` |
| 工程契约、敏感字段、前端契约、性能 backlog | `docs/engineering.md` |
| Server 依赖与测试插件 | `monitor-server/pom.xml` |
| Server dev/prod 配置 | `monitor-server/src/main/resources/application-dev.yml`, `application-prod.yml` |
| Docker Compose 部署拓扑 | `docker-compose.yml` |
| CI / E2E 工作流 | `.github/workflows/ci.yml` |
| TSDB 抽象 | `monitor-server/src/main/java/com/example/tsdb/TimeSeriesAdapter.java` |
| TSDB provider 工厂 | `monitor-server/src/main/java/com/example/tsdb/TsdbAdapterFactory.java` |
| InfluxDB provider、JSONL buffer、replay | `monitor-server/src/main/java/com/example/tsdb/InfluxDbProvider.java` |
| Spring Security 配置 | `monitor-server/src/main/java/com/example/config/SecurityConfiguration.java` |
| JWT 过滤器 | `monitor-server/src/main/java/com/example/filter/JwtFilter.java` |
| API Token 过滤器 | `monitor-server/src/main/java/com/example/filter/ApiTokenFilter.java` |
| 请求日志脱敏 | `monitor-server/src/main/java/com/example/filter/RequestLogFilter.java` |
| OTLP metrics controller | `monitor-server/src/main/java/com/example/controller/OtlpMetricsController.java` |
| ClientServiceImpl | `monitor-server/src/main/java/com/example/service/impl/ClientServiceImpl.java` |
| MonitorController | `monitor-server/src/main/java/com/example/controller/MonitorController.java` |
| ClientController | `monitor-server/src/main/java/com/example/controller/ClientController.java` |
| SSE 抽象与本地实现 | `monitor-server/src/main/java/com/example/config/SseEventBus.java`, `LocalSseEventBus.java` |
| SSE Controller | `monitor-server/src/main/java/com/example/controller/SseController.java` |
| 前端 REST helper | `monitor-web/src/net/index.js` |
| 前端 SSE helper | `monitor-web/src/net/sse.js` |
| 前端 WebSocket helper | `monitor-web/src/net/ws.js` |
| Flyway schema | `monitor-server/src/main/resources/db/migration/` |
