import { describe, expect, it } from 'vitest'
import {
  buildRuntimeHistoryPayload,
  normalizeRuntimeHistory,
  sampleByLttb
} from '@/echarts/runtime-history-data'

/**
 * Build a deterministic runtime row for chart payload tests.
 *
 * @param {number} index Sample index
 * @returns {object} Runtime history row
 */
function runtimeRow(index) {
  return {
    timestamp: `2026-06-03T00:${String(index).padStart(2, '0')}:00.000Z`,
    cpuUsage: index / 100,
    memoryUsage: index,
    networkUpload: index * 2,
    networkDownload: index * 3,
    diskRead: index * 4,
    diskWrite: index * 5
  }
}

describe('runtime-history-data', () => {
  it('normalizes backend runtime rows into numeric chart units', () => {
    const normalized = normalizeRuntimeHistory([
      {
        timestamp: '2026-06-03T00:00:00.000Z',
        cpuUsage: '0.1234',
        memoryUsage: '1.5',
        networkUpload: '2.25',
        networkDownload: null,
        diskRead: '3.66',
        diskWrite: undefined
      }
    ])

    expect(normalized).toEqual([
      {
        timestamp: '2026-06-03T00:00:00.000Z',
        cpuUsage: 12.3,
        memoryUsage: 1536,
        networkUpload: 2.3,
        networkDownload: 0,
        diskRead: 3.7,
        diskWrite: 0
      }
    ])
  })

  it('caps LTTB output and preserves first and last rows', () => {
    const rows = Array.from({ length: 20 }, (_, index) => ({
      timestamp: `2026-06-03T00:${String(index).padStart(2, '0')}:00.000Z`,
      value: index === 10 ? 1000 : index
    }))

    const sampled = sampleByLttb(rows, 6, (row) => row.value)

    expect(sampled).toHaveLength(6)
    expect(sampled[0]).toBe(rows[0])
    expect(sampled[sampled.length - 1]).toBe(rows[rows.length - 1])
    expect(sampled.some((row) => row.value === 1000)).toBe(true)
  })

  it('builds bounded chart payloads for all runtime panels', () => {
    const rows = Array.from({ length: 12 }, (_, index) => runtimeRow(index))
    const payload = buildRuntimeHistoryPayload(rows, 5)

    expect(payload.sourceLength).toBe(12)
    expect(payload.maxPoints).toBe(5)
    for (const chartPayload of [payload.cpu, payload.memory, payload.network, payload.disk]) {
      expect(chartPayload.labels.length).toBeLessThanOrEqual(5)
      expect(chartPayload.series.every((series) => series.length === chartPayload.labels.length)).toBe(
        true
      )
      expect(chartPayload.labels[0]).toBe(rows[0].timestamp)
      expect(chartPayload.labels[chartPayload.labels.length - 1]).toBe(rows[rows.length - 1].timestamp)
    }
    expect(payload.cpu.series).toHaveLength(1)
    expect(payload.memory.series).toHaveLength(1)
    expect(payload.network.series).toHaveLength(2)
    expect(payload.disk.series).toHaveLength(2)
  })
})
