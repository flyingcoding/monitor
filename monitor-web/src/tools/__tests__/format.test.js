import { describe, expect, it } from 'vitest'
import { fitByUnit, percentageToStatus } from '@/tools/format'

describe('fitByUnit', () => {
  it('should convert KB to MB when value is greater than 1024', () => {
    expect(fitByUnit(2048, 'KB')).toBe('2.0 MB')
  })
})

describe('percentageToStatus', () => {
  it('should return success for low percentage', () => {
    expect(percentageToStatus(30)).toBe('success')
  })

  it('should return warning for medium percentage', () => {
    expect(percentageToStatus(60)).toBe('warning')
  })

  it('should return exception for high percentage', () => {
    expect(percentageToStatus(95)).toBe('exception')
  })
})
