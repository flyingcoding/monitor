<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { fetchPublicSummary } from '@/net/status-page'

const summary = ref(null)
const loading = ref(true)
const errorMessage = ref('')
const lastFetchAt = ref(null)
let refreshHandle = null

/**
 * 拉取一次状态页数据；用于挂载与定时轮询。loading 仅在首次拉取时表现为 spinner，
 * 后续轮询失败保留旧数据并不打扰用户。
 */
function fetchOnce() {
  fetchPublicSummary(
    (data) => {
      summary.value = data
      lastFetchAt.value = Date.now()
      errorMessage.value = ''
      loading.value = false
    },
    (msg) => {
      // 首次失败显示错误占位；后续失败保留旧数据
      if (!summary.value) {
        errorMessage.value = msg || '加载失败'
        loading.value = false
      }
    }
  )
}

/**
 * 整体可用率徽章颜色：>=99% 绿 / >=95% 黄 / 其它红 / 数据缺失灰。
 */
const overallStatus = computed(() => {
  const v = summary.value && summary.value.overallAvailability
  if (v === null || v === undefined) return { className: 'badge-unknown', text: '数据不足', percent: '—' }
  const percent = (v * 100).toFixed(2)
  if (v >= 0.99) return { className: 'badge-up', text: '全部正常', percent: percent + '%' }
  if (v >= 0.95) return { className: 'badge-degraded', text: '部分异常', percent: percent + '%' }
  return { className: 'badge-down', text: '严重异常', percent: percent + '%' }
})

/**
 * 整体标题样式：管理员配置的品牌色会覆盖默认徽章绿。
 */
const titleStyle = computed(() => {
  const color = summary.value && summary.value.brandColor
  return color ? { color } : {}
})

/**
 * 状态页客户端卡片着色：每个 0..1 桶按比例映射到 CSS class，方便阅读。
 *
 * @param {number|null} value 桶值（0..1），null/undefined 表示 "未上线" 时段
 * @returns {string} CSS 类名
 */
function bucketClass(value) {
  if (value === null || value === undefined) return 'bucket-empty'
  if (value >= 0.999) return 'bucket-up'
  if (value <= 0.001) return 'bucket-down'
  return 'bucket-partial'
}

/**
 * 单 client 可用率徽章。
 *
 * @param {object} client StatusPageClientVO
 * @returns {string} 显示文本
 */
function clientAvailabilityText(client) {
  if (client.availability24h === null || client.availability24h === undefined) {
    return '数据不足'
  }
  return (client.availability24h * 100).toFixed(2) + '%'
}

/**
 * 单 client 在线徽章。
 */
function clientStatusText(client) {
  return client.online ? '在线' : '离线'
}

function clientStatusClass(client) {
  return client.online ? 'dot dot-up' : 'dot dot-down'
}

/**
 * 距上次上报的可读时间。秒级 → 分钟 → 小时 → 天。
 */
function lastSeenText(client) {
  const seconds = client.lastSeenSecondsAgo
  if (seconds === null || seconds === undefined) return '从未上线'
  if (seconds < 60) return `${seconds} 秒前`
  if (seconds < 3600) return `${Math.floor(seconds / 60)} 分钟前`
  if (seconds < 86400) return `${Math.floor(seconds / 3600)} 小时前`
  return `${Math.floor(seconds / 86400)} 天前`
}

onMounted(() => {
  fetchOnce()
  // 30s 轮询；与服务端 Caffeine TTL 对齐，避免无效查询
  refreshHandle = setInterval(fetchOnce, 30 * 1000)
})

onBeforeUnmount(() => {
  if (refreshHandle) {
    clearInterval(refreshHandle)
    refreshHandle = null
  }
})
</script>

