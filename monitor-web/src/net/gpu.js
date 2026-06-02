import { get } from '@/net'
import { withQuery } from '@/net/query'

/**
 * 加载指定客户端的最新 NVIDIA GPU 快照。
 *
 * @param {number} clientId 客户端ID
 * @param {Function} success 成功回调，参数为快照对象（可能为 null）
 * @param {Function} [failure] 失败回调
 */
export function fetchGpuSnapshot(clientId, success, failure) {
  get(withQuery('/api/monitor/gpu', { clientId }), success, failure)
}
