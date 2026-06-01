import { defineConfig, devices } from '@playwright/test'
import { STORAGE_STATE } from './e2e/fixtures/admin'

/**
 * v2.0-tests PR4：Playwright E2E 配置。
 *
 * <h3>架构决策</h3>
 *
 * - **baseURL**：默认 `http://localhost:80`（monitor-web 容器 nginx 端口）。CI 通过
 *   `E2E_BASE_URL` 环境变量覆盖。本地开发若想跑 e2e，需要先 `make up` 启全栈。
 * - **不用 webServer**：项目用外部 docker-compose 启全栈（含 mysql/redis/rabbitmq/influxdb/server/web）。
 *   Playwright 自己 vite preview 启不起来 monitor-server 依赖链。CI 由 .github/workflows/ci.yml
 *   的 e2e job 显式 `docker compose up -d --wait` 编排。
 * - **三浏览器矩阵**（D7 决策）：Chromium + Firefox + WebKit 全跑。
 * - **retries** (D8 决策)：CI 上 2 次重试容忍 docker-compose 启动尾期的 race，本地 0 重试避免掩盖真 bug。
 * - **fullyParallel: false + workers: 1**：docker-compose 后端状态（admin 行 / client 表 / SSE 订阅）
 *   在三浏览器之间共享，串行避免污染。
 * - **storageState auth 复用**（Playwright 官方 auth 模式）：`setup` project 登录一次把
 *   cookies + localStorage 落盘到 `e2e/.auth/admin.json`，三浏览器 project 通过
 *   `dependencies: ['setup']` + `use.storageState` 复用，把真实登录次数从 ~15 次降到 ~7 次，
 *   避开 JWT 签发限流（FlowUtils 每用户每 base 秒只签 1 个 JWT）。dashboard 测试天然拿到
 *   localStorage 里的 JWT，无需自己登录；login.spec 用 `test.use({ storageState: { ... } })`
 *   覆盖回登出态以测真实登录流程。
 * - **trace / screenshot / video on failure**：CI fail 时把 playwright-report 作为 artifact 上传，
 *   方便事后追踪。
 */
export default defineConfig({
  testDir: './e2e',
  // 单 worker 串行（后端状态共享），三浏览器 project 仍可并行（不同 BrowserContext）
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: 1,
  timeout: 60_000,
  expect: {
    timeout: 10_000
  },
  reporter: process.env.CI
    ? [
        ['github'],
        ['html', { open: 'never', outputFolder: 'playwright-report' }]
      ]
    : 'list',
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:80',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    actionTimeout: 15_000,
    navigationTimeout: 30_000
  },
  projects: [
    // 先跑 setup project：登录一次把 storageState（cookies + localStorage）写到
    // e2e/.auth/admin.json。三浏览器 project dependencies: ['setup'] 复用它，避免
    // 每个测试都真实登录撞 JWT 签发限流（FlowUtils 每用户每 base 秒只签 1 个）。
    { name: 'setup', testMatch: /auth\.setup\.ts/ },
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'], storageState: STORAGE_STATE },
      dependencies: ['setup']
    },
    {
      name: 'firefox',
      use: { ...devices['Desktop Firefox'], storageState: STORAGE_STATE },
      dependencies: ['setup']
    },
    {
      name: 'webkit',
      use: { ...devices['Desktop Safari'], storageState: STORAGE_STATE },
      dependencies: ['setup']
    }
  ]
})