<template>
  <div class="status-page">
    <div class="container">
      <!-- 头部品牌区 -->
      <header class="status-header">
        <img
          v-if="summary && summary.logoUrl"
          :src="summary.logoUrl"
          alt="logo"
          class="status-logo"
        />
        <div class="status-title-block">
          <h1 :style="titleStyle">{{ (summary && summary.title) || '系统状态' }}</h1>
          <p v-if="summary && summary.subtitle" class="status-subtitle">{{ summary.subtitle }}</p>
        </div>
      </header>

      <!-- 总览徽章 -->
      <section v-if="summary && !loading" class="overall-card" :class="overallStatus.className">
        <div class="overall-text">
          <div class="overall-label">{{ overallStatus.text }}</div>
          <div class="overall-percent">最近 24 小时可用率 {{ overallStatus.percent }}</div>
        </div>
      </section>

      <!-- 加载占位 -->
      <section v-if="loading" class="loading-card">
        <el-skeleton :rows="3" animated />
      </section>

      <!-- 错误占位 -->
      <section v-else-if="errorMessage" class="error-card">
        <el-empty :image-size="80" :description="`无法加载状态页数据：${errorMessage}`" />
      </section>

      <!-- 客户端卡片 -->
      <section v-else-if="summary && summary.clients && summary.clients.length" class="client-grid">
        <article
          v-for="(client, index) in summary.clients"
          :key="index"
          class="client-card"
        >
          <div class="client-header">
            <span :class="clientStatusClass(client)" :title="clientStatusText(client)"></span>
            <div class="client-name">{{ client.displayName }}</div>
            <div class="client-availability">{{ clientAvailabilityText(client) }}</div>
          </div>
          <div class="bucket-row">
            <template v-if="client.buckets && client.buckets.length">
              <span
                v-for="(b, i) in client.buckets"
                :key="i"
                class="bucket"
                :class="bucketClass(b)"
                :title="`${i * 30} ~ ${(i + 1) * 30} 分钟前：${b >= 0.5 ? '在线' : '离线'}`"
              ></span>
            </template>
            <div v-else class="bucket-empty-placeholder">最近 24 小时数据不足</div>
          </div>
          <div class="client-footer">
            <span>{{ clientStatusText(client) }}</span>
            <span>·</span>
            <span>{{ lastSeenText(client) }}</span>
          </div>
        </article>
      </section>

      <!-- 空状态 -->
      <section v-else class="empty-card">
        <el-empty :image-size="100" description="暂无公开的客户端" />
      </section>

      <!-- 底栏：上次刷新时间 -->
      <footer class="status-footer">
        <span v-if="lastFetchAt">
          上次更新：{{ new Date(lastFetchAt).toLocaleTimeString() }}（每 30 秒自动刷新）
        </span>
      </footer>
    </div>
  </div>
</template>

<style scoped>
.status-page {
  min-height: 100vh;
  width: 100%;
  background-color: var(--el-bg-color-page);
  padding: 24px 16px;
  box-sizing: border-box;
}

.container {
  max-width: 1100px;
  margin: 0 auto;
}

.status-header {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 24px;
}

.status-logo {
  width: 48px;
  height: 48px;
  object-fit: contain;
}

.status-title-block h1 {
  margin: 0;
  font-size: 28px;
  line-height: 1.2;
}

.status-subtitle {
  margin: 4px 0 0;
  color: grey;
  font-size: 14px;
}

.overall-card {
  padding: 20px 24px;
  border-radius: 10px;
  background-color: var(--el-bg-color);
  border-left: 6px solid #10b981;
  margin-bottom: 24px;
}

.overall-card.badge-up {
  border-left-color: #10b981;
}

.overall-card.badge-degraded {
  border-left-color: #f59e0b;
}

.overall-card.badge-down {
  border-left-color: #ef4444;
}

.overall-card.badge-unknown {
  border-left-color: #9ca3af;
}

.overall-label {
  font-size: 22px;
  font-weight: 600;
}

.overall-percent {
  margin-top: 6px;
  font-size: 14px;
  color: grey;
}

.loading-card,
.error-card,
.empty-card {
  padding: 24px;
  border-radius: 10px;
  background-color: var(--el-bg-color);
}

.client-grid {
  display: grid;
  grid-template-columns: 1fr;
  gap: 12px;
}

@media (min-width: 768px) {
  .client-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (min-width: 1100px) {
  .client-grid {
    grid-template-columns: repeat(3, 1fr);
  }
}

.client-card {
  background-color: var(--el-bg-color);
  border-radius: 10px;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.04);
}

.client-header {
  display: flex;
  align-items: center;
  gap: 8px;
}

.dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  display: inline-block;
  flex-shrink: 0;
}

.dot-up {
  background-color: #10b981;
}

.dot-down {
  background-color: #ef4444;
}

.client-name {
  flex: 1;
  font-size: 15px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.client-availability {
  font-size: 13px;
  color: grey;
}

.bucket-row {
  display: flex;
  gap: 2px;
  height: 22px;
  align-items: stretch;
}

.bucket {
  flex: 1;
  min-width: 3px;
  border-radius: 2px;
  background-color: #9ca3af;
}

.bucket-up {
  background-color: #10b981;
}

.bucket-partial {
  background-color: #f59e0b;
}

.bucket-down {
  background-color: #ef4444;
}

.bucket-empty {
  background-color: var(--el-border-color);
}

.bucket-empty-placeholder {
  font-size: 12px;
  color: grey;
  text-align: center;
  width: 100%;
}

.client-footer {
  display: flex;
  gap: 6px;
  align-items: center;
  font-size: 12px;
  color: grey;
}

.status-footer {
  margin-top: 24px;
  text-align: center;
  color: grey;
  font-size: 12px;
}
</style>
