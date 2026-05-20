<script setup>
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { get, post } from '@/net'
import {
  copyIp,
  cpuNameToImage,
  fitByUnit,
  osNameToIcon,
  percentageToStatus,
  rename
} from '@/tools'
import { downloadCsv } from '@/tools/csv'
import { ElMessage, ElMessageBox } from 'element-plus'
import RuntimeHistory from '@/component/RuntimeHistory.vue'
import Gpus from '@/component/Gpus.vue'
import Processes from '@/component/Processes.vue'
import SmartHealth from '@/component/SmartHealth.vue'
import SystemdServices from '@/component/SystemdServices.vue'
import { Connection, Delete, Download } from '@element-plus/icons-vue'

const locations = [
  { name: 'cn', desc: '中国大陆' },
  { name: 'hk', desc: '香港' },
  { name: 'jp', desc: '日本' },
  { name: 'us', desc: '美国' },
  { name: 'sg', desc: '新加坡' },
  { name: 'kr', desc: '韩国' },
  { name: 'de', desc: '德国' }
]
const props = defineProps({
  id: Number,
  update: Function
})
const emits = defineEmits(['delete', 'terminal'])
const details = reactive({
  base: {},
  runtime: {
    list: []
  },
  editNode: false
})
const baseLoading = ref(true)
const runtimeLoading = ref(true)

// 时间范围预设（毫秒）
const TIME_RANGE_PRESETS = [
  { key: '1h', label: '1 小时', ms: 3600e3 },
  { key: '6h', label: '6 小时', ms: 6 * 3600e3 },
  { key: '24h', label: '24 小时', ms: 24 * 3600e3 },
  { key: '7d', label: '7 天', ms: 7 * 24 * 3600e3 }
]
const MAX_RANGE_MS = 7 * 24 * 3600e3
// SSE 实时增量缓存上限（仅在 preset === '1h' 时生效）
const SSE_LIVE_CAP = 360

// 当前时间范围；preset 与 custom 二选一，custom 时 preset 为 null
const timeRange = reactive({ preset: '1h', custom: null })
const customRange = ref(null)
// 上一次合法的 customRange 值，校验失败时回滚
let lastValidCustomRange = null

/**
 * 当前时间范围是否处于 "1h 实时" 模式：仅此模式下 SSE 增量会拼接到历史曲线，
 * 其他时段（6h / 24h / 7d / 自定义）切到固定窗口视图，SSE 不再扩展 list，
 * 避免 7d 视图被 SSE 反复扩张直到内存压力 / 渲染卡顿。
 */
const isLiveMode = computed(() => timeRange.preset === '1h')

/**
 * 把 reactive timeRange 算成 ISO 起止时间；preset === '1h' 时返回 null
 * 让后端走旧默认 1h 行为（向后兼容）。
 */
function resolveQueryRange() {
  if (timeRange.preset === '1h') {
    return null
  }
  if (timeRange.preset) {
    const preset = TIME_RANGE_PRESETS.find((p) => p.key === timeRange.preset)
    if (!preset) return null
    const now = new Date()
    return {
      from: new Date(now.getTime() - preset.ms).toISOString(),
      to: now.toISOString()
    }
  }
  if (timeRange.custom && timeRange.custom.length === 2) {
    return {
      from: new Date(timeRange.custom[0]).toISOString(),
      to: new Date(timeRange.custom[1]).toISOString()
    }
  }
  return null
}
const nodeEdit = reactive({
  name: '',
  location: ''
})
const enableNodeEdit = () => {
  details.editNode = true
  nodeEdit.name = details.base.node
  nodeEdit.location = details.base.location
}
const submitNodeEdit = () => {
  post(
    '/api/monitor/node',
    {
      id: props.id,
      node: nodeEdit.name,
      location: nodeEdit.location
    },
    () => {
      details.editNode = false
      updateDetails()
      ElMessage.success('节点信息更新成功！')
    }
  )
}
function updateDetails() {
  props.update()
  init(props.id)
}

function deleteClient() {
  ElMessageBox.confirm('删除此主机后所有统计数据都将丢失，您确定要这样做吗？', '删除主机', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    type: 'warning'
  })
    .then(() => {
      get(`/api/monitor/delete?clientId=${props.id}`, () => {
        emits('delete')
        props.update()
        ElMessage.success('主机已成功移除')
      })
    })
    .catch(() => {})
}

// 获取 token 用于 SSE
function getToken() {
  const str = localStorage.getItem('authorize') || sessionStorage.getItem('authorize')
  if (!str) return null
  return JSON.parse(str).token
}

