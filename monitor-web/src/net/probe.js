import { get, post } from '@/net'
import axios from 'axios'
import { ElMessage } from 'element-plus'

const authItemName = 'authorize'

/**
 * 读取当前登录 token，仅探测模块中用于 PUT/DELETE 等 axios 直接调用场景。
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

// ============ 探测任务 CRUD ============
const listProbes = (success, failure) => get('/api/probes', success, failure)
const createProbe = (payload, success, failure) =>
  post('/api/probes', payload, success, failure)
const updateProbe = (id, payload, success, failure) =>
  put(`/api/probes/${id}`, payload, success, failure)
const deleteProbe = (id, success, failure) =>
  del(`/api/probes/${id}`, success, failure)

/**
 * 分页查询单个任务的探测历史。
 *
 * @param {number} id 任务 ID
 * @param {object} params {page, size}
 * @param {Function} success 成功回调，参数为 {records, total, page, size}
 * @param {Function} failure 失败回调
 */
function listProbeHistory(id, params, success, failure) {
  const query = Object.entries(params || {})
    .filter(([, v]) => v !== '' && v !== null && v !== undefined)
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`)
    .join('&')
  get(`/api/probes/${id}/history${query ? `?${query}` : ''}`, success, failure)
}

/** 探测类型元数据。 */
const PROBE_TYPES = [
  { value: 'http', label: 'HTTP / HTTPS' },
  { value: 'tcp', label: 'TCP 端口' },
  { value: 'icmp', label: 'ICMP / Ping' }
]

/**
 * 通过 value 查找探测类型元数据。
 *
 * @param {string} value 类型 value
 * @returns {object} 元数据对象
 */
function probeTypeMeta(value) {
  return PROBE_TYPES.find((t) => t.value === value) || { label: value }
}

export {
  listProbes,
  createProbe,
  updateProbe,
  deleteProbe,
  listProbeHistory,
  PROBE_TYPES,
  probeTypeMeta
}
