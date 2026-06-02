<script setup>
import PreviewCard from '@/component/PreviewCard.vue'
import { computed, onBeforeUnmount, reactive, ref } from 'vue'
import { get } from '@/net'
import ClientDetails from '@/component/ClientDetails.vue'
import RegisterCard from '@/component/RegisterCard.vue'
import { Plus } from '@element-plus/icons-vue'
import { useRoute } from 'vue-router'
import { useStore } from '@/store'
import TerminalWindow from '@/component/TerminalWindow.vue'
import { createAuthenticatedEventSource, parseSseJson } from '@/net/sse'

const locations = [
  { name: 'cn', desc: '中国大陆' },
  { name: 'hk', desc: '香港' },
  { name: 'jp', desc: '日本' },
  { name: 'us', desc: '美国' },
  { name: 'sg', desc: '新加坡' },
  { name: 'kr', desc: '韩国' },
  { name: 'de', desc: '德国' }
]

const store = useStore()
const list = ref([])
const loading = ref(true)
const route = useRoute()

// SSE 订阅替代轮询
let eventSource = null
let sseRetryDelay = 1000
const SSE_MAX_DELAY = 60000

/**
 * 建立主机列表SSE连接，并在断开时按指数退避策略重连。
 */
function connectSSE() {
  eventSource = createAuthenticatedEventSource('/api/sse/clients')
  if (!eventSource) {
    loading.value = false
    return
  }
  eventSource.addEventListener('clients', (event) => {
    const data = parseSseJson(event)
    if (!data) return
    list.value = data
    loading.value = false
    sseRetryDelay = 1000
  })
  eventSource.onerror = () => {
    if (eventSource) eventSource.close()
    if (!list.value.length) loading.value = false
    setTimeout(() => {
      if (route.name === 'manage') connectSSE()
    }, sseRetryDelay)
    sseRetryDelay = Math.min(sseRetryDelay * 2, SSE_MAX_DELAY)
  }
}

// 手动更新（用于删除/重命名等操作后刷新）
const updateList = () => {
  if (route.name === 'manage') {
    loading.value = true
    get('/api/monitor/list', (data) => {
      list.value = data
      loading.value = false
    })
  }
}

connectSSE()

onBeforeUnmount(() => {
  if (eventSource) {
    eventSource.close()
    eventSource = null
  }
})

const register = reactive({
  show: false,
  token: ''
})
const detail = reactive({
  show: false,
  id: -1
})
const displayClientDetails = (id) => {
  detail.show = true
  detail.id = id
}
const checkedNodes = ref([])

const clientList = computed(() => {
  if (checkedNodes.value.length === 0) {
    return list.value
  } else {
    return list.value.filter((item) => checkedNodes.value.includes(item.location))
  }
})
const refreshToken = () => get('/api/monitor/register', (token) => (register.token = token))

function openTerminal(id) {
  terminal.show = true
  terminal.id = id
  detail.show = false
}
const terminal = reactive({
  show: false,
  id: -1
})
</script>

<template>
  <div class="manage-main">
    <div style="display: flex; justify-content: space-between; align-items: end">
      <div>
        <div class="title"><i class="fa-solid fa-server"></i> 管理主机列表</div>
        <div class="desc">在这里你可以管理你的各个服务器，并快速查看其内存、CPU等实时监控数据</div>
      </div>
      <div>
        <el-button
          :icon="Plus"
          type="primary"
          plain
          :disabled="!store.isAdmin"
          @click="register.show = true"
          >添加新主机</el-button
        >
      </div>
    </div>
    <el-divider style="margin: 10px 0" />
    <div style="margin-bottom: 20px">
      <el-checkbox-group v-model="checkedNodes">
        <el-checkbox v-for="node in locations" :key="node" :label="node.name" border>
          <span :class="`fi fi-${node.name}`"></span>
          <span style="font-size: 13px; margin-left: 10px">{{ node.desc }}</span>
        </el-checkbox>
      </el-checkbox-group>
    </div>
    <div class="skeleton-list" v-if="loading">
      <el-skeleton v-for="idx in 4" :key="idx" animated class="skeleton-item">
        <template #template>
          <el-skeleton-item variant="image" style="width: 300px; height: 170px" />
          <div style="padding: 14px">
            <el-skeleton-item variant="p" style="width: 70%" />
            <el-skeleton-item variant="p" style="width: 50%; margin-top: 8px" />
          </div>
        </template>
      </el-skeleton>
    </div>
    <div class="card-list" v-else-if="list.length">
      <preview-card
        v-for="item in clientList"
        :key="item.id"
        :data="item"
        :update="updateList"
        @click="displayClientDetails(item.id)"
      />
    </div>
    <el-empty description="当前无主机连接，请点击添加主机按钮" v-else />
    <el-drawer
      size="520"
      :show-close="false"
      v-model="detail.show"
      :with-header="false"
      v-if="list.length"
      @close="detail.id = -1"
    >
      <client-details
        :id="detail.id"
        :update="updateList"
        @delete="updateList"
        @terminal="openTerminal"
      />
    </el-drawer>
    <el-drawer
      v-model="register.show"
      direction="btt"
      style="width: 600px; margin: 10px auto"
      :with-header="false"
      size="320"
      @open="refreshToken"
    >
      <register-card :token="register.token" />
    </el-drawer>
    <el-drawer
      style="width: 800px"
      :size="500"
      direction="btt"
      :close-on-click-modal="false"
      @close="terminal.id = -1"
      v-model="terminal.show"
    >
      <template #header>
        <div>
          <div style="font-size: 18px; color: dodgerblue; font-weight: bold">SSH远程连接</div>
          <div style="font-size: 14px">
            远程连接的建立由服务端完成，因此部署在内部网络中的服务器在外网也能正常访问。
          </div>
        </div>
      </template>
      <terminal-window :id="terminal.id" />
    </el-drawer>
  </div>
</template>

<style scoped>
:deep(.el-checkbox-group .el-checkbox) {
  margin-right: 10px;
}
:deep(.el-drawer) {
  margin: 10px;
  height: calc(100% - 20px);
  border-radius: 10px;
}
:deep(.el-drawer__body) {
  padding: 0;
}
.manage-main {
  margin: 0 50px;
  .title {
    font-size: 22px;
    font-weight: bold;
  }
  .desc {
    font-size: 15px;
    color: grey;
  }
  .card-list {
    display: flex;
    gap: 20px;
    flex-wrap: wrap;
  }
  .skeleton-list {
    display: flex;
    gap: 20px;
    flex-wrap: wrap;
  }
  .skeleton-item {
    width: 300px;
  }
}
</style>
