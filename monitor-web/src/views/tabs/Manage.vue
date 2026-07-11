<script setup>
import PreviewCard from '@/component/PreviewCard.vue'
import { computed, defineAsyncComponent, onBeforeUnmount, reactive, ref } from 'vue'
import { get } from '@/net'
import {
  CircleCheckFilled,
  CircleCloseFilled,
  Location,
  Monitor,
  Plus
} from '@element-plus/icons-vue'
import { useRoute } from 'vue-router'
import { useStore } from '@/store'
import { createReconnectingEventSource } from '@/net/sse'
import { SERVER_LOCATIONS as locations } from '@/tools/locations'

// 抽屉内容只在打开时加载，避免管理页首屏拉入详情图表、终端和 SFTP 依赖。
const ClientDetails = defineAsyncComponent(() => import('@/component/ClientDetails.vue'))
const RegisterCard = defineAsyncComponent(() => import('@/component/RegisterCard.vue'))
const TerminalWindow = defineAsyncComponent(() => import('@/component/TerminalWindow.vue'))

const store = useStore()
const list = ref([])
const loading = ref(true)
const route = useRoute()
const checkedNodes = ref([])

/**
 * 建立主机列表 SSE 连接，并在断开时按指数退避策略重连。
 */
const clientsSse = createReconnectingEventSource({
  path: '/api/sse/clients',
  eventName: 'clients',
  shouldReconnect: () => route.name === 'manage',
  onUnavailable: () => {
    loading.value = false
  },
  onMessage: (data) => {
    list.value = Array.isArray(data) ? data : []
    loading.value = false
  },
  onError: () => {
    if (!list.value.length) loading.value = false
  }
})

/**
 * 启动主机列表实时连接。
 */
function connectSSE() {
  clientsSse.connect()
}

/**
 * 在删除、重命名等操作后通过 REST 主动刷新主机列表。
 */
function updateList() {
  if (route.name === 'manage') {
    loading.value = true
    get('/api/monitor/list', (data) => {
      list.value = Array.isArray(data) ? data : []
      loading.value = false
    })
  }
}

connectSSE()

onBeforeUnmount(() => {
  clientsSse.close()
})

const register = reactive({
  show: false,
  token: ''
})

const detail = reactive({
  show: false,
  id: -1
})

const terminal = reactive({
  show: false,
  id: -1,
  openKey: 0
})

/**
 * 打开指定主机的详情抽屉。
 *
 * @param {number} id 主机 ID
 */
function displayClientDetails(id) {
  detail.show = true
  detail.id = id
}

/**
 * 根据选中的地区编码过滤主机列表。
 */
const clientList = computed(() => {
  if (checkedNodes.value.length === 0) return list.value
  return list.value.filter((item) => checkedNodes.value.includes(item.location))
})

/**
 * 从当前实时列表派生全部、在线和离线数量，不新增服务端指标。
 */
const hostSummary = computed(() => {
  const total = list.value.length
  const online = list.value.filter((item) => item && item.online).length
  return {
    total,
    online,
    offline: Math.max(0, total - online)
  }
})

const hasActiveFilters = computed(() => checkedNodes.value.length > 0)

/**
 * 清空地区筛选并恢复全部主机视图。
 */
function clearFilters() {
  checkedNodes.value = []
}

/**
 * 刷新客户端注册令牌。
 */
function refreshToken() {
  get('/api/monitor/register', (token) => (register.token = token))
}

/**
 * 打开终端抽屉；openKey 确保重复点击同一主机时子组件也能重新聚焦对应 Tab。
 *
 * @param {number} id 主机 ID
 */
function openTerminal(id) {
  terminal.show = true
  terminal.id = id
  terminal.openKey++
  detail.show = false
}
</script>

