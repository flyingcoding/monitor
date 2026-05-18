import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'

const getMock = vi.fn()

vi.mock('@/net', () => ({
  get: (...args) => getMock(...args)
}))

import { fetchSmartSnapshot } from '@/net/smart'

describe('fetchSmartSnapshot', () => {
  beforeEach(() => {
    getMock.mockReset()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('should call GET /api/monitor/smart with clientId and forward callbacks', () => {
    const success = vi.fn()
    const failure = vi.fn()
    fetchSmartSnapshot(42, success, failure)

    expect(getMock).toHaveBeenCalledTimes(1)
    expect(getMock).toHaveBeenCalledWith('/api/monitor/smart?clientId=42', success, failure)
  })

  it('should pass undefined failure when omitted', () => {
    const success = vi.fn()
    fetchSmartSnapshot(7, success)

    expect(getMock).toHaveBeenCalledWith('/api/monitor/smart?clientId=7', success, undefined)
  })
})