// SSE 订阅替代轮询
let runtimeEventSource = null
let runtimeRetryDelay = 1000
const RUNTIME_SSE_MAX_DELAY = 60000

/**
 * 建立指定主机运行时SSE连接，并在断开时按指数退避策略重连。
 *
 * @param {number} clientId 主机ID
 */
function connectRuntimeSSE(clientId) {
  if (runtimeEventSource) {
    runtimeEventSource.close()
    runtimeEventSource = null
  }
  if (clientId === -1) return
  const token = getToken()
  if (!token) return
  const baseUrl = import.meta.env.VITE_API_BASE_URL || ''
  runtimeEventSource = new EventSource(`${baseUrl}/api/sse/runtime/${clientId}?token=${token}`)
  runtimeEventSource.addEventListener('runtime', (event) => {
    const data = JSON.parse(event.data)
    // 仅在 1h 实时模式下拼接 SSE 增量；其他时段视图冻结，避免无限增长。
    // 历史窗口可能含 1k+ 点（7d step 10min），SSE 拼接会把列表迅速放大并扰乱聚合曲线语义。
    if (isLiveMode.value) {
      if (details.runtime.list.length >= SSE_LIVE_CAP) details.runtime.list.splice(0, 1)
      details.runtime.list.push(data)
    }
    runtimeLoading.value = false
    runtimeRetryDelay = 1000
  })
  runtimeEventSource.onerror = () => {
    if (runtimeEventSource) runtimeEventSource.close()
    setTimeout(() => {
      if (props.id !== -1) connectRuntimeSSE(props.id)
    }, runtimeRetryDelay)
    runtimeRetryDelay = Math.min(runtimeRetryDelay * 2, RUNTIME_SSE_MAX_DELAY)
  }
}

onBeforeUnmount(() => {
  if (runtimeEventSource) {
    runtimeEventSource.close()
    runtimeEventSource = null
  }
})

const now = computed(() => details.runtime.list[details.runtime.list.length - 1])

/**
 * 解析 capabilities_json 字符串为对象，失败时返回 null。
 *
 * @returns {object|null} capabilities 对象
 */
const capabilities = computed(() => {
  const raw = details.base && details.base.capabilitiesJson
  if (!raw) return null
  try {
    return typeof raw === 'string' ? JSON.parse(raw) : raw
  } catch (_e) {
    return null
  }
})

/**
 * 仅当客户端启用且 systemctl 可用时显示 systemd tab。
 */
const showSystemdTab = computed(() => {
  const cap = capabilities.value
  return !!(cap && cap.systemd && cap.systemd.available)
})

/**
 * 仅当客户端启用且 smartctl 可用时显示 SMART tab。
 */
const showSmartTab = computed(() => {
  const cap = capabilities.value
  return !!(cap && cap.smart && cap.smart.available)
})

/**
 * 仅当客户端启用进程采集（patterns 非空）时显示进程 tab。
 */
const showProcessTab = computed(() => {
  const cap = capabilities.value
  return !!(cap && cap.process && cap.process.enabled)
})

/**
 * 仅当客户端启用且 nvidia-smi 可用时显示 GPU tab。
 */
const showGpuTab = computed(() => {
  const cap = capabilities.value
  return !!(cap && cap.gpu && cap.gpu.available)
})

/**
 * 拉取当前 timeRange 对应的历史数据并写回 details.runtime。
 * preset === '1h' 时不传 from/to，让后端走旧默认 1h 行为。
 */
function loadHistory() {
  if (props.id === -1) return
  runtimeLoading.value = true
  details.runtime = { list: [] }
  const range = resolveQueryRange()
  const params = new URLSearchParams({ clientId: String(props.id) })
  if (range) {
    params.set('from', range.from)
    params.set('to', range.to)
  }
  get(`/api/monitor/runtime_history?${params.toString()}`, (data) => {
    Object.assign(details.runtime, data)
    runtimeLoading.value = false
  })
}

/**
 * 切到预设时段：重置 customRange，重发 history 请求。
 */
function selectPreset(key) {
  timeRange.preset = key
  timeRange.custom = null
  customRange.value = null
  lastValidCustomRange = null
  loadHistory()
}

/**
 * datetimerange 变更回调：跨度 ≤ 7 天时切换到自定义模式并重发 history；
 * 跨度超限时 ElMessage.warning + 回滚到上次合法值（datepicker 自身已 disabledDate，
 * 这里是多步操作的兜底，例如 clear + 手动键入）。
 */
