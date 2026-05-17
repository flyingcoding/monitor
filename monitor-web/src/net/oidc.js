import { get, post } from '@/net'
import axios from 'axios'
import { ElMessage } from 'element-plus'

const authItemName = 'authorize'

/**
 * 从 storage 读取当前登录 token，用于 PUT/DELETE 直接调用 axios 时附加 Authorization。
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
 * 通用错误处理：将 RestBean 错误 message 透传 ElMessage 提示，并调用 failure 回调。
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
 * 通用 RestBean 响应解析。
 *
 * @param {object} response axios response
 * @param {Function} success 成功回调，参数为 data
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
 * axios PUT 请求，附带认证头并自动解析 RestBean。
 *
 * @param {string} url 请求地址
 * @param {object} data 请求体
 * @param {Function} success 成功回调
 * @param {Function} failure 失败回调
 */
function put(url, data, success, failure) {
  axios
    .put(url, data, { headers: { Authorization: `Bearer ${readToken()}` } })
    .then((response) => unwrap(response, success, failure))
    .catch(buildErrorHandler(failure))
}

/**
 * axios DELETE 请求，附带认证头并自动解析 RestBean。
 *
 * @param {string} url 请求地址
 * @param {Function} success 成功回调
 * @param {Function} failure 失败回调
 */
function del(url, success, failure) {
  axios
    .delete(url, { headers: { Authorization: `Bearer ${readToken()}` } })
    .then((response) => unwrap(response, success, failure))
    .catch(buildErrorHandler(failure))
}

/**
 * 公开 Provider 列表，不需要登录。axios 直发，避免 takeAccessToken 提示登录过期。
 *
 * @param {Function} success 成功回调，参数为 list
 * @param {Function} failure 失败回调（默认静默）
 */
function listPublicProviders(success, failure) {
  axios
    .get('/api/oidc/providers/public')
    .then((response) => {
      const body = response.data
      if (body && body.code === 200) success(body.data || [])
      else if (typeof failure === 'function') failure(body && body.message)
    })
    .catch((err) => {
      if (typeof failure === 'function') failure(err && err.message)
    })
}

// 管理员 Provider CRUD
const listProviders = (success, failure) => get('/api/oidc/providers', success, failure)
const createProvider = (payload, success, failure) =>
  post('/api/oidc/providers', payload, success, failure)
const updateProvider = (id, payload, success, failure) =>
  put(`/api/oidc/providers/${id}`, payload, success, failure)
const deleteProvider = (id, success, failure) =>
  del(`/api/oidc/providers/${id}`, success, failure)

// 个人绑定
const listBindings = (success, failure) => get('/api/oidc/bindings', success, failure)
const unbindProvider = (provider, success, failure) =>
  del(`/api/oidc/bindings/${provider}`, success, failure)
/**
 * 申请一次性"绑定意图" token（P2-2）。
 * 后端 5min TTL 关联当前 accountId；前端拿到后立刻跳转到 /api/oidc/bindings/start/{provider}?intent=...
 *
 * @param {Function} success 成功回调，参数为 { intentToken, ttlSeconds }
 * @param {Function} failure 失败回调
 */
const issueBindingIntent = (success, failure) =>
  post('/api/oidc/bindings/intent', {}, success, failure)

export {
  listPublicProviders,
  listProviders,
  createProvider,
  updateProvider,
  deleteProvider,
  listBindings,
  unbindProvider,
  issueBindingIntent
}
