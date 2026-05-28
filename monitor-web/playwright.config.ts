import { defineConfig, devices } from '@playwright/test'

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
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    { name: 'firefox', use: { ...devices['Desktop Firefox'] } },
    { name: 'webkit', use: { ...devices['Desktop Safari'] } }
  ]
})
