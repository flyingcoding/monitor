# 项目演进文档

## 1. 项目现状总览

### 技术栈

| 层级 | 技术 | 版本 |
|------|------|------|
| **Server** | Spring Boot | 3.5.10 |
| | Java | 21 |
| | MyBatis-Plus | 3.5.16 |
| | Spring Security + JWT | java-jwt 4.5.0 |
| | MapStruct | 1.6.3 |
| | Resilience4j | 2.2.0 |
| | sshj | 0.39.0 |
| | Knife4j (OpenAPI) | 4.5.0 |
| | Flyway | (Spring Boot 管理) |
| **Client** | Java | 21 (Virtual Threads) |
| | OSHI | 6.9.3 |
| | fastjson2 | 2.0.61 |
| **Web** | Vue | 3.5.28 |
| | Element Plus | 2.13.2 |
| | ECharts | 5.6.0 |
| | Vite | 6.4.1 |
| | Pinia | 2.3.1 |
| | xterm.js | 5.5.0 |
| | Axios | 1.13.5 |
| **存储** | MySQL | 8.0 |
| | InfluxDB | 2.7 (server) / 7.5.0 (client) |
| | Redis | 7 |
| **消息队列** | RabbitMQ | — |

### 已实现功能（v1.0 稳定化已完成）

#### 监控采集与展示
- **系统指标采集**：CPU 使用率、内存用量、磁盘用量与 IO 速率、网络上下行速率（10 秒间隔）
- **静态信息采集**：OS 架构/名称/版本、CPU 型号与核心数、总内存、总磁盘、IP 地址
- **时序数据存储**：InfluxDB 存储运行时指标，支持历史曲线查询
- **前端图表**：ECharts 按需加载、LTTB 采样

#### 实时通道
- **SSE 事件总线**：客户端状态变更、监控数据更新推前端（含断线重连与服务端写入容错）
- **WebSocket SSH 终端**：基于 sshj + xterm.js 的浏览器内终端

#### 安全与权限
- **JWT 认证**：72h 有效期，BCrypt 密码加密
- **Redis IP 限流**：100 次/5s
- **多用户权限**：管理员/普通用户角色，管理员可创建子账户并分配可见客户端
- **SSH 凭证加密**：`client_ssh` 表使用 AES-256 加密存储，密钥来自 `SSH_ENCRYPT_KEY`

#### 工程与运维
- **批量上报**：客户端聚合发送，降低网络开销
- **健康检查**：服务端定时检测客户端存活（30s 间隔，60s 超时）
- **容错机制**：Resilience4j 断路器保护 InfluxDB 写入、客户端指数退避重试与离线缓存补报
- **客户端运行时**：Java 21 + Virtual Threads，maven-shade 单 jar 部署，已脱离 Spring Boot 依赖
- **数据库迁移**：Flyway 管理 schema 版本
- **API 文档**：Knife4j 自动生成（仅开发环境）
- **邮件通知**：RabbitMQ 队列 + SMTP 发送验证码邮件
- **容器化部署**：docker-compose 一站式（MySQL/Redis/RabbitMQ/InfluxDB + monitor-server/web）
- **CI 工作流**：GitHub Actions（lint + build）
- **前端工程化**：pnpm + Vitest 工具函数测试

### 架构概览

```
┌─────────────┐     HTTP (10s)      ┌──────────────────────────────────┐
│ Client      │ ──────────────────> │ Server (Spring Boot 3.5.10)      │
│ (Java 21)   │   批量上报+离线补报    │                                  │
│ OSHI 采集    │                     │  CorsFilter                      │
│ Virtual     │                     │  → RequestLogFilter (雪花ID)      │
│  Threads    │                     │  → FlowLimitingFilter (Redis)    │
└─────────────┘                     │  → JwtFilter (Spring Security)   │
                                    │                                  │
                                    │  Controllers → Services          │
                                    │  → MyBatis-Plus → MySQL          │
                                    │  → InfluxDB (时序数据)            │
                                    │  → Redis (缓存/限流)             │
                                    │  → RabbitMQ (邮件队列)           │
                                    │                                  │
                                    │  WebSocket (SSH 终端)            │
                                    │  SSE (实时事件推送)               │
                                    └──────────────┬───────────────────┘
                                                   │
                                    ┌──────────────┴───────────────────┐
                                    │ Web (Vue 3.5.28)                 │
                                    │ Element Plus + ECharts (按需)    │
                                    │ xterm.js (SSH) + Pinia (持久化)   │
                                    └──────────────────────────────────┘
```

