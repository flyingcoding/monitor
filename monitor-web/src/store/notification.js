import { defineStore } from 'pinia'

const MAX_RECENT = 10

/**
 * 通知中心 Pinia store：聚合最近告警事件与浏览器通知权限管理。
 * recentAlerts 与 unreadCount 不持久化（刷新即清空），由 SSE 连接重建后台告警流。
 */
export const useNotificationStore = defineStore('notification', {
  state: () => ({
    /** 最近告警，最新在前，最多保留 10 条。 */
    recentAlerts: [],
    /** 未读告警数量，进入告警 tab 或手动清空时归零。 */
    unreadCount: 0,
    /** 浏览器 Notification API 权限状态：default/granted/denied/unsupported。 */
    permission: typeof Notification !== 'undefined' ? Notification.permission : 'unsupported'
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
     * 清空所有状态，登出时调用。
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
     * 在权限允许时弹出浏览器通知，仅 critical/warning 级别推送。
     *
     * @param {object} alert AlertHistoryVO
     */
    tryNotify(alert) {
      if (typeof Notification === 'undefined') return
      if (this.permission !== 'granted') return
      if (alert.level === 'info') return
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
  }
})
