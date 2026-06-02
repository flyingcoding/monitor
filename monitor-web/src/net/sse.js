import { takeAccessToken } from '@/net'

const DEFAULT_RETRY_DELAY = 1000
const DEFAULT_MAX_RETRY_DELAY = 60000

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

/**
 * 创建带指数退避重连和关闭清理能力的 SSE 控制器。
 *
 * @param {object} options SSE 配置
 * @param {string|Function} options.path SSE 地址路径，支持函数以便重连时读取最新 id
 * @param {string} options.eventName 业务事件名
 * @param {Function} options.onMessage 成功解析 JSON 后的业务回调
 * @param {Function} [options.shouldReconnect] 是否允许继续重连
 * @param {Function} [options.onError] 连接错误回调
 * @param {Function} [options.onUnavailable] 无 token 或无法创建连接时的回调
 * @param {number} [options.initialDelay] 初始重连延迟
 * @param {number} [options.maxDelay] 最大重连延迟
 * @returns {{connect: Function, close: Function, isActive: Function}} SSE 控制器
 */
function createReconnectingEventSource(options) {
  const {
    path,
    eventName,
    onMessage,
    shouldReconnect = () => true,
    onError,
    onUnavailable,
    initialDelay = DEFAULT_RETRY_DELAY,
    maxDelay = DEFAULT_MAX_RETRY_DELAY
  } = options
  let eventSource = null
  let reconnectTimer = null
  let retryDelay = initialDelay
  let closed = true

  /**
   * 解析当前连接路径。
   */
  function resolvePath() {
    return typeof path === 'function' ? path() : path
  }

  /**
   * 清理等待中的重连 timer。
   */
  function clearReconnectTimer() {
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
  }

  /**
   * 关闭当前 EventSource 实例。
   */
  function closeEventSource() {
    if (eventSource) {
      eventSource.close()
      eventSource = null
    }
  }

  /**
   * 按当前 retryDelay 安排下一次重连。
   */
  function scheduleReconnect() {
    if (closed || !shouldReconnect()) return
    const delay = retryDelay
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null
      if (!closed && shouldReconnect()) connect()
    }, delay)
    retryDelay = Math.min(retryDelay * 2, maxDelay)
  }

  /**
   * 建立 SSE 连接；重复调用会先清理旧连接和旧重连 timer。
   */
  function connect() {
    closed = false
    clearReconnectTimer()
    closeEventSource()

    const nextPath = resolvePath()
    if (!nextPath) return null

    eventSource = createAuthenticatedEventSource(nextPath)
    if (!eventSource) {
      if (typeof onUnavailable === 'function') onUnavailable()
      return null
    }

    eventSource.addEventListener(eventName, (event) => {
      const data = parseSseJson(event)
      if (!data) return
      onMessage(data, event)
      retryDelay = initialDelay
    })
    eventSource.onerror = (event) => {
      closeEventSource()
      if (typeof onError === 'function') onError(event)
      scheduleReconnect()
    }
    return eventSource
  }

  /**
   * 主动关闭连接并阻止等待中的重连。
   */
  function close() {
    closed = true
    clearReconnectTimer()
    closeEventSource()
    retryDelay = initialDelay
  }

  /**
   * 当前是否存在活动 EventSource。
   */
  function isActive() {
    return !!eventSource
  }

  return { connect, close, isActive }
}

export { createAuthenticatedEventSource, parseSseJson, createReconnectingEventSource }
