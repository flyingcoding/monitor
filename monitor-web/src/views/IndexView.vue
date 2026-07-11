<script setup>
import { logout } from '@/net'
import router from '@/router'
import {
  Aim,
  ArrowDown,
  Bell,
  Document,
  Lock,
  Monitor,
  Moon,
  Sunny,
  SwitchButton
} from '@element-plus/icons-vue'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useDark, useMediaQuery, useToggle } from '@vueuse/core'
import { useRoute } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import TabItem from '@/component/TabItem.vue'
import NotificationBell from '@/component/NotificationBell.vue'
import { useStore } from '@/store'
import { useNotificationStore } from '@/store/notification'
import { connectAlertSse, closeAlertSse } from '@/net/alertSse'

const store = useStore()
const route = useRoute()
const isDark = useDark()
const toggleDark = useToggle(isDark)
const isMobile = useMediaQuery('(max-width: 767px)')
const mobileMenuOpen = ref(false)
const notificationStore = useNotificationStore()

const tabs = [
  { id: 1, name: '管理', route: 'manage', icon: Monitor },
  { id: 2, name: '安全', route: 'security', icon: Lock },
  { id: 3, name: '告警', route: 'alert-history', icon: Bell },
  { id: 4, name: '状态页', route: 'status-page-config', icon: Document, adminOnly: true },
  { id: 5, name: '探测', route: 'probes', icon: Aim, adminOnly: true }
]

/** 首次访问 Notification 引导：localStorage 键，记录上次 dismiss 时间戳（毫秒）。 */
const NOTIFICATION_PROMPT_KEY = 'notification_prompt_dismissed_at'
/** dismiss 后冷却 30 天，再 prompt 一次。 */
const NOTIFICATION_PROMPT_COOLDOWN_MS = 30 * 24 * 60 * 60 * 1000
/** 延迟 5 秒触发，避免与 SSE 连接、路由动画相互打架。 */
const NOTIFICATION_PROMPT_DELAY_MS = 5000

/** 用于 onBeforeUnmount 清理 setTimeout 句柄。 */
let notificationPromptTimer = null

/**
 * 当前用户可见的导航列表，普通用户隐藏管理员专属入口。
 */
const visibleTabs = computed(() => tabs.filter((item) => !item.adminOnly || store.isAdmin))

/**
 * 根据当前路由推断侧栏激活项，告警子路由统一归到告警入口。
 */
const activeTabId = computed(() => {
  if (route.name && route.name.toString().startsWith('alert')) return 3
  const matched = tabs.find((item) => route.name === item.route)
  return matched ? matched.id : 1
})

const userInitial = computed(() => {
  const username = store.user.username || 'U'
  return username.trim().charAt(0).toUpperCase() || 'U'
})

const roleLabel = computed(() => (store.isAdmin ? '管理员' : '子账户'))

/**
 * 导航到指定业务页面并收起移动端侧栏。
 *
 * @param {object} item 导航项
 */
function changePage(item) {
  mobileMenuOpen.value = false
  if (route.name !== item.route) {
    router.push({ name: item.route })
  }
}

/**
 * 切换应用明暗主题。
 */
function toggleTheme() {
  toggleDark()
}

/**
 * 打开移动端导航面板。
 */
function openMobileMenu() {
  mobileMenuOpen.value = true
}

/**
 * 关闭移动端导航面板。
 */
function closeMobileMenu() {
  mobileMenuOpen.value = false
}

/**
 * 处理全局 Escape，确保键盘用户可以关闭移动端导航。
 *
 * @param {KeyboardEvent} event 键盘事件
 */
function handleGlobalKeydown(event) {
  if (event.key === 'Escape' && mobileMenuOpen.value) {
    closeMobileMenu()
  }
}

/**
 * 清理实时连接和通知状态后退出登录。
 */
function userLogout() {
  closeAlertSse()
  notificationStore.reset()
  logout(() => router.push('/'))
}

/**
 * 首次访问通知引导：登录后延迟触发，并按浏览器权限与冷却时间决定是否展示。
 */
function maybePromptForNotification() {
  if (notificationStore.permission !== 'default') return
  let dismissedAt = 0
  try {
    const raw = localStorage.getItem(NOTIFICATION_PROMPT_KEY)
    dismissedAt = raw ? Number(raw) : 0
  } catch (_e) {
    dismissedAt = 0
  }
  if (dismissedAt && Date.now() - dismissedAt < NOTIFICATION_PROMPT_COOLDOWN_MS) return
  ElMessageBox.confirm(
    '订阅告警事件后，关键告警（警告 / 严重）会在桌面弹出提醒。是否启用浏览器通知？',
    '开启浏览器通知',
    {
      confirmButtonText: '启用',
      cancelButtonText: '暂不启用',
      type: 'info'
    }
  )
    .then(async () => {
      await notificationStore.requestPermission()
      try {
        localStorage.setItem(NOTIFICATION_PROMPT_KEY, String(Date.now()))
      } catch (_e) {
        // 写入失败时保留默认行为，下次访问仍可再次提示。
      }
    })
    .catch(() => {
      try {
        localStorage.setItem(NOTIFICATION_PROMPT_KEY, String(Date.now()))
      } catch (_e) {
        // 写入失败时保留默认行为，下次访问仍可再次提示。
      }
    })
}

