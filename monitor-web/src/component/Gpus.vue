<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { fetchGpuSnapshot } from '@/net/gpu'
import { createReconnectingEventSource } from '@/net/sse'
import { formatNumber, formatUpdatedAt, thresholdProgressStatus, thresholdTagType } from '@/tools/format'

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
const GPU_TEMP_DANGER = 85
const GPU_TEMP_WARNING = 75
const GPU_UTIL_EXCEPTION = 90
const GPU_UTIL_WARNING = 70

/**
 * 客户端是否启用并可用 NVIDIA GPU 采集。
 */
const gpuAvailable = computed(() => {
  const cap = props.capabilities && props.capabilities.gpu
  if (!cap) return false
  return cap.available === true
})

const gpuSse = createReconnectingEventSource({
  path: () => (props.clientId === -1 || !gpuAvailable.value ? null : `/api/sse/gpu/${props.clientId}`),
  eventName: 'gpu-snapshot',
  shouldReconnect: () => props.clientId !== -1 && gpuAvailable.value,
  onMessage: (data) => {
    snapshot.value = data
    loading.value = false
  }
})

/**
 * 订阅当前主机 GPU 快照 SSE 事件，断开后由公共控制器自动指数退避重连。
 */
function connectGpuSSE() {
  gpuSse.connect()
}

/**
 * 全量拉取一次最新 GPU 快照。
 *
 * @param {number} id 客户端ID
 */
function loadSnapshot(id) {
  if (id === -1 || !gpuAvailable.value) {
    loading.value = false
    gpuSse.close()
    return
  }
  loading.value = true
  snapshot.value = null
  fetchGpuSnapshot(
    id,
    (data) => {
      snapshot.value = data
      loading.value = false
    },
    () => {
      loading.value = false
    }
  )
  connectGpuSSE()
}

watch(() => props.clientId, loadSnapshot, { immediate: true })
watch(gpuAvailable, (value) => {
  if (value) {
    loadSnapshot(props.clientId)
  } else {
    gpuSse.close()
  }
})

onBeforeUnmount(() => {
  gpuSse.close()
})

const gpus = computed(() => (snapshot.value && snapshot.value.gpus) || [])

/**
 * 显存使用百分比；total 缺失或为 0 时返回 null。
 *
 * @param {object} gpu GPU 数据
 * @returns {number|null} 百分比
 */
function memoryPercent(gpu) {
  if (!gpu) return null
  const used = gpu.memoryUsedMb
  const total = gpu.memoryTotalMb
  if (used == null || total == null || total <= 0) return null
  return (used / total) * 100
}
</script>

<template>
  <div class="gpus">
    <div v-if="!gpuAvailable" class="gpus-empty">
      <el-empty description="该主机未启用 GPU 监控或 nvidia-smi 不可用" />
    </div>
    <template v-else>
      <el-skeleton v-if="loading" :rows="4" animated />
      <template v-else>
        <el-empty v-if="!gpus.length" description="暂无 GPU 数据" />
        <div v-else>
          <div class="gpu-cards">
            <div v-for="gpu in gpus" :key="gpu.index" class="gpu-card">
              <div class="gpu-card-header">
                <span class="gpu-name">
                  <i class="fa-solid fa-microchip"></i>
                  GPU {{ gpu.index }}
                </span>
                <el-tag size="small" type="primary" effect="plain">
                  {{ gpu.name || '未知型号' }}
                </el-tag>
                <el-tag
                  v-if="gpu.temperatureCelsius != null"
                  :type="thresholdTagType(gpu.temperatureCelsius, GPU_TEMP_DANGER, GPU_TEMP_WARNING)"
                  size="small"
                  effect="dark"
                >
                  {{ formatNumber(gpu.temperatureCelsius) }} °C
                </el-tag>
              </div>
              <div class="gpu-metrics">
                <div class="metric">
                  <div class="metric-label">利用率</div>
                  <el-progress
                    v-if="gpu.utilizationPercent != null"
                    :percentage="Number(gpu.utilizationPercent)"
                    :status="
                      thresholdProgressStatus(
                        gpu.utilizationPercent,
                        GPU_UTIL_EXCEPTION,
                        GPU_UTIL_WARNING
                      )
                    "
                    :stroke-width="14"
                  />
                  <span v-else class="metric-na">-</span>
                </div>
                <div class="metric">
                  <div class="metric-label">显存</div>
                  <el-progress
                    v-if="memoryPercent(gpu) != null"
                    :percentage="Number(memoryPercent(gpu).toFixed(1))"
                    :stroke-width="14"
                  />
                  <span v-else class="metric-na">-</span>
                  <div class="metric-detail">
                    {{ formatNumber(gpu.memoryUsedMb, 0) }} / {{ formatNumber(gpu.memoryTotalMb, 0) }} MB
                  </div>
                </div>
                <div class="metric">
                  <div class="metric-label">功耗</div>
                  <div class="metric-value">
                    {{ formatNumber(gpu.powerDrawWatts) }} W
                  </div>
                </div>
              </div>
            </div>
          </div>
          <div v-if="snapshot && snapshot.updatedAt" class="gpu-updated">
            最近更新：{{ formatUpdatedAt(snapshot.updatedAt) }}
          </div>
        </div>
      </template>
    </template>
  </div>
</template>

<style scoped>
.gpus {
  width: 100%;
}

.gpus-empty {
  padding: 30px 0;
}

.gpu-cards {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.gpu-card {
  padding: 12px;
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  background: var(--el-bg-color);
}

.gpu-card-header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.gpu-name {
  font-size: 15px;
  font-weight: bold;
  color: var(--el-color-primary);
}

.gpu-metrics {
  display: grid;
  grid-template-columns: 1fr 1fr 100px;
  gap: 16px;
  align-items: start;
}

.metric {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.metric-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.metric-value {
  font-size: 14px;
  font-weight: bold;
}

.metric-detail {
  font-size: 11px;
  color: var(--el-text-color-secondary);
}

.metric-na {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.gpu-updated {
  margin-top: 10px;
  font-size: 12px;
  color: grey;
  text-align: right;
}
</style>
