// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  buildSftpSocketUrl,
  downloadBase64File,
  joinRemotePath,
  parentRemotePath,
  remoteFileName
} from '@/tools/sftp'

describe('sftp helpers', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('builds authenticated SFTP WebSocket URL', () => {
    expect(buildSftpSocketUrl('ws://localhost:8080/', 42, 'a b', 'session-1')).toBe(
      'ws://localhost:8080/sftp/42?token=a%20b&sessionId=session-1'
    )
  })

  it('joins remote paths without duplicate slashes', () => {
    expect(joinRemotePath('.', 'app.log')).toBe('app.log')
    expect(joinRemotePath('/tmp/', '/app.log')).toBe('/tmp/app.log')
  })

  it('resolves parent path and filename', () => {
    expect(parentRemotePath('/tmp/app.log')).toBe('/tmp')
    expect(parentRemotePath('/tmp')).toBe('/')
    expect(parentRemotePath('/')).toBe('/')
    expect(parentRemotePath('tmp')).toBe('.')
    expect(remoteFileName('/tmp/app.log')).toBe('app.log')
  })

  it('downloads base64 content as a browser file', () => {
    const anchor = document.createElement('a')
    const appendSpy = vi.spyOn(document.body, 'appendChild')
    const clickSpy = vi.spyOn(anchor, 'click').mockImplementation(() => {})
    vi.spyOn(document, 'createElement').mockReturnValue(anchor)
    vi.stubGlobal('URL', {
      createObjectURL: vi.fn(() => 'blob:1'),
      revokeObjectURL: vi.fn()
    })

    downloadBase64File('SGVsbG8=', 'hello.txt')

    expect(appendSpy).toHaveBeenCalled()
    expect(anchor.download).toBe('hello.txt')
    expect(clickSpy).toHaveBeenCalled()
    expect(URL.createObjectURL).toHaveBeenCalled()
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:1')
  })
})
