<template>
  <el-container class="main-container">
    <el-header class="main-header">
      <el-image src="icon.svg" style="height: 40px"></el-image>
      <div class="tabs">
        <tab-item
          v-for="item in visibleTabs"
          :key="item.id"
          :name="item.name"
          :active="item.id === tab"
          @click="changePage(item)"
        />
        <el-switch
          style="margin: 0 20px"
          v-model="dark"
          active-color="#424242"
          :active-action-icon="Moon"
          :inactive-action-icon="Sunny"
        />
        <notification-bell style="margin-right: 10px" />
        <div style="text-align: right; line-height: 16px; margin-right: 10px">
          <div>
            <el-tag type="success" v-if="store.isAdmin" size="small">管理员</el-tag>
            <el-tag v-else size="small">子账户</el-tag>
            {{ store.user.username }}
          </div>
          <div style="font-size: 13px; color: grey">{{ store.user.email }}</div>
        </div>
      </div>
      <el-dropdown>
        <el-avatar
          class="avatar"
          src="https://cube.elemecdn.com/0/88/03b0d39583f48206768a7534e55bcpng.png"
        />
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item @click="userLogout">
              <el-icon><Back /></el-icon>
              退出登录
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </el-header>
    <el-main class="main-content">
      <router-view v-slot="{ Component }">
        <transition name="el-fade-in-linear" mode="out-in">
          <keep-alive exclude="security,AlertView">
            <component :is="Component" />
          </keep-alive>
        </transition>
      </router-view>
    </el-main>
  </el-container>
</template>

<script setup>
import { logout } from '@/net'
import router from '@/router'
import { Back, Moon, Sunny } from '@element-plus/icons-vue'
import { onMounted, onBeforeUnmount, ref, watch, computed } from 'vue'
import { useDark } from '@vueuse/core'
import { useRoute } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import TabItem from '@/component/TabItem.vue'
import NotificationBell from '@/component/NotificationBell.vue'
import { useStore } from '@/store'
import { useNotificationStore } from '@/store/notification'
import { connectAlertSse, closeAlertSse } from '@/net/alertSse'

const store = useStore()
const route = useRoute()
const dark = ref(useDark())
const notificationStore = useNotificationStore()
const tabs = [
  { id: 1, name: '管理', route: 'manage' },
  { id: 2, name: '安全', route: 'security' },
  { id: 3, name: '告警', route: 'alert-history' },
  { id: 4, name: '状态页', route: 'status-page-config', adminOnly: true },
  { id: 5, name: '探测', route: 'probes', adminOnly: true }
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
 * 根据当前路由名称推断激活的 tab id，告警相关子路由统一归到告警 tab。
 *
 * @returns {number} 激活的 tab id
 */
const defaultIndex = () => {
  if (route.name && route.name.toString().startsWith('alert')) return 3
  for (let tab of tabs) {
    if (route.name === tab.route) return tab.id
  }
  return 1
}
const tab = ref(defaultIndex())

/**
 * 当前用户可见的 tab 列表。普通用户隐藏 adminOnly tab。
 */
const visibleTabs = computed(() => tabs.filter((t) => !t.adminOnly || store.isAdmin))

watch(
  () => route.name,
  () => {
    tab.value = defaultIndex()
  }
)

function changePage(item) {
  tab.value = item.id
  router.push({ name: item.route })
}

function userLogout() {
  closeAlertSse()
  notificationStore.reset()
  logout(() => router.push('/'))
}

/**
 * 首次访问通知引导：登录后延迟 5 秒，按以下条件决定是否弹出 confirm：
 * - 浏览器支持 Notification API（permission != 'unsupported'）
 * - 当前权限为 'default'（用户既未授权也未拒绝）
 * - 30 天内未 dismiss 过本次 prompt
 * 用户确认 → 触发 requestPermission；取消或确认后均写 dismissed_at，避免短期内重复骚扰。
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
        /* 写入失败忽略，下次仍会 prompt */
      }
    })
    .catch(() => {
      try {
        localStorage.setItem(NOTIFICATION_PROMPT_KEY, String(Date.now()))
      } catch (_e) {
        /* 同上 */
      }
    })
}

onMounted(() => {
  connectAlertSse((event) => {
    notificationStore.pushAlert(event)
  })
  notificationPromptTimer = setTimeout(() => {
    notificationPromptTimer = null
    maybePromptForNotification()
  }, NOTIFICATION_PROMPT_DELAY_MS)
})

onBeforeUnmount(() => {
  closeAlertSse()
  if (notificationPromptTimer) {
    clearTimeout(notificationPromptTimer)
    notificationPromptTimer = null
  }
})
</script>

<style scoped>
.main-container {
  height: 100vh;
  width: 100vw;

  .main-header {
    height: 55px;
    background-color: var(--el-bg-color);
    border-bottom: solid 1px var(--el-border-color);
    display: flex;
    align-items: center;

    .tabs {
      height: 55px;
      gap: 10px;
      flex: 1;
      align-items: center;
      display: flex;
      justify-content: right;
    }
  }

  .main-content {
    height: 100%;
    background-color: #f5f5f5;
  }
}

.dark .main-container .main-content {
  background-color: #232323;
}
</style>
