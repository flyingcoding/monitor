import { get, post } from '@/net'
import axios from 'axios'
import { ElMessage } from 'element-plus'

const authItemName = 'authorize'

/**
 * 读取当前登录 token，仅 alert 模块中用于 PUT/DELETE 等 axios 直接调用场景。
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

// ============ 告警规则 ============
const listAlertRules = (success, failure) => get('/api/alert/rule', success, failure)
const getAlertRule = (id, success, failure) => get(`/api/alert/rule/${id}`, success, failure)
const createAlertRule = (payload, success, failure) =>
  post('/api/alert/rule', payload, success, failure)
const updateAlertRule = (id, payload, success, failure) =>
  put(`/api/alert/rule/${id}`, payload, success, failure)
const deleteAlertRule = (id, success, failure) => del(`/api/alert/rule/${id}`, success, failure)
const silenceAlertRule = (id, minutes, success, failure) =>
  post(`/api/alert/rule/${id}/silence?minutes=${minutes}`, {}, success, failure)

// ============ 告警历史 ============
/**
 * 列表查询；params 内字段：page,size,clientId,level,status,from,to。
 *
 * @param {object} params 查询参数
 * @param {Function} success 成功回调
 * @param {Function} failure 失败回调
 */
function listAlertHistory(params, success, failure) {
  const query = Object.entries(params)
    .filter(([_, v]) => v !== '' && v !== null && v !== undefined)
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`)
    .join('&')
  get(`/api/alert/history${query ? `?${query}` : ''}`, success, failure)
}
const getAlertHistory = (id, success, failure) =>
  get(`/api/alert/history/${id}`, success, failure)
const ackAlertHistory = (id, success, failure) =>
  post(`/api/alert/history/${id}/ack`, {}, success, failure)
const closeAlertHistory = (id, success, failure) =>
  post(`/api/alert/history/${id}/close`, {}, success, failure)

// ============ 通知通道 ============
const listChannels = (success, failure) => get('/api/notification/channel', success, failure)
const getChannel = (id, success, failure) =>
  get(`/api/notification/channel/${id}`, success, failure)
const createChannel = (payload, success, failure) =>
  post('/api/notification/channel', payload, success, failure)
const updateChannel = (id, payload, success, failure) =>
  put(`/api/notification/channel/${id}`, payload, success, failure)
const deleteChannel = (id, success, failure) =>
  del(`/api/notification/channel/${id}`, success, failure)
const testChannel = (id, success, failure) =>
  post(`/api/notification/channel/${id}/test`, {}, success, failure)

export {
  listAlertRules,
  getAlertRule,
  createAlertRule,
  updateAlertRule,
  deleteAlertRule,
  silenceAlertRule,
  listAlertHistory,
  getAlertHistory,
  ackAlertHistory,
  closeAlertHistory,
  listChannels,
  getChannel,
  createChannel,
  updateChannel,
  deleteChannel,
  testChannel
}
