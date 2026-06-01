import { test, expect } from '@playwright/test'
import {
  AUTH_STORAGE_KEY,
  clearAuth,
  loginAsAdmin,
  readPersistedAuth
} from './fixtures/admin'

/**
 * v2.0-tests PR4：登录黄金路径 E2E。
 *
 * <h3>覆盖链路</h3>
 *
 * 1. 访问 `/`（WelcomeView + LoginPage 嵌套路由）
 * 2. 输入 admin / 已知明文密码
 * 3. 点击 "立即登录" 触发 POST `/api/auth/login`（被 nginx 反代到 monitor-server:80）
 * 4. 后端校验 BCrypt + 返回 RestBean<AuthorizeVO> { token, expire, role, username, email }
 * 5. 前端 storeAccessToken 写入 localStorage / sessionStorage 的 authorize 键
 * 6. router.push('/index') 跳转主面板
 *
 * <h3>断言点</h3>
 *
 * - URL 从 `/` 跳到 `/index`（或 /index/ 末尾斜杠，由 Vue Router 决定）
 * - localStorage.authorize 写入合法 JSON（含 token + expire）
 * - 管理主面板渲染（出现 "管理主机列表" 标题）
 */

test.describe('登录黄金路径', () => {
  // 覆盖 project 级 storageState：这组测试要验证真实登录流程，必须从登出态开始。
  // 空 storageState（无 cookies / origins）等效未登录，否则 setup 注入的 JWT 会让
  // beforeEach 一进 '/' 就被 router 守卫跳走 /index，"表单登录" 用例无从测起。
  test.use({ storageState: { cookies: [], origins: [] } })

  test.beforeEach(async ({ page }) => {
    // 起点：保证未登录态。先访问根路径让 storage API 可用，再清掉历史 auth。
    await page.goto('/')
    await clearAuth(page)
  })

  test('admin 表单登录成功并持久化 JWT 到 localStorage', async ({ page }) => {
    await loginAsAdmin(page)

    // 主面板 URL 必须在 /index 下
    await expect(page).toHaveURL(/\/index/, { timeout: 15_000 })

    // localStorage 已写入 authorize（{token, expire}）
    const auth = await readPersistedAuth(page)
    // 后端 AuthorizeVO.expire 序列化为 "yyyy-MM-dd HH:mm:ss.SSS"（空格分隔，非 ISO 带 T）。
    // 这是 app 既有契约——src/net/index.js takeAccessToken 一直用 new Date(authObj.expire)
    // 消费此格式。断言对齐真实格式，接受空格或 T 分隔。
    expect(auth.expire, 'authorize.expire 应是时间戳字符串').toMatch(
      /^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}/
    )
    // expire 必须晚于当前时间。空格分隔日期在 WebKit/Safari 下 new Date() 解析不可靠，
    // 替换为 T 后再解析以保证三浏览器一致；并断言解析结果是合法日期。
    const expireMs = new Date(auth.expire.replace(' ', 'T')).getTime()
    expect(Number.isNaN(expireMs), 'expire 应能解析为合法日期').toBe(false)
    expect(expireMs).toBeGreaterThan(Date.now())

    // 主面板渲染：管理 tab 默认激活，应能看到 "管理主机列表" 标题
    await expect(page.getByText('管理主机列表')).toBeVisible({ timeout: 15_000 })
  })

  test('错误密码登录失败且不跳转', async ({ page }) => {
    // 直接在登录页操作，不使用 loginAsAdmin（后者会等跳转）
    await page.getByPlaceholder('用户名/邮箱').fill('admin')
    await page.getByPlaceholder('密码').fill('definitely-wrong-password')
    await page.getByRole('button', { name: '立即登录' }).click()

    // 等业务响应处理完。后端 RestBean.code != 200 时前端 defaultFailure 走 ElMessage.warning
    // 不会跳转 /index，URL 必须保持在登录路由（'/'）。等 2s 给后端响应时间。
    await page.waitForTimeout(2_000)

    // URL 不应跳到 /index
    expect(page.url()).not.toMatch(/\/index/)

    // localStorage 不应写入 authorize
    const raw = await page.evaluate(
      (key) => window.localStorage.getItem(key),
      AUTH_STORAGE_KEY
    )
    expect(raw, '密码错误不应写入 authorize').toBeNull()
  })

  test('已登录访问根路径自动跳到 /index', async ({ page }) => {
    // 先登录
    await loginAsAdmin(page)
    await expect(page).toHaveURL(/\/index/)

    // 主动导航回根路径，router beforeEach 应识别已登录态并跳回 /index
    await page.goto('/')
    await expect(page).toHaveURL(/\/index/, { timeout: 10_000 })
  })
})
