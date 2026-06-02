import { describe, expect, it } from 'vitest'
import { buildQueryString, withQuery } from '@/net/query'

describe('buildQueryString', () => {
  it('filters empty values and preserves meaningful falsy values', () => {
    expect(
      buildQueryString({
        keyword: '',
        level: null,
        status: undefined,
        page: 0,
        enabled: false
      })
    ).toBe('page=0&enabled=false')
  })

  it('encodes keys and values', () => {
    expect(buildQueryString({ 'client id': 42, keyword: 'cpu load' })).toBe(
      'client%20id=42&keyword=cpu%20load'
    )
  })
})

describe('withQuery', () => {
  it('returns original url when query is empty', () => {
    expect(withQuery('/api/probes', { keyword: '' })).toBe('/api/probes')
  })

  it('appends query with the correct separator', () => {
    expect(withQuery('/api/probes', { page: 1 })).toBe('/api/probes?page=1')
    expect(withQuery('/api/probes?size=10', { page: 1 })).toBe('/api/probes?size=10&page=1')
  })
})
