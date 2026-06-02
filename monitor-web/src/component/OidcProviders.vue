<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import {
  createProvider,
  deleteProvider,
  listProviders,
  updateProvider
} from '@/net/oidc'
import { submitEnabledToggle } from '@/tools/toggle'

const loading = ref(false)
const providers = ref([])

const dialog = reactive({
  show: false,
  isEdit: false,
  editingId: null,
  saving: false
})

const initialForm = () => ({
  name: '',
  displayName: '',
  iconUrl: '',
  issuerUrl: '',
  clientId: '',
  clientSecret: '',
  scopes: 'openid,profile,email',
  enabled: true
})

const form = reactive(initialForm())
const formRef = ref()

const validationRules = {
  name: [
    { required: true, message: '请输入 Provider 名（唯一标识，用于 OAuth2 路由）', trigger: 'blur' },
    {
      pattern: /^[a-z0-9-]+$/,
      message: '仅允许小写字母 / 数字 / 短横线',
      trigger: 'blur'
    },
    { max: 64, message: 'name 长度不能超过 64', trigger: 'blur' }
  ],
  issuerUrl: [
    { required: true, message: '请输入 OIDC Issuer URL', trigger: 'blur' }
  ],
  clientId: [{ required: true, message: '请输入 Client ID', trigger: 'blur' }],
  clientSecret: [{ required: true, message: '请输入 Client Secret', trigger: 'blur' }],
  scopes: [{ required: true, message: '请输入 scopes', trigger: 'blur' }]
}

const editingValidationRules = {
  ...validationRules,
  clientSecret: []
}

/**
 * 拉取 Provider 列表。
 */
