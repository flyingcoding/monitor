import { describe, expect, it } from 'vitest'
import {
  fitByUnit,
  formatMemoryBytes,
  formatNumber,
  formatRatioPercent,
  formatUpdatedAt,
  percentageToStatus,
  thresholdProgressStatus,
  thresholdTagType
} from '@/tools/format'

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

describe('formatMemoryBytes', () => {
  it('formats non-positive values as 0 MB', () => {
    expect(formatMemoryBytes(0)).toBe('0 MB')
  })

  it('formats bytes as MB and GB', () => {
    expect(formatMemoryBytes(512 * 1024 * 1024)).toBe('512.0 MB')
    expect(formatMemoryBytes(2 * 1024 * 1024 * 1024)).toBe('2.00 GB')
  })
})

describe('formatRatioPercent', () => {
  it('formats ratio values as percentage text', () => {
    expect(formatRatioPercent(0.123)).toBe('12.3%')
  })

  it('returns zero percentage for empty values', () => {
    expect(formatRatioPercent(null)).toBe('0.0%')
  })
})

describe('formatNumber', () => {
  it('formats numbers with requested digits', () => {
    expect(formatNumber(12.345, 2)).toBe('12.35')
  })

  it('returns fallback for empty values', () => {
    expect(formatNumber(null)).toBe('-')
  })
})

describe('formatUpdatedAt', () => {
  it('returns default text for empty values', () => {
    expect(formatUpdatedAt(null)).toBe('尚未上报')
  })
})

describe('thresholdTagType', () => {
  it('maps threshold values to Element Plus tag types', () => {
    expect(thresholdTagType(null, 85, 75)).toBe('info')
    expect(thresholdTagType(90, 85, 75)).toBe('danger')
    expect(thresholdTagType(80, 85, 75)).toBe('warning')
    expect(thresholdTagType(60, 85, 75)).toBe('success')
  })
})

describe('thresholdProgressStatus', () => {
  it('maps threshold values to Element Plus progress statuses', () => {
    expect(thresholdProgressStatus(null, 90, 70)).toBe('')
    expect(thresholdProgressStatus(95, 90, 70)).toBe('exception')
    expect(thresholdProgressStatus(75, 90, 70)).toBe('warning')
    expect(thresholdProgressStatus(50, 90, 70)).toBe('success')
  })
})