### 项目定位（差异化护城河）

本项目定位为**"中文友好的自托管轻量监控 + Web 终端平台"**，对标 Beszel / 哪吒监控 v1 / Uptime Kuma，**不**与 Datadog / Coroot / SigNoz / Netdata Cloud 等云原生/SaaS 平台正面竞争。

三项差异化优势：
1. **浏览器内 SSH 终端**（sshj + WebSocket + xterm.js）—— Beszel / Netdata / 哪吒 v1 均无
2. **多用户权限 + 子账户**（管理员可分配可见客户端）—— 比 Beszel 共享 Hub 更细
3. **中文友好**（Knife4j 中文 API 文档、邮件中文模板、文档与告警文案）

---

## 2. 竞品分析（2026-05 刷新）

### 竞品最新动态

| 竞品 | 2026 H1 状态 | 关键变化 |
|------|-------------|---------|
| **Apache HertzBeat 1.8.0**（2026-02-05） | **Apache 顶级项目** + **CNCF Observability Landscape** | 重新定位为 "AI-powered next-generation real-time observability"，YAML 模板 + Java 插件双扩展 |
| **哪吒监控 v1** | gRPC/Web 端口合并到单 8008、Agent 统一密钥、Caddy h2c 反代必选 | 一行命令安装所有 Agent，部署体验全面简化 |
| **Beszel v0.18.x** | GPU（nvtop/NVIDIA）+ SMART + Service Patterns + OAuth/OIDC + S3 自动备份 + REST API + macmon | 63 次 release，社区生态（Home Assistant / Raycast / iOS / Android）成熟 |
| **Netdata v2.9.0** | OpenTelemetry log ingestion + 14+ 数据库慢查询分析 + Go 重写 SQL Server collector + NVIDIA DCGM + Geo Maps | 已升级为完整 OTel + 数据库可观测 + ML 异常检测平台 |
| **Coroot**（新晋） | eBPF 零侵入采集（HTTP/MySQL/PG/Redis/Kafka），TLS 加密流量通过 uprobes 捕获 | 内核级 syscall 追踪，CPU 开销仅 15%（vs Java agent 38%），ClickHouse 后端 |

### 功能对比矩阵

