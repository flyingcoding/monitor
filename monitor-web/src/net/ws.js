import { takeAccessToken } from '@/net'
import { withQuery } from '@/net/query'

/**
 * Resolve the browser location without making module import depend on `window`.
 *
 * @returns {Location|null} Current browser location when available
 */
function currentLocation() {
  return typeof window === 'undefined' ? null : window.location
}

/**
 * Build the WebSocket base URL from explicit config or the current page host.
 *
 * @param {string} baseUrl Configured WebSocket base URL
 * @param {Location|object|null} locationObj Browser location fallback
 * @returns {string} Normalized WebSocket base URL without a trailing slash
 */
function buildWebSocketBaseUrl(
  baseUrl = import.meta.env.VITE_WS_BASE_URL,
  locationObj = currentLocation()
) {
  const fallbackProtocol = locationObj?.protocol === 'https:' ? 'wss' : 'ws'
  const fallbackBaseUrl = locationObj?.host ? `${fallbackProtocol}://${locationObj.host}` : ''
  const resolvedBaseUrl = baseUrl || fallbackBaseUrl
  return resolvedBaseUrl.endsWith('/') ? resolvedBaseUrl.slice(0, -1) : resolvedBaseUrl
}

/**
 * Build an authenticated WebSocket URL with the shared access token contract.
 *
 * @param {string} path WebSocket endpoint path beginning with `/`
 * @param {object} params Extra query parameters
 * @param {object} options Optional base URL, token, and location overrides
 * @returns {string|null} Authenticated WebSocket URL, or null when no valid token exists
 */
function buildAuthenticatedWsUrl(path, params = {}, options = {}) {
  const token = Object.prototype.hasOwnProperty.call(options, 'token')
    ? options.token
    : takeAccessToken()
  if (!token) return null

  const normalizedBaseUrl = buildWebSocketBaseUrl(options.baseUrl, options.location)
  if (!normalizedBaseUrl) return null

  const normalizedPath = path.startsWith('/') ? path : `/${path}`
  return withQuery(`${normalizedBaseUrl}${normalizedPath}`, {
    token,
    ...params
  })
}

/**
 * Build the terminal shell WebSocket URL for a client.
 *
 * @param {number} clientId Client ID
 * @param {string} sessionId Terminal session ID
 * @param {object} options Optional base URL, token, and location overrides
 * @returns {string|null} Terminal WebSocket URL, or null when no token exists
 */
function buildTerminalSocketUrl(clientId, sessionId, options = {}) {
  return buildAuthenticatedWsUrl(`/terminal/${clientId}`, { sessionId }, options)
}

/**
 * Build the SFTP WebSocket URL for a client.
 *
 * @param {number} clientId Client ID
 * @param {string} sessionId Terminal session ID that owns the SFTP session
 * @param {object} options Optional base URL, token, and location overrides
 * @returns {string|null} SFTP WebSocket URL, or null when no token exists
 */
function buildSftpSocketUrl(clientId, sessionId, options = {}) {
  return buildAuthenticatedWsUrl(`/sftp/${clientId}`, { sessionId }, options)
}

/**
 * Create a WebSocket and bind handlers in one shared entry point.
 *
 * @param {string} url WebSocket URL
 * @param {object} handlers Optional onopen/onmessage/onerror/onclose handlers
 * @param {object} options Optional WebSocket constructor override for tests
 * @returns {WebSocket} Managed WebSocket instance
 */
function createManagedWebSocket(url, handlers = {}, options = {}) {
  const WebSocketCtor = options.WebSocketCtor || WebSocket
  const socket = new WebSocketCtor(url)
  socket.onopen = handlers.onopen || null
  socket.onmessage = handlers.onmessage || null
  socket.onerror = handlers.onerror || null
  socket.onclose = handlers.onclose || null
  return socket
}

/**
 * Close a WebSocket and clear handlers so late events do not mutate disposed UI state.
 *
 * @param {WebSocket|null} socket WebSocket instance to close
 * @returns {null} Null replacement for the disposed socket reference
 */
function closeManagedWebSocket(socket) {
  if (!socket) return null
  socket.onopen = null
  socket.onmessage = null
  socket.onerror = null
  socket.onclose = null
  socket.close()
  return null
}

export {
  buildWebSocketBaseUrl,
  buildAuthenticatedWsUrl,
  buildTerminalSocketUrl,
  buildSftpSocketUrl,
  createManagedWebSocket,
  closeManagedWebSocket
}