function onCustomRangeChange(value) {
  if (!value || value.length !== 2) {
    customRange.value = lastValidCustomRange
    return
  }
  const from = new Date(value[0]).getTime()
  const to = new Date(value[1]).getTime()
  if (!Number.isFinite(from) || !Number.isFinite(to) || from >= to) {
    ElMessage.warning('请选择有效的时间范围')
    customRange.value = lastValidCustomRange
    return
  }
  if (to - from > MAX_RANGE_MS) {
    ElMessage.warning('时间跨度不能超过 7 天')
    customRange.value = lastValidCustomRange
    return
  }
  lastValidCustomRange = value
  timeRange.preset = null
  timeRange.custom = value
  loadHistory()
}

/**
 * datepicker disabledDate：禁选未来日期；禁选 now 之前 7 天以外的日期。
 * 注意 disabledDate 只能对每个日期单独判断，无法直接限制 "跨度 ≤ 7 天"，
 * 跨度规则由 {@link onCustomRangeChange} 兜底。
 */
function disabledDate(date) {
  const now = Date.now()
  const ts = date.getTime()
  return ts > now || ts < now - MAX_RANGE_MS
}

/**
 * CSV 导出列定义；字段名与后端 RuntimeHistoryVO.list[].* 形状一致。
 */
const csvColumns = [
  { key: 'timestamp', label: '时间', format: (v) => (v ? new Date(v).toISOString() : '') },
  {
    key: 'cpuUsage',
    label: 'CPU 使用率(%)',
    // 后端原始值是 0~1 比例，按 % 导出并保留 2 位小数，与图表展示一致
    format: (v) => (typeof v === 'number' ? (v * 100).toFixed(2) : '')
  },
  {
    key: 'memoryUsage',
    label: '内存使用(GB)',
    format: (v) => (typeof v === 'number' ? v.toFixed(3) : '')
  },
  {
    key: 'diskUsage',
    label: '磁盘使用(GB)',
    format: (v) => (typeof v === 'number' ? v.toFixed(3) : '')
  },
  {
    key: 'diskRead',
    label: '磁盘读(MB/s)',
    format: (v) => (typeof v === 'number' ? v.toFixed(3) : '')
  },
  {
    key: 'diskWrite',
    label: '磁盘写(MB/s)',
    format: (v) => (typeof v === 'number' ? v.toFixed(3) : '')
  },
  {
    key: 'networkUpload',
    label: '网络上行(KB/s)',
    format: (v) => (typeof v === 'number' ? v.toFixed(3) : '')
  },
  {
    key: 'networkDownload',
    label: '网络下行(KB/s)',
    format: (v) => (typeof v === 'number' ? v.toFixed(3) : '')
  }
]

/**
 * 导出当前图表窗口的运行时数据为 CSV。PRD §D5：CSV = 图表现场看到的数据（聚合后）。
 */
function exportRuntimeCsv() {
  if (!details.runtime.list || !details.runtime.list.length) {
    ElMessage.warning('暂无数据可导出')
    return
  }
  const tag = timeRange.preset || 'custom'
  const date = new Date().toISOString().slice(0, 10)
  const safeName = (details.base.name || `client-${props.id}`).replace(/[^\w一-龥-]/g, '_')
  downloadCsv(details.runtime.list, csvColumns, `runtime-${safeName}-${tag}-${date}`)
  ElMessage.success('CSV 已开始下载')
}

const init = (value) => {
  if (value !== -1) {
    baseLoading.value = true
    details.base = {}
    // 切换主机时重置时间范围到默认 1h 实时
    timeRange.preset = '1h'
    timeRange.custom = null
    customRange.value = null
    lastValidCustomRange = null
    connectRuntimeSSE(value)
    get(`/api/monitor/details?clientId=${value}`, (data) => {
      Object.assign(details.base, data)
      baseLoading.value = false
    })
    // 首次拉取走 loadHistory（preset === '1h' 时不传 from/to，与旧默认行为一致）
    loadHistory()
  } else {
    baseLoading.value = false
    runtimeLoading.value = false
    if (runtimeEventSource) {
      runtimeEventSource.close()
      runtimeEventSource = null
    }
  }
}
watch(() => props.id, init, { immediate: true })
</script>

