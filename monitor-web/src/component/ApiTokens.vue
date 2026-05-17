<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { CopyDocument, Plus, Refresh } from '@element-plus/icons-vue'
import {
  createToken,
  deleteToken,
  listTokens,
  rotateToken
} from '@/net/api-token'

const loading = ref(false)
const tokens = ref([])

const createDialog = reactive({
  show: false,
  saving: false
})

const initialForm = () => ({
  name: '',
  scope: 'readonly',
  expiresAt: null
})
const form = reactive(initialForm())
const formRef = ref()

const validationRules = {
  name: [
    { required: true, message: '请输入 Token 名称', trigger: 'blur' },
    { max: 128, message: '名称长度不能超过 128', trigger: 'blur' }
  ],
  scope: [{ required: true, message: '请选择 Scope', trigger: 'change' }]
}

// 创建成功后展示一次性明文 token 的对话框
const plaintextDialog = reactive({
  show: false,
  token: '',
  rotated: false,
  acknowledged: false
})

const canClosePlaintext = computed(() => plaintextDialog.acknowledged)

/**
 * 拉取当前账号的 API Token 列表。
 */
function loadTokens() {
  loading.value = true
  listTokens(
    (list) => {
      tokens.value = Array.isArray(list) ? list : []
      loading.value = false
    },
    () => {
      loading.value = false
    }
  )
}

function openCreate() {
  Object.assign(form, initialForm())
  createDialog.show = true
}

function submitCreate() {
  if (!formRef.value) return
  formRef.value.validate((valid) => {
    if (!valid) return
    createDialog.saving = true
    const payload = {
      name: form.name,
      scope: form.scope,
      expiresAt: form.expiresAt ? new Date(form.expiresAt).toISOString() : null
    }
    createToken(
      payload,
      (data) => {
        createDialog.saving = false
        createDialog.show = false
        showPlaintext(data, false)
        loadTokens()
      },
      () => {
        createDialog.saving = false
      }
    )
  })
}

/**
 * 把刚创建的 token 明文展示给用户；关闭前必须勾选"已保存"。
 *
 * @param {{token: string}} created 服务端返回
 * @param {boolean} rotated 是否来自 rotate 操作（仅文案差异）
 */
function showPlaintext(created, rotated) {
  plaintextDialog.token = created && created.token ? created.token : ''
  plaintextDialog.rotated = rotated
  plaintextDialog.acknowledged = false
  plaintextDialog.show = true
}

async function copyToken() {
  if (!plaintextDialog.token) return
  try {
    if (navigator.clipboard) {
      await navigator.clipboard.writeText(plaintextDialog.token)
    } else {
      const ta = document.createElement('textarea')
      ta.value = plaintextDialog.token
      document.body.appendChild(ta)
      ta.select()
      document.execCommand('copy')
      document.body.removeChild(ta)
    }
    ElMessage.success('已复制到剪贴板')
  } catch (e) {
    ElMessage.warning('复制失败，请手动选中复制')
  }
}

