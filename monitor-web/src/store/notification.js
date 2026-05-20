import { defineStore } from 'pinia'

const MAX_RECENT = 10

/** 等级 → 数值优先级，数值越大越严重。tryNotify 据此判断是否达到 settings.minLevel 阈值。 */
const LEVEL_RANK = { info: 0, warning: 1, critical: 2 }

/**
 * 把任意 level 字符串归一为数值优先级。未知 level 视为最低（不触发通知）。
 *
 * @param {string} level 告警等级
 * @returns {number} 数值优先级
 */
function rankOf(level) {
  return Object.prototype.hasOwnProperty.call(LEVEL_RANK, level) ? LEVEL_RANK[level] : -1
}

/**
 * 通知中心 Pinia store：聚合最近告警事件与浏览器通知权限管理。
 * recentAlerts 与 unreadCount 不持久化（刷新即清空），由 SSE 连接重建后台告警流；
 * permission 由浏览器维护，每次启动从 Notification.permission 读取；
 * 仅 settings（用户偏好）通过 pinia-plugin-persistedstate 持久化。
 */
export const useNotificationStore = defineStore('notification', {
  state: () => ({
    /** 最近告警，最新在前，最多保留 10 条。 */
    recentAlerts: [],
    /** 未读告警数量，进入告警 tab 或手动清空时归零。 */
    unreadCount: 0,
    /** 浏览器 Notification API 权限状态：default/granted/denied/unsupported。 */
    permission: typeof Notification !== 'undefined' ? Notification.permission : 'unsupported',
    /** 用户偏好：是否启用浏览器通知 + 最低弹窗等级。persistedstate 仅持久化此字段。 */
    settings: {
      enabled: true,
      minLevel: 'warning'
    }
  }),
  actions: {
    /**
     * 追加新告警到列表顶部，并触发浏览器通知（如权限允许）。
     *
     * @param {object} alert AlertHistoryVO 形态对象
     */
    pushAlert(alert) {
      if (!alert) return
      this.recentAlerts.unshift(alert)
      if (this.recentAlerts.length > MAX_RECENT) {
        this.recentAlerts.splice(MAX_RECENT)
      }
      this.unreadCount += 1
      this.tryNotify(alert)
    },
    /**
     * 重置未读计数，通常在用户查看通知列表后调用。
     */
    clearUnread() {
      this.unreadCount = 0
    },
    /**
     * 清空运行时状态（告警列表 + 未读数），登出时调用。
     * 不重置 settings 与 permission：用户偏好与浏览器权限独立于会话生命周期。
     */
    reset() {
      this.recentAlerts = []
      this.unreadCount = 0
    },
    /**
     * 请求浏览器通知权限并更新本地权限状态。
     *
     * @returns {Promise<string>} 解析后的权限字符串
     */
    async requestPermission() {
      if (typeof Notification === 'undefined') {
        this.permission = 'unsupported'
        return this.permission
      }
      try {
        const result = await Notification.requestPermission()
        this.permission = result
        return result
      } catch (_e) {
        this.permission = 'denied'
        return this.permission
      }
    },
    /**
     * 在权限允许且符合用户偏好时弹出浏览器通知。
     * 静默退出条件（任一命中即跳过）：
     * - 浏览器不支持 Notification API
     * - 浏览器权限未授予
     * - settings.enabled 为 false（用户在偏好中关闭）
     * - 告警等级低于 settings.minLevel（用户调高了过滤阈值）
     *
     * @param {object} alert AlertHistoryVO
     */
    tryNotify(alert) {
      if (typeof Notification === 'undefined') return
      if (this.permission !== 'granted') return
      if (!this.settings || this.settings.enabled === false) return
      const threshold = rankOf(this.settings.minLevel || 'warning')
      if (rankOf(alert.level) < threshold) return
      try {
        const title = `[${this.levelText(alert.level)}] 告警触发`
        const body = alert.message || `规则 ${alert.ruleName || alert.ruleId} 触发`
        new Notification(title, { body, tag: `alert-${alert.id}` })
      } catch (e) {
        console.warn('浏览器通知发送失败', e)
      }
    },
    /**
     * 等级 → 中文映射，用于浏览器通知标题。
     *
     * @param {string} level 告警等级
     * @returns {string} 中文等级
     */
    levelText(level) {
      const map = { info: '信息', warning: '警告', critical: '严重' }
      return map[level] || level
    }
  },
  persist: {
    paths: ['settings']
  }
})
