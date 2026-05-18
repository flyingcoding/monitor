import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'

const getMock = vi.fn()

vi.mock('@/net', () => ({
  get: (...args) => getMock(...args)
}))

import { fetchGpuSnapshot } from '@/net/gpu'

describe('fetchGpuSnapshot', () => {
  beforeEach(() => {
    getMock.mockReset()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('should call GET /api/monitor/gpu with clientId and forward callbacks', () => {
    const success = vi.fn()
    const failure = vi.fn()
    fetchGpuSnapshot(42, success, failure)

    expect(getMock).toHaveBeenCalledTimes(1)
    expect(getMock).toHaveBeenCalledWith('/api/monitor/gpu?clientId=42', success, failure)
  })

  it('should pass undefined failure when omitted', () => {
    const success = vi.fn()
    fetchGpuSnapshot(7, success)

    expect(getMock).toHaveBeenCalledWith('/api/monitor/gpu?clientId=7', success, undefined)
  })
})
