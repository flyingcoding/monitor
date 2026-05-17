<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { get } from '@/net'
import {
  createAlertRule,
  deleteAlertRule,
  listAlertRules,
  listChannels,
  silenceAlertRule,
  updateAlertRule
} from '@/net/alert'
import {
  ALERT_LEVELS,
  ALERT_METRICS,
  ALERT_OPERATORS,
  levelMeta,
  metricMeta,
  operatorMeta
} from '@/tools/alert'

const loading = ref(false)
const rules = ref([])
const clientOptions = ref([])
const channelOptions = ref([])

const dialog = reactive({
  show: false,
  isEdit: false,
  editingId: null,
  saving: false
})

const initialForm = () => ({
  name: '',
  clientId: null,
  metric: 'cpu',
  operator: 'gt',
  threshold: 80,
  durationSec: 60,
  level: 'warning',
  enabled: true,
  channelIds: []
})
const form = reactive(initialForm())
const formRef = ref()

const metricSelected = computed(() => metricMeta(form.metric))

const validationRules = {
  name: [
    { required: true, message: '请输入规则名称', trigger: 'blur' },
    { max: 64, message: '名称长度不能超过 64', trigger: 'blur' }
  ],
  metric: [{ required: true, message: '请选择监控指标', trigger: 'change' }],
  operator: [{ required: true, message: '请选择比较运算符', trigger: 'change' }],
  threshold: [
    { required: true, type: 'number', message: '请输入阈值', trigger: 'blur' },
    {
      validator: (_r, value, cb) => {
        const meta = metricSelected.value
        if (meta.unit === '%' && (value < 0 || value > 100)) {
          cb(new Error('百分比阈值需在 0 ~ 100 之间'))
          return
        }
        cb()
      },
      trigger: 'blur'
    }
  ],
  durationSec: [
    { required: true, type: 'number', message: '请输入持续时间', trigger: 'blur' },
    {
      validator: (_r, value, cb) => {
        if (value < 10 || value > 86400) cb(new Error('持续时间需在 10 ~ 86400 秒之间'))
        else cb()
      },
      trigger: 'blur'
    }
  ],
  level: [{ required: true, message: '请选择告警等级', trigger: 'change' }]
}

/**
 * 拉取所有规则列表。
 */
function loadRules() {
  loading.value = true
  listAlertRules(
    (data) => {
      rules.value = data || []
      loading.value = false
    },
    () => {
      loading.value = false
    }
  )
}

/**
 * 打开创建对话框。
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
 * @param {object} row 当前规则
 */
function openEdit(row) {
  Object.assign(form, {
    name: row.name,
    clientId: row.clientId,
    metric: row.metric,
    operator: row.operator,
    threshold: row.threshold,
    durationSec: row.durationSec,
    level: row.level,
    enabled: row.enabled,
    channelIds: Array.isArray(row.channelIds) ? [...row.channelIds] : []
  })
  dialog.isEdit = true
  dialog.editingId = row.id
  dialog.show = true
}

/**
 * 提交创建或更新表单。
 */
function submitForm() {
  if (!formRef.value) return
  formRef.value.validate((valid) => {
    if (!valid) return
    dialog.saving = true
    const payload = {
      name: form.name,
      clientId: form.clientId,
      metric: form.metric,
      operator: form.operator,
      threshold: Number(form.threshold),
      durationSec: Number(form.durationSec),
      level: form.level,
      enabled: form.enabled,
      channelIds: form.channelIds || []
    }
    if (dialog.isEdit) {
      updateAlertRule(
        dialog.editingId,
        payload,
        () => {
          dialog.saving = false
          dialog.show = false
          ElMessage.success('规则更新成功')
          loadRules()
        },
        () => {
          dialog.saving = false
        }
      )
    } else {
      createAlertRule(
        payload,
        () => {
          dialog.saving = false
          dialog.show = false
          ElMessage.success('规则创建成功')
          loadRules()
        },
        () => {
          dialog.saving = false
        }
      )
    }
  })
}

function deleteRule(row) {
  ElMessageBox.confirm(`确认删除规则 "${row.name}"？`, '删除规则', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    type: 'warning'
  })
    .then(() => {
      deleteAlertRule(row.id, () => {
        ElMessage.success('规则已删除')
        loadRules()
      })
    })
    .catch(() => {})
}

function silenceRule(row) {
  ElMessageBox.prompt('请输入静默分钟数 (1 ~ 10080)', `静默规则 "${row.name}"`, {
    confirmButtonText: '确认',
    cancelButtonText: '取消',
    inputType: 'number',
    inputValue: '60',
    inputValidator: (value) => {
      const n = Number(value)
      if (!Number.isInteger(n) || n < 1 || n > 10080) {
        return '请输入 1 ~ 10080 之间的整数'
      }
      return true
    }
  })
    .then(({ value }) => {
      silenceAlertRule(row.id, value, () => {
        ElMessage.success(`已静默 ${value} 分钟`)
        loadRules()
      })
    })
    .catch(() => {})
}

function toggleEnabled(row) {
  const payload = {
    name: row.name,
    clientId: row.clientId,
    metric: row.metric,
    operator: row.operator,
    threshold: row.threshold,
    durationSec: row.durationSec,
    level: row.level,
    enabled: row.enabled,
    channelIds: Array.isArray(row.channelIds) ? [...row.channelIds] : []
  }
  updateAlertRule(
    row.id,
    payload,
    () => {
      ElMessage.success(row.enabled ? '已启用' : '已禁用')
    },
    () => {
      // 失败时回滚
      row.enabled = !row.enabled
    }
  )
}

/**
 * 根据 clientId 显示客户端名称，空值显示"全局"。
 *
 * @param {number|null} id 客户端 id
 * @returns {string} 名称
 */
