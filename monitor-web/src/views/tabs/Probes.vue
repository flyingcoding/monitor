<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createProbe,
  deleteProbe,
  listProbeHistory,
  listProbes,
  PROBE_TYPES,
  probeTypeMeta,
  updateProbe
} from '@/net/probe'
import { listChannels } from '@/net/alert'

defineOptions({ name: 'Probes' })

const MASK = '***'

const loading = ref(false)
const probes = ref([])
const channelOptions = ref([])

const dialog = reactive({
  show: false,
  isEdit: false,
  editingId: null,
  saving: false
})

const initialForm = () => ({
  name: '',
  type: 'http',
  target: '',
  intervalSec: 60,
  timeoutSec: 10,
  expectedStatusCode: null,
  expectedBodyPattern: '',
  headersList: [], // [{ key, value }]
  basicAuthUsername: '',
  basicAuthPassword: '',
  sslWarnDays: 30,
  consecutiveFailuresThreshold: 2,
  channelIds: [],
  enabled: true
})

const form = reactive(initialForm())
const formRef = ref()

const history = reactive({
  show: false,
  taskId: null,
  taskName: '',
  loading: false,
  records: [],
  total: 0,
  page: 1,
  size: 20
})

const isHttp = computed(() => form.type === 'http')

const validationRules = {
  name: [
    { required: true, message: '请输入任务名称', trigger: 'blur' },
    { max: 128, message: '名称不能超过 128 字符', trigger: 'blur' }
  ],
  type: [{ required: true, message: '请选择探测类型', trigger: 'change' }],
  target: [
    { required: true, message: '请输入探测目标', trigger: 'blur' },
    { max: 512, message: '目标长度不能超过 512 字符', trigger: 'blur' }
  ],
  intervalSec: [
    { required: true, type: 'number', message: '请输入探测周期', trigger: 'blur' },
    {
      validator: (_r, value, cb) => {
        if (value < 10 || value > 86400) cb(new Error('周期需在 10 ~ 86400 秒之间'))
        else cb()
      },
      trigger: 'blur'
    }
  ],
  timeoutSec: [
    { required: true, type: 'number', message: '请输入超时', trigger: 'blur' },
    {
      validator: (_r, value, cb) => {
        if (value < 1 || value > 60) cb(new Error('超时需在 1 ~ 60 秒之间'))
        else cb()
      },
      trigger: 'blur'
    }
  ],
  consecutiveFailuresThreshold: [
    { required: true, type: 'number', message: '请输入连续失败阈值', trigger: 'blur' }
  ]
}

/**
 * 拉取所有探测任务（仅 admin 可见）。
 */
function loadProbes() {
  loading.value = true
  listProbes(
    (data) => {
      probes.value = data || []
      loading.value = false
    },
    () => {
      loading.value = false
    }
  )
}

/**
 * 拉取通知通道（供表单 multi-select 渲染）。
 */
function loadChannels() {
  listChannels(
    (data) => {
      channelOptions.value = data || []
    },
    () => {
      channelOptions.value = []
    }
  )
}

/**
 * 打开新建对话框。
 */
function openCreate() {
  Object.assign(form, initialForm())
  dialog.isEdit = false
  dialog.editingId = null
  dialog.show = true
}

/**
 * 打开编辑对话框并填充表单。
 *
 * @param {object} row 探测任务行
 */
function openEdit(row) {
  const headersList = []
  if (row.headers && typeof row.headers === 'object') {
    for (const [k, v] of Object.entries(row.headers)) {
      headersList.push({ key: k, value: v })
    }
  }
  Object.assign(form, {
    name: row.name,
    type: row.type,
    target: row.target,
    intervalSec: row.intervalSec,
    timeoutSec: row.timeoutSec,
    expectedStatusCode: row.expectedStatusCode || null,
    expectedBodyPattern: row.expectedBodyPattern || '',
    headersList,
    basicAuthUsername: row.basicAuthUsername || '',
    basicAuthPassword: row.hasBasicAuthPassword ? MASK : '',
    sslWarnDays: row.sslWarnDays || 30,
    consecutiveFailuresThreshold: row.consecutiveFailuresThreshold,
    channelIds: Array.isArray(row.channelIds) ? [...row.channelIds] : [],
    enabled: row.enabled !== false
  })
  dialog.isEdit = true
  dialog.editingId = row.id
  dialog.show = true
}

/**
 * 新增一行 Header 配置。
 */
function addHeader() {
  form.headersList.push({ key: '', value: '' })
}

/**
 * 删除某个 Header。
 *
 * @param {number} idx 索引
 */
function removeHeader(idx) {
  form.headersList.splice(idx, 1)
}

