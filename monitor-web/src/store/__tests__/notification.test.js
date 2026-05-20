// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useNotificationStore } from '@/store/notification'

describe('useNotificationStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    // 干净 localStorage，避免 persistedstate 残留
    if (typeof window !== 'undefined' && window.localStorage) {
      window.localStorage.clear()
    }
  })

  afterEach(() => {
    vi.restoreAllMocks()
    // 清理可能被替换的全局 Notification mock
    if ('Notification' in globalThis) {
      delete globalThis.Notification
    }
  })

  /**
   * 在 globalThis 上挂一个 Notification 构造器 mock，并设置静态 permission/requestPermission。
   *
   * @param {object} opts permission / requestPermissionImpl 覆盖项
   * @returns {Function} 构造器 spy，可用于断言调用次数
   */
  function installNotificationMock(opts = {}) {
    const ctorSpy = vi.fn()
    const requestPermissionImpl =
      opts.requestPermissionImpl || vi.fn().mockResolvedValue(opts.permission || 'granted')

    function FakeNotification(title, options) {
      ctorSpy(title, options)
    }
    FakeNotification.permission = opts.permission || 'granted'
    FakeNotification.requestPermission = requestPermissionImpl
    globalThis.Notification = FakeNotification
    return ctorSpy
  }

  it('initial state has empty alerts, zero unread, and default settings', () => {
    installNotificationMock({ permission: 'default' })
    const store = useNotificationStore()
    expect(store.recentAlerts).toEqual([])
    expect(store.unreadCount).toBe(0)
    expect(store.settings.enabled).toBe(true)
    expect(store.settings.minLevel).toBe('warning')
  })

  it('pushAlert prepends alert, increments unread, and triggers tryNotify', () => {
    const ctor = installNotificationMock({ permission: 'granted' })
    const store = useNotificationStore()
    store.pushAlert({ id: 1, level: 'critical', message: 'down', firedAt: Date.now() })
    expect(store.recentAlerts).toHaveLength(1)
    expect(store.unreadCount).toBe(1)
    expect(ctor).toHaveBeenCalledTimes(1)
    expect(ctor.mock.calls[0][0]).toBe('[严重] 告警触发')
    expect(ctor.mock.calls[0][1]).toEqual({ body: 'down', tag: 'alert-1' })
  })

  it('pushAlert caps recentAlerts to MAX_RECENT (10)', () => {
    installNotificationMock({ permission: 'granted' })
    const store = useNotificationStore()
    for (let i = 1; i <= 12; i++) {
      store.pushAlert({ id: i, level: 'info' })
    }
    expect(store.recentAlerts).toHaveLength(10)
    // 最新 id=12 在头部
    expect(store.recentAlerts[0].id).toBe(12)
  })

  it('pushAlert ignores null payload', () => {
    installNotificationMock({ permission: 'granted' })
    const store = useNotificationStore()
    store.pushAlert(null)
    expect(store.recentAlerts).toHaveLength(0)
    expect(store.unreadCount).toBe(0)
  })

  it('tryNotify skips when settings.enabled is false', () => {
    const ctor = installNotificationMock({ permission: 'granted' })
    const store = useNotificationStore()
    store.settings.enabled = false
    store.pushAlert({ id: 1, level: 'critical', message: 'down' })
    expect(store.unreadCount).toBe(1)
    expect(ctor).not.toHaveBeenCalled()
  })

  it('tryNotify skips when alert level is below settings.minLevel', () => {
    const ctor = installNotificationMock({ permission: 'granted' })
    const store = useNotificationStore()
    store.settings.minLevel = 'critical'
    store.pushAlert({ id: 1, level: 'warning', message: 'cpu high' })
    // warning 不达 critical 门槛 → 不弹
    expect(ctor).not.toHaveBeenCalled()
    store.pushAlert({ id: 2, level: 'critical', message: 'cpu max' })
    expect(ctor).toHaveBeenCalledTimes(1)
  })

  it('tryNotify still skips info even when minLevel is warning (default)', () => {
    const ctor = installNotificationMock({ permission: 'granted' })
    const store = useNotificationStore()
    store.pushAlert({ id: 1, level: 'info', message: 'heartbeat' })
    expect(ctor).not.toHaveBeenCalled()
  })

  it('tryNotify allows info when minLevel is lowered to info', () => {
    const ctor = installNotificationMock({ permission: 'granted' })
    const store = useNotificationStore()
    store.settings.minLevel = 'info'
    store.pushAlert({ id: 1, level: 'info', message: 'heartbeat' })
    expect(ctor).toHaveBeenCalledTimes(1)
  })

  it('tryNotify is a no-op when permission != granted', () => {
    const ctor = installNotificationMock({ permission: 'default' })
    const store = useNotificationStore()
    // 注入 default permission 后再 pushAlert
    store.permission = 'default'
    store.pushAlert({ id: 1, level: 'critical' })
    expect(ctor).not.toHaveBeenCalled()
  })

  it('requestPermission delegates to Notification.requestPermission and updates state', async () => {
    const reqImpl = vi.fn().mockResolvedValue('granted')
    installNotificationMock({ permission: 'default', requestPermissionImpl: reqImpl })
    const store = useNotificationStore()
    store.permission = 'default'
    const result = await store.requestPermission()
    expect(reqImpl).toHaveBeenCalledTimes(1)
    expect(result).toBe('granted')
    expect(store.permission).toBe('granted')
  })

  it('requestPermission falls back to denied when underlying call throws', async () => {
    const reqImpl = vi.fn().mockRejectedValue(new Error('blocked'))
    installNotificationMock({ permission: 'default', requestPermissionImpl: reqImpl })
    const store = useNotificationStore()
    const result = await store.requestPermission()
    expect(result).toBe('denied')
    expect(store.permission).toBe('denied')
  })

  it('requestPermission returns unsupported when Notification is missing', async () => {
    // 不安装 mock,显式删除
    if ('Notification' in globalThis) delete globalThis.Notification
    setActivePinia(createPinia())
    const store = useNotificationStore()
    const result = await store.requestPermission()
    expect(result).toBe('unsupported')
    expect(store.permission).toBe('unsupported')
  })

  it('clearUnread resets unreadCount but preserves recentAlerts', () => {
    installNotificationMock({ permission: 'granted' })
    const store = useNotificationStore()
    store.pushAlert({ id: 1, level: 'critical' })
    expect(store.unreadCount).toBe(1)
    store.clearUnread()
    expect(store.unreadCount).toBe(0)
    expect(store.recentAlerts).toHaveLength(1)
  })

  it('reset clears recentAlerts and unreadCount but preserves settings', () => {
    installNotificationMock({ permission: 'granted' })
    const store = useNotificationStore()
    store.settings.enabled = false
    store.settings.minLevel = 'critical'
    store.pushAlert({ id: 1, level: 'critical' })
    store.pushAlert({ id: 2, level: 'critical' })
    store.reset()
    expect(store.recentAlerts).toEqual([])
    expect(store.unreadCount).toBe(0)
    // settings 不被清空
    expect(store.settings.enabled).toBe(false)
    expect(store.settings.minLevel).toBe('critical')
  })

  it('levelText maps known levels to Chinese and falls back to raw value', () => {
    installNotificationMock({ permission: 'granted' })
    const store = useNotificationStore()
    expect(store.levelText('info')).toBe('信息')
    expect(store.levelText('warning')).toBe('警告')
    expect(store.levelText('critical')).toBe('严重')
    expect(store.levelText('unknown')).toBe('unknown')
  })

  it('tryNotify swallows Notification constructor exceptions', () => {
    installNotificationMock({ permission: 'granted' })
    // 替换为抛错的构造器
    function ThrowingNotification() {
      throw new Error('boom')
    }
    ThrowingNotification.permission = 'granted'
    ThrowingNotification.requestPermission = vi.fn().mockResolvedValue('granted')
    globalThis.Notification = ThrowingNotification

    const store = useNotificationStore()
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {})
    // 不应当抛
    expect(() => store.pushAlert({ id: 99, level: 'critical' })).not.toThrow()
    expect(warnSpy).toHaveBeenCalled()
  })
})