watch(
  () => route.fullPath,
  () => closeMobileMenu()
)

watch(isMobile, (mobile) => {
  if (!mobile) closeMobileMenu()
})

onMounted(() => {
  window.addEventListener('keydown', handleGlobalKeydown)
  connectAlertSse((event) => {
    notificationStore.pushAlert(event)
  })
  notificationPromptTimer = setTimeout(() => {
    notificationPromptTimer = null
    maybePromptForNotification()
  }, NOTIFICATION_PROMPT_DELAY_MS)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', handleGlobalKeydown)
  closeAlertSse()
  if (notificationPromptTimer) {
    clearTimeout(notificationPromptTimer)
    notificationPromptTimer = null
  }
})
</script>

<template>
  <div class="app-shell">
    <button
      v-if="mobileMenuOpen"
      type="button"
      class="nav-backdrop"
      aria-label="关闭主导航"
      @click="closeMobileMenu"
    />

    <aside
      class="side-nav"
      :class="{ open: mobileMenuOpen }"
      :aria-hidden="isMobile && !mobileMenuOpen"
      :inert="isMobile && !mobileMenuOpen"
    >
      <div class="brand-lockup">
        <img src="/icon.svg" alt="" class="brand-mark" />
        <span>flying monitor</span>
      </div>
      <nav class="nav-list" aria-label="主导航">
        <tab-item
          v-for="item in visibleTabs"
          :key="item.id"
          :name="item.name"
          :icon="item.icon"
          :active="item.id === activeTabId"
          @click="changePage(item)"
        />
      </nav>
      <div class="mobile-account">
        <div class="mobile-account-profile">
          <el-avatar class="user-avatar" :size="38">{{ userInitial }}</el-avatar>
          <div>
            <strong>{{ store.user.username || '用户' }}</strong>
            <span>{{ roleLabel }}</span>
          </div>
        </div>
        <el-button :icon="SwitchButton" text @click="userLogout">退出登录</el-button>
      </div>
    </aside>

    <section class="shell-workspace">
      <header class="top-bar">
        <el-button
          class="mobile-menu-button"
          text
          circle
          aria-label="打开主导航"
          @click="openMobileMenu"
        >
          <span class="hamburger-icon" aria-hidden="true"><span /><span /><span /></span>
        </el-button>
        <div class="mobile-brand" aria-hidden="true">
          <img src="/icon.svg" alt="" />
          <span>flying monitor</span>
        </div>
        <div class="workspace-context">基础设施控制台</div>
        <div class="top-actions">
          <el-tooltip :content="isDark ? '切换到浅色模式' : '切换到深色模式'" placement="bottom">
            <el-button
              class="utility-button"
              text
              circle
              :aria-label="isDark ? '切换到浅色模式' : '切换到深色模式'"
              @click="toggleTheme"
            >
              <el-icon><component :is="isDark ? Sunny : Moon" /></el-icon>
            </el-button>
          </el-tooltip>
          <notification-bell class="notification-action" />
          <span class="utility-divider" aria-hidden="true" />
          <el-dropdown class="account-dropdown" trigger="click">
            <button type="button" class="user-trigger" aria-label="打开账户菜单">
              <div class="user-copy">
                <span class="user-name">{{ store.user.username || '用户' }}</span>
                <span class="user-meta">
                  {{ roleLabel
                  }}<template v-if="store.user.email"> · {{ store.user.email }}</template>
                </span>
              </div>
              <el-avatar class="user-avatar" :size="38">{{ userInitial }}</el-avatar>
              <el-icon class="user-chevron"><ArrowDown /></el-icon>
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item :icon="SwitchButton" @click="userLogout">
                  退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <main id="main-content" class="main-content">
        <router-view v-slot="{ Component }">
          <transition name="route-fade" mode="out-in">
            <keep-alive exclude="security,AlertView">
              <component :is="Component" />
            </keep-alive>
          </transition>
        </router-view>
      </main>
    </section>
  </div>
</template>

<style scoped>
.app-shell {
  width: 100%;
  height: 100vh;
  display: flex;
  overflow: hidden;
  background: var(--app-canvas);
}

.side-nav {
  position: relative;
  z-index: 30;
  flex: 0 0 216px;
  width: 216px;
  min-height: 100%;
  padding: 0 14px 24px;
  display: flex;
  flex-direction: column;
  background: var(--app-shell);
  box-shadow: 10px 0 28px rgba(4, 15, 32, 0.08);
}

.brand-lockup {
  height: 70px;
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 0 6px;
  color: #ffffff;
  font-size: 18px;
  font-weight: 750;
  letter-spacing: -0.02em;
  white-space: nowrap;
}

.brand-mark {
  width: 38px;
  height: 38px;
  flex: 0 0 auto;
}

.nav-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-top: 22px;
}

.shell-workspace {
  min-width: 0;
  min-height: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
}

.top-bar {
  position: relative;
  z-index: 20;
  flex: 0 0 70px;
  height: 70px;
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 0 28px;
  border-bottom: 1px solid var(--app-border);
  background: var(--app-surface);
}

