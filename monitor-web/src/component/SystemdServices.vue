<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { getSystemdSnapshot } from '@/net/systemd'
import { createReconnectingEventSource } from '@/net/sse'

const props = defineProps({
  /** 客户端 ID。 */
  clientId: {
    type: Number,
    required: true
  }
})

const units = ref([])
const updatedAt = ref(null)
const loading = ref(true)

/**
 * 全量拉取一次最新 systemd 快照（首次加载或 SSE 重连前调用）。
 */
function reloadSnapshot() {
  if (!props.clientId || props.clientId === -1) {
    loading.value = false
    return
  }
  loading.value = true
  getSystemdSnapshot(
    props.clientId,
    (data) => {
      if (data) {
        units.value = data.units || []
        updatedAt.value = data.updatedAt
      } else {
        units.value = []
        updatedAt.value = null
      }
      loading.value = false
    },
    () => {
      loading.value = false
    }
  )
}

const systemdSse = createReconnectingEventSource({
  path: () =>
    !props.clientId || props.clientId === -1 ? null : `/api/sse/systemd/${props.clientId}`,
  eventName: 'systemd-snapshot',
  shouldReconnect: () => !!props.clientId && props.clientId !== -1,
  onMessage: (data) => {
    units.value = data.units || []
    updatedAt.value = data.updatedAt
  }
})

/**
 * 订阅 systemd 快照 SSE 事件，断开后由公共控制器按指数退避自动重连。
 */
function connectSse() {
  systemdSse.connect()
}

watch(
  () => props.clientId,
  () => {
    reloadSnapshot()
    connectSse()
  },
  { immediate: true }
)

onBeforeUnmount(() => {
  systemdSse.close()
})

/**
 * 计算未健康 unit 数（前端显示用），与后端 systemdFailedCount 同义。
 */
const failedCount = computed(() => units.value.filter((u) => !u.healthy).length)

/**
 * 把 unit 状态映射为 Element Plus 徽章 type。
 *
 * @param {object} unit unit 状态对象
 * @returns {string} type
 */
function statusType(unit) {
  if (!unit) return 'info'
  if (unit.healthy) return 'success'
  if (unit.activeState === 'failed') return 'danger'
  return 'warning'
}

/**
 * 把 unit 状态映射为短文案。
 *
 * @param {object} unit unit 状态对象
 * @returns {string} 文案
 */
function statusLabel(unit) {
  if (!unit) return '未知'
  if (unit.healthy) return '运行中'
  if (unit.activeState === 'failed') return '失败'
  if (unit.activeState === 'inactive') return '已停止'
  if (unit.activeState === 'activating') return '启动中'
  if (unit.activeState === 'deactivating') return '停止中'
  return unit.activeState || '未知'
}

function formatUpdatedAt(value) {
  if (!value) return '尚未上报'
  try {
    return new Date(value).toLocaleString()
  } catch (_e) {
    return String(value)
  }
}
</script>

<template>
  <div class="systemd-services">
    <div class="header">
      <span class="title">
        <i class="fa-solid fa-gears"></i>
        systemd 服务
      </span>
      <span class="updated-at" v-if="!loading">
        最近更新：{{ formatUpdatedAt(updatedAt) }}
      </span>
      <el-tag v-if="!loading && failedCount > 0" type="danger" effect="dark" size="small">
        {{ failedCount }} 个失败
      </el-tag>
      <el-tag
        v-else-if="!loading && units.length > 0"
        type="success"
        effect="plain"
        size="small"
      >
        全部健康
      </el-tag>
    </div>
    <el-skeleton v-if="loading" :rows="4" animated />
    <el-empty
      v-else-if="!units.length"
      description="客户端未启用 systemd 采集或暂无数据"
    />
    <el-table v-else :data="units" stripe size="small">
      <el-table-column prop="name" label="Unit" min-width="160" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusType(row)" size="small" effect="dark">
            {{ statusLabel(row) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="loadState" label="LoadState" width="120" />
      <el-table-column prop="activeState" label="ActiveState" width="120" />
      <el-table-column prop="subState" label="SubState" width="120" />
      <el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip />
    </el-table>
  </div>
</template>

<style scoped>
.systemd-services {
  padding: 10px 0;
}
.header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
}
.title {
  font-size: 16px;
  font-weight: bold;
  color: var(--el-color-primary);
}
.updated-at {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
