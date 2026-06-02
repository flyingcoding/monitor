import { get, post, put, del, publicGet } from '@/net'

/**
 * 公开 Provider 列表，不需要登录。axios 直发，避免 takeAccessToken 提示登录过期。
 *
 * @param {Function} success 成功回调，参数为 list
 * @param {Function} failure 失败回调（默认静默）
 */
function listPublicProviders(success, failure) {
  publicGet('/api/oidc/providers/public', (data) => success(data || []), failure)
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
