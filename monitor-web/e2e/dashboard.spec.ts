import { test, expect } from '@playwright/test'
import { clearAuth, loginAsAdmin } from './fixtures/admin'

/**
 * v2.0-tests PR4：监控面板黄金路径 E2E。
 *
 * <h3>覆盖链路</h3>
 *
 * 1. 复用 login fixture 登入 admin
 * 2. Manage 页面通过 SSE `/api/sse/clients` 拉取客户端列表
 * 3. **空数据路径**（测试默认）：fresh docker-compose 启动，client 表无行 →
 *    主面板渲染 el-empty "当前无主机连接" 提示
 * 4. **有数据路径**（如未来 e2e job 注入 seed client）：点开 PreviewCard →
 *    ClientDetails 抽屉 + ECharts canvas 渲染 + 时间范围按钮存在
 *
 * <h3>断言点（空数据路径）</h3>
 *
 * - URL 在 `/index`
 * - 顶部能看到 "管理主机列表" / "添加新主机" 按钮（admin 可见）
 * - el-empty 文案出现（"当前无主机连接" / fallback "暂无数据"）
 *
 * <h3>不测的内容</h3>
 *
 * - SSE 长连接收事件后图表实时更新（异步 + 浏览器自动化时序难，留到性能档）
 * - SSH 终端（WebSocket + xterm.js 自动化最难）
 * - 30d 自定义时间范围（v2.0-frontend-ux 已限 7d 上限）
 */

test.describe('监控面板黄金路径', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/')
    await clearAuth(page)
    await loginAsAdmin(page)
  })

  test('登录后管理主机列表渲染（空数据或有数据均算通过）', async ({ page }) => {
    await expect(page).toHaveURL(/\/index/)

    // 标题 "管理主机列表" 必须出现
    await expect(page.getByText('管理主机列表')).toBeVisible({ timeout: 15_000 })

    // 副标题描述也应渲染（验证 Manage.vue 模板树完整）
    await expect(page.getByText(/在这里你可以管理你的各个服务器/)).toBeVisible()

    // 等 SSE 回 clients 事件（或 fallback timeout 让 loading=false）
    // 用 locator 替代 page.waitForFunction：等所有 .el-skeleton 元素消失（loading=false 后 v-if 干掉占位）
    // Playwright 推荐 locator-based wait，且 .el-skeleton 不存在时 count() 自然为 0 - assertion 会 pass
    await expect(page.locator('.el-skeleton')).toHaveCount(0, { timeout: 30_000 })

    // 检测客户端列表渲染状态
    // 路径 A：空数据 → el-empty
    // 路径 B：有数据 → preview-card（class .preview-card 或自定义）
    const emptyHint = page.getByText(/当前无主机连接|暂无数据/)
    const previewCards = page.locator('.card-list > *')

    const cardCount = await previewCards.count()
    if (cardCount === 0) {
      // 空数据路径：el-empty 必须显示
      await expect(emptyHint).toBeVisible()
    } else {
      // 有数据路径：至少有 1 个客户端 card
      expect(cardCount).toBeGreaterThan(0)
      // 点开第一个 card 看 ClientDetails 抽屉打开 + ECharts canvas 渲染
      await previewCards.first().click()
      // el-drawer 必须出现（class el-drawer__body）
      await expect(page.locator('.el-drawer__body').first()).toBeVisible({
        timeout: 10_000
      })
      // ClientDetails 时间范围按钮 "1 小时" "6 小时" 应存在
      await expect(page.getByRole('button', { name: /1 小时/ })).toBeVisible({
        timeout: 10_000
      })
      await expect(page.getByRole('button', { name: /6 小时/ })).toBeVisible()
      // ECharts canvas 渲染（具体数量 >=1 即可）
      const canvasCount = await page.locator('canvas').count()
      expect(canvasCount).toBeGreaterThanOrEqual(1)
    }
  })

  test('admin 可见添加新主机按钮', async ({ page }) => {
    await expect(page).toHaveURL(/\/index/)

    // role=button + name=添加新主机
    const addBtn = page.getByRole('button', { name: '添加新主机' })
    await expect(addBtn).toBeVisible({ timeout: 15_000 })
    await expect(addBtn).toBeEnabled()
  })

  test('国家筛选 checkbox 渲染', async ({ page }) => {
    await expect(page).toHaveURL(/\/index/)

    // Manage.vue 渲染 7 个 locations (cn/hk/jp/us/sg/kr/de)
    // 检查 "中国大陆" / "香港" 文案存在（任一足证 checkbox 组渲染）
    await expect(page.getByText('中国大陆')).toBeVisible({ timeout: 15_000 })
    await expect(page.getByText('美国')).toBeVisible()
  })
})