/**
 * 将 headersList 转为 Map：忽略 key 为空的行；value 为 '***' 时保持占位，由后端走 preserve 逻辑。
 *
 * @returns {object} 序列化后的 headers map
 */
function serializeHeaders() {
  const map = {}
  for (const h of form.headersList) {
    if (h && h.key && h.key.trim() !== '') {
      map[h.key.trim()] = h.value === null || h.value === undefined ? '' : h.value
    }
  }
  return map
}

/**
 * 提交创建 / 更新。
 */
function submitForm() {
  if (!formRef.value) return
  formRef.value.validate((valid) => {
    if (!valid) return
    dialog.saving = true
    const payload = {
      name: form.name,
      type: form.type,
      target: form.target,
      intervalSec: Number(form.intervalSec),
      timeoutSec: Number(form.timeoutSec),
      expectedStatusCode: form.expectedStatusCode || null,
      expectedBodyPattern: form.expectedBodyPattern || null,
      basicAuthUsername: form.basicAuthUsername || null,
      sslWarnDays: form.sslWarnDays || 30,
      consecutiveFailuresThreshold: Number(form.consecutiveFailuresThreshold),
      channelIds: form.channelIds || [],
      enabled: form.enabled
    }
    // headers
    if (isHttp.value) {
      const headers = serializeHeaders()
      payload.headers = headers
    } else {
      // 非 HTTP 类型不传 headers（后端清空）
      payload.headers = {}
    }
    // basic_auth_password 语义：留空 '' 表示清空，'***' 表示沿用旧
    if (isHttp.value) {
      payload.basicAuthPassword = form.basicAuthPassword
    } else {
      payload.basicAuthPassword = ''
    }

    if (dialog.isEdit) {
      updateProbe(
        dialog.editingId,
        payload,
        () => {
          dialog.saving = false
          dialog.show = false
          ElMessage.success('探测任务更新成功')
          loadProbes()
        },
        () => {
          dialog.saving = false
        }
      )
    } else {
      createProbe(
        payload,
        () => {
          dialog.saving = false
          dialog.show = false
          ElMessage.success('探测任务创建成功')
          loadProbes()
        },
        () => {
          dialog.saving = false
        }
      )
    }
  })
}

/**
 * 删除任务，含二次确认。
 *
 * @param {object} row 探测任务
 */
function deleteRow(row) {
  ElMessageBox.confirm(`确认删除探测任务 "${row.name}"？删除后历史也将随 30 天滚动清理消失`, '删除任务', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    type: 'warning'
  })
    .then(() => {
      deleteProbe(row.id, () => {
        ElMessage.success('任务已删除')
        loadProbes()
      })
    })
    .catch(() => {})
}

/**
 * 切换启用状态：直接以新值发送 update 请求。
 *
 * @param {object} row 探测任务
 */
function toggleEnabled(row) {
  const payload = {
    name: row.name,
    type: row.type,
    target: row.target,
    intervalSec: row.intervalSec,
    timeoutSec: row.timeoutSec,
    expectedStatusCode: row.expectedStatusCode || null,
    expectedBodyPattern: row.expectedBodyPattern || null,
    headers: null, // null 保留旧 headersEnc
    basicAuthUsername: row.basicAuthUsername || null,
    basicAuthPassword: MASK, // 沿用旧密文
    sslWarnDays: row.sslWarnDays || 30,
    consecutiveFailuresThreshold: row.consecutiveFailuresThreshold,
    channelIds: Array.isArray(row.channelIds) ? [...row.channelIds] : [],
    enabled: row.enabled
  }
  updateProbe(
    row.id,
    payload,
    () => {
      ElMessage.success(row.enabled ? '已启用' : '已禁用')
    },
    () => {
      row.enabled = !row.enabled
    }
  )
}

/**
 * 打开历史抽屉，加载第一页。
 *
 * @param {object} row 探测任务
 */
function openHistory(row) {
  history.show = true
  history.taskId = row.id
  history.taskName = row.name
  history.page = 1
  history.size = 20
  fetchHistory()
}

/**
 * 根据当前 history.page / history.size 拉取历史。
 */
function fetchHistory() {
  if (!history.taskId) return
  history.loading = true
  listProbeHistory(
    history.taskId,
    { page: history.page, size: history.size },
    (body) => {
      history.records = (body && body.records) || []
      history.total = body && body.total ? body.total : 0
      history.loading = false
    },
    () => {
      history.loading = false
    }
  )
}

function onHistoryPageChange(page) {
  history.page = page
  fetchHistory()
}

/**
 * 格式化日期时间。
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

/**
 * 渲染通道标签。
 *
 * @param {number[]} ids 通道 id 数组
 * @returns {string[]} 通道名称数组
 */
