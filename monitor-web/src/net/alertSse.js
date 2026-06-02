import { createAuthenticatedEventSource, parseSseJson } from '@/net/sse'

let alertEventSource = null
let retryDelay = 1000
const MAX_DELAY = 60000
let alertHandler = null
let manuallyClosed = false

/**
 * 建立告警事件 SSE 连接，断线时按指数退避自动重连，最大 60s 间隔。
 *
 * @param {Function} onAlert 收到 alert-fired 事件时的回调，参数为 AlertHistoryVO
 */
function connectAlertSse(onAlert) {
  if (typeof onAlert === 'function') alertHandler = onAlert
  if (alertEventSource) return
  manuallyClosed = false
  alertEventSource = createAuthenticatedEventSource('/api/sse/alerts')
  if (!alertEventSource) return
  alertEventSource.addEventListener('alert-fired', (event) => {
    const data = parseSseJson(event)
    if (!data) return
    if (typeof alertHandler === 'function') alertHandler(data)
    retryDelay = 1000
  })
  alertEventSource.onerror = () => {
    if (alertEventSource) {
      alertEventSource.close()
      alertEventSource = null
    }
    if (manuallyClosed) return
    setTimeout(() => {
      if (!manuallyClosed) connectAlertSse(alertHandler)
    }, retryDelay)
    retryDelay = Math.min(retryDelay * 2, MAX_DELAY)
  }
}

/**
 * 主动关闭告警 SSE 连接，登出或组件卸载时调用以避免重连。
 */
function closeAlertSse() {
  manuallyClosed = true
  if (alertEventSource) {
    alertEventSource.close()
    alertEventSource = null
  }
  retryDelay = 1000
}

export { connectAlertSse, closeAlertSse }
