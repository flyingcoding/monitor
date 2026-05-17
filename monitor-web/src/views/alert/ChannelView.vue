<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createChannel,
  deleteChannel,
  listChannels,
  testChannel,
  updateChannel
} from '@/net/alert'
import { CHANNEL_TYPES, channelTypeMeta } from '@/tools/alert'

const loading = ref(false)
const channels = ref([])

const dialog = reactive({
  show: false,
  isEdit: false,
  editingId: null,
  saving: false
})

const initialMailConfig = () => ({ to_addrs: '', subject_template: '' })
const initialWebhookConfig = () => ({ url_enc: '', headers: '', body_template: '' })
const initialDingtalkConfig = () => ({ webhook_url_enc: '', secret_enc: '', at_mobiles: '' })
const initialFeishuConfig = () => ({ webhook_url_enc: '', secret_enc: '' })

const initialForm = () => ({
  name: '',
  type: 'mail',
  enabled: true,
  config: initialMailConfig()
})

const form = reactive(initialForm())
const formRef = ref()

/**
 * 根据通道类型返回对应的初始 config 对象。
 *
 * @param {string} type 通道类型
 * @returns {object} 配置初值
 */
function buildEmptyConfig(type) {
  if (type === 'webhook') return initialWebhookConfig()
  if (type === 'dingtalk') return initialDingtalkConfig()
  if (type === 'feishu') return initialFeishuConfig()
  return initialMailConfig()
}

watch(
  () => form.type,
  (newType, oldType) => {
    if (newType !== oldType && !dialog.isEdit) {
      form.config = buildEmptyConfig(newType)
    }
  }
)

const validationRules = {
  name: [
    { required: true, message: '请输入通道名称', trigger: 'blur' },
    { max: 64, message: '名称长度不能超过 64', trigger: 'blur' }
  ],
  type: [{ required: true, message: '请选择通道类型', trigger: 'change' }]
}

const isMail = computed(() => form.type === 'mail')
const isWebhook = computed(() => form.type === 'webhook')
const isDingtalk = computed(() => form.type === 'dingtalk')
const isFeishu = computed(() => form.type === 'feishu')

/**
 * 拉取通道列表。
 */
function loadChannels() {
  loading.value = true
  listChannels(
    (data) => {
      channels.value = data || []
      loading.value = false
    },
    () => {
      loading.value = false
    }
  )
}

function openCreate() {
  Object.assign(form, initialForm())
  dialog.isEdit = false
  dialog.editingId = null
  dialog.show = true
}

function openEdit(row) {
  // 编辑时复制后端 config，敏感字段显示 "***" 占位，提交时如果未修改保留 "***" 后端会沿用旧值
  const config = row.config ? JSON.parse(JSON.stringify(row.config)) : buildEmptyConfig(row.type)
  Object.assign(form, {
    name: row.name,
    type: row.type,
    enabled: row.enabled !== false,
    config
  })
  dialog.isEdit = true
  dialog.editingId = row.id
  dialog.show = true
}

/**
 * 序列化表单 config，将逗号分隔字符串字段转换为对应后端可解析结构。
 *
 * @returns {object} 已准备好上传的 config
 */
function serializedConfig() {
  const cfg = { ...form.config }
  // headers 字段允许 JSON 字符串，解析失败保留原值
  if (form.type === 'webhook' && typeof cfg.headers === 'string' && cfg.headers.trim()) {
    try {
      cfg.headers = JSON.parse(cfg.headers)
    } catch (_e) {
      // 保持字符串，由后端校验
    }
  }
  return cfg
}

function submitForm() {
  if (!formRef.value) return
  formRef.value.validate((valid) => {
    if (!valid) return
    dialog.saving = true
    const payload = {
      name: form.name,
      type: form.type,
      enabled: form.enabled,
      config: serializedConfig()
    }
    if (dialog.isEdit) {
      updateChannel(
        dialog.editingId,
        payload,
        () => {
          dialog.saving = false
          dialog.show = false
          ElMessage.success('通道更新成功')
          loadChannels()
        },
        () => {
          dialog.saving = false
        }
      )
    } else {
      createChannel(
        payload,
        () => {
          dialog.saving = false
          dialog.show = false
          ElMessage.success('通道创建成功')
          loadChannels()
        },
        () => {
          dialog.saving = false
        }
      )
    }
  })
}

function deleteRow(row) {
  ElMessageBox.confirm(`确认删除通道 "${row.name}"？删除后引用该通道的规则将不再发送`, '删除通道', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    type: 'warning'
  })
    .then(() => {
      deleteChannel(row.id, () => {
        ElMessage.success('通道已删除')
        loadChannels()
      })
    })
    .catch(() => {})
}

function testRow(row) {
  testChannel(
    row.id,
    () => {
      ElMessage.success('测试通知已发送，请确认接收情况')
    },
    () => {
      // 失败提示已由 alert.js 统一处理
    }
  )
}