function confirmDelete(row) {
  ElMessageBox.confirm(
    `确认删除 Token "${row.name}"？删除后使用该 Token 的任何调用都会立即返回 401。`,
    '删除 API Token',
    { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
  )
    .then(() => {
      deleteToken(row.id, () => {
        ElMessage.success('Token 已删除')
        loadTokens()
      })
    })
    .catch(() => {})
}

function confirmRotate(row) {
  ElMessageBox.confirm(
    `确认旋转 Token "${row.name}"？旧 Token 立即失效，新明文只展示一次，请务必复制保存。`,
    '旋转 API Token',
    { confirmButtonText: '旋转', cancelButtonText: '取消', type: 'warning' }
  )
    .then(() => {
      rotateToken(row.id, (data) => {
        ElMessage.success('Token 已旋转，请复制新明文')
        showPlaintext(data, true)
        loadTokens()
      })
    })
    .catch(() => {})
}

function formatDate(value) {
  if (!value) return '—'
  try {
    return new Date(value).toLocaleString()
  } catch (_e) {
    return '—'
  }
}

/**
 * el-dialog before-close 拦截器：未勾选"已保存"时阻止关闭并提示。
 *
 * @param {Function} done 关闭回调
 */
function handlePlaintextClose(done) {
  if (canClosePlaintext.value) {
    done()
  } else {
    ElMessage.warning('请先勾选"我已复制并妥善保存"')
  }
}

onMounted(() => {
  loadTokens()
})
</script>

<template>
  <div class="info-card">
    <div class="title"><i class="fa-solid fa-key"></i> API Token 管理</div>
    <el-divider style="margin: 10px 0" />
    <p style="font-size: 13px; color: grey; margin: 0 0 10px 0">
      API Token 用于脚本 / CI 等场景调用本服务的 REST API；明文仅在创建/旋转时展示一次。
    </p>
    <div class="action-bar">
      <el-button :icon="Plus" type="primary" plain @click="openCreate">新增 Token</el-button>
      <el-button :icon="Refresh" @click="loadTokens">刷新</el-button>
    </div>
    <el-table
      v-loading="loading"
      :data="tokens"
      stripe
      style="margin-top: 12px; background-color: var(--el-bg-color)"
      empty-text="尚未创建任何 API Token"
    >
      <el-table-column prop="name" label="名称" min-width="120" show-overflow-tooltip />
      <el-table-column label="Token" width="180">
        <template #default="{ row }">
          <span style="font-family: monospace; font-size: 13px">{{ row.prefixTail }}</span>
        </template>
      </el-table-column>
      <el-table-column label="Scope" width="100">
        <template #default="{ row }">
          <el-tag :type="row.scope === 'readonly' ? 'info' : 'warning'" size="small">
            {{ row.scope }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="过期时间" width="180">
        <template #default="{ row }">
          {{ row.expiresAt ? formatDate(row.expiresAt) : '永不过期' }}
        </template>
      </el-table-column>
      <el-table-column label="最近使用" width="180">
        <template #default="{ row }">
          <div>{{ formatDate(row.lastUsedAt) }}</div>
          <div style="font-size: 12px; color: grey" v-if="row.lastUsedIp">来自 {{ row.lastUsedIp }}</div>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="180">
        <template #default="{ row }">{{ formatDate(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="warning" link @click="confirmRotate(row)">旋转</el-button>
          <el-button size="small" type="danger" link @click="confirmDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 创建 Token 表单对话框 -->
    <el-dialog
      v-model="createDialog.show"
      title="新增 API Token"
      width="480px"
      :close-on-click-modal="false"
    >
      <el-form ref="formRef" :model="form" :rules="validationRules" label-width="100">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" placeholder="便于识别的用途，如 'CI 部署'" maxlength="128" />
        </el-form-item>
        <el-form-item label="Scope" prop="scope">
          <el-radio-group v-model="form.scope">
            <el-radio value="readonly">只读 (GET/HEAD)</el-radio>
            <el-radio value="readwrite">读写 (全方法)</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="过期时间">
          <el-date-picker
            v-model="form.expiresAt"
            type="datetime"
            placeholder="不选表示永不过期"
            style="width: 100%"
          />
        </el-form-item>
        <el-alert
          type="info"
          show-icon
          :closable="false"
          style="margin-top: 8px"
          title="创建成功后会一次性展示完整 Token 明文，关闭对话框后将不再显示。请立刻复制。"
        />
      </el-form>
      <template #footer>
        <el-button @click="createDialog.show = false">取消</el-button>
        <el-button type="primary" :loading="createDialog.saving" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>

    <!-- 一次性明文展示对话框 -->
    <el-dialog
      v-model="plaintextDialog.show"
      :title="plaintextDialog.rotated ? 'Token 已旋转 — 请复制新明文' : '请复制 Token — 仅展示一次'"
      width="560px"
      :close-on-click-modal="false"
      :show-close="canClosePlaintext"
      :before-close="handlePlaintextClose"
    >
      <el-alert
        type="warning"
        show-icon
        :closable="false"
        :title="
          plaintextDialog.rotated
            ? '旧 Token 已经失效；以下是新 Token 明文。'
            : 'API Token 仅在此时可见；服务端不留明文。请立即复制并妥善保存。'
        "
        style="margin-bottom: 12px"
      />
      <el-input
        :model-value="plaintextDialog.token"
        readonly
        type="textarea"
        :rows="2"
        style="font-family: monospace"
      />
      <div style="margin-top: 12px; display: flex; gap: 10px; align-items: center">
        <el-button :icon="CopyDocument" type="primary" @click="copyToken">复制到剪贴板</el-button>
        <el-checkbox v-model="plaintextDialog.acknowledged">我已复制并妥善保存</el-checkbox>
      </div>
      <template #footer>
        <el-button
          type="primary"
          :disabled="!canClosePlaintext"
          @click="
            () => {
              plaintextDialog.show = false
              plaintextDialog.token = ''
            }
          "
          >完成</el-button
        >
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.info-card {
  border-radius: 7px;
  padding: 15px 20px;
  background-color: var(--el-bg-color);

  .title {
    font-size: 18px;
    font-weight: bold;
    color: dodgerblue;
  }
}

.action-bar {
  display: flex;
  gap: 10px;
  align-items: center;
}
</style>
