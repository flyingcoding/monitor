import { get, post, del } from '@/net'

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