<template>
  <section class="manage-main">
    <header class="page-heading">
      <div>
        <h1>管理主机列表</h1>
        <p>在这里你可以管理你的各个服务器，并快速查看 CPU、内存与网络状态</p>
      </div>
      <el-button
        class="add-host-button"
        :icon="Plus"
        type="primary"
        :disabled="!store.isAdmin"
        @click="register.show = true"
      >
        添加新主机
      </el-button>
    </header>

    <div class="overview-toolbar">
      <section class="summary-panel" aria-label="主机状态摘要">
        <div class="summary-item summary-total">
          <el-icon aria-hidden="true"><Monitor /></el-icon>
          <div>
            <span>全部主机</span>
            <strong>{{ hostSummary.total }}</strong>
          </div>
        </div>
        <div class="summary-item summary-online">
          <el-icon aria-hidden="true"><CircleCheckFilled /></el-icon>
          <div>
            <span>在线</span>
            <strong>{{ hostSummary.online }}</strong>
          </div>
        </div>
        <div class="summary-item summary-offline">
          <el-icon aria-hidden="true"><CircleCloseFilled /></el-icon>
          <div>
            <span>离线</span>
            <strong>{{ hostSummary.offline }}</strong>
          </div>
        </div>
      </section>

      <section class="filter-panel" aria-label="地区筛选">
        <div class="filter-heading">
          <span
            ><el-icon aria-hidden="true"><Location /></el-icon>地区筛选</span
          >
          <el-button v-if="hasActiveFilters" type="primary" link @click="clearFilters">
            清除筛选
          </el-button>
        </div>
        <div class="filter-scroll">
          <button
            type="button"
            class="all-region-button"
            :class="{ selected: !hasActiveFilters }"
            :aria-pressed="!hasActiveFilters"
            @click="clearFilters"
          >
            <el-icon aria-hidden="true"><Location /></el-icon>
            全部地区
          </button>
          <el-checkbox-group
            v-model="checkedNodes"
            class="region-filters"
            aria-label="按地区筛选主机"
          >
            <el-checkbox-button v-for="node in locations" :key="node.name" :value="node.name">
              <span :class="`fi fi-${node.name}`" aria-hidden="true"></span>
              <span>{{ node.desc }}</span>
            </el-checkbox-button>
          </el-checkbox-group>
        </div>
      </section>
    </div>

    <div v-if="loading" class="skeleton-list" aria-label="正在加载主机列表">
      <el-skeleton v-for="idx in 4" :key="idx" animated class="skeleton-item">
        <template #template>
          <div class="skeleton-card">
            <el-skeleton-item variant="h3" style="width: 58%" />
            <el-skeleton-item variant="text" style="width: 35%; margin-left: auto" />
            <el-skeleton-item variant="p" style="width: 72%; margin-top: 32px" />
            <el-skeleton-item variant="p" style="width: 62%; margin-top: 12px" />
            <el-skeleton-item variant="p" style="width: 90%; margin-top: 30px" />
            <el-skeleton-item variant="p" style="width: 84%; margin-top: 12px" />
          </div>
        </template>
      </el-skeleton>
    </div>

    <div v-else-if="clientList.length" class="card-list">
      <preview-card
        v-for="item in clientList"
        :key="item.id"
        :data="item"
        :update="updateList"
        @open="displayClientDetails(item.id)"
      />
    </div>

    <div v-else class="empty-panel">
      <el-empty v-if="!list.length" description="当前无主机连接，请点击页面上方的添加主机按钮" />
      <el-empty v-else description="没有符合当前地区筛选的主机">
        <el-button type="primary" plain @click="clearFilters">清除筛选</el-button>
      </el-empty>
    </div>

    <el-drawer
      v-if="list.length"
      v-model="detail.show"
      class="detail-drawer"
      size="min(640px, 96vw)"
      :show-close="false"
      :with-header="false"
      @close="detail.id = -1"
    >
      <client-details
        v-if="detail.show && detail.id !== -1"
        :id="detail.id"
        :update="updateList"
        @delete="updateList"
        @terminal="openTerminal"
      />
    </el-drawer>

    <el-drawer
      v-model="register.show"
      class="register-drawer"
      direction="btt"
      :with-header="false"
      size="330"
      @open="refreshToken"
    >
      <register-card v-if="register.show" :token="register.token" />
    </el-drawer>

    <el-drawer
      v-model="terminal.show"
      class="terminal-drawer"
      :size="520"
      direction="btt"
      :close-on-click-modal="false"
      @close="terminal.id = -1"
    >
      <template #header>
        <div class="terminal-heading">
          <strong>SSH 远程连接</strong>
          <span>连接由服务端建立，内部网络中的服务器也可安全访问。</span>
        </div>
      </template>
      <terminal-window
        v-if="terminal.show && terminal.id !== -1"
        :id="terminal.id"
        :open-key="terminal.openKey"
      />
    </el-drawer>
  </section>
</template>

<style scoped>
.manage-main {
  width: min(100%, 1380px);
  margin: 0 auto;
}

.page-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 24px;
}

.page-heading h1 {
  margin: 0;
  color: var(--app-text);
  font-size: clamp(24px, 2.2vw, 30px);
  font-weight: 760;
  letter-spacing: -0.03em;
  line-height: 1.22;
}

.page-heading p {
  margin: 8px 0 0;
  color: var(--app-text-secondary);
  font-size: 14px;
  line-height: 1.6;
}

.add-host-button {
  min-height: 42px;
  padding: 0 18px;
  box-shadow: 0 8px 18px rgba(11, 107, 238, 0.18);
}

.overview-toolbar {
  display: grid;
  grid-template-columns: minmax(360px, 460px) minmax(460px, 1fr);
  gap: 16px;
  margin-bottom: 20px;
}

.summary-panel,
.filter-panel,
.empty-panel {
  border: 1px solid var(--app-border);
  border-radius: var(--app-radius-md);
  background: var(--app-surface);
  box-shadow: var(--app-shadow-sm);
}

.summary-panel {
  min-height: 108px;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  align-items: center;
  padding: 16px 8px;
}

.summary-item {
  min-width: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 0 12px;
}

.summary-item + .summary-item {
  border-left: 1px solid var(--app-border);
}

.summary-item > .el-icon {
  flex: 0 0 auto;
  font-size: 22px;
}

