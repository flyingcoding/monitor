<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { Refresh, Search } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { get } from '@/net'
import {
  ackAlertHistory,
  closeAlertHistory,
  getAlertHistory,
  listAlertHistory
} from '@/net/alert'
import {
  ALERT_LEVELS,
  ALERT_STATUSES,
  formatMetricValue,
  levelMeta,
  metricMeta,
  statusMeta
} from '@/tools/alert'
import { useAlertStore } from '@/store/alert'
import { useNotificationStore } from '@/store/notification'

const alertStore = useAlertStore()
const notificationStore = useNotificationStore()

const loading = ref(false)
const rows = ref([])
const total = ref(0)

const clientOptions = ref([])
const filters = reactive({
  clientId: alertStore.filters.clientId,
  level: alertStore.filters.level,
  status: alertStore.filters.status,
  range: alertStore.filters.from && alertStore.filters.to ? [alertStore.filters.from, alertStore.filters.to] : null
})
const page = ref(alertStore.page)
const size = ref(alertStore.size)

const detail = reactive({ show: false, row: null, loading: false })

const queryParams = computed(() => {
  const params = {
    page: page.value,
    size: size.value
  }
  if (filters.clientId) params.clientId = filters.clientId
  if (filters.level) params.level = filters.level
  if (filters.status) params.status = filters.status
  if (filters.range && filters.range.length === 2) {
    params.from = new Date(filters.range[0]).toISOString()
    params.to = new Date(filters.range[1]).toISOString()
  }
  return params
})

/**
 * 同步当前筛选器状态到 Pinia store，供切换路由后回到本页时保留上下文。
 */
function persistFilters() {
  alertStore.filters = {
    clientId: filters.clientId,
    level: filters.level,
    status: filters.status,
    from: filters.range && filters.range[0] ? filters.range[0] : null,
    to: filters.range && filters.range[1] ? filters.range[1] : null
  }
  alertStore.page = page.value
  alertStore.size = size.value
}

/**
 * 从后端拉取告警历史列表，自动处理分页和筛选条件。
 */
function loadHistory() {
  loading.value = true
  listAlertHistory(
    queryParams.value,
    (data) => {
      rows.value = (data && data.records) || data || []
      total.value = (data && data.total !== undefined ? data.total : rows.value.length) || 0
      loading.value = false
    },
    () => {
      loading.value = false
    }
  )
}

/**
 * 重置筛选器并重新加载列表。
 */
function resetFilters() {
  filters.clientId = null
  filters.level = ''
  filters.status = ''
  filters.range = null
  page.value = 1
  persistFilters()
  loadHistory()
}

/**
 * 应用筛选器，分页回到第一页。
 */
function applyFilters() {
  page.value = 1
  persistFilters()
  loadHistory()
}

function openDetail(row) {
  detail.show = true
  detail.row = row
  detail.loading = true
  getAlertHistory(
    row.id,
    (data) => {
      detail.row = data
      detail.loading = false
    },
    () => {
      detail.loading = false
    }
  )
}

function ackRow(row) {
  ElMessageBox.confirm('确认该告警？确认后状态将变为"已确认"', '确认告警', {
    confirmButtonText: '确认',
    cancelButtonText: '取消',
    type: 'warning'
  })
    .then(() => {
      ackAlertHistory(row.id, () => {
        ElMessage.success('告警已确认')
        loadHistory()
      })
    })
    .catch(() => {})
}

function closeRow(row) {
  ElMessageBox.confirm('关闭该告警？状态将变为"已解除"', '关闭告警', {
    confirmButtonText: '确认',
    cancelButtonText: '取消',
    type: 'warning'
  })
    .then(() => {
      closeAlertHistory(row.id, () => {
        ElMessage.success('告警已关闭')
        loadHistory()
      })
    })
    .catch(() => {})
}

function handlePageChange(newPage) {
  page.value = newPage
  persistFilters()
  loadHistory()
}

function handleSizeChange(newSize) {
  size.value = newSize
  page.value = 1
  persistFilters()
  loadHistory()
}

