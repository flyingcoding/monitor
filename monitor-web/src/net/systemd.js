import { get } from '@/net'
import { withQuery } from '@/net/query'

/**
 * v1.3：客户端 systemd unit 状态查询。
 * <p>
 * 通过 {@link get} 拉取指定客户端的最近一次 systemd 快照；前端 {@code SystemdServices.vue}
 * 同时建立 SSE 订阅获得实时刷新（参见 {@code /api/sse/systemd/{clientId}}）。
 */

/**
 * 拉取指定客户端最近一次 systemd unit 状态快照。
 *
 * @param {number} clientId 客户端 ID
 * @param {Function} success 成功回调，参数为 {@code SystemdSnapshotResponseVO}（含 clientId / units / updatedAt）
 * @param {Function} [failure] 失败回调
 */
function getSystemdSnapshot(clientId, success, failure) {
  return get(withQuery('/api/monitor/systemd', { clientId }), success, failure)
}

export { getSystemdSnapshot }
