// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

/**
 * 与 NotificationBell.test.js 同款 stub：避免加载真实 element-plus（CSS 解析失败）。
 * 仅保留断言所需的 props 与事件转发。
 */
const elStubs = {
  'el-form': {
    name: 'ElForm',
    props: ['labelWidth', 'labelPosition'],
    template: '<form class="el-form-stub"><slot /></form>'
  },
  'el-form-item': {
    name: 'ElFormItem',
    props: ['label'],
    template: '<div class="el-form-item-stub" :data-label="label"><slot /></div>'
  },
  'el-switch': {
    name: 'ElSwitch',
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<span class="el-switch-stub" :data-value="modelValue"></span>'
  },
  'el-radio-group': {
    name: 'ElRadioGroup',
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<div class="el-radio-group-stub" :data-value="modelValue"><slot /></div>'
  },
  'el-radio': {
    name: 'ElRadio',
    props: ['value'],
    template: '<label class="el-radio-stub"><slot /></label>'
  },
  'el-tag': {
    name: 'ElTag',
    props: ['type', 'size'],
    template: '<span class="el-tag-stub" :data-type="type"><slot /></span>'
  },
  'el-button': {
    name: 'ElButton',
    props: ['size', 'type', 'link'],
    emits: ['click'],
    template: '<button class="el-button-stub" @click="$emit(\'click\', $event)"><slot /></button>'
  },
  'el-divider': { name: 'ElDivider', template: '<hr />' },
  'el-alert': {
    name: 'ElAlert',
    props: ['type', 'title', 'showIcon', 'closable'],
    template: '<div class="el-alert-stub" :data-title="title"><slot /></div>'
  }
}

import NotificationPreference from '@/component/NotificationPreference.vue'
import { useNotificationStore } from '@/store/notification'

/**
 * 安装一个浏览器 Notification API mock，控制初始 permission。
 *
 * @param {string} permission default/granted/denied/unsupported
 */
function installNotification(permission) {
  function FakeNotification() {}
  FakeNotification.permission = permission
  FakeNotification.requestPermission = vi.fn().mockResolvedValue(permission)
  globalThis.Notification = FakeNotification
}

describe('NotificationPreference.vue', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    if (typeof window !== 'undefined' && window.localStorage) {
      window.localStorage.clear()
    }
  })

  afterEach(() => {
    vi.restoreAllMocks()
    if ('Notification' in globalThis) delete globalThis.Notification
  })

  it('renders three minLevel radios (info / warning / critical)', async () => {
    installNotification('granted')
    setActivePinia(createPinia())
    const wrapper = mount(NotificationPreference, { global: { stubs: elStubs } })
    await wrapper.vm.$nextTick()
    const html = wrapper.html()
    expect(html).toContain('信息及以上')
    expect(html).toContain('警告及以上')
    expect(html).toContain('仅严重')
  })

  it('toggling enable switch writes back to store.settings.enabled', async () => {
    installNotification('granted')
    setActivePinia(createPinia())
    const store = useNotificationStore()
    expect(store.settings.enabled).toBe(true)
    const wrapper = mount(NotificationPreference, { global: { stubs: elStubs } })
    const sw = wrapper.findComponent({ name: 'ElSwitch' })
    expect(sw.exists()).toBe(true)
    sw.vm.$emit('update:modelValue', false)
    await wrapper.vm.$nextTick()
    expect(store.settings.enabled).toBe(false)
  })

  it('selecting critical radio writes back to store.settings.minLevel', async () => {
    installNotification('granted')
    setActivePinia(createPinia())
    const store = useNotificationStore()
    expect(store.settings.minLevel).toBe('warning')
    const wrapper = mount(NotificationPreference, { global: { stubs: elStubs } })
    const radioGroup = wrapper.findComponent({ name: 'ElRadioGroup' })
    expect(radioGroup.exists()).toBe(true)
    radioGroup.vm.$emit('update:modelValue', 'critical')
    await wrapper.vm.$nextTick()
    expect(store.settings.minLevel).toBe('critical')
  })

  it('shows "申请权限" button when permission is default', async () => {
    installNotification('default')
    setActivePinia(createPinia())
    const wrapper = mount(NotificationPreference, { global: { stubs: elStubs } })
    await wrapper.vm.$nextTick()
    expect(wrapper.html()).toContain('申请权限')
    expect(wrapper.html()).toContain('默认未请求')
  })

  it('hides "申请权限" button when permission is granted', async () => {
    installNotification('granted')
    setActivePinia(createPinia())
    const wrapper = mount(NotificationPreference, { global: { stubs: elStubs } })
    await wrapper.vm.$nextTick()
    expect(wrapper.html()).not.toContain('申请权限')
    expect(wrapper.html()).toContain('已授权')
  })

  it('hides "申请权限" button when permission is denied (cannot re-prompt)', async () => {
    installNotification('denied')
    setActivePinia(createPinia())
    const wrapper = mount(NotificationPreference, { global: { stubs: elStubs } })
    await wrapper.vm.$nextTick()
    expect(wrapper.html()).not.toContain('申请权限')
    expect(wrapper.html()).toContain('已拒绝')
  })

  it('clicking "申请权限" triggers store.requestPermission', async () => {
    installNotification('default')
    setActivePinia(createPinia())
    const store = useNotificationStore()
    const spy = vi.spyOn(store, 'requestPermission').mockResolvedValue('granted')
    const wrapper = mount(NotificationPreference, { global: { stubs: elStubs } })
    await wrapper.vm.$nextTick()
    const buttons = wrapper.findAllComponents({ name: 'ElButton' })
    const reqBtn = buttons.find((b) => (b.text() || '').includes('申请权限'))
    expect(reqBtn).toBeTruthy()
    await reqBtn.trigger('click')
    expect(spy).toHaveBeenCalledTimes(1)
  })

  it('displays unsupported tag when browser has no Notification API', async () => {
    if ('Notification' in globalThis) delete globalThis.Notification
    setActivePinia(createPinia())
    const wrapper = mount(NotificationPreference, { global: { stubs: elStubs } })
    await wrapper.vm.$nextTick()
    expect(wrapper.html()).toContain('当前浏览器不支持桌面通知')
  })
})