function clientName(id) {
  if (id === null || id === undefined) return '全局规则'
  const c = clientOptions.value.find((item) => item.id === id)
  return c ? c.name : `#${id}`
}

/**
 * 渲染通道标签列表。
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

/**
 * 切换指标时重置阈值到对应范围合理初始值。
 */
function onMetricChange() {
  const meta = metricSelected.value
  if (meta.unit === '%') {
    form.threshold = 80
  } else if (meta.unit === 'KB/s') {
    form.threshold = 10240
  }
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
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

onMounted(() => {
  get(
    '/api/monitor/simple-list',
    (data) => {
      clientOptions.value = data || []
    },
    () => {
      clientOptions.value = []
    }
  )
  listChannels(
    (data) => {
      channelOptions.value = data || []
    },
    () => {
      channelOptions.value = []
    }
  )
  loadRules()
})
</script>

<template>
  <div class="rule-container">
    <div class="action-bar">
      <el-button :icon="Plus" type="primary" plain @click="openCreate">新增规则</el-button>
      <el-button :icon="Refresh" @click="loadRules">刷新</el-button>
    </div>
    <el-table
      v-loading="loading"
      :data="rules"
      stripe
      style="margin-top: 12px; background-color: var(--el-bg-color)"
      empty-text="暂无告警规则"
    >
      <el-table-column prop="name" label="名称" min-width="160" />
      <el-table-column label="作用域" width="140">
        <template #default="{ row }">{{ clientName(row.clientId) }}</template>
      </el-table-column>
      <el-table-column label="指标" width="140">
        <template #default="{ row }">{{ metricMeta(row.metric).label }}</template>
      </el-table-column>
      <el-table-column label="条件" width="160">
        <template #default="{ row }">
          {{ operatorMeta(row.operator).label }} {{ row.threshold
          }}{{ metricMeta(row.metric).unit }}
        </template>
      </el-table-column>
      <el-table-column label="持续 (秒)" width="100" prop="durationSec" />
      <el-table-column label="等级" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="levelMeta(row.level).type">
            {{ levelMeta(row.level).label }}
          </el-tag>
        </template>
      </el-table-column>
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
          <span v-if="!row.channelIds || row.channelIds.length === 0" style="color: grey">
            未绑定
          </span>
        </template>
      </el-table-column>
      <el-table-column label="静默至" width="140">
        <template #default="{ row }">
          <span v-if="row.silenceUntil">{{ formatDate(row.silenceUntil) }}</span>
          <span v-else style="color: grey">-</span>
        </template>
      </el-table-column>
      <el-table-column label="启用" width="90">
        <template #default="{ row }">
          <el-switch v-model="row.enabled" @change="toggleEnabled(row)" />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="primary" link @click="openEdit(row)">编辑</el-button>
          <el-button size="small" link @click="silenceRule(row)">静默</el-button>
          <el-button size="small" type="danger" link @click="deleteRule(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-dialog
      v-model="dialog.show"
      :title="dialog.isEdit ? '编辑告警规则' : '新增告警规则'"
      width="560px"
      :close-on-click-modal="false"
    >
      <el-form
        ref="formRef"
        :model="form"
        :rules="validationRules"
        label-width="100"
      >
        <el-form-item label="规则名称" prop="name">
          <el-input v-model="form.name" placeholder="请输入规则名称" maxlength="64" />
        </el-form-item>
        <el-form-item label="作用客户端">
          <el-select
            v-model="form.clientId"
            placeholder="留空则为全局规则"
            clearable
            filterable
            style="width: 100%"
          >
            <el-option
              v-for="c in clientOptions"
              :key="c.id"
              :label="c.name || `#${c.id}`"
              :value="c.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="监控指标" prop="metric">
          <el-select v-model="form.metric" @change="onMetricChange" style="width: 100%">
            <el-option
              v-for="m in ALERT_METRICS"
              :key="m.value"
              :label="m.label"
              :value="m.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="比较运算符" prop="operator">
          <el-select v-model="form.operator" style="width: 100%">
            <el-option
              v-for="o in ALERT_OPERATORS"
              :key="o.value"
              :label="o.label"
              :value="o.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="`阈值 (${metricSelected.unit})`" prop="threshold">
          <el-input-number
            v-model="form.threshold"
            :min="0"
            :max="metricSelected.unit === '%' ? 100 : 1000000"
            :precision="metricSelected.unit === '%' ? 1 : 0"
            style="width: 100%"
          />
          <span style="margin-left: 10px; color: grey">
            {{ metricSelected.unit === '%' ? '百分比 0~100' : '原始单位 KB/s' }}
          </span>
        </el-form-item>
        <el-form-item label="持续时间" prop="durationSec">
          <el-input-number
            v-model="form.durationSec"
            :min="10"
            :max="86400"
            :step="10"
            style="width: 100%"
          />
          <span style="margin-left: 10px; color: grey">秒</span>
        </el-form-item>
        <el-form-item label="告警等级" prop="level">
          <el-radio-group v-model="form.level">
            <el-radio
              v-for="l in ALERT_LEVELS"
              :key="l.value"
              :value="l.value"
            >
              {{ l.label }}
            </el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="通知通道">
          <el-select
            v-model="form.channelIds"
            multiple
            placeholder="请选择通知通道"
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
      </el-form>
      <template #footer>
        <el-button @click="dialog.show = false">取消</el-button>
        <el-button type="primary" :loading="dialog.saving" @click="submitForm">
          {{ dialog.isEdit ? '保存' : '创建' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.rule-container {
  padding: 8px 0;
}
.action-bar {
  display: flex;
  gap: 10px;
  align-items: center;
}
</style>
