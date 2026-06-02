import { get, post, put, del } from '@/net'
import { withQuery } from '@/net/query'

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
  get(withQuery(`/api/probes/${id}/history`, params), success, failure)
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
