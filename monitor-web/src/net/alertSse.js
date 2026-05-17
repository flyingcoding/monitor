import { takeAccessToken } from '@/net'

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
  const token = takeAccessToken()
  if (!token) return
  manuallyClosed = false
  const baseUrl = import.meta.env.VITE_API_BASE_URL || ''
  alertEventSource = new EventSource(`${baseUrl}/api/sse/alerts?token=${token}`)
  alertEventSource.addEventListener('alert-fired', (event) => {
    try {
      const data = JSON.parse(event.data)
      if (typeof alertHandler === 'function') alertHandler(data)
    } catch (e) {
      console.warn('告警事件解析失败', e)
    }
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