/**
 * 根据 clientId 查找客户端名称。
 *
 * @param {number} id 客户端 id
 * @returns {string} 名称或占位符
 */
function clientName(id) {
  if (id === null || id === undefined) return '全局'
  const c = clientOptions.value.find((item) => item.id === id)
  return c ? c.name : `#${id}`
}

/**
 * 格式化日期为本地字符串。
 *
 * @param {string|Date} value 日期
 * @returns {string} 格式化结果
 */
function formatDate(value) {
  if (!value) return '-'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return '-'
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

// 监听 SSE 推送的新告警，将其插入当前列表顶部（仅匹配筛选条件时）
watch(
  () => notificationStore.recentAlerts.length,
  (newLen, oldLen) => {
    if (newLen <= oldLen) return
    const fresh = notificationStore.recentAlerts[0]
    if (!fresh) return
    // 已在当前列表则更新而非重复插入
    const existing = rows.value.find((r) => r.id === fresh.id)
    if (existing) {
      Object.assign(existing, fresh)
      return
    }
    // 筛选条件匹配时插入到顶部
    if (filters.clientId && fresh.clientId !== filters.clientId) return
    if (filters.level && fresh.level !== filters.level) return
    if (filters.status && fresh.status !== filters.status) return
    if (page.value === 1) {
      rows.value.unshift(fresh)
      if (rows.value.length > size.value) rows.value.splice(size.value)
      total.value += 1
    }
  }
)

onMounted(() => {
  // 使用 /api/monitor/list（按权限过滤，子账户也可访问）作为客户端选项来源
  get(
    '/api/monitor/list',
    (data) => {
      clientOptions.value = (data || []).map((item) => ({ id: item.id, name: item.name }))
    },
    () => {
      clientOptions.value = []
    }
  )
  loadHistory()
})

onBeforeUnmount(() => {
  persistFilters()
})
</script>

<template>
  <div class="history-container">
    <div class="filter-bar">
      <el-select
        v-model="filters.clientId"
        placeholder="全部客户端"
        clearable
        style="width: 180px"
        filterable
      >
        <el-option
          v-for="c in clientOptions"
          :key="c.id"
          :label="c.name || `#${c.id}`"
          :value="c.id"
        />
      </el-select>
      <el-select
        v-model="filters.level"
        placeholder="全部等级"
        clearable
        style="width: 140px"
      >
        <el-option v-for="l in ALERT_LEVELS" :key="l.value" :label="l.label" :value="l.value" />
      </el-select>
      <el-select
        v-model="filters.status"
        placeholder="全部状态"
        clearable
        style="width: 140px"
      >
        <el-option
          v-for="s in ALERT_STATUSES"
          :key="s.value"
          :label="s.label"
          :value="s.value"
        />
      </el-select>
      <el-date-picker
        v-model="filters.range"
        type="datetimerange"
        range-separator="至"
        start-placeholder="开始时间"
        end-placeholder="结束时间"
        format="YYYY-MM-DD HH:mm"
        value-format="YYYY-MM-DDTHH:mm:ss"
      />
      <el-button :icon="Search" type="primary" @click="applyFilters">查询</el-button>
      <el-button :icon="Refresh" @click="resetFilters">重置</el-button>
    </div>
    <el-table
      v-loading="loading"
      :data="rows"
      stripe
      style="margin-top: 12px; background-color: var(--el-bg-color)"
      empty-text="暂无告警记录"
      @row-click="openDetail"
    >
      <el-table-column label="等级" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="levelMeta(row.level).type">
            {{ levelMeta(row.level).label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="客户端" width="140">
        <template #default="{ row }">{{ clientName(row.clientId) }}</template>
      </el-table-column>
      <el-table-column prop="ruleName" label="规则名称" min-width="160">
        <template #default="{ row }">{{ row.ruleName || `#${row.ruleId}` }}</template>
      </el-table-column>
      <el-table-column label="当前值" width="120">
        <template #default="{ row }">
          {{ formatMetricValue(metricMeta(row.metric).value || row.metric, row.currentValue) }}
        </template>
      </el-table-column>
      <el-table-column label="触发时间" width="170">
        <template #default="{ row }">{{ formatDate(row.firedAt) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="statusMeta(row.status).type">
            {{ statusMeta(row.status).label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{ row }">
          <el-button
            size="small"
            type="primary"
            link
            :disabled="row.status !== 'firing'"
            @click.stop="ackRow(row)"
          >
            确认
          </el-button>
          <el-button
            size="small"
            type="danger"
            link
            :disabled="row.status === 'resolved'"
            @click.stop="closeRow(row)"
          >
            关闭
          </el-button>
          <el-button size="small" link @click.stop="openDetail(row)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>
    <div class="pagination-bar">
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        :total="total"
        :page-sizes="[20, 50, 100]"
        layout="total, sizes, prev, pager, next, jumper"
        background
        @current-change="handlePageChange"
        @size-change="handleSizeChange"
      />
    </div>
    <el-drawer
      v-model="detail.show"
      size="420"
      direction="rtl"
      @close="detail.row = null"
    >
      <template #header>
        <span style="font-weight: bold">告警详情</span>
      </template>
      <div v-if="detail.loading" style="padding: 20px">
        <el-skeleton :rows="6" animated />
      </div>
      <div v-else-if="detail.row" class="detail-content">
        <div class="detail-item">
          <span class="label">规则名称</span>
          <span>{{ detail.row.ruleName || `#${detail.row.ruleId}` }}</span>
        </div>
        <div class="detail-item">
          <span class="label">等级</span>
          <el-tag size="small" :type="levelMeta(detail.row.level).type">
            {{ levelMeta(detail.row.level).label }}
          </el-tag>
        </div>
        <div class="detail-item">
          <span class="label">状态</span>
          <el-tag size="small" :type="statusMeta(detail.row.status).type">
            {{ statusMeta(detail.row.status).label }}
          </el-tag>
        </div>
        <div class="detail-item">
          <span class="label">客户端</span>
          <span>{{ clientName(detail.row.clientId) }}</span>
        </div>
        <div class="detail-item">
          <span class="label">当前值</span>
          <span>
            {{
              formatMetricValue(metricMeta(detail.row.metric).value || detail.row.metric, detail.row.currentValue)
            }}
          </span>
        </div>
        <div class="detail-item">
          <span class="label">触发时间</span>
          <span>{{ formatDate(detail.row.firedAt) }}</span>
        </div>
        <div class="detail-item">
          <span class="label">解除时间</span>
          <span>{{ formatDate(detail.row.resolvedAt) }}</span>
        </div>
        <div class="detail-item">
          <span class="label">确认时间</span>
          <span>{{ formatDate(detail.row.ackedAt) }}</span>
        </div>
        <div class="detail-item">
          <span class="label">确认人</span>
          <span>{{ detail.row.ackedBy ? `用户 #${detail.row.ackedBy}` : '-' }}</span>
        </div>
        <div class="detail-item">
          <span class="label">告警消息</span>
          <span>{{ detail.row.message || '-' }}</span>
        </div>
        <div style="text-align: right; margin-top: 16px">
          <el-button
            type="primary"
            :disabled="detail.row.status !== 'firing'"
            @click="ackRow(detail.row)"
          >
            确认告警
          </el-button>
          <el-button
            type="danger"
            :disabled="detail.row.status === 'resolved'"
            @click="closeRow(detail.row)"
          >
            关闭告警
          </el-button>
        </div>
      </div>
      <el-empty v-else description="无详情数据" />
    </el-drawer>
  </div>
</template>

<style scoped>
.history-container {
  padding: 8px 0;
}
.filter-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
}
.pagination-bar {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
}
.detail-content {
  padding: 0 20px 20px 20px;
  font-size: 14px;
}
.detail-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 8px 0;
  border-bottom: 1px dashed var(--el-border-color-lighter);
}
.detail-item .label {
  color: var(--el-text-color-secondary);
  width: 80px;
  flex-shrink: 0;
  font-size: 13px;
}
:deep(.el-drawer__body) {
  padding: 0;
}
</style>