| 维度 | 本项目 | Beszel | Apache HertzBeat | WGCLOUD | 哪吒 v1 | Netdata | Coroot |
|------|--------|--------|-----------|---------|---------|---------|--------|
| **GitHub Stars** | — | ~25k+ | ~7k+ | ~5k+ | ~10k+ | ~78k+ | ~7k+ |
| **开源协议** | — | MIT | Apache 2.0 | Apache 2.0 | Apache 2.0 | GPLv3 | Apache 2.0 |
| **核心语言** | Java + Vue | Go | Java | Java + Go | Go + React | C + Go | Go + eBPF |
| **Agent 架构** | HTTP 推送 | SSH/WebSocket | **无 Agent** | Go 推送 | gRPC 反连（单端口） | C 边缘计算 | **eBPF 零侵入** |
| **采集间隔** | 10s | 可配置 | 可配置 | 可配置 | 秒级 | **1 秒** | 持续流式 |
| **告警系统** | **无** | 阈值告警 + 50+ 通道 | 阈值 + 多通道 + 模板化 | 阈值 + 自愈 | 阈值告警 | 阈值 + ML 异常检测 | SLO + RCA |
| **通知渠道** | 邮件（仅验证码） | Discord/Telegram/Slack/邮件 | 邮件/Slack/钉钉/微信/Webhook | 邮件/钉钉/微信/短信 | Telegram/Discord/Bark/邮件 | Slack/PagerDuty/邮件 | Webhook/邮件 |
| **协议支持** | HTTP | SSH | HTTP/JMX/SSH/SNMP/JDBC/Prom | SNMP/PING/JDBC | HTTP/TCP/ICMP | OTel + 800+ | eBPF + OTel |
| **OTel 兼容** | **无** | 无 | **是**（输出 Prom） | 无 | 无 | **是**（输入/输出） | **是**（OTLP-native） |
| **容器监控** | 无 | Docker/Podman | Docker + K8s | Docker + K8s | 无 | Docker + K8s | K8s 优秀 |
| **K8s 原生** | 无 | 无 | Helm Chart | 是 | 无 | Helm + DaemonSet | DaemonSet |
| **进程监控** | 无 | systemd 服务 | 有 | 有 | 无 | 有 | eBPF 自动 |
| **GPU 监控** | 无 | NVIDIA/Intel iGPU 讨论中 | 有 | 有 | 无 | NVIDIA DCGM | 无 |
| **SMART 磁盘** | 无 | **内置磁盘 + 外置讨论中** | 无 | 无 | 无 | 有 | 无 |
| **服务探测** | 无 | 无 | HTTP/TCP/SNMP/Ping | SNMP/PING | HTTP/TCP/ICMP | OTel + 800+ | 自动服务图 |
| **公开状态页** | 无 | **是** | 无 | 无 | **是** | 无 | 无 |
| **OIDC/SSO** | 无 | **是**（多 provider） | 是 | 是 | OAuth | Cloud 多用户 | 是 |
| **REST API** | 部分 | **是**（API Token） | 是 | 是 | 是 | 是 | 是 |
| **Dashboard 自定义** | 固定布局 | 固定布局 | 中等（YAML 模板） | 中等（大屏模式） | **最佳**（主题 + CSS/JS） | 良好 | 自动服务图 |
| **AI/ML** | 无 | 无 | **AI-powered**（HertzBeat AI） | AI-LLM 分析 | 无 | 内置 ML 异常检测 | RCA + 异常检测 |
| **Web 终端** | **是**（sshj） | 无 | 无 | **是**（堡垒机） | **是** | 无 | 无 |
| **多用户权限** | **是**（子账户分配） | 是（共享 Hub） | 是 | 是 | 是 | Cloud | 是 |
| **中文友好** | **是** | 部分 | 是 | 是 | 是 | 部分 | 否 |

### 竞品定位分析

| 竞品 | 定位 | 与本项目的差距 | 可借鉴之处 |
|------|------|---------------|-----------|
| **Beszel** | 极简轻量家用/小规模（最贴近本项目定位） | 告警通道丰富、GPU/SMART/OIDC/API Token 标配 | OIDC 多 provider、REST API、systemd 服务监控、S3 备份 |
| **Apache HertzBeat** | 企业级 AI-powered 无 Agent | Apache + CNCF 生态、协议覆盖广、YAML 扩展、AI 标签 | YAML 模板化监控定义（用于告警规则） |
| **WGCLOUD** | 综合运维平台 | 数据库/MQ/网络设备/资产管理一体化 | 大屏展示模式、批量命令执行 |
| **哪吒监控 v1** | VPS 面板 / 状态页 | 部署体验、gRPC 反连 NAT 友好、单端口架构 | 单端口架构、Agent 统一密钥简化部署、公开状态页 |
| **Netdata** | 全栈可观测性平台 | 1 秒粒度、ML 异常检测、OTel 完整集成 | 数据库慢查询面板、Geo Maps |
| **Coroot** | eBPF 零侵入云原生观测 | eBPF + 自动服务图 + RCA | 自动服务依赖图思路（不抄实现） |

