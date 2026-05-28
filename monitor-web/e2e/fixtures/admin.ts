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

/**
 * E2E admin 明文密码，CI 在 docker-compose 启动后 UPDATE 到 `account.password`。
 *
 * **长度约束**：LoginPage.vue 的 el-input 设置 {@code maxlength="20"}，超过 20
 * 字符的密码会被 Element Plus 截断（PR4 第三轮 CI 失败根因：旧值
 * "e2e-admin-password-known" 长 24 字符 → 截断为前 20 字符 "e2e-admin-password-k"
 * → BCrypt 校验失败 → 所有 Playwright 测试 401 Bad credentials）。这里固定使用
 * 19 字符明文，留 1 字符余量。
 *
 * 验证：与 ci.yml 中 python3 bcrypt 现场生成 hash 时使用的明文必须完全一致。
 */
export const ADMIN_PASSWORD = 'e2e-admin-pwd-known'

/**
 * BCrypt 哈希示例 for {@link ADMIN_PASSWORD}（rounds=10, prefix=$2a$）。
 *
 * <p><b>PR4 第三轮起 CI 不再使用此常量</b>：ci.yml 在 workflow 运行时用 python3
 * 现场生成新 hash 写入 DB（避免 YAML 单引号 / GitHub env 注入 / bash $ 展开链
 * 的任何潜伏 escape bug），所以前端 e2e 测试代码也不需要把固定 hash 与 CI 同步。
 * 本常量仅作为「BCrypt 可校验性」的文档样例保留。
 *
 * 离线生成（Python 3 + bcrypt）：
 * ```bash
 * python3 -c "import bcrypt; print(bcrypt.hashpw(b'e2e-admin-pwd-known', bcrypt.gensalt(rounds=10, prefix=b'2a')).decode())"
 * ```
 *
 * 注：BCrypt 同密码每次生成的哈希不同（salt 不同），但都能 verify 通过。
 */
export const ADMIN_BCRYPT_HASH =
  '$2a$10$42MG/4naYZgiq7gzkijS0eGTmXSPF64tQyx7s9huO98qHKWx9/24a'

/** localStorage 中 JWT 持久化 key，与 src/net/index.js authItemName 一致。 */
export const AUTH_STORAGE_KEY = 'authorize'

/**
 * UI 登录 admin 用户。调用前需保证：
 * 1. docker-compose 全栈已启动（monitor-web nginx 监听 80，monitor-server 8001）
 * 2. account.password 已被 UPDATE 为 {@link ADMIN_BCRYPT_HASH}
 *
 * <h3>诊断 CI 失败的关键改造</h3>
 *
 * PR4 第二轮 CI run（26555780399）所有 18 个 test 都 timeout 在
 * {@code page.waitForURL('/index')}，原因是 Playwright 默认 waitUntil=load
 * 对 Vue Router SPA history.pushState 不触发 load 事件。改用「监听 /api/auth/login
 * 响应」+「判断 URL 已变化」双重信号，在 30s 内无响应或非 200 时立刻把后端 status
 * + body 打到 console，方便 CI 日志定位是 RestBean.code != 200（密码不匹配 / 限流
 * / 5xx）还是网络/前端问题。
 *
 * <h3>等待策略</h3>
 *
 * 1. {@link Page#waitForResponse}（先于 click 注册）拿到 POST /api/auth/login 实际响应
 * 2. 解析 RestBean.code；非 200 抛 AssertionError 携带 status + body
 * 3. {@link Page#waitForURL} 用 {@code waitUntil: 'commit'} 跳过 load 事件等待，
 *    SPA 的 history.pushState 直接命中。
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

  // 在 click 之前注册响应监听，避免 race（click 触发的请求可能已经飞出去）
  const loginResponsePromise = page.waitForResponse(
    (response) =>
      response.url().includes('/api/auth/login') && response.request().method() === 'POST',
    { timeout: 20_000 }
  )

  // 点击 "立即登录"
  await page.getByRole('button', { name: '立即登录' }).click()

  // 等后端响应回来
  const loginResponse = await loginResponsePromise
  const status = loginResponse.status()
  let body: string
  try {
    body = await loginResponse.text()
  } catch {
    body = '<unable to read response body>'
  }

  // 后端返回 HTTP 200 + RestBean.code=200 视为登录成功；其他全部视为失败并 dump 上下文
  if (status !== 200) {
    throw new Error(
      `登录请求失败 HTTP ${status} body=${body.slice(0, 500)}`
    )
  }
  let parsed: { code?: number; message?: string; data?: unknown }
  try {
    parsed = JSON.parse(body)
  } catch {
    throw new Error(`登录响应不是合法 JSON，body=${body.slice(0, 500)}`)
  }
  if (parsed.code !== 200) {
    throw new Error(
      `登录失败 RestBean.code=${parsed.code} message=${parsed.message} body=${body.slice(0, 500)}`
    )
  }

  // 跳转到 /index/管理 tab（首次加载默认 manage）
  // SPA history.pushState 不触发 'load'；用 waitUntil: 'commit' 仅等 URL commit
  // （不等 load event，因为 router.push 不会触发 load）
  await page.waitForURL(/\/index/, {
    timeout: 15_000,
    waitUntil: 'commit'
  })
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
