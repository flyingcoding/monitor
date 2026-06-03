import { describe, expect, it } from 'vitest'
import {
  createTerminalTab,
  nextClientSessionIndex,
  resolveNextActiveTabName
} from '@/tools/terminal-tabs'

describe('terminal tab helpers', () => {
  it('creates tab state with isolated connection defaults', () => {
    const tab = createTerminalTab(42, 2, () => 'session-1')

    expect(tab).toMatchObject({
      name: 'session-1',
      sessionId: 'session-1',
      clientId: 42,
      title: '主机 #42 · 2',
      panel: 'terminal',
      state: 1,
      loading: true,
      connection: {
        ip: '',
        port: 22,
        username: '',
        password: ''
      }
    })
  })

  it('keeps current active tab when closing an inactive tab', () => {
    const tabs = [{ name: 'a' }, { name: 'b' }, { name: 'c' }]

    expect(resolveNextActiveTabName(tabs, 'b', 'a')).toBe('a')
  })

  it('activates next tab first when closing the active tab', () => {
    const tabs = [{ name: 'a' }, { name: 'b' }, { name: 'c' }]

    expect(resolveNextActiveTabName(tabs, 'b', 'b')).toBe('c')
  })

  it('falls back to previous tab when active last tab is closed', () => {
    const tabs = [{ name: 'a' }, { name: 'b' }]

    expect(resolveNextActiveTabName(tabs, 'b', 'b')).toBe('a')
  })

  it('calculates the next session index per client', () => {
    const tabs = [{ clientId: 1 }, { clientId: 2 }, { clientId: 1 }]

    expect(nextClientSessionIndex(tabs, 1)).toBe(3)
    expect(nextClientSessionIndex(tabs, 2)).toBe(2)
    expect(nextClientSessionIndex(tabs, 3)).toBe(1)
  })
})