.workspace-context {
  color: var(--app-text-secondary);
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 0.02em;
}

.top-actions {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 8px;
}

.utility-button,
.mobile-menu-button {
  width: 40px;
  height: 40px;
  color: var(--app-text-secondary);
  font-size: 18px;
}

.utility-button:hover,
.mobile-menu-button:hover {
  background: var(--app-primary-soft);
  color: var(--app-primary);
}

.notification-action :deep(.el-button) {
  width: 40px;
  height: 40px;
  color: var(--app-text-secondary);
  font-size: 18px;
}

.notification-action :deep(.el-button:hover) {
  background: var(--app-primary-soft);
  color: var(--app-primary);
}

.utility-divider {
  width: 1px;
  height: 28px;
  margin: 0 4px;
  background: var(--app-border);
}

.user-trigger {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 48px;
  padding: 4px 3px 4px 10px;
  border: 0;
  border-radius: 10px;
  background: transparent;
  color: var(--app-text);
  cursor: pointer;
  transition: background-color var(--app-transition);
}

.user-trigger:hover {
  background: var(--app-surface-soft);
}

.user-copy {
  max-width: 220px;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
  min-width: 0;
}

.user-name,
.user-meta {
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-name {
  font-size: 14px;
  font-weight: 700;
}

.user-meta {
  color: var(--app-text-secondary);
  font-size: 11px;
}

.user-avatar {
  flex: 0 0 auto;
  background: var(--app-primary);
  color: #ffffff;
  font-weight: 750;
}

.user-chevron {
  color: var(--app-text-secondary);
  font-size: 12px;
}

.mobile-menu-button {
  display: none;
}

.mobile-brand,
.mobile-account {
  display: none;
}

.main-content {
  min-width: 0;
  min-height: 0;
  flex: 1;
  overflow: auto;
  padding: 28px 26px 44px;
  background: var(--app-canvas);
  scrollbar-gutter: stable;
}

.nav-backdrop {
  display: none;
}

.route-fade-enter-active,
.route-fade-leave-active {
  transition:
    opacity var(--app-transition),
    transform var(--app-transition);
}

.route-fade-enter-from {
  opacity: 0;
  transform: translateY(4px);
}

.route-fade-leave-to {
  opacity: 0;
}

@media (max-width: 1023px) and (min-width: 768px) {
  .side-nav {
    flex-basis: 192px;
    width: 192px;
  }

  .brand-lockup {
    font-size: 16px;
  }

  .top-bar {
    padding: 0 22px;
  }

  .main-content {
    padding: 24px 20px 40px;
  }
}

@media (max-width: 767px) {
  .side-nav {
    position: fixed;
    inset: 0 auto 0 0;
    width: min(82vw, 280px);
    flex-basis: auto;
    transform: translateX(-105%);
    transition: transform 220ms ease-out;
    box-shadow: var(--app-shadow-md);
  }

  .side-nav.open {
    transform: translateX(0);
  }

  .nav-backdrop {
    position: fixed;
    inset: 0;
    z-index: 25;
    display: block;
    width: 100%;
    height: 100%;
    padding: 0;
    border: 0;
    background: rgba(4, 15, 32, 0.54);
    cursor: default;
  }

  .top-bar {
    flex-basis: 62px;
    height: 62px;
    padding: 0 14px;
  }

  .mobile-menu-button {
    display: inline-flex;
  }

  .hamburger-icon {
    width: 18px;
    display: inline-flex;
    flex-direction: column;
    gap: 4px;
  }

  .hamburger-icon span {
    width: 100%;
    height: 2px;
    border-radius: 999px;
    background: currentColor;
  }

  .mobile-brand {
    min-width: 0;
    display: flex;
    align-items: center;
    gap: 7px;
    color: var(--app-text);
    font-size: 14px;
    font-weight: 750;
    letter-spacing: -0.02em;
    white-space: nowrap;
  }

  .mobile-brand img {
    width: 30px;
    height: 30px;
  }

  .workspace-context,
  .utility-divider,
  .user-copy,
  .user-chevron {
    display: none;
  }

  .account-dropdown {
    display: none;
  }

  .mobile-account {
    margin-top: auto;
    display: flex;
    flex-direction: column;
    gap: 10px;
    padding: 16px 6px 0;
    border-top: 1px solid rgba(255, 255, 255, 0.12);
  }

  .mobile-account-profile {
    min-width: 0;
    display: flex;
    align-items: center;
    gap: 10px;
  }

  .mobile-account-profile > div {
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  .mobile-account-profile strong,
  .mobile-account-profile span {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .mobile-account-profile strong {
    color: #ffffff;
    font-size: 13px;
  }

  .mobile-account-profile span {
    color: var(--app-shell-text);
    font-size: 11px;
  }

  .mobile-account .el-button {
    width: 100%;
    justify-content: flex-start;
    color: var(--app-shell-text);
  }

  .top-actions {
    gap: 4px;
  }

  .main-content {
    padding: 22px 16px 36px;
    scrollbar-gutter: auto;
  }
}
</style>
