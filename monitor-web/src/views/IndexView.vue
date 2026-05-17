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
  { id: 4, name: '状态页', route: 'status-page-config', adminOnly: true }
]

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

onMounted(() => {
  connectAlertSse((event) => {
    notificationStore.pushAlert(event)
  })
})

onBeforeUnmount(() => {
  closeAlertSse()
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
