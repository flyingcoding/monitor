import { get, put, publicGet } from '@/net'

/**
 * 公开状态页汇总（匿名访问，不附带 Authorization）。
 *
 * @param {Function} success 成功回调，参数为汇总 VO
 * @param {Function} failure 失败回调
 */
function fetchPublicSummary(success, failure) {
  publicGet('/api/status/summary', (data) => success(data || null), failure)
}

/**
 * 管理员读取状态页配置（含候选客户端列表）。
 *
 * @param {Function} success 成功回调
 * @param {Function} failure 失败回调
 */
const fetchAdminConfig = (success, failure) => get('/api/status/config', success, failure)

/**
 * 管理员更新状态页配置。
 *
 * @param {object} payload 更新请求 VO
 * @param {Function} success 成功回调
 * @param {Function} failure 失败回调
 */
function updateAdminConfig(payload, success, failure) {
  put('/api/status/config', payload, success, failure)
}

export { fetchPublicSummary, fetchAdminConfig, updateAdminConfig }
