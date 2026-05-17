import { get } from '@/net'
import axios from 'axios'
import { ElMessage } from 'element-plus'

const authItemName = 'authorize'

/**
 * 读取登录态 token（与 @/net 内的取值方式一致），用于 PUT 直接调用 axios 的 admin 接口。
 *
 * @returns {string|null} JWT token 或 null
 */
function readToken() {
  const str = localStorage.getItem(authItemName) || sessionStorage.getItem(authItemName)
  if (!str) return null
  try {
    return JSON.parse(str).token
  } catch (_e) {
    return null
  }
}

/**
 * 统一 axios 错误处理：将 RestBean message 透传 ElMessage 提示。
 *
 * @param {Function} failure 失败回调
 * @returns {Function} axios catch handler
 */
function buildErrorHandler(failure) {
  return (error) => {
    const data = error.response && error.response.data
    if (data && data.message) {
      ElMessage.warning(data.message)
    } else {
      ElMessage.error('请求失败，请稍后重试')
    }
    if (typeof failure === 'function') failure()
  }
}

/**
 * 解析 RestBean 响应。
 *
 * @param {object} response axios response
 * @param {Function} success 成功回调
 * @param {Function} failure 失败回调
 */
function unwrap(response, success, failure) {
  const body = response.data
  if (body && body.code === 200) {
    success(body.data)
  } else {
    const message = body ? body.message : '请求失败'
    ElMessage.warning(message)
    if (typeof failure === 'function') failure(message)
  }
}

/**
 * 公开状态页汇总（匿名访问，不附带 Authorization）。
 *
 * @param {Function} success 成功回调，参数为汇总 VO
 * @param {Function} failure 失败回调
 */
function fetchPublicSummary(success, failure) {
  axios
    .get('/api/status/summary')
    .then((response) => {
      const body = response.data
      if (body && body.code === 200) {
        success(body.data || null)
      } else if (typeof failure === 'function') {
        failure(body && body.message)
      }
    })
    .catch((err) => {
      if (typeof failure === 'function') failure(err && err.message)
    })
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
  axios
    .put('/api/status/config', payload, {
      headers: { Authorization: `Bearer ${readToken()}` }
    })
    .then((response) => unwrap(response, success, failure))
    .catch(buildErrorHandler(failure))
}

export { fetchPublicSummary, fetchAdminConfig, updateAdminConfig }