### 核心差距（重新评估）

1. **告警能力缺失（最严重）** —— 所有竞品都有，本项目零代码
2. **OIDC/SSO/API Token 缺失** —— Beszel 标配，企业部署刚需
3. **公开状态页缺失** —— 哪吒/Beszel/Statping/Uptime Kuma 标配
4. **服务可用性探测缺失** —— HTTP/TCP/Ping 探测，告警体系的延伸
5. **GPU/SMART/systemd 服务监控缺失** —— Beszel 已是基线
6. **OTel 协议不兼容** —— 长期会被生态边缘化（v2.0 解决）

> 不再追求：K8s 原生、eBPF 内核级采集、AI/ML 异常检测 —— 与"守住轻量赛道"定位冲突。

---

## 3. 技术债务与改进项（按代码现状刷新）

### 安全加固

| 项目 | 现状 | 建议 |
|------|------|------|
| ✅ SSH 凭证加密 | **已完成**（AES-256 + `SSH_ENCRYPT_KEY`） | — |
| ✅ JWT 实现 | **已落地**（java-jwt 4.5.0） | v2.0 增加 Refresh Token 机制 |
| ⚠️ CORS 配置 | 允许所有来源 (`*`) | 生产环境限制为具体域名 |
| ⚠️ JWT 有效期 | 72 小时 | 缩短至 24 小时（v2.0 配合 Refresh Token） |
| ❌ OIDC / SSO | 缺失 | **v1.2 必做**（Spring Security OAuth2） |
| ❌ API 文档生产环境保护 | Profile 控制 | v1.2 增加独立认证或完全禁用 |
| ❌ 密码复杂度策略 | 无要求 | v1.2 增加最小长度 + 特殊字符 |
| ❌ 请求日志脱敏 | 记录完整体 | v1.2 脱敏密码/Token 字段 |

### 测试覆盖

| 项目 | 现状 | 建议 |
|------|------|------|
| ❌ Server 单元测试 | 框架已就绪（JUnit 5 + Testcontainers），零实际测试 | **v1.3 启动**：Service 层核心逻辑 > 60% |
| ❌ Client 单元测试 | 框架已就绪（JUnit 5），零实际测试 | **v1.3 启动**：MonitorUtils 指标采集 |
| ⚠️ Web 单元测试 | Vitest 工具函数已有少量测试（2de6996） | **v1.3 扩展**：Pinia Store + 关键组件 |
| ❌ 集成测试 | 无 | v2.0 引入 Testcontainers 跑数据库/Redis/MQ 集成 |
| ❌ E2E 测试 | 无 | v2.0 使用 Playwright 覆盖登录、监控面板、终端核心流程 |

### 性能优化

| 项目 | 现状 | 建议 |
|------|------|------|
| ✅ 指标上报 | **已改为批量上报**（ad2de7e） | — |
| ✅ 客户端离线补报 | **已实现**（64e973d） | — |
| ✅ 客户端断线重试 | **已实现**（ff74e87 / efb609f） | — |
| ✅ 前端图表渲染 | LTTB 采样已实现 | 大数据集可在 v2.0 引入 Web Worker |
| ⚠️ InfluxDB 写入 | 已有 Resilience4j 断路器，但仍是逐条 | v1.x 增加批量写入 + 写缓冲 |
| ❌ Redis 缓存层 | 仅限流和验证码 | v1.x 扩展为客户端状态/详情缓存 |
| ❌ 数据库索引 | 基础索引 | 根据查询模式添加复合索引（v1.1 告警表设计时一并补） |

### 代码质量

