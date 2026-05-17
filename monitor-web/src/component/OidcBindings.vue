<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import {
  listBindings,
  listPublicProviders,
  unbindProvider
} from '@/net/oidc'

const bindings = ref([])
const availableProviders = ref([])

const unboundProviders = computed(() => {
  const boundNames = new Set(bindings.value.map((b) => b.providerName))
  return availableProviders.value.filter((p) => !boundNames.has(p.name))
})

/**
 * 拉取当前账号已绑定的 OIDC 列表 + 公开可用 Provider 列表。
 */
function refresh() {
  listBindings(
    (list) => {
      bindings.value = Array.isArray(list) ? list : []
    },
    () => {
      bindings.value = []
    }
  )
  listPublicProviders(
    (list) => {
      availableProviders.value = Array.isArray(list) ? list : []
    },
    () => {
      availableProviders.value = []
    }
  )
}

/**
 * 跳转到 OAuth2 授权端点；已登录态下 OIDC SuccessHandler 会落 binding 行而非新建账号。
 *
 * @param {string} providerName Provider name
 */
function bindNew(providerName) {
  window.location.href = `/oauth2/authorization/${encodeURIComponent(providerName)}`
}

/**
 * 解绑前弹确认；后端校验失败时（无其他登录方式）会显式提示。
 *
 * @param {object} row 绑定 VO
 */
function confirmUnbind(row) {
  ElMessageBox.confirm(
    `确认解除 "${row.displayName || row.providerName}" 的绑定？解绑后将无法用该 Provider 登录此账号。`,
    '解除绑定',
    { confirmButtonText: '解除', cancelButtonText: '取消', type: 'warning' }
  )
    .then(() => {
      unbindProvider(
        row.providerName,
        () => {
          ElMessage.success('已解除绑定')
          refresh()
        },
        () => {
          // 错误信息已由 net/oidc.js 通过 ElMessage 显示
        }
      )
    })
    .catch(() => {})
}

onMounted(() => {
  refresh()
})
</script>

<template>
  <div class="info-card">
    <div class="title"><i class="fa-solid fa-link"></i> OIDC 单点登录</div>
    <el-divider style="margin: 10px 0" />
    <div v-if="!availableProviders.length && !bindings.length" style="color: grey; padding: 12px 0">
      管理员尚未启用 OIDC 登录或未配置任何 Provider。
    </div>
    <div v-else>
      <div v-if="bindings.length" class="bindings-list">
        <div v-for="row in bindings" :key="row.providerName" class="binding-item">
          <div style="flex: 1">
            <div style="font-weight: bold">{{ row.displayName || row.providerName }}</div>
            <div style="font-size: 13px; color: grey">
              {{ row.email || '邮箱未提供' }}
              <span v-if="row.boundAt" style="margin-left: 10px">
                绑定时间：{{ new Date(row.boundAt).toLocaleString() }}
              </span>
            </div>
          </div>
          <el-button type="danger" size="small" plain @click="confirmUnbind(row)">解除绑定</el-button>
        </div>
      </div>
      <div v-else style="color: grey; padding: 8px 0; font-size: 14px">
        当前账号尚未绑定任何 OIDC Provider。
      </div>
      <div v-if="unboundProviders.length" style="margin-top: 15px">
        <div style="font-size: 13px; color: grey; margin-bottom: 8px">绑定新的 OIDC Provider：</div>
        <div class="provider-buttons">
          <el-button
            v-for="p in unboundProviders"
            :key="p.name"
            :icon="Plus"
            plain
            size="small"
            @click="bindNew(p.name)"
          >
            <img v-if="p.iconUrl" :src="p.iconUrl" alt="" class="provider-icon" />
            <span>{{ p.displayName || p.name }}</span>
          </el-button>
        </div>
      </div>
    </div>
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

.bindings-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.binding-item {
  border-radius: 5px;
  background-color: var(--el-bg-color-page);
  padding: 10px;
  display: flex;
  align-items: center;
  text-align: left;
}

.provider-buttons {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.provider-icon {
  width: 14px;
  height: 14px;
  object-fit: contain;
  margin-right: 4px;
}
</style>
