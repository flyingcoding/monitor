// @vitest-environment jsdom
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { buildCsv, downloadCsv } from '@/tools/csv'

describe('buildCsv', () => {
  it('renders Chinese header labels from columns', () => {
    const csv = buildCsv(
      [{ a: 1, b: 2 }],
      [
        { key: 'a', label: '甲' },
        { key: 'b', label: '乙' }
      ]
    )
    const [header] = csv.split('\r\n')
    expect(header).toBe('甲,乙')
  })

  it('escapes commas with surrounding double quotes', () => {
    const csv = buildCsv([{ name: 'a,b' }], [{ key: 'name', label: 'name' }])
    expect(csv).toBe('name\r\n"a,b"')
  })

  it('escapes newlines with surrounding double quotes', () => {
    const csv = buildCsv([{ note: 'line1\nline2' }], [{ key: 'note', label: 'note' }])
    expect(csv).toBe('note\r\n"line1\nline2"')
  })

  it('doubles existing double quotes inside the cell per RFC 4180', () => {
    const csv = buildCsv([{ v: 'he said "hi"' }], [{ key: 'v', label: 'v' }])
    expect(csv).toBe('v\r\n"he said ""hi"""')
  })

  it('returns only header when rows array is empty', () => {
    const csv = buildCsv([], [{ key: 'a', label: 'A' }])
    expect(csv).toBe('A')
  })

  it('returns only header when rows is null', () => {
    const csv = buildCsv(null, [{ key: 'a', label: 'A' }])
    expect(csv).toBe('A')
  })

  it('renders empty string for null / undefined cell values', () => {
    const csv = buildCsv(
      [{ a: null, b: undefined, c: 0 }],
      [
        { key: 'a', label: 'A' },
        { key: 'b', label: 'B' },
        { key: 'c', label: 'C' }
      ]
    )
    expect(csv).toBe('A,B,C\r\n,,0')
  })

  it('invokes format callback with the raw value and the row', () => {
    const format = vi.fn((v, row) => `${row.id}-${v}`)
    buildCsv([{ id: 1, v: 'x' }], [{ key: 'v', label: 'V', format }])
    expect(format).toHaveBeenCalledTimes(1)
    expect(format).toHaveBeenCalledWith('x', { id: 1, v: 'x' })
  })

  it('uses format output for the cell value', () => {
    const csv = buildCsv(
      [{ ts: 1700000000000 }],
      [{ key: 'ts', label: 'time', format: (v) => new Date(v).toISOString() }]
    )
    expect(csv).toBe('time\r\n2023-11-14T22:13:20.000Z')
  })
})

describe('downloadCsv', () => {
  let createObjectURL
  let revokeObjectURL
  let clickSpy
  let appendChildSpy
  let removeChildSpy

  beforeEach(() => {
    createObjectURL = vi.fn(() => 'blob:mock-url')
    revokeObjectURL = vi.fn()
    // jsdom does not implement URL.createObjectURL; stub it
    Object.defineProperty(URL, 'createObjectURL', {
      configurable: true,
      writable: true,
      value: createObjectURL
    })
    Object.defineProperty(URL, 'revokeObjectURL', {
      configurable: true,
      writable: true,
      value: revokeObjectURL
    })
    clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    appendChildSpy = vi.spyOn(document.body, 'appendChild')
    removeChildSpy = vi.spyOn(document.body, 'removeChild')
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('creates a Blob via URL.createObjectURL and triggers anchor click', () => {
    downloadCsv([{ a: 1 }], [{ key: 'a', label: 'A' }], 'report')
    expect(createObjectURL).toHaveBeenCalledTimes(1)
    const blob = createObjectURL.mock.calls[0][0]
    expect(blob).toBeInstanceOf(Blob)
    expect(blob.type).toContain('text/csv')
    expect(clickSpy).toHaveBeenCalledTimes(1)
    expect(appendChildSpy).toHaveBeenCalledTimes(1)
    expect(removeChildSpy).toHaveBeenCalledTimes(1)
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:mock-url')
  })

  it('appends .csv extension when missing', () => {
    downloadCsv([], [{ key: 'a', label: 'A' }], 'no-ext')
    const anchor = appendChildSpy.mock.calls[0][0]
    expect(anchor.download).toBe('no-ext.csv')
  })

  it('keeps the .csv extension when caller already supplied it', () => {
    downloadCsv([], [{ key: 'a', label: 'A' }], 'already.csv')
    const anchor = appendChildSpy.mock.calls[0][0]
    expect(anchor.download).toBe('already.csv')
  })

  it('prefixes the blob payload with a UTF-8 BOM', async () => {
    downloadCsv([{ a: '中文' }], [{ key: 'a', label: '列' }], 'bom')
    const blob = createObjectURL.mock.calls[0][0]
    // Read raw bytes — readAsText strips the BOM, so inspect via ArrayBuffer
    const buffer = await new Promise((resolve, reject) => {
      const reader = new FileReader()
      reader.onload = () => resolve(reader.result)
      reader.onerror = reject
      reader.readAsArrayBuffer(blob)
    })
    const bytes = new Uint8Array(buffer)
    // UTF-8 BOM = EF BB BF
    expect(bytes[0]).toBe(0xef)
    expect(bytes[1]).toBe(0xbb)
    expect(bytes[2]).toBe(0xbf)
    const decoded = new TextDecoder('utf-8').decode(bytes.subarray(3))
    expect(decoded).toBe('列\r\n中文')
  })
})