.summary-item div {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.summary-item span {
  color: var(--app-text-secondary);
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
}

.summary-item strong {
  color: var(--app-text);
  font-size: 26px;
  font-variant-numeric: tabular-nums;
  line-height: 1;
}

.summary-total > .el-icon {
  color: var(--app-primary);
}

.summary-online > .el-icon {
  color: var(--app-success);
}

.summary-offline > .el-icon {
  color: var(--app-danger);
}

.filter-panel {
  min-width: 0;
  padding: 14px 16px 16px;
}

.filter-heading {
  min-height: 28px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}

.filter-heading > span {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: var(--app-text);
  font-size: 13px;
  font-weight: 700;
}

.filter-heading .el-icon {
  color: var(--app-primary);
  font-size: 16px;
}

.filter-scroll {
  display: flex;
  align-items: center;
  gap: 8px;
  overflow-x: auto;
  padding: 1px 1px 4px;
  scrollbar-width: thin;
}

.all-region-button {
  flex: 0 0 auto;
  min-height: 38px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 0 13px;
  border: 1px solid var(--app-border);
  border-radius: var(--app-radius-sm);
  background: var(--app-surface);
  color: var(--app-text-secondary);
  cursor: pointer;
  font-size: 13px;
  font-weight: 600;
  transition:
    border-color var(--app-transition),
    background-color var(--app-transition),
    color var(--app-transition);
}

.all-region-button:hover,
.all-region-button.selected {
  border-color: var(--app-primary);
  background: var(--app-primary-soft);
  color: var(--app-primary);
}

.region-filters {
  flex: 0 0 auto;
  display: flex;
  gap: 8px;
}

.region-filters :deep(.el-checkbox-button) {
  flex: 0 0 auto;
}

.region-filters :deep(.el-checkbox-button__inner) {
  min-height: 38px;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 0 13px;
  border: 1px solid var(--app-border) !important;
  border-radius: var(--app-radius-sm) !important;
  background: var(--app-surface);
  color: var(--app-text-secondary);
  box-shadow: none !important;
  font-size: 13px;
  font-weight: 600;
}

.region-filters :deep(.el-checkbox-button.is-checked .el-checkbox-button__inner) {
  border-color: var(--app-primary) !important;
  background: var(--app-primary-soft);
  color: var(--app-primary);
}

.card-list,
.skeleton-list {
  display: grid;
  grid-template-columns: repeat(3, minmax(280px, 1fr));
  gap: 18px;
}

.skeleton-card {
  min-height: 290px;
  display: flex;
  flex-wrap: wrap;
  align-content: flex-start;
  padding: 20px;
  border: 1px solid var(--app-border);
  border-radius: var(--app-radius-md);
  background: var(--app-surface);
}

.empty-panel {
  min-height: 360px;
  display: grid;
  place-items: center;
}

.terminal-heading {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.terminal-heading strong {
  color: var(--app-primary);
  font-size: 18px;
}

.terminal-heading span {
  color: var(--app-text-secondary);
  font-size: 13px;
}

:deep(.detail-drawer) {
  height: calc(100% - 20px);
  margin: 10px;
  border-radius: var(--app-radius-md);
  box-shadow: var(--app-shadow-md);
}

:deep(.detail-drawer .el-drawer__body) {
  padding: 0;
}

:deep(.register-drawer),
:deep(.terminal-drawer) {
  width: min(920px, calc(100% - 24px));
  margin: 12px auto;
  border-radius: var(--app-radius-md) var(--app-radius-md) 0 0;
  box-shadow: var(--app-shadow-md);
}

:deep(.register-drawer) {
  width: min(620px, calc(100% - 24px));
}

@media (max-width: 1180px) {
  .overview-toolbar {
    grid-template-columns: 1fr;
  }

  .card-list,
  .skeleton-list {
    grid-template-columns: repeat(2, minmax(280px, 1fr));
  }
}

@media (max-width: 680px) {
  .page-heading {
    align-items: stretch;
    flex-direction: column;
    gap: 16px;
  }

  .add-host-button {
    width: 100%;
    min-height: 44px;
  }

  .overview-toolbar {
    display: flex;
    flex-direction: column;
  }

  .summary-panel {
    min-height: 116px;
    padding: 14px 2px;
  }

  .summary-item {
    flex-direction: column;
    gap: 6px;
    padding: 0 6px;
    text-align: center;
  }

  .summary-item div {
    align-items: center;
  }

  .summary-item strong {
    font-size: 24px;
  }

  .filter-panel {
    margin-inline: -16px;
    padding-inline: 16px;
    border-right: 0;
    border-left: 0;
    border-radius: 0;
  }

  .filter-scroll {
    padding-bottom: 6px;
  }

  .all-region-button,
  .region-filters :deep(.el-checkbox-button__inner) {
    min-height: 44px;
  }

  .card-list,
  .skeleton-list {
    grid-template-columns: minmax(0, 1fr);
  }

  .empty-panel {
    min-height: 300px;
  }

  :deep(.detail-drawer) {
    width: calc(100% - 12px) !important;
    height: calc(100% - 12px);
    margin: 6px;
  }
}
</style>
