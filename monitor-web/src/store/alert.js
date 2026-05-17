import { defineStore } from 'pinia'

/**
 * 告警筛选状态 Pinia store：保留筛选条件以便切换路由后回到历史页保留上下文。
 * 列表数据不放 store，由页面组件按筛选条件请求后保存到组件内。
 */
export const useAlertStore = defineStore('alert', {
  state: () => ({
    filters: {
      clientId: null,
      level: '',
      status: '',
      from: null,
      to: null
    },
    page: 1,
    size: 20
  }),
  actions: {
    /**
     * 重置筛选器和分页到初始状态。
     */
    resetFilters() {
      this.filters = { clientId: null, level: '', status: '', from: null, to: null }
      this.page = 1
      this.size = 20
    }
  }
})
