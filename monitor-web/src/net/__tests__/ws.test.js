// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest'
import {
  buildAuthenticatedWsUrl,
  buildSftpSocketUrl,
  buildTerminalSocketUrl,
  buildWebSocketBaseUrl,
  closeManagedWebSocket,
  createManagedWebSocket
} from '@/net/ws'

describe('websocket net helpers', () => {
  it('normalizes explicit and browser-derived base URLs', () => {
    expect(buildWebSocketBaseUrl('ws://localhost:8080/')).toBe('ws://localhost:8080')
    expect(buildWebSocketBaseUrl('', { protocol: 'https:', host: 'monitor.example.com' })).toBe(
      'wss://monitor.example.com'
    )
  })

  it('builds authenticated terminal and SFTP URLs', () => {
    const options = { baseUrl: 'ws://localhost:8080/', token: 'a b' }

    expect(buildTerminalSocketUrl(42, 'terminal-1', options)).toBe(
      'ws://localhost:8080/terminal/42?token=a%20b&sessionId=terminal-1'
    )
    expect(buildSftpSocketUrl(42, 'session-1', options)).toBe(
      'ws://localhost:8080/sftp/42?token=a%20b&sessionId=session-1'
    )
  })

  it('returns null when an authenticated URL has no token', () => {
    expect(
      buildAuthenticatedWsUrl('/terminal/42', {}, { baseUrl: 'ws://localhost:8080', token: '' })
    ).toBeNull()
  })

  it('closes managed sockets and drops event handlers', () => {
    const socket = {
      onopen: vi.fn(),
      onmessage: vi.fn(),
      onerror: vi.fn(),
      onclose: vi.fn(),
      close: vi.fn()
    }

    expect(closeManagedWebSocket(socket)).toBeNull()
    expect(socket.onopen).toBeNull()
    expect(socket.onmessage).toBeNull()
    expect(socket.onerror).toBeNull()
    expect(socket.onclose).toBeNull()
    expect(socket.close).toHaveBeenCalled()
  })

  it('creates managed sockets with bound handlers', () => {
    const onopen = vi.fn()
    const onmessage = vi.fn()
    const createdSockets = []
    class MockWebSocket {
      constructor(url) {
        this.url = url
        createdSockets.push(this)
      }
    }

    const socket = createManagedWebSocket(
      'ws://localhost:8080/terminal/42',
      {
        onopen,
        onmessage
      },
      {
        WebSocketCtor: MockWebSocket
      }
    )

    expect(createdSockets).toEqual([socket])
    expect(socket.url).toBe('ws://localhost:8080/terminal/42')
    expect(socket.onopen).toBe(onopen)
    expect(socket.onmessage).toBe(onmessage)
    expect(socket.onerror).toBeNull()
    expect(socket.onclose).toBeNull()
  })
})
