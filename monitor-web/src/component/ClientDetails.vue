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
import { ElMessage, ElMessageBox } from 'element-plus'
import RuntimeHistory from '@/component/RuntimeHistory.vue'
import Gpus from '@/component/Gpus.vue'
import Processes from '@/component/Processes.vue'
import SmartHealth from '@/component/SmartHealth.vue'
import SystemdServices from '@/component/SystemdServices.vue'
import { Connection, Delete } from '@element-plus/icons-vue'

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
    if (details.runtime.list.length >= 360) details.runtime.list.splice(0, 1)
    details.runtime.list.push(data)
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

const init = (value) => {
  if (value !== -1) {
    baseLoading.value = true
    runtimeLoading.value = true
    details.base = {}
    details.runtime = { list: [] }
    connectRuntimeSSE(value)
    get(`/api/monitor/details?clientId=${value}`, (data) => {
      Object.assign(details.base, data)
      baseLoading.value = false
    })
    get(`/api/monitor/runtime_history?clientId=${value}`, (data) => {
      Object.assign(details.runtime, data)
      runtimeLoading.value = false
    })
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
</style>
