// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'

const netMock = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  takeAccessToken: vi.fn(),
  historyRequests: []
}))

vi.mock('@/net', () => ({
  get: netMock.get,
  post: netMock.post,
  takeAccessToken: netMock.takeAccessToken
}))

const genericSlotStub = {
  template: '<div><slot /></div>'
}

const stubs = {
  'el-scrollbar': genericSlotStub,
  'el-skeleton': { template: '<div class="el-skeleton-stub" />' },
  'el-divider': { template: '<hr />' },
  'el-button': {
    props: ['icon', 'type', 'size', 'plain', 'text', 'disabled'],
    emits: ['click'],
    template: '<button class="el-button-stub" :disabled="disabled" @click="$emit(\'click\', $event)"><slot /></button>'
  },
  'el-button-group': genericSlotStub,
  'el-date-picker': {
    props: ['modelValue', 'type', 'size', 'rangeSeparator', 'startPlaceholder', 'endPlaceholder'],
    template: '<div class="el-date-picker-stub" />'
  },
  'el-empty': { props: ['description'], template: '<div class="el-empty-stub">{{ description }}</div>' },
  'el-progress': genericSlotStub,
  'el-image': { template: '<img />' },
  'el-select': genericSlotStub,
  'el-option': genericSlotStub,
  'el-input': { template: '<input />' },
  RuntimeHistory: {
    props: ['data'],
    template: '<div class="runtime-history-stub">{{ data && data[0] && data[0].timestamp }}</div>'
  },
  Gpus: true,
  Processes: true,
  SmartHealth: true,
  SystemdServices: true
}

/**
 * 构造后端 ClientDetailsVO 最小形状，避免模板访问 toFixed / icon 映射时报错。
 *
 * @param {number} id clientId
 * @returns {object} 客户端详情
 */
function baseDetails(id) {
  return {
    id,
    name: `host-${id}`,
    online: true,
    ip: '127.0.0.1',
    location: 'cn',
    node: 'local',
    cpuName: 'Intel Core',
    cpuCore: 4,
    memory: 16,
    osName: 'Linux',
    osVersion: '6.1',
    capabilitiesJson: '{}'
  }
}

/**
 * 构造 RuntimeHistoryVO 最小形状，覆盖模板和 CSV 列访问的所有字段。
 *
 * @param {string} timestamp 样本时间
 * @returns {object} 运行时历史响应
 */
function runtimeHistory(timestamp) {
  return {
    memory: 16,
    disk: 256,
    list: [
      {
        timestamp,
        cpuUsage: 0.2,
        memoryUsage: 4,
        diskUsage: 80,
        networkUpload: 1,
        networkDownload: 2,
        diskRead: 3,
        diskWrite: 4
      }
    ]
  }
}

class FakeEventSource {
  static instances = []

  constructor(url) {
    this.url = url
    this.closed = false
    this.listeners = {}
    FakeEventSource.instances.push(this)
  }

  /**
   * 记录 SSE listener，测试不主动触发事件。
   *
   * @param {string} name 事件名
   * @param {Function} handler 回调
   */
  addEventListener(name, handler) {
    this.listeners[name] = handler
  }

  /**
   * 标记 SSE 已关闭，用于组件切换 id 时的资源清理。
   */
  close() {
    this.closed = true
  }
}

import ClientDetails from '@/component/ClientDetails.vue'

describe('ClientDetails.vue', () => {
  beforeEach(() => {
    netMock.get.mockReset()
    netMock.post.mockReset()
    netMock.takeAccessToken.mockReset()
    netMock.historyRequests = []
    FakeEventSource.instances = []
    globalThis.EventSource = FakeEventSource
    netMock.takeAccessToken.mockReturnValue('test-token')
    window.localStorage.setItem(
      'authorize',
      JSON.stringify({ token: 'test-token', expire: '2099-01-01T00:00:00Z' })
    )
    netMock.get.mockImplementation((url, success) => {
      if (url.includes('/api/monitor/details')) {
        const clientId = Number(new URL(url, 'http://monitor.test').searchParams.get('clientId'))
        success(baseDetails(clientId))
        return
      }
      if (url.includes('/api/monitor/runtime_history')) {
        netMock.historyRequests.push({ url, success })
      }
    })
  })

  afterEach(() => {
    vi.restoreAllMocks()
    window.localStorage.clear()
    delete globalThis.EventSource
  })

  it('ignores stale runtime history responses after client id changes', async () => {
    const wrapper = mount(ClientDetails, {
      props: { id: 42, update: vi.fn() },
      global: { stubs }
    })
    await nextTick()
    expect(netMock.historyRequests).toHaveLength(1)

    await wrapper.setProps({ id: 43 })
    await nextTick()
    expect(netMock.historyRequests).toHaveLength(2)

    netMock.historyRequests[1].success(runtimeHistory('new-response'))
    await nextTick()
    expect(wrapper.vm.$.setupState.details.runtime.list[0].timestamp).toBe('new-response')

    netMock.historyRequests[0].success(runtimeHistory('stale-response'))
    await nextTick()
    expect(wrapper.vm.$.setupState.details.runtime.list[0].timestamp).toBe('new-response')
  })
})
