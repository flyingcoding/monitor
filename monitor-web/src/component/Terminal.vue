<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { AttachAddon } from '@xterm/addon-attach/src/AttachAddon'
import { Terminal } from '@xterm/xterm'
import { takeAccessToken } from '@/net'
import { withQuery } from '@/net/query'
import '@xterm/xterm/css/xterm.css'

const props = defineProps({
  id: Number,
  sessionId: {
    type: String,
    default: ''
  }
})
const emits = defineEmits(['dispose'])
const terminalRef = ref()

const MAX_RECONNECT = 5
let reconnectCount = 0
let reconnectTimer = null
let socket = null
let attachAddon = null

const term = new Terminal({
  lineHeight: 1.2,
  rows: 20,
  fontSize: 13,
  fontFamily: "Monaco, Menlo, Consolas, 'Courier New', monospace",
  fontWeight: 'bold',
  theme: {
    background: '#000000'
  },
  cursorBlink: true,
  cursorStyle: 'underline',
  scrollback: 100,
  tabStopWidth: 4
})

/**
 * 构建终端 WebSocket 地址，并在 query 中附带访问令牌。
 */
function buildTerminalSocketUrl() {
  const token = takeAccessToken()
  if (!token) {
    return null
  }
  const wsBaseUrl = import.meta.env.VITE_WS_BASE_URL || `ws://${window.location.host}`
  const normalizedBaseUrl = wsBaseUrl.endsWith('/') ? wsBaseUrl.slice(0, -1) : wsBaseUrl
  return withQuery(`${normalizedBaseUrl}/terminal/${props.id}`, {
    token,
    sessionId: props.sessionId
  })
}

/**
 * 建立终端 WebSocket 连接并处理重连。
 */
function connect() {
  const socketUrl = buildTerminalSocketUrl()
  if (!socketUrl) {
    emits('dispose')
    return
  }
  socket = new WebSocket(socketUrl)

  socket.onopen = () => {
    reconnectCount = 0
    if (attachAddon) {
      attachAddon.dispose()
    }
    attachAddon = new AttachAddon(socket)
    term.loadAddon(attachAddon)
  }

  socket.onclose = (evt) => {
    if (attachAddon) {
      attachAddon.dispose()
      attachAddon = null
    }
    if (evt.code === 1000) {
      ElMessage.success('远程SSH连接已断开')
      emits('dispose')
      return
    }
    if (reconnectCount < MAX_RECONNECT) {
      reconnectCount++
      const delay = 2000 * Math.pow(2, reconnectCount - 1)
      ElMessage.warning(`连接断开，${delay / 1000}秒后尝试第${reconnectCount}次重连...`)
      reconnectTimer = setTimeout(() => connect(), delay)
    } else {
      ElMessage.error('重连失败，已达最大重试次数')
      emits('dispose')
    }
  }

  socket.onerror = () => {
    // onclose will handle reconnection
  }
}

onMounted(() => {
  term.open(terminalRef.value)
  term.focus()
  connect()
})

onBeforeUnmount(() => {
  if (reconnectTimer) {
    clearTimeout(reconnectTimer)
    reconnectTimer = null
  }
  reconnectCount = MAX_RECONNECT // prevent reconnection during unmount
  if (socket) {
    socket.close()
  }
  if (attachAddon) {
    attachAddon.dispose()
  }
  term.dispose()
})
</script>

<template>
  <div ref="terminalRef" class="xterm" />
</template>

<style scoped></style>