function loadProviders() {
  loading.value = true
  listProviders(
    (list) => {
      providers.value = Array.isArray(list) ? list : []
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
  Object.assign(form, {
    name: row.name,
    displayName: row.displayName || '',
    iconUrl: row.iconUrl || '',
    issuerUrl: row.issuerUrl,
    clientId: row.clientId,
    clientSecret: '',
    scopes: row.scopes,
    enabled: row.enabled !== false
  })
  dialog.isEdit = true
  dialog.editingId = row.id
  dialog.show = true
}

function submitForm() {
  if (!formRef.value) return
  formRef.value.validate((valid) => {
    if (!valid) return
    dialog.saving = true
    if (dialog.isEdit) {
      // 更新时 name 不变；clientSecret 留空表示沿用旧密钥
      const payload = {
        displayName: form.displayName,
        iconUrl: form.iconUrl,
        issuerUrl: form.issuerUrl,
        clientId: form.clientId,
        clientSecret: form.clientSecret || '',
        scopes: form.scopes,
        enabled: form.enabled
      }
      updateProvider(
        dialog.editingId,
        payload,
        () => {
          dialog.saving = false
          dialog.show = false
          ElMessage.success('Provider 更新成功')
          loadProviders()
        },
        () => {
          dialog.saving = false
        }
      )
    } else {
      const payload = {
        name: form.name,
        displayName: form.displayName,
        iconUrl: form.iconUrl,
        issuerUrl: form.issuerUrl,
        clientId: form.clientId,
        clientSecret: form.clientSecret,
        scopes: form.scopes,
        enabled: form.enabled
      }
      createProvider(
        payload,
        () => {
          dialog.saving = false
          dialog.show = false
          ElMessage.success('Provider 创建成功')
          loadProviders()
        },
        () => {
          dialog.saving = false
        }
      )
    }
  })
}

function deleteRow(row) {
  ElMessageBox.confirm(
    `确认删除 Provider "${row.displayName || row.name}"？已绑定该 Provider 的用户将无法用此方式登录。`,
    '删除 Provider',
    { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' }
  )
    .then(() => {
      deleteProvider(row.id, () => {
        ElMessage.success('Provider 已删除')
        loadProviders()
      })
    })
    .catch(() => {})
}

function toggleEnabled(row) {
  const payload = {
    displayName: row.displayName,
    iconUrl: row.iconUrl,
    issuerUrl: row.issuerUrl,
    clientId: row.clientId,
    clientSecret: '',
    scopes: row.scopes,
    enabled: row.enabled
  }
  submitEnabledToggle({
    row,
    update: (success, failure) => updateProvider(row.id, payload, success, failure)
  })
}

onMounted(() => {
  loadProviders()
})
</script>

<template>
  <div class="info-card">
    <div class="title"><i class="fa-solid fa-id-card"></i> OIDC Provider 管理</div>
    <el-divider style="margin: 10px 0" />
    <div class="action-bar">
      <el-button :icon="Plus" type="primary" plain @click="openCreate">新增 Provider</el-button>
      <el-button :icon="Refresh" @click="loadProviders">刷新</el-button>
    </div>
    <el-table
      v-loading="loading"
      :data="providers"
      stripe
      style="margin-top: 12px; background-color: var(--el-bg-color)"
      empty-text="暂无 Provider"
    >
      <el-table-column prop="displayName" label="显示名" min-width="140">
        <template #default="{ row }">{{ row.displayName || row.name }}</template>
      </el-table-column>
      <el-table-column prop="name" label="name" width="120" />
      <el-table-column prop="issuerUrl" label="Issuer" min-width="200" show-overflow-tooltip />
      <el-table-column label="Client ID" min-width="140" show-overflow-tooltip>
        <template #default="{ row }">{{ row.clientId }}</template>
      </el-table-column>
      <el-table-column label="Secret" width="100">
        <template #default="{ row }">
          <el-tag v-if="row.hasSecret" type="success" size="small">已配置</el-tag>
          <el-tag v-else type="danger" size="small">未配置</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="启用" width="80">
        <template #default="{ row }">
          <el-switch v-model="row.enabled" @change="toggleEnabled(row)" />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="170" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="primary" link @click="openEdit(row)">编辑</el-button>
          <el-button size="small" type="danger" link @click="deleteRow(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-dialog
      v-model="dialog.show"
      :title="dialog.isEdit ? '编辑 OIDC Provider' : '新增 OIDC Provider'"
      width="560px"
      :close-on-click-modal="false"
    >
      <el-form
        ref="formRef"
        :model="form"
        :rules="dialog.isEdit ? editingValidationRules : validationRules"
        label-width="120"
      >
        <el-form-item label="name" prop="name">
          <el-input
            v-model="form.name"
            :disabled="dialog.isEdit"
            placeholder="如 github / google / internal-keycloak"
            maxlength="64"
          />
        </el-form-item>
        <el-form-item label="显示名">
          <el-input v-model="form.displayName" placeholder="登录页按钮文案，如 GitHub" maxlength="128" />
        </el-form-item>
        <el-form-item label="图标 URL">
          <el-input v-model="form.iconUrl" placeholder="登录页按钮图标 URL（可选）" />
        </el-form-item>
        <el-form-item label="Issuer URL" prop="issuerUrl">
          <el-input
            v-model="form.issuerUrl"
            placeholder="OIDC Issuer，如 https://accounts.google.com"
          />
        </el-form-item>
        <el-form-item label="Client ID" prop="clientId">
          <el-input v-model="form.clientId" placeholder="OAuth Client ID" />
        </el-form-item>
        <el-form-item label="Client Secret" prop="clientSecret">
          <el-input
            v-model="form.clientSecret"
            type="password"
            show-password
            :placeholder="dialog.isEdit ? '留空表示沿用已配置的密钥' : '请输入 Client Secret'"
          />
        </el-form-item>
        <el-form-item label="Scopes" prop="scopes">
          <el-input v-model="form.scopes" placeholder="逗号分隔，如 openid,profile,email" />
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
          title="Provider name 不可修改；Client Secret 留空时保留原值，重新输入覆盖。"
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
