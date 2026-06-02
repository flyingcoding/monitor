import { beforeEach, describe, expect, it, vi } from 'vitest'
import { submitEnabledToggle } from '@/tools/toggle'
import { ElMessage } from 'element-plus'

vi.mock('element-plus', () => ({
  ElMessage: {
    success: vi.fn()
  }
}))

describe('submitEnabledToggle', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('shows enabled success message after update succeeds', () => {
    const row = { enabled: true }

    submitEnabledToggle({
      row,
      update: (success) => success()
    })

    expect(ElMessage.success).toHaveBeenCalledWith('已启用')
    expect(row.enabled).toBe(true)
  })

  it('shows disabled success message after update succeeds', () => {
    const row = { enabled: false }

    submitEnabledToggle({
      row,
      update: (success) => success()
    })

    expect(ElMessage.success).toHaveBeenCalledWith('已禁用')
    expect(row.enabled).toBe(false)
  })

  it('rolls back enabled state when update fails', () => {
    const row = { enabled: true }

    submitEnabledToggle({
      row,
      update: (_success, failure) => failure()
    })

    expect(ElMessage.success).not.toHaveBeenCalled()
    expect(row.enabled).toBe(false)
  })
})
