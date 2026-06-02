import { createReconnectingEventSource } from '@/net/sse'

let alertHandler = null
let manuallyClosed = false

const alertSse = createReconnectingEventSource({
  path: '/api/sse/alerts',
  eventName: 'alert-fired',
  shouldReconnect: () => !manuallyClosed,
  onMessage: (data) => {
    if (typeof alertHandler === 'function') alertHandler(data)
  }
})

/**
 * 建立告警事件 SSE 连接，断线时按指数退避自动重连，最大 60s 间隔。
 *
 * @param {Function} onAlert 收到 alert-fired 事件时的回调，参数为 AlertHistoryVO
 */
function connectAlertSse(onAlert) {
  if (typeof onAlert === 'function') alertHandler = onAlert
  if (alertSse.isActive()) return
  manuallyClosed = false
  alertSse.connect()
}

/**
 * 主动关闭告警 SSE 连接，登出或组件卸载时调用以避免重连。
 */
function closeAlertSse() {
  manuallyClosed = true
  alertSse.close()
}

export { connectAlertSse, closeAlertSse }
