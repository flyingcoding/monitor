<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, Promotion } from '@element-plus/icons-vue'
import { fetchAdminConfig, updateAdminConfig } from '@/net/status-page'

const loading = ref(false)
const saving = ref(false)
const availableClients = ref([])

const initialForm = () => ({
  title: '',
  subtitle: '',
  brandColor: '#10b981',
  logoUrl: '',
  enabled: true,
  // null = 默认公开所有（前端控件复用 mode 切换）；'select' = 自定义白名单
  mode: 'all',
  selectedClientIds: []
})

const form = reactive(initialForm())

const validationRules = {
  title: [{ max: 128, message: '标题长度不能超过 128', trigger: 'blur' }],
  subtitle: [{ max: 255, message: '副标题长度不能超过 255', trigger: 'blur' }],
  brandColor: [{ max: 32, message: '颜色值长度不能超过 32', trigger: 'blur' }],
  logoUrl: [{ max: 255, message: 'Logo URL 长度不能超过 255', trigger: 'blur' }]
}

const formRef = ref()

const previewHref = computed(() => `${window.location.origin}/status`)

/**
 * 拉取当前配置和候选客户端列表。
 */
function loadConfig() {
  loading.value = true
  fetchAdminConfig(
    (data) => {
      if (data) {
        form.title = data.title || ''
        form.subtitle = data.subtitle || ''
        form.brandColor = data.brandColor || '#10b981'
        form.logoUrl = data.logoUrl || ''
        form.enabled = data.enabled !== false
        if (data.clientIds === null || data.clientIds === undefined) {
          form.mode = 'all'
          form.selectedClientIds = []
        } else {
          form.mode = 'select'
          form.selectedClientIds = Array.isArray(data.clientIds) ? data.clientIds : []
        }
        availableClients.value = Array.isArray(data.availableClients) ? data.availableClients : []
      }
      loading.value = false
    },
    () => {
      loading.value = false
    }
  )
}

/**
 * 保存配置。
 *
 * P1-2：'all' 模式时显式提交当前候选客户端的全量 id 列表，
 * 不能传 null —— 后端 P2-3 默认拒绝（null/未配置 ⇒ 不展示），
 * 传 null 会导致状态页空白。'select' 模式按当前选中传（空数组 = 明确隐藏所有）。
 *
 * 已知权衡：保存后新注册的客户端不会自动出现在状态页，需要管理员重新打开页面并保存。
 */
function saveConfig() {
  if (!formRef.value) return
  formRef.value.validate((valid) => {
    if (!valid) return
    saving.value = true
    const allIds = (availableClients.value || []).map((c) => c.id).filter((id) => id != null)
    const payload = {
      title: form.title || '',
      subtitle: form.subtitle || '',
      brandColor: form.brandColor || '',
      logoUrl: form.logoUrl || '',
      enabled: form.enabled,
      clientIds: form.mode === 'all' ? allIds : Array.from(form.selectedClientIds || [])
    }
    updateAdminConfig(
      payload,
      () => {
        saving.value = false
        ElMessage.success('状态页配置已保存')
        loadConfig()
      },
      () => {
        saving.value = false
      }
    )
  })
}

function openPreview() {
  window.open('/status', '_blank')
}

onMounted(() => {
  loadConfig()
})
</script>

<template>
  <div class="status-config-root">
    <div class="info-card">
      <div class="title"><i class="fa-solid fa-tachograph-digital"></i> 公开状态页配置</div>
      <el-divider style="margin: 10px 0" />
      <p style="font-size: 13px; color: grey; margin: 0 0 12px 0">
        公开状态页可在
        <el-link type="primary" underline="never" :href="previewHref" target="_blank">
          {{ previewHref }}
        </el-link>
        访问；任何访客（包括未登录用户）均可查看勾选的客户端在线状态与最近 24 小时可用率。响应严格不暴露
        IP / CPU / 内存等敏感字段。
      </p>
      <div class="action-bar">
        <el-button :icon="Refresh" @click="loadConfig" :disabled="loading">刷新</el-button>
        <el-button :icon="Promotion" @click="openPreview">访问状态页</el-button>
      </div>
      <el-form
        ref="formRef"
        :model="form"
        :rules="validationRules"
        label-width="120"
        style="margin-top: 16px"
        v-loading="loading"
      >
        <el-form-item label="启用状态页">
          <el-switch v-model="form.enabled" />
          <span style="margin-left: 10px; font-size: 12px; color: grey">
            禁用后访问 /status 仅返回标题，不展示客户端
          </span>
        </el-form-item>
        <el-form-item label="标题" prop="title">
          <el-input v-model="form.title" placeholder="如：xxx 服务状态" maxlength="128" />
        </el-form-item>
        <el-form-item label="副标题" prop="subtitle">
          <el-input
            v-model="form.subtitle"
            placeholder="状态页副标题（可选）"
            maxlength="255"
            type="textarea"
            :autosize="{ minRows: 1, maxRows: 3 }"
          />
        </el-form-item>
        <el-form-item label="品牌色" prop="brandColor">
          <el-color-picker v-model="form.brandColor" show-alpha />
          <el-input
            v-model="form.brandColor"
            placeholder="如 #10b981"
            style="margin-left: 12px; width: 200px"
            maxlength="32"
          />
        </el-form-item>
        <el-form-item label="Logo URL" prop="logoUrl">
          <el-input v-model="form.logoUrl" placeholder="状态页左上角 Logo（可选）" maxlength="255" />
        </el-form-item>
        <el-form-item label="公开客户端">
          <el-radio-group v-model="form.mode">
            <el-radio value="all">公开全部已注册客户端</el-radio>
            <el-radio value="select">自定义白名单</el-radio>
          </el-radio-group>
          <div v-if="form.mode === 'all'" style="margin-top: 6px; font-size: 12px; color: grey">
            保存时将提交当前 {{ availableClients.length }} 个候选客户端的 id；新注册的客户端需重新打开本页并保存才会公开。
          </div>
        </el-form-item>
        <el-form-item v-if="form.mode === 'select'" label="选择客户端">
          <el-select
            v-model="form.selectedClientIds"
            multiple
            filterable
            placeholder="请选择要公开的客户端"
            style="width: 100%"
            :empty-values="[null, undefined]"
          >
            <el-option
              v-for="client in availableClients"
              :key="client.id"
              :value="client.id"
              :label="client.displayName ? `${client.displayName}（${client.name}）` : client.name"
            />
          </el-select>
          <div style="margin-top: 6px; font-size: 12px; color: grey">
            空选 = 状态页将不展示任何客户端，仅保留标题/副标题。
          </div>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="saving" @click="saveConfig">保存配置</el-button>
        </el-form-item>
      </el-form>
    </div>
  </div>
</template>

<style scoped>
.status-config-root {
  width: 100%;
  display: flex;
  justify-content: center;
}

.info-card {
  width: 100%;
  max-width: 960px;
  padding: 24px;
  border: 1px solid var(--app-border);
  border-radius: var(--app-radius-md);
  background: var(--app-surface);
  box-shadow: var(--app-shadow-sm);

  .title {
    font-size: 18px;
    font-weight: 750;
    color: var(--app-text);
  }
}

.action-bar {
  display: flex;
  gap: 10px;
  align-items: center;
}

@media (max-width: 600px) {
  .info-card {
    padding: 18px 14px;
  }

  .action-bar {
    flex-wrap: wrap;
  }

  .action-bar .el-button {
    flex: 1;
  }
}
</style>
