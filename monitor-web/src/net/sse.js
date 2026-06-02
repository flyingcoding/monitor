import { takeAccessToken } from '@/net'

/**
 * 创建携带当前登录 token 的 SSE 连接。
 *
 * @param {string} path SSE 接口路径，例如 /api/sse/clients
 * @returns {EventSource|null} EventSource 实例；无有效 token 时返回 null
 */
function createAuthenticatedEventSource(path) {
  const token = takeAccessToken()
  if (!token) return null
  const baseUrl = import.meta.env.VITE_API_BASE_URL || ''
  const separator = path.includes('?') ? '&' : '?'
  return new EventSource(`${baseUrl}${path}${separator}token=${encodeURIComponent(token)}`)
}

/**
 * 解析 SSE JSON 数据，解析失败时返回 fallback。
 *
 * @param {MessageEvent} event SSE message event
 * @param {*} fallback 解析失败时返回的值
 * @returns {*} JSON 解析结果或 fallback
 */
function parseSseJson(event, fallback = null) {
  try {
    return JSON.parse(event.data)
  } catch (_e) {
    return fallback
  }
}

export { createAuthenticatedEventSource, parseSseJson }
