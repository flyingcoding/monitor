import { describe, expect, it, vi, beforeEach } from 'vitest'

// 用 vi.mock 在导入前替换 @/net.get
vi.mock('@/net', () => ({
  get: vi.fn()
}))

import { get } from '@/net'
import { getProcessSnapshot } from '@/net/process'

describe('process net layer', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('应通过指定 clientId 发起 GET 请求', () => {
    const success = vi.fn()
    getProcessSnapshot(123, success)

    expect(get).toHaveBeenCalledTimes(1)
    expect(get).toHaveBeenCalledWith('/api/monitor/process?clientId=123', success, undefined)
  })

  it('应将 failure 回调透传给 get', () => {
    const success = vi.fn()
    const failure = vi.fn()
    getProcessSnapshot(456, success, failure)

    expect(get).toHaveBeenCalledWith('/api/monitor/process?clientId=456', success, failure)
  })
})
