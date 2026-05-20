// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

const pushMock = vi.fn()

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: pushMock })
}))

vi.mock('@/tools/alert', () => ({
  levelMeta: (level) => ({ label: level, type: 'info' })
}))

/**
 * 通用 Element Plus 组件 stub：透传 slot + 转发 update:visible 等事件。
 * 测试目的：在不加载真实 element-plus（避免 CSS 解析）的前提下断言 props / slot 内容。
 */
const elStubs = {
  'el-popover': {
    name: 'ElPopover',
    props: ['placement', 'width', 'trigger', 'visible'],
    emits: ['show', 'update:visible'],
    template:
      '<div class="el-popover-stub"><slot name="reference" /><div class="popover-content"><slot /></div></div>'
  },
  'el-badge': {
    name: 'ElBadge',
    props: ['value', 'hidden', 'max'],
    template: '<span class="el-badge-stub" :data-value="value" :data-hidden="hidden"><slot /></span>'
  },
  'el-button': {
    name: 'ElButton',
    props: ['icon', 'circle', 'text', 'size', 'link', 'type'],
    emits: ['click'],
    template: '<button class="el-button-stub" @click="$emit(\'click\', $event)"><slot /></button>'
  },
  'el-divider': { name: 'ElDivider', template: '<hr />' },
  'el-scrollbar': {
    name: 'ElScrollbar',
    props: ['maxHeight'],
    template: '<div class="el-scrollbar-stub"><slot /></div>'
  },
  'el-tag': {
    name: 'ElTag',
    props: ['size', 'type'],
    template: '<span class="el-tag-stub"><slot /></span>'
  }
}

import NotificationBell from '@/component/NotificationBell.vue'
import { useNotificationStore } from '@/store/notification'

describe('NotificationBell.vue', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    pushMock.mockReset()
    if (typeof window !== 'undefined' && window.localStorage) {
      window.localStorage.clear()
    }
    function FakeNotification() {}
    FakeNotification.permission = 'granted'
    FakeNotification.requestPermission = vi.fn().mockResolvedValue('granted')
    globalThis.Notification = FakeNotification
  })

  afterEach(() => {
    vi.restoreAllMocks()
    if ('Notification' in globalThis) delete globalThis.Notification
  })

  it('renders unread badge value from store', async () => {
    setActivePinia(createPinia())
    const wrapper = mount(NotificationBell, { global: { stubs: elStubs } })
    const store = useNotificationStore()
    store.pushAlert({ id: 1, level: 'warning', message: 'cpu high' })
    store.pushAlert({ id: 2, level: 'critical', message: 'mem high' })
    await wrapper.vm.$nextTick()
    const badge = wrapper.findComponent({ name: 'ElBadge' })
    expect(badge.exists()).toBe(true)
    expect(badge.props('value')).toBe(2)
    expect(badge.props('hidden')).toBe(false)
  })

  it('hides badge when unreadCount is zero', async () => {
    const wrapper = mount(NotificationBell, { global: { stubs: elStubs } })
    await wrapper.vm.$nextTick()
    const badge = wrapper.findComponent({ name: 'ElBadge' })
    expect(badge.props('hidden')).toBe(true)
  })

  it('clears unread when popover opens (via @show handler)', async () => {
    const wrapper = mount(NotificationBell, { global: { stubs: elStubs } })
    const store = useNotificationStore()
    store.pushAlert({ id: 1, level: 'critical' })
    expect(store.unreadCount).toBe(1)
    const popover = wrapper.findComponent({ name: 'ElPopover' })
    popover.vm.$emit('show')
    await wrapper.vm.$nextTick()
    expect(store.unreadCount).toBe(0)
  })

  it('viewAll navigates to alert-history route and clears unread', async () => {
    const wrapper = mount(NotificationBell, { global: { stubs: elStubs } })
    const store = useNotificationStore()
    store.pushAlert({ id: 1, level: 'critical' })
    await wrapper.vm.$nextTick()
    const buttons = wrapper.findAllComponents({ name: 'ElButton' })
    const viewAllBtn = buttons.find((b) => (b.text() || '').includes('查看全部告警'))
    expect(viewAllBtn).toBeTruthy()
    await viewAllBtn.trigger('click')
    expect(pushMock).toHaveBeenCalledWith({ name: 'alert-history' })
    expect(store.unreadCount).toBe(0)
  })

  it('shows "启用浏览器通知" button when permission is default', async () => {
    function FakeNotification() {}
    FakeNotification.permission = 'default'
    FakeNotification.requestPermission = vi.fn().mockResolvedValue('default')
    globalThis.Notification = FakeNotification
    setActivePinia(createPinia())
    const wrapper = mount(NotificationBell, { global: { stubs: elStubs } })
    await wrapper.vm.$nextTick()
    expect(wrapper.html()).toContain('启用浏览器通知')
  })

  it('shows "通知已拒绝" label when permission is denied', async () => {
    function FakeNotification() {}
    FakeNotification.permission = 'denied'
    FakeNotification.requestPermission = vi.fn().mockResolvedValue('denied')
    globalThis.Notification = FakeNotification
    setActivePinia(createPinia())
    const wrapper = mount(NotificationBell, { global: { stubs: elStubs } })
    await wrapper.vm.$nextTick()
    expect(wrapper.html()).toContain('通知已拒绝')
  })

  it('does not show enable button when permission is granted', async () => {
    function FakeNotification() {}
    FakeNotification.permission = 'granted'
    FakeNotification.requestPermission = vi.fn().mockResolvedValue('granted')
    globalThis.Notification = FakeNotification
    setActivePinia(createPinia())
    const wrapper = mount(NotificationBell, { global: { stubs: elStubs } })
    await wrapper.vm.$nextTick()
    expect(wrapper.html()).not.toContain('启用浏览器通知')
  })
})
