import { get, post } from '@/net'
import axios from 'axios'
import { ElMessage } from 'element-plus'

const authItemName = 'authorize'

/**
 * 从 storage 读取当前登录 token，用于 DELETE 直接调用 axios 时附加 Authorization。
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
 * 列出当前账号的全部 API Token（不含明文）。
 *
 * @param {Function} success 成功回调，参数为 token 元数据数组
 * @param {Function} [failure] 失败回调
 */
const listTokens = (success, failure) => get('/api/tokens', success, failure)

/**
 * 创建新的 API Token。
 *
 * @param {{name: string, scope: 'readonly'|'readwrite', expiresAt?: string|null}} payload 创建请求
 * @param {Function} success 成功回调，参数为 {token: string, meta: object}；token 仅返回一次
 * @param {Function} [failure] 失败回调
 */
const createToken = (payload, success, failure) =>
  post('/api/tokens', payload, success, failure)

/**
 * 删除指定 token。
 *
 * @param {number|string} id token 主键
 * @param {Function} success 成功回调
 * @param {Function} [failure] 失败回调
 */
const deleteToken = (id, success, failure) => del(`/api/tokens/${id}`, success, failure)

/**
 * 旋转 token（删除旧 token + 按相同元数据生成新 token）。新明文同样仅返回一次。
 *
 * @param {number|string} id 旧 token 主键
 * @param {Function} success 成功回调，参数同 createToken
 * @param {Function} [failure] 失败回调
 */
const rotateToken = (id, success, failure) =>
  post(`/api/tokens/${id}/rotate`, {}, success, failure)

export { listTokens, createToken, deleteToken, rotateToken }
