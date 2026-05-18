<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { fetchSmartSnapshot } from '@/net/smart'

const props = defineProps({
  /** 客户端ID。 */
  clientId: {
    type: Number,
    required: true
  },
  /** 客户端能力快照（来自 ClientDetailsVO.capabilities）。 */
  capabilities: {
    type: Object,
    default: () => null
  }
})

const snapshot = ref(null)
const loading = ref(true)

/**
 * 客户端是否启用并可用 SMART 采集。
 */
const smartAvailable = computed(() => {
  const cap = props.capabilities && props.capabilities.smart
  if (!cap) return false
  return cap.available === true
})

/**
 * 获取 token 用于 SSE 订阅。
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

let smartEventSource = null
let smartRetryDelay = 1000
const SMART_SSE_MAX_DELAY = 60000

/**
 * 订阅指定主机 SMART 快照 SSE 事件，自动指数退避重连。
 *
 * @param {number} clientId 客户端ID
 */
function connectSmartSSE(clientId) {
  if (smartEventSource) {
    smartEventSource.close()
    smartEventSource = null
  }
  if (clientId === -1) return
  const token = getToken()
  if (!token) return
  const baseUrl = import.meta.env.VITE_API_BASE_URL || ''
  smartEventSource = new EventSource(`${baseUrl}/api/sse/smart/${clientId}?token=${token}`)
  smartEventSource.addEventListener('smart-snapshot', (event) => {
    try {
      snapshot.value = JSON.parse(event.data)
      loading.value = false
      smartRetryDelay = 1000
    } catch (_e) {
      // ignore parse error to keep stream alive
    }
  })
  smartEventSource.onerror = () => {
    if (smartEventSource) smartEventSource.close()
    setTimeout(() => {
      if (props.clientId !== -1) connectSmartSSE(props.clientId)
    }, smartRetryDelay)
    smartRetryDelay = Math.min(smartRetryDelay * 2, SMART_SSE_MAX_DELAY)
  }
}

function loadSnapshot(id) {
  if (id === -1 || !smartAvailable.value) {
    loading.value = false
    return
  }
  loading.value = true
  snapshot.value = null
  fetchSmartSnapshot(
    id,
    (data) => {
      snapshot.value = data
      loading.value = false
    },
    () => {
      loading.value = false
    }
  )
  connectSmartSSE(id)
}

watch(() => props.clientId, loadSnapshot, { immediate: true })
watch(smartAvailable, (value) => {
  if (value) {
    loadSnapshot(props.clientId)
  } else if (smartEventSource) {
    smartEventSource.close()
    smartEventSource = null
  }
})

onBeforeUnmount(() => {
  if (smartEventSource) {
    smartEventSource.close()
    smartEventSource = null
  }
})

const disks = computed(() => (snapshot.value && snapshot.value.disks) || [])

/**
 * 选择温度的状态标签 type。
 *
 * @param {number} temp 温度
 * @returns {string} Element Plus tag type
 */
function temperatureType(temp) {
  if (temp == null) return 'info'
  if (temp >= 70) return 'danger'
  if (temp >= 55) return 'warning'
  return 'success'
}
</script>

<template>
  <div class="smart-health">
    <div v-if="!smartAvailable" class="smart-empty">
      <el-empty description="该主机未启用 SMART 监控或 smartctl 不可用" />
    </div>
    <template v-else>
      <el-skeleton v-if="loading" :rows="4" animated />
      <template v-else>
        <el-empty v-if="!disks.length" description="暂无 SMART 数据" />
        <div v-else>
          <el-table :data="disks" stripe size="small">
            <el-table-column prop="device" label="设备" min-width="120" />
            <el-table-column prop="modelName" label="型号" min-width="160">
              <template #default="{ row }">
                <span>{{ row.modelName || '-' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="类型" width="80">
              <template #default="{ row }">
                <el-tag size="small" :type="row.nvme ? 'primary' : 'info'" effect="plain">
                  {{ row.nvme ? 'NVMe' : 'SATA' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="温度" width="100">
              <template #default="{ row }">
                <el-tag
                  v-if="row.temperatureCelsius != null"
                  :type="temperatureType(row.temperatureCelsius)"
                  size="small"
                >
                  {{ row.temperatureCelsius }} °C
                </el-tag>
                <span v-else>-</span>
              </template>
            </el-table-column>
            <el-table-column label="Reallocated" width="110">
              <template #default="{ row }">
                <span :style="{ color: row.reallocatedSector > 0 ? '#f56c6c' : 'inherit' }">
                  {{ row.reallocatedSector ?? '-' }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="Pending" width="100">
              <template #default="{ row }">
                <span :style="{ color: row.currentPending > 0 ? '#f56c6c' : 'inherit' }">
                  {{ row.currentPending ?? '-' }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="Uncorrectable" width="120">
              <template #default="{ row }">
                <span :style="{ color: row.offlineUncorrectable > 0 ? '#f56c6c' : 'inherit' }">
                  {{ row.offlineUncorrectable ?? '-' }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="Media Errors" width="120">
              <template #default="{ row }">
                <span :style="{ color: row.mediaErrors > 0 ? '#f56c6c' : 'inherit' }">
                  {{ row.mediaErrors ?? '-' }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag :type="row.critical ? 'danger' : 'success'" size="small">
                  {{ row.critical ? '关键异常' : '正常' }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
          <div v-if="snapshot && snapshot.updatedAt" class="smart-updated">
            最近更新：{{ new Date(snapshot.updatedAt).toLocaleString() }}
          </div>
        </div>
      </template>
    </template>
  </div>
</template>

<style scoped>
.smart-health {
  width: 100%;
}

.smart-empty {
  padding: 30px 0;
}

.smart-updated {
  margin-top: 10px;
  font-size: 12px;
  color: grey;
  text-align: right;
}
</style>