function channelLabels(ids) {
  if (!Array.isArray(ids) || ids.length === 0) return []
  return ids.map((id) => {
    const c = channelOptions.value.find((item) => item.id === id)
    return c ? c.name : `#${id}`
  })
}

onMounted(() => {
  loadChannels()
  loadProbes()
})
</script>

<template>
  <div class="probes-main">
    <div style="display: flex; justify-content: space-between; align-items: end">
      <div>
        <div class="title"><i class="fa-solid fa-satellite-dish"></i> 服务探测</div>
        <div class="desc">配置 HTTP / TCP / ICMP 探测任务，连续失败或 SSL 即将过期时触发告警通知</div>
      </div>
    </div>
    <el-divider style="margin: 10px 0" />
    <div class="action-bar">
      <el-button :icon="Plus" type="primary" plain @click="openCreate">新增探测任务</el-button>
      <el-button :icon="Refresh" @click="loadProbes">刷新</el-button>
    </div>
    <el-table
      v-loading="loading"
      :data="probes"
      stripe
      style="margin-top: 12px; background-color: var(--el-bg-color)"
      empty-text="暂无探测任务"
    >
      <el-table-column prop="name" label="名称" min-width="160" />
      <el-table-column label="类型" width="120">
        <template #default="{ row }">{{ probeTypeMeta(row.type).label }}</template>
      </el-table-column>
      <el-table-column prop="target" label="目标" min-width="220" show-overflow-tooltip />
      <el-table-column label="周期 / 超时" width="120">
        <template #default="{ row }">{{ row.intervalSec }}s / {{ row.timeoutSec }}s</template>
      </el-table-column>
      <el-table-column label="失败阈值" width="100" prop="consecutiveFailuresThreshold" />
      <el-table-column label="通道" min-width="180">
        <template #default="{ row }">
          <el-tag
            v-for="(label, idx) in channelLabels(row.channelIds)"
            :key="idx"
            size="small"
            style="margin-right: 4px"
          >
            {{ label }}
          </el-tag>
          <span v-if="!row.channelIds || row.channelIds.length === 0" style="color: grey">未绑定</span>
        </template>
      </el-table-column>
      <el-table-column label="启用" width="90">
        <template #default="{ row }">
          <el-switch v-model="row.enabled" @change="toggleEnabled(row)" />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="primary" link @click="openEdit(row)">编辑</el-button>
          <el-button size="small" link @click="openHistory(row)">历史</el-button>
          <el-button size="small" type="danger" link @click="deleteRow(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 创建 / 编辑表单 -->
    <el-dialog
      v-model="dialog.show"
      :title="dialog.isEdit ? '编辑探测任务' : '新增探测任务'"
      width="680px"
      :close-on-click-modal="false"
    >
      <el-form
        ref="formRef"
        :model="form"
        :rules="validationRules"
        label-width="120"
      >
        <el-form-item label="任务名称" prop="name">
          <el-input v-model="form.name" placeholder="请输入任务名称" maxlength="128" />
        </el-form-item>
        <el-form-item label="探测类型" prop="type">
          <el-select v-model="form.type" :disabled="dialog.isEdit" style="width: 100%">
            <el-option
              v-for="t in PROBE_TYPES"
              :key="t.value"
              :label="t.label"
              :value="t.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="探测目标" prop="target">
          <el-input
            v-model="form.target"
            :placeholder="form.type === 'http' ? 'https://example.com/health' : (form.type === 'tcp' ? 'host:port' : 'host 或 IP')"
            maxlength="512"
          />
        </el-form-item>
        <el-form-item label="探测周期 (秒)" prop="intervalSec">
          <el-input-number
            v-model="form.intervalSec"
            :min="10"
            :max="86400"
            :step="10"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="超时 (秒)" prop="timeoutSec">
          <el-input-number
            v-model="form.timeoutSec"
            :min="1"
            :max="60"
            style="width: 100%"
          />
        </el-form-item>
        <template v-if="isHttp">
          <el-form-item label="期望状态码">
            <el-input-number
              v-model="form.expectedStatusCode"
              :min="100"
              :max="599"
              placeholder="留空表示接受 2xx"
              style="width: 100%"
            />
            <div style="color: var(--el-text-color-secondary); font-size: 12px; margin-top: 4px">
              留空表示默认接受 2xx 全段；填入 200 / 204 等数字时严格匹配
            </div>
          </el-form-item>
          <el-form-item label="响应体正则">
            <el-input
              v-model="form.expectedBodyPattern"
              placeholder="可选，留空表示不校验"
              maxlength="512"
            />
          </el-form-item>
          <el-form-item label="Custom Headers">
            <div style="width: 100%">
              <div v-for="(h, idx) in form.headersList" :key="idx" class="header-row">
                <el-input
                  v-model="h.key"
                  placeholder="Header 名（如 Authorization）"
                  style="width: 38%"
                />
                <el-input
                  v-model="h.value"
                  type="password"
                  show-password
                  placeholder="Header 值（敏感，整体加密存储）"
                  style="width: 50%"
                />
                <el-button type="danger" link @click="removeHeader(idx)">删除</el-button>
              </div>
              <el-button :icon="Plus" link @click="addHeader">新增 Header</el-button>
              <div style="color: var(--el-text-color-secondary); font-size: 12px; margin-top: 4px">
                Header 值将与 v1.2 OIDC client_secret 同款 AES-256-GCM 加密存储；编辑时为 *** 表示沿用旧值
              </div>
            </div>
          </el-form-item>
          <el-form-item label="Basic Auth 用户">
            <el-input
              v-model="form.basicAuthUsername"
              placeholder="可选，留空表示不启用 Basic Auth"
              maxlength="128"
            />
          </el-form-item>
          <el-form-item label="Basic Auth 密码">
            <el-input
              v-model="form.basicAuthPassword"
              type="password"
              show-password
              placeholder="敏感字段；*** 表示沿用旧密文，清空表示移除"
              maxlength="255"
            />
          </el-form-item>
          <el-form-item label="SSL 预警天数">
            <el-input-number
              v-model="form.sslWarnDays"
              :min="1"
              :max="365"
              style="width: 100%"
            />
            <div style="color: var(--el-text-color-secondary); font-size: 12px; margin-top: 4px">
              证书剩余天 ≤ 此值时单独触发"即将过期"告警（HTTPS 探测）
            </div>
          </el-form-item>
        </template>
        <el-form-item label="连续失败阈值" prop="consecutiveFailuresThreshold">
          <el-input-number
            v-model="form.consecutiveFailuresThreshold"
            :min="1"
            :max="100"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="通知通道">
          <el-select
            v-model="form.channelIds"
            multiple
            placeholder="请选择通知通道（可多选）"
            style="width: 100%"
          >
            <el-option
              v-for="c in channelOptions"
              :key="c.id"
              :label="c.name"
              :value="c.id"
              :disabled="c.enabled === false"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
        <el-alert
          v-if="dialog.isEdit"
          type="info"
          show-icon
          :closable="false"
          style="margin-top: 8px"
          title="敏感字段 (*** 占位) 表示已加密保存，留空原 *** 不变即保留原值；清空输入框表示移除"
        />
      </el-form>
      <template #footer>
        <el-button @click="dialog.show = false">取消</el-button>
        <el-button type="primary" :loading="dialog.saving" @click="submitForm">
          {{ dialog.isEdit ? '保存' : '创建' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 历史抽屉 -->
    <el-drawer
      v-model="history.show"
      :title="`探测历史 - ${history.taskName}`"
      direction="rtl"
      size="50%"
      :close-on-click-modal="true"
    >
      <el-table
        v-loading="history.loading"
        :data="history.records"
        stripe
        empty-text="暂无历史记录"
      >
        <el-table-column label="执行时间" width="180">
          <template #default="{ row }">{{ formatDate(row.executedAt) }}</template>
        </el-table-column>
        <el-table-column label="结果" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.success ? 'success' : 'danger'">
              {{ row.success ? '成功' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="延迟" width="90">
          <template #default="{ row }">{{ row.latencyMs == null ? '-' : `${row.latencyMs} ms` }}</template>
        </el-table-column>
        <el-table-column label="状态码" width="80">
          <template #default="{ row }">{{ row.statusCode == null ? '-' : row.statusCode }}</template>
        </el-table-column>
        <el-table-column label="SSL 剩余" width="100">
          <template #default="{ row }">{{ row.sslDaysRemaining == null ? '-' : `${row.sslDaysRemaining} 天` }}</template>
        </el-table-column>
        <el-table-column label="错误信息" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ row.errorMessage || '-' }}</template>
        </el-table-column>
      </el-table>
      <div style="margin-top: 12px; text-align: right">
        <el-pagination
          background
          layout="total, prev, pager, next"
          :total="history.total"
          :page-size="history.size"
          :current-page="history.page"
          @current-change="onHistoryPageChange"
        />
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.probes-main {
  margin: 0 50px;
  .title {
    font-size: 22px;
    font-weight: bold;
  }
  .desc {
    font-size: 15px;
    color: grey;
  }
}
.action-bar {
  display: flex;
  gap: 10px;
  align-items: center;
}
.header-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
</style>