<template>
  <el-scrollbar>
    <div class="client-details">
      <el-skeleton v-if="baseLoading" :rows="8" animated />
      <div v-else>
        <div style="display: flex; justify-content: space-between">
          <div class="title">
            <i class="fa-solid fa-server"></i>
            服务器信息
          </div>
          <div>
            <el-button :icon="Connection" type="primary" @click="emits('terminal', id)" plain text
              >SSH远程连接</el-button
            >
            <el-button
              :icon="Delete"
              type="danger"
              @click="deleteClient"
              style="margin-left: 0"
              plain
              text
              >删除此主机</el-button
            >
          </div>
        </div>
        <el-divider style="margin: 10px 0" />
        <div class="details-list">
          <div>
            <span>服务器ID</span>
            <span>{{ details.base.id }}</span>
          </div>
          <div>
            <span>服务器名称</span>
            <span style="margin-right: 10px">{{ details.base.name }}</span>
            <i
              class="fa-solid fa-pen-to-square interact-item"
              @click.stop="rename(details.base.id, details.base.name, updateDetails)"
            ></i>
          </div>
          <div>
            <span>运行状态</span>
            <span>
              <i
                style="color: #02ca02"
                class="fa-solid fa-circle-play"
                v-if="details.base.online"
              ></i>
              <i style="color: #8a8a8a" class="fa-solid fa-circle-stop" v-else></i>
              {{ details.base.online ? '运行中' : '离线' }}
            </span>
          </div>
          <div>
            <span>公网IP地址</span>
            <span>
              {{ details.base.ip }}
              <i
                class="fa-solid fa-copy interact-item"
                style="color: dodgerblue"
                @click.stop="copyIp(details.base.ip)"
              ></i>
            </span>
          </div>
          <div v-if="!details.editNode">
            <span>服务器节点</span>
            <span :class="`fi fi-${details.base.location}`"></span>&nbsp;
            <span>{{ details.base.node }}</span
            >&nbsp;
            <i @click.stop="enableNodeEdit" class="fa-solid fa-pen-to-square interact-item" />
          </div>
          <div v-else>
            <span>服务器节点</span>
            <div style="display: inline-block; height: 15px">
              <div style="display: flex">
                <el-select v-model="nodeEdit.location" style="width: 80px" size="small">
                  <el-option v-for="item in locations" :key="item.name" :value="item.name">
                    <span :class="`fi fi-${item.name}`"></span>&nbsp;
                    {{ item.desc }}
                  </el-option>
                </el-select>
                <el-input
                  v-model="nodeEdit.name"
                  style="margin-left: 10px"
                  size="small"
                  placeholder="请输入节点名称..."
                />
                <div style="margin-left: 10px">
                  <i @click.stop="submitNodeEdit" class="fa-solid fa-check interact-item" />
                </div>
              </div>
            </div>
          </div>
          <div style="display: flex">
            <span>处理器</span>
            <span>{{ details.base.cpuName }}</span>
            <el-image
              style="margin-left: 10px; height: 20px"
              :src="`/cpu-icons/${cpuNameToImage(details.base.cpuName)}`"
            />
          </div>
          <div>
            <span>硬件信息</span>
            <i class="fa-solid fa-microchip"></i>
            <span>{{ ` ${details.base.cpuCore} CPU 核心数 / ` }}</span>
            <i class="fa-solid fa-memory"></i>
            <span>{{ ` ${details.base.memory.toFixed(1)} GB 内存容量` }}</span>
          </div>
          <div>
            <span>操作系统</span>
            <i
              :style="{ color: osNameToIcon(details.base.osName).color }"
              :class="`fa-brands ${osNameToIcon(details.base.osName).icon}`"
            />
            <span style="margin-left: 10px">{{
              `${details.base.osName} ${details.base.osVersion}`
            }}</span>
          </div>
        </div>
        <div class="title">
          <i class="fa-solid fa-gauge-high"></i>
          实时监控
        </div>
        <el-divider style="margin: 10px 0" />
        <div v-if="details.base.online" style="min-height: 200px">
          <el-skeleton v-if="runtimeLoading" :rows="6" animated />
          <template v-else>
            <div style="display: flex" v-if="details.runtime.list.length">
              <el-progress
                type="dashboard"
                :width="100"
                :percentage="now.cpuUsage * 100"
                :status="percentageToStatus(now.cpuUsage * 100)"
              >
                <div style="font-size: 17px; font-weight: bold; color: initial">CPU</div>
                <div style="font-size: 13px; color: grey; margin-top: 5px">
                  {{ (now.cpuUsage * 100).toFixed(1) }}%
                </div>
              </el-progress>
              <el-progress
                style="margin-left: 20px"
                type="dashboard"
                :width="100"
                :percentage="(now.memoryUsage / details.runtime.memory) * 100"
                :status="percentageToStatus((now.memoryUsage / details.runtime.memory) * 100)"
              >
                <div style="font-size: 16px; font-weight: bold; color: initial">内存</div>
                <div style="font-size: 13px; color: grey; margin-top: 5px">
                  {{ now.memoryUsage.toFixed(1) }} GB
                </div>
              </el-progress>
              <div
                style="
                  flex: 1;
                  margin-left: 30px;
                  display: flex;
                  flex-direction: column;
                  height: 80px;
                "
              >
                <div style="flex: 1; font-size: 14px">
                  <div>实时网络速度</div>
                  <div>
                    <i style="color: orange" class="fa-solid fa-arrow-up"></i>
                    <span>{{ ` ${fitByUnit(now.networkUpload, 'KB')}/s` }}</span>
                    <el-divider direction="vertical" />
                    <i style="color: dodgerblue" class="fa-solid fa-arrow-down"></i>
                    <span>{{ ` ${fitByUnit(now.networkDownload, 'KB')}/s` }}</span>
                  </div>
                </div>
                <div>
                  <div style="font-size: 13px; display: flex; justify-content: space-between">
                    <div>
                      <i class="fa-solid fa-hard-drive"></i>
                      <span> 磁盘总容量</span>
                    </div>
                    <div>
                      {{ now.diskUsage.toFixed(1) }} GB / {{ details.runtime.disk.toFixed(1) }} GB
                    </div>
                  </div>
                  <el-progress
                    type="line"
                    :show-text="false"
                    :status="percentageToStatus((now.diskUsage / details.runtime.disk) * 100)"
                    :percentage="(now.diskUsage / details.runtime.disk) * 100"
                  />
                </div>
              </div>
            </div>
            <div class="runtime-toolbar">
              <el-button-group>
                <el-button
                  v-for="p in TIME_RANGE_PRESETS"
                  :key="p.key"
                  :type="timeRange.preset === p.key ? 'primary' : 'default'"
                  size="small"
                  @click="selectPreset(p.key)"
                >
                  {{ p.label }}
                </el-button>
              </el-button-group>
              <el-date-picker
                v-model="customRange"
                type="datetimerange"
                size="small"
                range-separator="至"
                start-placeholder="开始时间"
                end-placeholder="结束时间"
                format="YYYY-MM-DD HH:mm"
                value-format="YYYY-MM-DDTHH:mm:ss"
                :disabled-date="disabledDate"
                style="margin-left: 10px"
                @change="onCustomRangeChange"
              />
              <div style="flex: 1"></div>
              <el-button
                :icon="Download"
                size="small"
                type="primary"
                plain
                :disabled="!details.runtime.list || !details.runtime.list.length"
                @click="exportRuntimeCsv"
              >
                导出 CSV
              </el-button>
            </div>
            <runtime-history style="margin-top: 20px" :data="details.runtime.list" />
            <el-empty description="暂无实时数据" v-if="!details.runtime.list.length" />
          </template>
        </div>
        <el-empty description="服务器处于离线状态，请检查服务器是否正常运行" v-else />
        <template v-if="showSystemdTab">
          <div class="title" style="margin-top: 20px">
            <i class="fa-solid fa-gears"></i>
            systemd 服务
          </div>
          <el-divider style="margin: 10px 0" />
          <systemd-services :client-id="props.id" />
        </template>
        <template v-if="showSmartTab">
          <div class="title" style="margin-top: 20px">
            <i class="fa-solid fa-hard-drive"></i>
            SMART 磁盘健康
          </div>
          <el-divider style="margin: 10px 0" />
          <smart-health :client-id="props.id" :capabilities="capabilities" />
        </template>
        <template v-if="showProcessTab">
          <div class="title" style="margin-top: 20px">
            <i class="fa-solid fa-list-check"></i>
            进程监控
          </div>
          <el-divider style="margin: 10px 0" />
          <processes :client-id="props.id" />
        </template>
        <template v-if="showGpuTab">
          <div class="title" style="margin-top: 20px">
            <i class="fa-solid fa-microchip"></i>
            GPU
          </div>
          <el-divider style="margin: 10px 0" />
          <gpus :client-id="props.id" :capabilities="capabilities" />
        </template>
      </div>
    </div>
  </el-scrollbar>
</template>

<style scoped>
.interact-item {
  transition: 0.3s;

  &:hover {
    cursor: pointer;
    scale: 1.1;
    opacity: 0.8;
  }
}

.client-details {
  height: 100%;
  padding: 20px;
}

.title {
  color: var(--el-color-primary);
  font-size: 18px;
  font-weight: bold;
}
.details-list {
  font-size: 14px;

  & div {
    margin-bottom: 10px;
    & span:first-child {
      color: grey;
      font-size: 13px;
      font-weight: normal;
      width: 120px;
      display: inline-block;
    }
    & span {
      font-weight: bold;
    }
  }
}

.runtime-toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 16px;
}
</style>
