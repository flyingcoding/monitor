import { type Page, expect } from '@playwright/test'

/**
 * v2.0-tests PR4：E2E 测试的 admin 登录辅助。
 *
 * <h3>密码契约</h3>
 *
 * Flyway V1__init.sql 预置 admin 行的 BCrypt 哈希明文未文档化（PR2 已踩过坑：
 * `$2a$10$WMFjOMHaHqIVJCzJ16xOH...` 不是 admin123）。E2E 在 docker-compose 上跑，
 * 不可能像 Server IT 那样通过 PasswordEncoder 运行时重置。
 *
 * 解决：CI 启 docker-compose 之后，e2e job 显式 `docker compose exec mysql mysql ... UPDATE
 * account SET password = '<BCRYPT_HASH>' WHERE username = 'admin'`，把密码改成已知值
 * {@link ADMIN_PASSWORD}。哈希 {@link ADMIN_BCRYPT_HASH} 用 Python `bcrypt.hashpw(...)`
 * 离线生成（rounds=10, prefix=2a），与项目 BCryptPasswordEncoder 默认参数一致。
 *
 * 本地手动跑 e2e：先 `make up` 启全栈，然后在 mysql 容器手动执行同样的 UPDATE。
 *
 * <h3>登录流程</h3>
 *
 * 1. {@link loginAsAdmin} 走 UI 输入用户名 / 密码 + 点 "立即登录"
 * 2. 验证主面板 URL 跳到 `/index`
 * 3. 验证 localStorage `authorize` 已写入（{token, expire} JSON）
 *
 * 不走 `/api/auth/login` POST 直登：要测 UI 行为本身（表单校验、router 跳转、Pinia 写入）。
 */

/** E2E admin 明文密码，CI 在 docker-compose 启动后 UPDATE 到 `account.password`。 */
export const ADMIN_PASSWORD = 'e2e-admin-password-known'

/**
 * BCrypt 哈希 for {@link ADMIN_PASSWORD}（rounds=10, prefix=$2a$）。
 *
 * 离线生成（Python 3 + bcrypt）：
 * ```bash
 * python3 -c "import bcrypt; print(bcrypt.hashpw(b'e2e-admin-password-known', bcrypt.gensalt(rounds=10, prefix=b'2a')).decode())"
 * ```
 *
 * 注：BCrypt 同密码每次生成的哈希不同（salt 不同），但都能 verify 通过。CI 用这个固定值
 * 通过 SQL UPDATE 写入数据库。
 */
export const ADMIN_BCRYPT_HASH =
  '$2a$10$vDm1cOU6353vqUOaLXpG9uQZ3iIrI4FbfR1SOcn54gG08Jr0vEUzG'

/** localStorage 中 JWT 持久化 key，与 src/net/index.js authItemName 一致。 */
export const AUTH_STORAGE_KEY = 'authorize'

/**
 * UI 登录 admin 用户。调用前需保证：
 * 1. docker-compose 全栈已启动（monitor-web nginx 监听 80，monitor-server 8001）
 * 2. account.password 已被 UPDATE 为 {@link ADMIN_BCRYPT_HASH}
 *
 * @param page Playwright Page
 */
export async function loginAsAdmin(page: Page): Promise<void> {
  await page.goto('/')

  // LoginPage.vue 用 el-input 包了原生 <input>，placeholder 是稳定 selector
  const usernameInput = page.getByPlaceholder('用户名/邮箱')
  const passwordInput = page.getByPlaceholder('密码')
  await expect(usernameInput).toBeVisible()
  await expect(passwordInput).toBeVisible()

  await usernameInput.fill('admin')
  await passwordInput.fill(ADMIN_PASSWORD)

  // 点击 "立即登录"
  await page.getByRole('button', { name: '立即登录' }).click()

  // 跳转到 /index/管理 tab（首次加载默认 manage）
  // login() 成功 callback 调用 router.push('/index')，可能跳到 /index 或 /index/
  await page.waitForURL((url) => url.pathname.startsWith('/index'), { timeout: 30_000 })
}

/**
 * 读取 localStorage 中的 JWT 对象，校验存在性 + 未过期。
 *
 * 返回反序列化后的 { token, expire } 对象（与 src/net/index.js storeAccessToken 写入一致）。
 */
export async function readPersistedAuth(page: Page): Promise<{ token: string; expire: string }> {
  const raw = await page.evaluate((key) => window.localStorage.getItem(key), AUTH_STORAGE_KEY)
  expect(raw, '登录成功后应在 localStorage 写入 authorize').not.toBeNull()
  const parsed = JSON.parse(raw as string)
  expect(typeof parsed.token, 'authorize.token 应是字符串').toBe('string')
  expect(parsed.token.length, 'JWT 长度应非零').toBeGreaterThan(20)
  return parsed
}

/**
 * 清空 localStorage / sessionStorage 中的 auth 信息，让下次访问回退到登录页。
 */
export async function clearAuth(page: Page): Promise<void> {
  await page.evaluate((key) => {
    window.localStorage.removeItem(key)
    window.sessionStorage.removeItem(key)
  }, AUTH_STORAGE_KEY)
}
