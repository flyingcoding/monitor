import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createAuthenticatedEventSource, createReconnectingEventSource, parseSseJson } from '@/net/sse'

const netMock = vi.hoisted(() => ({
  takeAccessToken: vi.fn()
}))

vi.mock('@/net', () => ({
  takeAccessToken: netMock.takeAccessToken
}))

class FakeEventSource {
  static instances = []

  constructor(url) {
    this.url = url
    this.closed = false
    this.listeners = {}
    this.onerror = null
    FakeEventSource.instances.push(this)
  }

  /**
   * 记录业务事件监听器，测试通过 emit 主动触发。
   *
   * @param {string} name 事件名
   * @param {Function} handler 事件处理器
   */
  addEventListener(name, handler) {
    this.listeners[name] = handler
  }

  /**
   * 触发指定 SSE 事件。
   *
   * @param {string} name 事件名
   * @param {*} payload 事件数据
   */
  emit(name, payload) {
    this.listeners[name]({ data: JSON.stringify(payload) })
  }

  /**
   * 标记连接已关闭。
   */
  close() {
    this.closed = true
  }
}

describe('sse helpers', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    netMock.takeAccessToken.mockReset()
    netMock.takeAccessToken.mockReturnValue('token with space')
    FakeEventSource.instances = []
    globalThis.EventSource = FakeEventSource
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
    delete globalThis.EventSource
  })

  it('creates an EventSource with encoded auth token', () => {
    const source = createAuthenticatedEventSource('/api/sse/example?clientId=1')

    expect(source).toBeInstanceOf(FakeEventSource)
    expect(source.url).toBe('/api/sse/example?clientId=1&token=token%20with%20space')
  })

  it('parses JSON event data and returns fallback on invalid payload', () => {
    expect(parseSseJson({ data: '{"ok":true}' })).toEqual({ ok: true })
    expect(parseSseJson({ data: '{bad json}' }, 'fallback')).toBe('fallback')
  })

  it('reconnects with exponential backoff and clears pending reconnects on close', () => {
    const onMessage = vi.fn()
    const controller = createReconnectingEventSource({
      path: () => '/api/sse/reconnect',
      eventName: 'snapshot',
      onMessage
    })

    controller.connect()
    expect(FakeEventSource.instances).toHaveLength(1)

    FakeEventSource.instances[0].emit('snapshot', { id: 1 })
    expect(onMessage).toHaveBeenCalledWith({ id: 1 }, expect.any(Object))

    FakeEventSource.instances[0].onerror({})
    expect(FakeEventSource.instances[0].closed).toBe(true)
    vi.advanceTimersByTime(999)
    expect(FakeEventSource.instances).toHaveLength(1)
    vi.advanceTimersByTime(1)
    expect(FakeEventSource.instances).toHaveLength(2)

    FakeEventSource.instances[1].onerror({})
    controller.close()
    vi.advanceTimersByTime(1000)
    expect(FakeEventSource.instances).toHaveLength(2)
  })
})
