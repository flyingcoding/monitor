<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { buildSftpSocketUrl, closeManagedWebSocket, createManagedWebSocket } from '@/net/ws'
import {
  MAX_SFTP_TRANSFER_BYTES,
  downloadBase64File,
  joinRemotePath,
  parentRemotePath,
  remoteFileName
} from '@/tools/sftp'

const props = defineProps({
  clientId: Number,
  sessionId: {
    type: String,
    default: ''
  }
})

const entries = ref([])
const currentPath = ref('.')
const connected = ref(false)
const loading = ref(false)
const newDirName = ref('')
const uploadInput = ref()
let socket = null
let disposed = false

const readableLimit = computed(() => `${MAX_SFTP_TRANSFER_BYTES / 1024 / 1024} MiB`)

/**
 * Build the current client SFTP WebSocket URL through the shared auth helper.
 *
 * @returns {string|null} SFTP WebSocket 地址；未登录时返回 null
 */
function buildSocketUrl() {
  return buildSftpSocketUrl(props.clientId, props.sessionId)
}

/**
 * 建立 SFTP WebSocket 连接，并等待服务端首帧目录列表。
 */
function connect() {
  const socketUrl = buildSocketUrl()
  if (!socketUrl) {
    ElMessage.warning('登录状态已失效，请重新登录')
    return
  }
  loading.value = true
  socket = createManagedWebSocket(socketUrl, {
    onopen: () => {
      connected.value = true
    },
    onmessage: (event) => handleSocketMessage(event.data),
    onerror: () => {
      loading.value = false
      ElMessage.error('SFTP 连接异常')
    },
    onclose: (event) => {
      connected.value = false
      loading.value = false
      socket = null
      if (!disposed && event.reason) {
        ElMessage.warning(event.reason)
      }
    }
  })
}

/**
 * 处理服务端 SFTP 响应。
 *
 * @param {string} rawMessage WebSocket 文本消息
 */
function handleSocketMessage(rawMessage) {
  let payload
  try {
    payload = JSON.parse(rawMessage)
  } catch (_e) {
    loading.value = false
    ElMessage.warning('SFTP 响应格式错误')
    return
  }
  if (payload.type === 'list') {
    entries.value = Array.isArray(payload.entries) ? payload.entries : []
    currentPath.value = payload.path || currentPath.value
    loading.value = false
    return
  }
  if (payload.type === 'download') {
    downloadBase64File(payload.contentBase64 || '', payload.name || remoteFileName(payload.path))
    loading.value = false
    ElMessage.success('文件下载已开始')
    return
  }
  if (payload.type === 'success') {
    loading.value = false
    ElMessage.success(payload.message || '操作成功')
    return
  }
  if (payload.type === 'error') {
    loading.value = false
    ElMessage.warning(payload.message || 'SFTP 操作失败')
  }
}

/**
 * 发送 SFTP 操作请求。
 *
 * @param {object} payload 请求载荷
 */
function sendAction(payload) {
  if (!socket || socket.readyState !== WebSocket.OPEN) {
    ElMessage.warning('SFTP 尚未连接')
    return
  }
  loading.value = true
  socket.send(JSON.stringify(payload))
}

/**
 * 刷新当前目录。
 */
function refreshCurrentPath() {
  sendAction({ action: 'list', path: currentPath.value || '.' })
}

/**
 * 打开目录行，普通文件不响应。
 *
 * @param {object} row 文件或目录行
 */
function openDirectory(row) {
  if (!row || !row.directory) return
  sendAction({ action: 'list', path: row.path })
}

/**
 * 返回当前路径的上级目录。
 */
function goParent() {
  sendAction({ action: 'list', path: parentRemotePath(currentPath.value) })
}

/**
 * 下载指定文件。
 *
 * @param {object} row 文件行
 */
function downloadFile(row) {
  if (!row || row.directory) return
  if (row.size > MAX_SFTP_TRANSFER_BYTES) {
    ElMessage.warning(`文件超过 ${readableLimit.value}，请等待分片传输版本`)
    return
  }
  sendAction({ action: 'download', path: row.path })
}

/**
 * 创建当前目录下的新目录。
 */
function createDirectory() {
  const name = newDirName.value.trim()
  if (!name) {
    ElMessage.warning('请输入目录名称')
    return
  }
  sendAction({ action: 'mkdir', path: joinRemotePath(currentPath.value, name) })
  newDirName.value = ''
}

/**
 * 删除远端文件或空目录，删除前要求用户确认。
 *
 * @param {object} row 文件或目录行
 */
function deleteEntry(row) {
  if (!row) return
  const label = row.directory ? '目录' : '文件'
  ElMessageBox.confirm(`确认删除${label} "${row.name}"？目录仅支持空目录删除。`, '删除确认', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    type: 'warning'
  })
    .then(() => {
      sendAction({ action: 'delete', path: row.path, directory: Boolean(row.directory) })
    })
    .catch(() => {})
}

/**
 * 打开原生文件选择器。
 */
