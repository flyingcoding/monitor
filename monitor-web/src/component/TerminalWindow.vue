<script setup>
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { get, post } from '@/net'
import { withQuery } from '@/net/query'
import SftpPanel from '@/component/SftpPanel.vue'
import Terminal from '@/component/Terminal.vue'
import {
  createTerminalTab,
  nextClientSessionIndex,
  resolveNextActiveTabName
} from '@/tools/terminal-tabs'

const props = defineProps({
  id: Number,
  openKey: {
    type: Number,
    default: 0
  }
})

const rules = {
  port: [{ required: true, message: '请输入端口', trigger: ['blur', 'change'] }],
  username: [{ required: true, message: '请输入用户名', trigger: ['blur', 'change'] }],
  password: [{ required: true, message: '请输入密码', trigger: ['blur', 'change'] }]
}

const tabs = ref([])
const activeName = ref('')
const formRefs = reactive({})

/**
 * 记录每个 Tab 内部表单实例，保存连接时按 Tab 定位校验对象。
 *
 * @param {string} name Tab 名称
 * @param {object|null} el 表单实例
 */
function setFormRef(name, el) {
  if (el) {
    formRefs[name] = el
  } else {
    delete formRefs[name]
  }
}

/**
 * 从后端加载指定 Tab 的 SSH 连接配置。
 *
 * @param {object} tab 终端 Tab 状态
 */
function loadConnection(tab) {
  tab.loading = true
  get(
    withQuery('/api/monitor/ssh', { clientId: tab.clientId }),
    (data) => {
      Object.assign(tab.connection, data || {})
      tab.loading = false
    },
    (message) => {
      tab.loading = false
      ElMessage.warning(message)
    }
  )
}

/**
 * 创建新的终端会话 Tab，并立即加载 SSH 配置。
 *
 * @param {number} clientId 主机 ID
 * @returns {object|null} 新建 Tab
 */
function createSessionTab(clientId) {
  if (!clientId || clientId === -1) return null
  const numericClientId = Number(clientId)
  const tab = createTerminalTab(
    numericClientId,
    nextClientSessionIndex(tabs.value, numericClientId)
  )
  tabs.value.push(tab)
  activeName.value = tab.name
  loadConnection(tab)
  return tab
}

/**
 * 从主机详情入口打开终端：已有同主机 Tab 时聚焦，否则创建新 Tab。
 *
 * @param {number} clientId 主机 ID
 */
function openOrFocusClientTab(clientId) {
  if (!clientId || clientId === -1) return
  const numericClientId = Number(clientId)
  const existing = tabs.value.find((tab) => tab.clientId === numericClientId)
  if (existing) {
    activeName.value = existing.name
    return
  }
  createSessionTab(numericClientId)
}

/**
 * 为当前激活主机新增一个并行 shell 会话。
 */
function createSiblingSession() {
  const active = tabs.value.find((tab) => tab.name === activeName.value)
  const clientId = active ? active.clientId : props.id
  createSessionTab(clientId)
}

/**
 * 保存指定 Tab 的 SSH 配置，成功后进入终端连接态。
 *
 * @param {object} tab 终端 Tab 状态
 */
function saveConnection(tab) {
  const form = formRefs[tab.name]
  if (!form) return
  form.validate((isValid) => {
    if (isValid) {
      post(
        '/api/monitor/ssh-save',
        {
          ...tab.connection,
          id: tab.clientId
        },
        () => (tab.state = 2)
      )
    }
  })
}

/**
 * 关闭指定终端 Tab，只销毁该 Tab 的终端组件和连接状态。
 *
 * @param {string} name Tab 名称
 */
function closeTab(name) {
  activeName.value = resolveNextActiveTabName(tabs.value, name, activeName.value)
  tabs.value = tabs.value.filter((tab) => tab.name !== name)
  delete formRefs[name]
}

/**
 * 终端连接释放后回到配置页，保留当前 Tab 与 SSH 配置。
 *
 * @param {object} tab 终端 Tab 状态
 */
function markDisconnected(tab) {
  tab.state = 1
}

watch(
  () => [props.id, props.openKey],
  ([id]) => {
    openOrFocusClientTab(id)
  },
  { immediate: true }
)
</script>

<template>
  <div class="terminal-main">
    <div class="terminal-toolbar">
      <el-button size="small" type="primary" plain :disabled="!activeName" @click="createSiblingSession">
        新建当前主机会话
      </el-button>
      <span class="terminal-tip">每个 Tab 都是独立 SSH 会话，关闭 Tab 不影响其他连接。</span>
    </div>
    <el-empty v-if="tabs.length === 0" description="暂无终端会话，请从主机详情打开终端" />
    <el-tabs
      v-else
      v-model="activeName"
      type="card"
      closable
      class="terminal-tabs"
      @tab-remove="closeTab"
    >
      <el-tab-pane v-for="tab in tabs" :key="tab.name" :name="tab.name" :label="tab.title">
        <div class="login" v-loading="tab.loading" v-if="tab.state === 1">
          <i style="font-size: 50px" class="fa-solid fa-terminal"></i>
          <div style="margin-top: 10px; font-weight: bold; font-size: 20px">服务端连接信息</div>
          <el-form
            style="width: 400px; margin: 20px auto"
            :model="tab.connection"
            :rules="rules"
            :ref="(el) => setFormRef(tab.name, el)"
            label-width="100"
          >
            <div style="display: flex; gap: 10px">
              <el-form-item style="width: 100%" label="服务器IP地址" prop="ip">
                <el-input v-model="tab.connection.ip" />
              </el-form-item>
              <el-form-item style="width: 80px" prop="port" label-width="0">
                <el-input placeholder="端口" v-model="tab.connection.port" />
              </el-form-item>
            </div>
            <el-form-item prop="username" label="登录用户名">
              <el-input placeholder="请输入用户名..." v-model="tab.connection.username" />
            </el-form-item>
            <el-form-item prop="password" label="登录密码">
              <el-input placeholder="请输入密码..." type="password" v-model="tab.connection.password" />
            </el-form-item>
            <el-button type="success" @click="saveConnection(tab)" plain>立即连接</el-button>
          </el-form>
        </div>
        <div v-if="tab.state === 2">
          <el-tabs v-model="tab.panel" type="border-card" class="session-panels">
            <el-tab-pane name="terminal" label="终端">
              <div class="terminal-frame">
                <terminal
                  :id="tab.clientId"
                  :session-id="tab.sessionId"
                  @dispose="markDisconnected(tab)"
                />
              </div>
            </el-tab-pane>
            <el-tab-pane name="sftp" label="文件">
              <sftp-panel
                v-if="tab.panel === 'sftp'"
                :client-id="tab.clientId"
                :session-id="tab.sessionId"
              />
            </el-tab-pane>
          </el-tabs>
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.terminal-main {
  width: 100%;
  height: 100%;

  .terminal-toolbar {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 0 10px 10px;
  }

  .terminal-tip {
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  .terminal-tabs {
    height: calc(100% - 42px);
  }

  .session-panels {
    margin: 0 10px 10px;
  }

  .terminal-frame {
    overflow: hidden;
  }

  .login {
    text-align: center;
    padding-top: 50px;
    height: 100%;
    box-sizing: border-box;
  }
}
</style>
