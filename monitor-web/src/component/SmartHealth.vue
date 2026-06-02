<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { fetchSmartSnapshot } from '@/net/smart'
import { createReconnectingEventSource } from '@/net/sse'
import { formatUpdatedAt, thresholdTagType } from '@/tools/format'

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
const SMART_TEMP_DANGER = 70
const SMART_TEMP_WARNING = 55

/**
 * 客户端是否启用并可用 SMART 采集。
 */
const smartAvailable = computed(() => {
  const cap = props.capabilities && props.capabilities.smart
  if (!cap) return false
  return cap.available === true
})

const smartSse = createReconnectingEventSource({
  path: () =>
    props.clientId === -1 || !smartAvailable.value ? null : `/api/sse/smart/${props.clientId}`,
  eventName: 'smart-snapshot',
  shouldReconnect: () => props.clientId !== -1 && smartAvailable.value,
  onMessage: (data) => {
    snapshot.value = data
    loading.value = false
  }
})

/**
 * 订阅当前主机 SMART 快照 SSE 事件，断开后由公共控制器自动指数退避重连。
 */
function connectSmartSSE() {
  smartSse.connect()
}

function loadSnapshot(id) {
  if (id === -1 || !smartAvailable.value) {
    loading.value = false
    smartSse.close()
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
  connectSmartSSE()
}

watch(() => props.clientId, loadSnapshot, { immediate: true })
watch(smartAvailable, (value) => {
  if (value) {
    loadSnapshot(props.clientId)
  } else {
    smartSse.close()
  }
})

onBeforeUnmount(() => {
  smartSse.close()
})

const disks = computed(() => (snapshot.value && snapshot.value.disks) || [])

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
                  :type="thresholdTagType(row.temperatureCelsius, SMART_TEMP_DANGER, SMART_TEMP_WARNING)"
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
            最近更新：{{ formatUpdatedAt(snapshot.updatedAt) }}
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
