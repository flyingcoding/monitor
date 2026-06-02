import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createEmailCodeRequester } from '@/tools/verification-code'
import { ElMessage } from 'element-plus'

vi.mock('element-plus', () => ({
  ElMessage: {
    success: vi.fn(),
    warning: vi.fn()
  }
}))

vi.mock('@/net', () => ({
  get: vi.fn((url, success, failure) => {
    if (url.includes('fail')) {
      failure('请求失败')
      return
    }
    success()
  })
}))

describe('createEmailCodeRequester', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('starts cooldown after request success', () => {
    vi.useFakeTimers()
    const cooldownRef = { value: 0 }
    const requester = createEmailCodeRequester({ cooldownRef, cooldownSeconds: 3, intervalMs: 1000 })

    requester.request('user@example.com', 'reset')

    expect(ElMessage.success).toHaveBeenCalledWith(
      '验证码已发送到邮箱: user@example.com，请注意查收'
    )
    expect(cooldownRef.value).toBe(3)
    expect(vi.getTimerCount()).toBe(1)

    vi.advanceTimersByTime(1000)
    expect(cooldownRef.value).toBe(2)

    requester.dispose()
    expect(vi.getTimerCount()).toBe(0)
  })

  it('resets cooldown on failure', () => {
    const cooldownRef = { value: 8 }
    const requester = createEmailCodeRequester({ cooldownRef })

    requester.request('fail@example.com', 'modify')

    expect(ElMessage.warning).toHaveBeenCalledWith('请求失败')
    expect(cooldownRef.value).toBe(0)
  })

  it('clears cooldown when email is missing', () => {
    const cooldownRef = { value: 5 }
    const requester = createEmailCodeRequester({ cooldownRef })

    requester.request('', 'reset')

    expect(ElMessage.warning).toHaveBeenCalledWith('请输入邮件地址')
    expect(cooldownRef.value).toBe(0)
  })
})
