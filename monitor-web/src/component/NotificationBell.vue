<script setup>
import { computed, onMounted, ref } from 'vue'
import { Bell } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'
import { useNotificationStore } from '@/store/notification'
import { levelMeta } from '@/tools/alert'

const router = useRouter()
const notificationStore = useNotificationStore()
const visible = ref(false)

const items = computed(() => notificationStore.recentAlerts)
const unread = computed(() => notificationStore.unreadCount)

/**
 * 用户点击单条告警跳转到告警历史页并清空未读计数。
 */
function viewAll() {
  notificationStore.clearUnread()
  visible.value = false
  router.push({ name: 'alert-history' })
}

/**
 * 用户主动请求浏览器通知权限。
 */
async function requestPermission() {
  await notificationStore.requestPermission()
}

/**
 * 时间格式化为本地短串，列表中使用。
 *
 * @param {string|Date} value firedAt
 * @returns {string} 格式化字符串
 */
function formatTime(value) {
  if (!value) return ''
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return ''
  const pad = (n) => String(n).padStart(2, '0')
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

onMounted(() => {
  // 用户首次打开页面时不主动请求权限，等待手动点击。
})
</script>

<template>
  <el-popover
    placement="bottom-end"
    :width="320"
    trigger="click"
    v-model:visible="visible"
    @show="notificationStore.clearUnread()"
  >
    <template #reference>
      <el-badge :value="unread" :hidden="unread === 0" :max="99" class="bell-badge">
        <el-button :icon="Bell" circle text aria-label="打开通知中心" />
      </el-badge>
    </template>
    <div class="bell-panel">
      <div class="bell-header">
        <span style="font-weight: bold">通知中心</span>
        <el-button
          v-if="notificationStore.permission !== 'granted'"
          size="small"
          link
          type="primary"
          @click="requestPermission"
        >
          {{ notificationStore.permission === 'denied' ? '通知已拒绝' : '启用浏览器通知' }}
        </el-button>
      </div>
      <el-divider style="margin: 8px 0" />
      <div v-if="items.length === 0" class="bell-empty">暂无新告警</div>
      <el-scrollbar v-else max-height="320px">
        <div v-for="alert in items" :key="alert.id" class="bell-item" @click="viewAll">
          <el-tag size="small" :type="levelMeta(alert.level).type">
            {{ levelMeta(alert.level).label }}
          </el-tag>
          <div class="bell-item-content">
            <div class="bell-item-message">
              {{ alert.message || `规则 ${alert.ruleName || alert.ruleId} 触发` }}
            </div>
            <div class="bell-item-time">{{ formatTime(alert.firedAt) }}</div>
          </div>
        </div>
      </el-scrollbar>
      <el-divider style="margin: 8px 0" />
      <div style="text-align: center">
        <el-button size="small" type="primary" link @click="viewAll">查看全部告警</el-button>
      </div>
    </div>
  </el-popover>
</template>

<style scoped>
.bell-badge :deep(.el-badge__content) {
  z-index: 1;
}
.bell-panel {
  padding: 4px;
}
.bell-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.bell-empty {
  padding: 20px;
  text-align: center;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.bell-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 8px;
  border-radius: 6px;
  cursor: pointer;
  transition: background-color 0.2s;
}
.bell-item:hover {
  background-color: var(--el-fill-color-light);
}
.bell-item-content {
  flex: 1;
  min-width: 0;
}
.bell-item-message {
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}
.bell-item-time {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 4px;
}
</style>
