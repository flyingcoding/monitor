import { afterEach, describe, expect, it, vi } from 'vitest'
import { storeAccessToken, takeAccessToken } from '@/net'
import { ElMessage } from 'element-plus'

vi.mock('element-plus', () => ({
  ElMessage: {
    warning: vi.fn()
  }
}))

function createStorageMock() {
  const store = new Map()
  return {
    getItem: vi.fn((key) => (store.has(key) ? store.get(key) : null)),
    setItem: vi.fn((key, value) => store.set(key, value)),
    removeItem: vi.fn((key) => store.delete(key)),
    clear: vi.fn(() => store.clear())
  }
}

describe('storeAccessToken', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('writes remembered login token to localStorage with the authorize key', () => {
    const localStorage = createStorageMock()
    const sessionStorage = createStorageMock()
    vi.stubGlobal('localStorage', localStorage)
    vi.stubGlobal('sessionStorage', sessionStorage)

    storeAccessToken(true, 'token-1', '2099-01-01 00:00:00.000')

    expect(localStorage.setItem).toHaveBeenCalledWith(
      'authorize',
      JSON.stringify({ token: 'token-1', expire: '2099-01-01 00:00:00.000' })
    )
    expect(sessionStorage.removeItem).toHaveBeenCalledWith('authorize')
    expect(sessionStorage.setItem).not.toHaveBeenCalled()
  })

  it('writes non-remembered login token to sessionStorage with the same payload shape', () => {
    const localStorage = createStorageMock()
    const sessionStorage = createStorageMock()
    vi.stubGlobal('localStorage', localStorage)
    vi.stubGlobal('sessionStorage', sessionStorage)

    storeAccessToken(false, 'token-2', '2099-01-02 00:00:00.000')

    expect(sessionStorage.setItem).toHaveBeenCalledWith(
      'authorize',
      JSON.stringify({ token: 'token-2', expire: '2099-01-02 00:00:00.000' })
    )
    expect(localStorage.removeItem).toHaveBeenCalledWith('authorize')
    expect(localStorage.setItem).not.toHaveBeenCalled()
  })

  it('prevents stale remembered token from shadowing a new session token', () => {
    const localStorage = createStorageMock()
    const sessionStorage = createStorageMock()
    vi.stubGlobal('localStorage', localStorage)
    vi.stubGlobal('sessionStorage', sessionStorage)

    storeAccessToken(true, 'old-local-token', '2099-01-01')
    storeAccessToken(false, 'new-session-token', '2099-01-02')

    expect(takeAccessToken()).toBe('new-session-token')
  })
})

describe('takeAccessToken', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('returns token from valid storage payload', () => {
    const localStorage = createStorageMock()
    const sessionStorage = createStorageMock()
    localStorage.setItem('authorize', JSON.stringify({ token: 'token-1', expire: '2099-01-01' }))
    vi.stubGlobal('localStorage', localStorage)
    vi.stubGlobal('sessionStorage', sessionStorage)

    expect(takeAccessToken()).toBe('token-1')
  })

  it('accepts backend space-separated expire format', () => {
    const localStorage = createStorageMock()
    const sessionStorage = createStorageMock()
    localStorage.setItem(
      'authorize',
      JSON.stringify({ token: 'token-space-date', expire: '2099-01-01 00:00:00.000' })
    )
    vi.stubGlobal('localStorage', localStorage)
    vi.stubGlobal('sessionStorage', sessionStorage)

    expect(takeAccessToken()).toBe('token-space-date')
  })

  it('clears invalid storage payload and returns null', () => {
    const localStorage = createStorageMock()
    const sessionStorage = createStorageMock()
    localStorage.setItem('authorize', '{bad-json')
    vi.stubGlobal('localStorage', localStorage)
    vi.stubGlobal('sessionStorage', sessionStorage)

    expect(takeAccessToken()).toBeNull()
    expect(localStorage.removeItem).toHaveBeenCalledWith('authorize')
    expect(sessionStorage.removeItem).toHaveBeenCalledWith('authorize')
  })

  it('clears malformed expire payload and returns null', () => {
    const localStorage = createStorageMock()
    const sessionStorage = createStorageMock()
    localStorage.setItem('authorize', JSON.stringify({ token: 'token-2', expire: 'not-a-date' }))
    vi.stubGlobal('localStorage', localStorage)
    vi.stubGlobal('sessionStorage', sessionStorage)

    expect(takeAccessToken()).toBeNull()
    expect(localStorage.removeItem).toHaveBeenCalledWith('authorize')
    expect(sessionStorage.removeItem).toHaveBeenCalledWith('authorize')
  })

  it('clears expired storage payload and returns null', () => {
    const localStorage = createStorageMock()
    const sessionStorage = createStorageMock()
    sessionStorage.setItem('authorize', JSON.stringify({ token: 'token-2', expire: '2000-01-01' }))
    vi.stubGlobal('localStorage', localStorage)
    vi.stubGlobal('sessionStorage', sessionStorage)

    expect(takeAccessToken()).toBeNull()
    expect(localStorage.removeItem).toHaveBeenCalledWith('authorize')
    expect(sessionStorage.removeItem).toHaveBeenCalledWith('authorize')
    expect(ElMessage.warning).toHaveBeenCalledWith('登录状态已过期，请重新登录！')
  })
})