| 项目 | 现状 | 建议 |
|------|------|------|
| ✅ 对象映射 | **MapStruct 1.6.3 已完成迁移** | — |
| ✅ 日志规范 | **logstash-logback-encoder 已集成** | v2.0 可对接 ELK |
| ⚠️ 异常处理 | 全局异常处理存在 | 统一业务异常体系，细化错误码 |
| ⚠️ 配置管理 | Maven Profile 切换 | 长期可考虑 Spring Cloud Config |

---

## 4. 短期演进（v1.1 - v1.3）

> 目标：6-9 个月，增量改进，不改变核心架构。
> 节奏：每档约 2-3 个月，对齐"告警先行 → 差异化补齐 → 监控增强"主线。

### v1.1 — 告警体系纯击（2026 Q3）

**目标**：补齐"所有竞品都有"的告警能力，扫清最严重的功能空白。

#### 阈值告警引擎
- 支持 CPU/内存/磁盘/网络的上下限阈值配置
- 告警持续时间过滤（避免瞬时抖动误报）
- 告警级别：Info / Warning / Critical
- 告警静默（按客户端/指标/时间段）
- 规则模型存表（新增 `alert_rule` 表，Flyway V2 迁移）

#### 多渠道通知
- 邮件（复用现有 RabbitMQ + SMTP 基础设施）
- Webhook（通用 HTTP 回调，支持自定义模板）
- 企业微信 / 钉钉 / 飞书机器人
- Telegram Bot
- 通知通道抽象：定义 `NotificationChannel` 接口，多实现可选
- 通知通道存表（新增 `notification_channel` 表）

#### 告警历史与确认
- MySQL 存储告警记录（新增 `alert_history` 表）
- 前端告警列表与详情页
- 告警确认/关闭操作
- 告警与客户端/指标关联查看

#### 前端通知中心
- 站内通知（告警、系统事件）
- 浏览器 Notification API 推送
- 未读计数小红点

### v1.2 — 差异化护城河补齐（2026 Q4）

**目标**：补齐与 Beszel / 哪吒 v1 的部署体验和企业能力差距。

#### OIDC / SSO 登录
- Spring Security OAuth2 Client
- 支持 Google / GitHub / GitLab / 通用 OIDC Provider
- 与现有 JWT 体系并行，登录后下发 JWT
- 管理员可配置允许的 OIDC Provider

#### 公开状态页
- 新增 `/status` 路由（无需登录）
- 展示客户端在线状态、最近 24h 可用率
- 管理员可选择"哪些客户端"展示在公开页
- 支持自定义状态页标题、Logo、品牌色

#### REST API + API Token
- 新增 `api_token` 表（hash 存储）
- 用户面板生成/撤销 Token
- `ApiTokenFilter` 在 `JwtFilter` 旁并行（不替换 JWT）
- API Token 权限粒度：只读 / 读写
- 现有 Controller 改造为 API Token 友好（错误码、响应格式统一）

#### 安全加固（顺手做）
- CORS 配置生产环境白名单
- 密码复杂度策略
- 请求日志脱敏（密码、Token 字段）

### v1.3 — 监控增强（2027 Q1）

**目标**：补齐与 Beszel / Netdata 已有的监控覆盖差距。

#### 服务可用性探测
- HTTP/HTTPS 可用性检测（状态码、响应时间、SSL 证书过期）
- TCP 端口探测
- ICMP Ping 探测
- 探测任务存表（新增 `probe_task` 表），可配置间隔
- 探测结果纳入告警体系（v1.1 引擎复用）

#### 进程监控
- 基于 OSHI 的进程列表采集（PID、名称、CPU、内存）
- Top N 进程排序
- 关键进程存活告警
- 客户端配置：可指定关注哪些进程

#### GPU 监控
- NVIDIA GPU（通过 `nvidia-smi` 命令解析，类似 Beszel `nvtop` 方案）
- 采集 GPU 利用率、显存、温度、功耗
- 客户端可选启用（无 GPU 时不上报）

#### SMART 磁盘健康
- 通过 `smartctl` 命令解析 SMART 属性
- 客户端可选启用
- 关键 SMART 属性异常时告警

