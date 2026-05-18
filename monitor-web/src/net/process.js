import { get } from '@/net'

/**
 * v1.3：拉取指定主机的最新进程快照。
 *
 * 调用 `GET /api/monitor/process?clientId=...`。无采集 / 缓存过期时返回 null。
 *
 * @param {number} clientId 主机ID
 * @param {(data: any) => void} success 成功回调，data 为 ProcessSnapshotResponseVO 或 null
 * @param {(message: string, status: number, url: string) => void} [failure] 失败回调
 */
export function getProcessSnapshot(clientId, success, failure) {
  get(`/api/monitor/process?clientId=${clientId}`, success, failure)
}