function triggerUpload() {
  if (!connected.value) {
    ElMessage.warning('SFTP 尚未连接')
    return
  }
  uploadInput.value?.click()
}

/**
 * 读取本地文件并以 base64 上传到当前远端目录。
 *
 * @param {Event} event 文件选择事件
 */
function handleUploadChange(event) {
  const file = event.target.files && event.target.files[0]
  event.target.value = ''
  if (!file) return
  if (file.size > MAX_SFTP_TRANSFER_BYTES) {
    ElMessage.warning(`文件超过 ${readableLimit.value}，请等待分片传输版本`)
    return
  }
  const reader = new FileReader()
  reader.onload = () => {
    const result = String(reader.result || '')
    const commaIndex = result.indexOf(',')
    const contentBase64 = commaIndex >= 0 ? result.slice(commaIndex + 1) : result
    sendAction({
      action: 'upload',
      path: joinRemotePath(currentPath.value, file.name),
      contentBase64
    })
  }
  reader.onerror = () => {
    loading.value = false
    ElMessage.warning('读取本地文件失败')
  }
  loading.value = true
  reader.readAsDataURL(file)
}

/**
 * 格式化远端文件大小。
 *
 * @param {number} size 字节数
 * @returns {string} 可读大小
 */
function formatSize(size) {
  const value = Number(size || 0)
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KiB`
  return `${(value / 1024 / 1024).toFixed(1)} MiB`
}

/**
 * 格式化远端文件修改时间。
 *
 * @param {number} modifiedAt 毫秒时间戳
 * @returns {string} 本地时间字符串
 */
function formatModifiedAt(modifiedAt) {
  if (!modifiedAt) return '-'
  return new Date(modifiedAt).toLocaleString()
}

onMounted(() => {
  connect()
})

onBeforeUnmount(() => {
  disposed = true
  socket = closeManagedWebSocket(socket)
})
</script>

<template>
  <div class="sftp-panel">
    <div class="sftp-toolbar">
      <div class="path-box">
        <span class="label">远端路径</span>
        <code>{{ currentPath }}</code>
      </div>
      <div class="actions">
        <el-button size="small" plain :disabled="!connected" @click="goParent">上级</el-button>
        <el-button size="small" plain :disabled="!connected" @click="refreshCurrentPath">刷新</el-button>
        <el-button size="small" type="primary" plain :disabled="!connected" @click="triggerUpload">
          上传文件
        </el-button>
        <input ref="uploadInput" type="file" class="hidden-input" @change="handleUploadChange" />
      </div>
    </div>

    <div class="mkdir-bar">
      <el-input
        v-model="newDirName"
        size="small"
        placeholder="新目录名称"
        clearable
        :disabled="!connected"
        @keyup.enter="createDirectory"
      />
      <el-button size="small" type="success" plain :disabled="!connected" @click="createDirectory">
        新建目录
      </el-button>
      <span class="limit-tip">单文件上传/下载限制 {{ readableLimit }}</span>
    </div>

    <el-table
      v-loading="loading"
      :data="entries"
      height="360"
      size="small"
      empty-text="当前目录为空"
      class="sftp-table"
    >
      <el-table-column label="名称" min-width="220">
        <template #default="{ row }">
          <button
            class="entry-name"
            :class="{ directory: row.directory }"
            type="button"
            @click="openDirectory(row)"
          >
            <i :class="row.directory ? 'fa-solid fa-folder' : 'fa-regular fa-file'"></i>
            <span>{{ row.name }}</span>
          </button>
        </template>
      </el-table-column>
      <el-table-column label="大小" width="120">
        <template #default="{ row }">
          <span>{{ row.directory ? '-' : formatSize(row.size) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="修改时间" width="190">
        <template #default="{ row }">
          <span>{{ formatModifiedAt(row.modifiedAt) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="190" align="right">
        <template #default="{ row }">
          <el-button
            size="small"
            text
            :disabled="row.directory || !connected"
            @click="downloadFile(row)"
          >
            下载
          </el-button>
          <el-button size="small" text type="danger" :disabled="!connected" @click="deleteEntry(row)">
            删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
.sftp-panel {
  padding: 10px;
  background: var(--el-bg-color);
  border-radius: 8px;
}

.sftp-toolbar,
.mkdir-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.path-box {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 8px;

  .label {
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }

  code {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    padding: 4px 8px;
    border: 1px solid var(--el-border-color);
    border-radius: 6px;
    background: var(--el-fill-color-light);
  }
}

.actions {
  display: flex;
  gap: 8px;
}

.mkdir-bar {
  .el-input {
    width: 220px;
  }
}

.limit-tip {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.hidden-input {
  display: none;
}

.entry-name {
  border: 0;
  background: transparent;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--el-text-color-primary);
  cursor: default;
  padding: 0;

  &.directory {
    color: var(--el-color-primary);
    cursor: pointer;
  }
}

.sftp-table {
  :deep(.el-table__body-wrapper) {
    background: var(--el-bg-color);
  }
}
</style>