#### systemd 服务监控
- Beszel "Service Patterns" 同款功能
- 客户端配置：监控哪些 systemd 服务
- 服务异常时告警

#### 测试覆盖启动
- Server 端 Service 层核心逻辑单测 > 60%
- Client 端 MonitorUtils 指标采集单测
- Web 端 Pinia Store + 关键组件 Vitest 覆盖

---

## 5. 中期演进（v2.0）

> 目标：6-12 个月，架构升级（不破坏 v1.x 兼容）。

### OTLP 指标接收（不重构 Client）

- Server 端新增 `OtlpMetricsController`（HTTP/Protobuf）
- 兼容标准 OTel metrics 数据模型
- 允许用户用 OTel Collector 中转其他生态指标（Node Exporter / Telegraf / Prometheus 远程写）进入本系统
- **Client 不改造**：仍使用现有 HTTP+JSON 上报，不引入 OTel SDK 重量级依赖
- 不接入 Logs / Traces（暂缓）

### 时序数据库适配层（双 Provider）

- 新增 `TimeSeriesAdapter` 抽象层
- `InfluxDbProvider`（默认，覆盖 v1.x 行为）
- `VictoriaMetricsProvider`（可选，MetricsQL 查询）
- Resilience4j 断路器逻辑在两个 Provider 之间复用
- 提供 `vmctl` 一键迁移脚本（InfluxDB v1/v2 → VictoriaMetrics）
- 用户通过 `application.yml` 切换

**为什么 VictoriaMetrics（已成行业共识）**：
- 摄入速度 20x 快于 InfluxDB
- RAM 用量 10x 节省（百万级时序）
- 数据点压缩 70x
- 支持 InfluxDB line protocol 摄入（迁移友好）
- 单节点扛 InfluxDB 集群工作量

### Web 终端能力升级

#### 多会话 Tab
- 一个主机多会话（多 tab）
- 跨主机并行连接
- WebSocket 多路复用：会话 ID + Tab 状态管理
- 前端使用 Element Plus Tabs 容器
- 会话保活与断线提示

#### SFTP 文件传输
- sshj 原生 SFTP 客户端
- 独立 WebSocket 通道，避免阻塞终端
- 支持大文件分片上传 / 断点续传
- 前端文件树 + 拖拽上传
- 不做会话审计回放（与轻量定位不匹配）

### 性能与可观测性

- InfluxDB 批量写入 + 写缓冲（在适配层落地）
- Redis 扩展为客户端状态缓存层
- 前端 LTTB 移到 Web Worker
- 服务端集成测试框架（Testcontainers）

### 前端体验

- Dashboard 时间范围选择器优化（1h / 6h / 24h / 7d / 30d / 自定义）
- 数据导出（CSV）
- 浏览器 Notification API 推送
- E2E 测试（Playwright）

---

## 6. 长期愿景（v3.0+）

> 目标：12+ 个月，聚焦自托管小团队场景的极致体验。

### 部署体验极致化

- **单二进制分发**：Server / Client 分别打包为单二进制（GraalVM Native Image 评估）
- **一键安装脚本**：跨平台（Linux / macOS / Windows）
- **Docker / docker-compose 模板**：开箱即用配置
- **可选 Helm Chart**：K8s 部署作为可选（不主推）

### 大规模性能优化

- 目标：单 Server 节点稳定支撑 1000+ 客户端
- 资源占用持续压减（Server JVM 内存、Web 加载体积）
- 客户端 JAR 体积优化（OSHI 按需裁剪）
- 时序数据冷热分层（热数据 InfluxDB/VM 7-30 天，冷数据归档对象存储）

### 多租户隔离

- 数据 / 配置 / 权限完全隔离
- 租户级资源配额（客户端数、告警规则数、API 调用频率）
- 租户管理控制台（仅超级管理员可见）

### 可选 SaaS 模式

