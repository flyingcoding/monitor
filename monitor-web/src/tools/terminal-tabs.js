/**
 * 生成终端会话 ID；只用于前端区分同一主机的多个 shell 会话。
 *
 * @returns {string} 终端会话 ID
 */
function createTerminalSessionId() {
  return `term-${Date.now()}-${Math.random().toString(16).slice(2, 8)}`
}

/**
 * 创建一个终端 Tab 状态对象。
 *
 * @param {number} clientId 主机 ID
 * @param {number} index 当前主机的会话序号
 * @param {Function} [sessionIdFactory] 会话 ID 工厂，测试时可注入
 * @returns {object} 终端 Tab
 */
function createTerminalTab(clientId, index, sessionIdFactory = createTerminalSessionId) {
  const sessionId = sessionIdFactory()
  return {
    name: sessionId,
    sessionId,
    clientId,
    title: `主机 #${clientId} · ${index}`,
    panel: 'terminal',
    state: 1,
    loading: true,
    connection: {
      ip: '',
      port: 22,
      username: '',
      password: ''
    }
  }
}

/**
 * 计算关闭 Tab 后应该激活的 Tab 名称。
 *
 * @param {Array<{name: string}>} tabs 关闭前的 Tab 列表
 * @param {string} closedName 被关闭的 Tab 名称
 * @param {string} activeName 当前激活 Tab 名称
 * @returns {string} 新激活 Tab 名称；无 Tab 时返回空字符串
 */
function resolveNextActiveTabName(tabs, closedName, activeName) {
  if (activeName !== closedName) return activeName
  const index = tabs.findIndex((tab) => tab.name === closedName)
  const next = tabs[index + 1] || tabs[index - 1]
  return next ? next.name : ''
}

/**
 * 计算指定主机下一次创建 Tab 时的序号。
 *
 * @param {Array<{clientId: number}>} tabs 当前 Tab 列表
 * @param {number} clientId 主机 ID
 * @returns {number} 下一个会话序号
 */
function nextClientSessionIndex(tabs, clientId) {
  return tabs.filter((tab) => tab.clientId === clientId).length + 1
}

export { createTerminalSessionId, createTerminalTab, resolveNextActiveTabName, nextClientSessionIndex }