function toggleEnabled(row) {
  const config = row.config ? row.config : buildEmptyConfig(row.type)
  const payload = {
    name: row.name,
    type: row.type,
    enabled: row.enabled,
    config
  }
  updateChannel(
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
 * 渲染列表中的 config 简要信息，避免暴露敏感字段。
 *
 * @param {object} row 通道行
 * @returns {string} 概要文本
 */
function configSummary(row) {
  if (!row.config) return '-'
  if (row.type === 'mail') {
    return `收件人: ${row.config.to_addrs || '-'}`
  }
  if (row.type === 'webhook') {
    return 'URL 已配置'
  }
  if (row.type === 'dingtalk' || row.type === 'feishu') {
    return 'Webhook 已配置'
  }
  return '-'
}

onMounted(() => {
  loadChannels()
})
</script>

<template>
  <div class="channel-container">
    <div class="action-bar">
      <el-button :icon="Plus" type="primary" plain @click="openCreate">新增通道</el-button>
      <el-button :icon="Refresh" @click="loadChannels">刷新</el-button>
    </div>
    <el-table
      v-loading="loading"
      :data="channels"
      stripe
      style="margin-top: 12px; background-color: var(--el-bg-color)"
      empty-text="暂无通知通道"
    >
      <el-table-column prop="name" label="名称" min-width="160" />
      <el-table-column label="类型" width="120">
        <template #default="{ row }">{{ channelTypeMeta(row.type).label }}</template>
      </el-table-column>
      <el-table-column label="配置摘要" min-width="200">
        <template #default="{ row }">{{ configSummary(row) }}</template>
      </el-table-column>
      <el-table-column label="启用" width="90">
        <template #default="{ row }">
          <el-switch v-model="row.enabled" @change="toggleEnabled(row)" />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="240" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="primary" link @click="openEdit(row)">编辑</el-button>
          <el-button size="small" link @click="testRow(row)">测试</el-button>
          <el-button size="small" type="danger" link @click="deleteRow(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-dialog
      v-model="dialog.show"
      :title="dialog.isEdit ? '编辑通知通道' : '新增通知通道'"
      width="560px"
      :close-on-click-modal="false"
    >
      <el-form
        ref="formRef"
        :model="form"
        :rules="validationRules"
        label-width="120"
      >
        <el-form-item label="通道名称" prop="name">
          <el-input v-model="form.name" placeholder="请输入通道名称" maxlength="64" />
        </el-form-item>
        <el-form-item label="通道类型" prop="type">
          <el-select v-model="form.type" :disabled="dialog.isEdit" style="width: 100%">
            <el-option
              v-for="c in CHANNEL_TYPES"
              :key="c.value"
              :label="c.label"
              :value="c.value"
            />
          </el-select>
        </el-form-item>
        <template v-if="isMail">
          <el-form-item label="收件人">
            <el-input
              v-model="form.config.to_addrs"
              type="textarea"
              rows="2"
              placeholder="多个邮箱用英文逗号分隔"
            />
          </el-form-item>
          <el-form-item label="主题模板">
            <el-input
              v-model="form.config.subject_template"
              placeholder="可选，留空则使用默认模板"
            />
          </el-form-item>
        </template>
        <template v-if="isWebhook">
          <el-form-item label="Webhook URL">
            <el-input
              v-model="form.config.url_enc"
              type="password"
              show-password
              placeholder="完整的 Webhook 地址（敏感）"
            />
          </el-form-item>
          <el-form-item label="请求头">
            <el-input
              v-model="form.config.headers"
              type="textarea"
              rows="3"
              placeholder='JSON 格式，例如：{"X-Token": "abc"}'
            />
          </el-form-item>
          <el-form-item label="请求体模板">
            <el-input
              v-model="form.config.body_template"
              type="textarea"
              rows="4"
              placeholder="可选，留空则使用默认 JSON 模板"
            />
          </el-form-item>
        </template>
        <template v-if="isDingtalk">
          <el-form-item label="Webhook URL">
            <el-input
              v-model="form.config.webhook_url_enc"
              type="password"
              show-password
              placeholder="钉钉机器人 Webhook 地址（敏感）"
            />
          </el-form-item>
          <el-form-item label="加签密钥">
            <el-input
              v-model="form.config.secret_enc"
              type="password"
              show-password
              placeholder="可选，加签机器人需填写"
            />
          </el-form-item>
          <el-form-item label="@手机号">
            <el-input
              v-model="form.config.at_mobiles"
              type="textarea"
              rows="2"
              placeholder="可选，多个手机号用英文逗号分隔"
            />
          </el-form-item>
        </template>
        <template v-if="isFeishu">
          <el-form-item label="Webhook URL">
            <el-input
              v-model="form.config.webhook_url_enc"
              type="password"
              show-password
              placeholder="飞书机器人 Webhook 地址（敏感）"
            />
          </el-form-item>
          <el-form-item label="加签密钥">
            <el-input
              v-model="form.config.secret_enc"
              type="password"
              show-password
              placeholder="可选，加签机器人需填写"
            />
          </el-form-item>
        </template>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
        <el-alert
          v-if="dialog.isEdit"
          type="info"
          show-icon
          :closable="false"
          style="margin-top: 8px"
          title="带 *** 的敏感字段表示已加密保存，留空 *** 不变即保留原值；如需修改请覆盖输入新值"
        />
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
.channel-container {
  padding: 8px 0;
}
.action-bar {
  display: flex;
  gap: 10px;
  align-items: center;
}
</style>