- 白标支持（自定义品牌 / Logo / 域名）
- 统一计费与用量统计（如选择商业化）
- 不做大型 SaaS，仅为小团队 / MSP 转售场景留出口

### 合规与审计

- 日志脱敏（密码 / Token / 个人信息）
- 数据本地化（数据库可选加密、磁盘加密支持）
- 操作审计日志（谁在何时改了什么配置）
- 不做企业级合规认证（SOC2 / ISO27001 等）

> **已删除的方向**：原 v3.0 提及的 AIOps 智能运维、eBPF 内核级采集、全栈可观测性平台（指标日志追踪三合一）均已剔除，原因是与"守住轻量赛道"定位冲突。

---

## 7. 技术升级路线

### 优先级排序

```
P0 (立即)     告警引擎 → 多渠道通知 → 告警历史 → 通知中心          [v1.1]
P1 (短期)     OIDC/SSO → 公开状态页 → REST API+Token              [v1.2]
P2 (短期)     服务探测 → 进程监控 → GPU → SMART → systemd → 测试    [v1.3]
P3 (中期)     OTLP 指标接收 → 时序 DB 适配层（VM 可选）             [v2.0]
P4 (中期)     Web 终端多 Tab + SFTP → 集成测试 → E2E               [v2.0]
P5 (长期)     部署体验 → 性能优化 → 多租户                          [v3.0+]
P6 (可选)     SaaS 模式 → 合规与审计                                [v3.0+]
```

### 技术组件升级建议

| 组件 | 当前 | 建议 | 优先级 | 说明 |
|------|------|---------|--------|------|
| SSH 库 | sshj 0.39.0 | 维持现状 + 启用 SFTP API | P4 | v2.0 Web 终端升级配套 |
| 对象映射 | MapStruct 1.6.3 | 维持现状 | — | 已是最佳实践 |
| 断路器 | Resilience4j 2.2.0 | 维持现状 + 复用到 VM Provider | P3 | v2.0 适配层复用 |
| 客户端协议 | HTTP+JSON | **维持**，不改 gRPC/OTel | — | 守住轻量定位的关键 |
| 服务端摄入端点 | HTTP+JSON | **增加 OTLP HTTP 端点** | P3 | v2.0 生态兼容 |
| 时序 DB | InfluxDB 2.7 | **InfluxDB 默认 + VictoriaMetrics 可选** | P3 | v2.0 双 Provider |
| 通知队列 | RabbitMQ + Mail | 扩展为通用通知队列 | P0 | v1.1 多通道接入 |
| 前端状态 | Pinia 2.3.1 | 维持现状 | — | Vue 3 官方推荐 |
| 前端图表 | ECharts 5.6.0（按需） | 维持现状 + Web Worker | P4 | v2.0 大数据集渲染 |
| 构建工具 | Vite 6.4.1 | 维持现状 | — | 最新版本 |
| 测试框架 | JUnit 5 + Vitest | **补充实际测试** | P2 | v1.3 启动测试覆盖 |
| 容器化 | docker-compose | 维持 + 评估 Helm Chart | P5 | v3.0+ K8s 部署可选 |
| CI/CD | GitHub Actions（lint+build） | 增加单测 + 集成测试 + 发布产物 | P2 | v1.3 配套 |

> 明确不做的升级：
> - **gRPC 替代 HTTP**（与轻量定位冲突，Client 不变）
> - **完整 OpenTelemetry Client SDK 接入**（重量级依赖）
> - **eBPF 内核级采集**（Linux + Go/C，与 Java 栈不匹配）
> - **AIOps / LLM 集成**（依赖外部 API，破坏自托管理念）
> - **K8s 监控 / Helm 主推**（赛道拥挤，Coroot/Netdata 已饱和）

### 里程碑规划（重新校准）

