import { get } from '@/net'
import { withQuery } from '@/net/query'

/**
 * 加载指定客户端的最新 SMART 磁盘健康快照。
 *
 * @param {number} clientId 客户端ID
 * @param {Function} success 成功回调，参数为快照对象（可能为 null）
 * @param {Function} [failure] 失败回调
 */
export function fetchSmartSnapshot(clientId, success, failure) {
  get(withQuery('/api/monitor/smart', { clientId }), success, failure)
}
