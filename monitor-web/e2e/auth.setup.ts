import { test as setup } from '@playwright/test'
import { STORAGE_STATE, loginAsAdmin } from './fixtures/admin'

/**
 * v2.0-tests PR4：Playwright 官方 auth 复用模式（storageState）。
 *
 * <h3>为什么需要它</h3>
 *
 * CI e2e job 三浏览器登录失败的根因之一是 JWT 签发限流：FlowUtils 对每个用户的
 * JWT_FREQUENCY key「只要存在即 403」，key TTL = base 秒（ci.yml 已设
 * MONITOR_JWT_LIMIT_BASE=1）。dashboard.spec 的 beforeEach 每个 test 都真实登录，
 * 叠加 login.spec + retries，串行下相邻登录常 < 1s → 撞「登录验证频繁，请稍后再试」403。
 *
 * <h3>方案</h3>
 *
 * 全套测试只在这里登录一次，把 BrowserContext 的 storageState（cookies + localStorage）
 * 落盘到 {@link STORAGE_STATE}。playwright.config.ts 让 chromium/firefox/webkit
 * 三个 project 都 dependencies: ['setup'] 并 use.storageState 复用，真实登录次数从
 * ~15 次降到 ~7 次（都 > 1s 间隔，base=1 足够）。dashboard 测试天然拿到 localStorage
 * 里的 JWT，router 守卫直接放行，无需自己登录。
 *
 * <h3>关键约束</h3>
 *
 * - {@link loginAsAdmin} 内部勾选「记住我」→ token 写 localStorage；storageState
 *   只持久化 cookies + localStorage，不存 sessionStorage，所以 remember=true 是前提。
 * - storageState() 写文件时 Playwright 会自动创建 e2e/.auth/ 父目录。
 */
setup('authenticate as admin', async ({ page }) => {
  // 复用健壮化的 UI 登录（含「记住我」勾选 + 等待 /api/auth/login 响应 + URL commit）
  await loginAsAdmin(page)

  // 持久化登录态（cookies + localStorage，含 authorize JWT）供三浏览器 project 复用
  await page.context().storageState({ path: STORAGE_STATE })
})
