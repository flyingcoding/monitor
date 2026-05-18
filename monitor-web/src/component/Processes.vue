<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { getProcessSnapshot } from '@/net/process'

const props = defineProps({
  /** 主机ID；-1 表示尚未选中任何主机。 */
  clientId: Number
})

const loading = ref(true)
const snapshot = ref(null)
let eventSource = null
let retryDelay = 1000
const MAX_RETRY_DELAY = 60000

/**
 * 取登录 token，用于 SSE 鉴权。
 *
 * @returns {string | null}
 */
function getToken() {
  const str = localStorage.getItem('authorize') || sessionStorage.getItem('authorize')
  if (!str) return null
  try {
    return JSON.parse(str).token
  } catch (_e) {
    return null
  }
}

/**
 * 把字节数格式化为 MB / GB 字符串。
 *
 * @param {number} bytes
 * @returns {string}
 */
function formatMemory(bytes) {
  if (!bytes || bytes <= 0) return '0 MB'
  const mb = bytes / 1024 / 1024
  if (mb >= 1024) return `${(mb / 1024).toFixed(2)} GB`
  return `${mb.toFixed(1)} MB`
}

/**
 * 把 0~1 的 CPU 占比格式化为百分比字符串。
 *
 * @param {number} value
 * @returns {string}
 */
function formatPercent(value) {
  if (value === null || value === undefined || Number.isNaN(value)) return '0.0%'
  return `${(value * 100).toFixed(1)}%`
}

const cpuRows = computed(() => snapshot.value?.top10ByCpu || [])
const memoryRows = computed(() => snapshot.value?.top10ByMemory || [])
const watchedEntries = computed(() => {
  const watched = snapshot.value?.watchedPatterns
  if (!watched || typeof watched !== 'object') return []
  return Object.entries(watched).map(([pattern, hit]) => ({ pattern, hit: !!hit }))
})

const missingCount = computed(() => watchedEntries.value.filter((entry) => !entry.hit).length)

/**
 * 建立进程快照 SSE 订阅，断开时按指数退避重连。
 *
 * @param {number} clientId 主机ID
 */
function connectSSE(clientId) {
  closeSSE()
  if (clientId === -1) return
  const token = getToken()
  if (!token) return
  const baseUrl = import.meta.env.VITE_API_BASE_URL || ''
  eventSource = new EventSource(`${baseUrl}/api/sse/process/${clientId}?token=${token}`)
  eventSource.addEventListener('process-snapshot', (event) => {
    try {
      snapshot.value = JSON.parse(event.data)
      loading.value = false
      retryDelay = 1000
    } catch (_e) {
      // 数据解析异常仅打 debug，不打扰用户
    }
  })
  eventSource.onerror = () => {
    closeSSE()
    setTimeout(() => {
      if (props.clientId !== -1) connectSSE(props.clientId)
    }, retryDelay)
    retryDelay = Math.min(retryDelay * 2, MAX_RETRY_DELAY)
  }
}

/**
 * 关闭 SSE 流。
 */
function closeSSE() {
  if (eventSource) {
    eventSource.close()
    eventSource = null
  }
}

/**
 * 首次进入或主机切换时拉取一次最新快照，并启动 SSE 订阅。
 *
 * @param {number} clientId
 */
function refresh(clientId) {
  if (clientId === -1) {
    loading.value = false
    snapshot.value = null
    closeSSE()
    return
  }
  loading.value = true
  snapshot.value = null
  getProcessSnapshot(
    clientId,
    (data) => {
      snapshot.value = data
      loading.value = false
    },
    () => {
      loading.value = false
    }
  )
  connectSSE(clientId)
}

watch(() => props.clientId, refresh, { immediate: true })

onBeforeUnmount(() => {
  closeSSE()
})
</script>

<template>
  <div class="processes-tab">
    <el-skeleton v-if="loading" :rows="6" animated />
    <template v-else-if="snapshot">
      <div class="title">
        <i class="fa-solid fa-list-ol"></i>
        关键进程
      </div>
      <el-divider style="margin: 10px 0" />
      <div v-if="watchedEntries.length">
        <el-tag
          v-for="entry in watchedEntries"
          :key="entry.pattern"
          :type="entry.hit ? 'success' : 'danger'"
          effect="plain"
          class="watched-tag"
        >
          <i :class="entry.hit ? 'fa-solid fa-check' : 'fa-solid fa-xmark'"></i>
          {{ entry.pattern }}
        </el-tag>
        <div class="missing-summary" v-if="missingCount > 0">
          <span style="color: #f56c6c"
            ><i class="fa-solid fa-triangle-exclamation"></i> 缺失 {{ missingCount }} 个关键进程</span
          >
        </div>
      </div>
      <el-empty v-else description="未配置 monitor.collect.process.patterns" :image-size="60" />

      <div class="title" style="margin-top: 20px">
        <i class="fa-solid fa-microchip"></i>
        Top {{ cpuRows.length }} CPU 使用率
      </div>
      <el-divider style="margin: 10px 0" />
      <el-table :data="cpuRows" size="small" stripe :empty-text="'暂无进程数据'">
        <el-table-column prop="name" label="进程" min-width="160" show-overflow-tooltip />
        <el-table-column prop="pid" label="PID" width="80" />
        <el-table-column label="CPU" width="100">
          <template #default="{ row }">{{ formatPercent(row.cpuPercent) }}</template>
        </el-table-column>
        <el-table-column label="内存" width="110">
          <template #default="{ row }">{{ formatMemory(row.memoryBytes) }}</template>
        </el-table-column>
      </el-table>

      <div class="title" style="margin-top: 20px">
        <i class="fa-solid fa-memory"></i>
        Top {{ memoryRows.length }} 内存占用
      </div>
      <el-divider style="margin: 10px 0" />
      <el-table :data="memoryRows" size="small" stripe :empty-text="'暂无进程数据'">
        <el-table-column prop="name" label="进程" min-width="160" show-overflow-tooltip />
        <el-table-column prop="pid" label="PID" width="80" />
        <el-table-column label="内存" width="110">
          <template #default="{ row }">{{ formatMemory(row.memoryBytes) }}</template>
        </el-table-column>
        <el-table-column label="CPU" width="100">
          <template #default="{ row }">{{ formatPercent(row.cpuPercent) }}</template>
        </el-table-column>
      </el-table>
    </template>
    <el-empty v-else description="客户端尚未上报进程快照，请稍后" />
  </div>
</template>

<style scoped>
.processes-tab {
  padding: 10px 0;
}

.title {
  color: var(--el-color-primary);
  font-size: 16px;
  font-weight: bold;
}

.watched-tag {
  margin-right: 8px;
  margin-bottom: 6px;
}

.missing-summary {
  margin-top: 8px;
  font-size: 13px;
}
</style>
