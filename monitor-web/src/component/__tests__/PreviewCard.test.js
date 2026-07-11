// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'

const toolMocks = vi.hoisted(() => ({
  copyIp: vi.fn(),
  rename: vi.fn()
}))

vi.mock('@/tools', () => ({
  copyIp: toolMocks.copyIp,
  fitByUnit: (value, unit) => `${value} ${unit}`,
  osNameToIcon: () => ({ color: '#0b6bee', icon: 'fa-linux' }),
  percentageToStatus: (value) => (value >= 80 ? 'exception' : 'success'),
  rename: toolMocks.rename
}))

const stubs = {
  'el-icon': { template: '<span class="el-icon-stub"><slot /></span>' },
  'el-progress': {
    name: 'ElProgress',
    props: ['percentage', 'status', 'strokeWidth', 'showText'],
    template: '<div class="el-progress-stub" :data-percentage="percentage" />'
  }
}

import PreviewCard from '@/component/PreviewCard.vue'

/**
 * 构造主机预览卡片最小完整数据。
 *
 * @returns {object} 主机预览数据
 */
function createHost() {
  return {
    id: 1,
    location: 'cn',
    name: '生产节点 · 上海',
    online: true,
    ip: '10.20.0.12',
    osName: 'Ubuntu',
    osVersion: '24.04',
    cpuName: 'AMD EPYC 7763',
    cpuCore: 32,
    memory: 64,
    cpuUsage: 0.34,
    memoryUsage: 27.8,
    networkUpload: 832,
    networkDownload: 1620
  }
}

describe('PreviewCard.vue', () => {
  beforeEach(() => {
    toolMocks.copyIp.mockReset()
    toolMocks.rename.mockReset()
  })

  it('opens host details with Enter and Space', async () => {
    const wrapper = mount(PreviewCard, {
      props: { data: createHost(), update: vi.fn() },
      global: { stubs }
    })

    await wrapper.trigger('keydown', { key: 'Enter' })
    await wrapper.trigger('keydown', { key: ' ' })

    expect(wrapper.emitted('open')).toHaveLength(2)
  })

  it('keeps rename and copy actions independent from opening the card', async () => {
    const update = vi.fn()
    const host = createHost()
    const wrapper = mount(PreviewCard, {
      props: { data: host, update },
      global: { stubs }
    })

    await wrapper.get(`[aria-label="重命名主机 ${host.name}"]`).trigger('click')
    await wrapper.get(`[aria-label="复制 IP ${host.ip}"]`).trigger('click')

    expect(toolMocks.rename).toHaveBeenCalledWith(host.id, host.name, update)
    expect(toolMocks.copyIp).toHaveBeenCalledWith(host.ip)
    expect(wrapper.emitted('open')).toBeUndefined()
  })

  it('clamps invalid utilization values to safe progress percentages', () => {
    const host = {
      ...createHost(),
      cpuUsage: 2,
      memory: 0,
      memoryUsage: Number.NaN
    }
    const wrapper = mount(PreviewCard, {
      props: { data: host, update: vi.fn() },
      global: { stubs }
    })

    const progress = wrapper.findAllComponents({ name: 'ElProgress' })
    expect(progress[0].props('percentage')).toBe(100)
    expect(progress[1].props('percentage')).toBe(0)
  })
})