```
2026 Q3  v1.1  告警体系纯击（阈值引擎 + 多通道 + 告警历史 + 通知中心）
2026 Q4  v1.2  差异化护城河（OIDC/SSO + 状态页 + REST API + API Token + 安全加固）
2027 Q1  v1.3  监控增强（探测 + 进程 + GPU + SMART + systemd + 测试覆盖启动）
2027 Q2  v2.0-alpha  OTLP 接收端点 + 时序 DB 适配层骨架
2027 Q3  v2.0-beta   VictoriaMetrics Provider + vmctl 迁移工具
2027 Q4  v2.0        Web 终端多 Tab + SFTP + 集成测试 + E2E
2028+    v3.0+       部署体验 / 性能优化 / 多租户 / 可选 SaaS / 合规审计
```

---

## 附录 A：数据库表结构

### 当前表（v1.0）

| 表名 | 用途 | 关键字段 |
|------|------|---------|
| `account` | 用户账户 | id, username, email, password (BCrypt), role, clients, register_time |
| `client` | 监控客户端 | id, name, token, location, node, register_time |
| `client_detail` | 客户端硬件信息 | id, os_arch, os_name, os_version, cpu_name, cpu_core, memory, disk, ip |
| `client_ssh` | SSH 连接信息（AES-256 加密） | id, ip, port, username, password |

### v1.1 计划新增

| 表名 | 用途 | 关键字段（预估） |
|------|------|---------|
| `alert_rule` | 告警规则 | id, name, client_id (NULL=全局), metric, operator, threshold, duration_sec, level, enabled, channel_ids (JSON 数组), silence_until, created_at, updated_at |
| `alert_history` | 告警历史 | id, rule_id, client_id, fired_at, resolved_at, status (firing/resolved/acknowledged), level, current_value, message, acked_by, acked_at |
| `notification_channel` | 通知通道配置 | id, name, type (mail/webhook/dingtalk/feishu), config (JSON，`_enc` 后缀字段 AES 加密), enabled, created_at |

> v1.1 实施记录（2026-05-17）：实际落地为 **3 表 + JSON 字段**（D2 决策），未单独建 `notification_rule` 关联表；channel↔rule 多对多关系通过 `alert_rule.channel_ids` JSON 数组承载。Flyway 迁移 `V2__alert.sql`。MVP 通道：邮件 / Webhook / 钉钉 / 飞书（D1 决策，企业微信 / Telegram 推迟到 v1.1.x patch）。

### v1.2 计划新增

| 表名 | 用途 | 关键字段（预估） |
|------|------|---------|
| `api_token` | API 访问令牌 | id, account_id, name, token_hash, scope, expires_at, last_used_at |
| `oidc_provider` | OIDC Provider 配置 | id, name, issuer_url, client_id, client_secret_enc, enabled |
| `status_page_config` | 公开状态页配置 | id, title, logo_url, brand_color, visible_clients (JSON) |

### v1.3 计划新增

| 表名 | 用途 | 关键字段（预估） |
|------|------|---------|
| `probe_task` | 服务可用性探测任务 | id, name, type (http/tcp/icmp), target, interval_sec, timeout_sec, expected, enabled |
| `probe_history` | 探测结果历史 | id, task_id, executed_at, success, latency_ms, status_code, message |
| `process_watch` | 关键进程监控配置 | id, client_id, process_pattern, alert_on_missing |

---

## 附录 B：调研索引

本文档基于以下调研成果重写：

- `.trellis/tasks/05-16-evolution/prd.md` — 7 项决策（D1-D7）记录
- `.trellis/tasks/05-16-evolution/research/competitors-2026.md` — 竞品 2026 H1 最新动态
- `.trellis/tasks/05-16-evolution/research/industry-trends-2026.md` — 五大行业趋势细节与量化数据
- `.trellis/tasks/05-16-evolution/research/gap-analysis.md` — 原文档 vs 2026 现实差距清单

---

*文档生成时间：2026-05-17*
*基于 2026 H1 竞品调研、行业趋势分析与代码实际状态重写*
