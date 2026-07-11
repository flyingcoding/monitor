<script setup>
import { computed } from 'vue'
import { ArrowDown, ArrowRight, ArrowUp, CopyDocument, EditPen } from '@element-plus/icons-vue'
import { copyIp, fitByUnit, osNameToIcon, percentageToStatus, rename } from '@/tools'

const props = defineProps({
  data: {
    type: Object,
    required: true
  },
  update: Function
})

const emit = defineEmits(['open'])

/**
 * 将运行时值转换为有限数字，避免异常数据破坏进度条布局。
 *
 * @param {unknown} value 原始数值
 * @returns {number} 安全数值
 */
function toFiniteNumber(value) {
  const numeric = Number(value)
  return Number.isFinite(numeric) ? numeric : 0
}

/**
 * 将百分比限制在 Element Plus 进度条接受的 0 到 100 范围。
 *
 * @param {number} value 原始百分比
 * @returns {number} 限制后的百分比
 */
function clampPercentage(value) {
  return Math.min(100, Math.max(0, value))
}

const cpuPercentage = computed(() => clampPercentage(toFiniteNumber(props.data.cpuUsage) * 100))
const memoryTotal = computed(() => toFiniteNumber(props.data.memory))
const memoryUsage = computed(() => toFiniteNumber(props.data.memoryUsage))
const memoryPercentage = computed(() => {
  if (memoryTotal.value <= 0) return 0
  return clampPercentage((memoryUsage.value / memoryTotal.value) * 100)
})
const osMeta = computed(() => osNameToIcon(props.data.osName))

/**
 * 请求父级打开当前主机详情。
 */
function openDetails() {
  emit('open')
}

/**
 * 允许键盘用户通过 Enter 或 Space 打开主机详情。
 *
 * @param {KeyboardEvent} event 键盘事件
 */
function handleCardKeydown(event) {
  if (event.key === 'Enter' || event.key === ' ') {
    event.preventDefault()
    openDetails()
  }
}

/**
 * 打开主机重命名交互，并阻止触发详情打开。
 */
function renameClient() {
  rename(props.data.id, props.data.name, props.update)
}

/**
 * 复制主机公网 IP，并阻止触发详情打开。
 */
function copyClientIp() {
  copyIp(props.data.ip)
}
</script>

<template>
  <article
    class="instance-card"
    :class="{ 'is-offline': !data.online }"
    role="button"
    tabindex="0"
    :aria-label="`查看主机 ${data.name} 详情，当前${data.online ? '运行中' : '离线'}`"
    @click="openDetails"
    @keydown="handleCardKeydown"
  >
    <header class="card-header">
      <div class="host-identity">
        <span :class="`fi fi-${data.location}`" aria-hidden="true"></span>
        <strong>{{ data.name }}</strong>
        <button
          type="button"
          class="card-icon-button"
          :aria-label="`重命名主机 ${data.name}`"
          @click.stop="renameClient"
        >
          <el-icon><EditPen /></el-icon>
        </button>
      </div>
      <div class="host-state">
        <span class="status-dot" aria-hidden="true"></span>
        <span>{{ data.online ? '运行中' : '离线' }}</span>
        <el-icon class="open-arrow" aria-hidden="true"><ArrowRight /></el-icon>
      </div>
    </header>

    <div class="card-divider" />

    <dl class="meta-list">
      <div>
        <dt>公网 IP</dt>
        <dd>
          <span>{{ data.ip }}</span>
          <button
            type="button"
            class="card-icon-button copy-button"
            :aria-label="`复制 IP ${data.ip}`"
            @click.stop="copyClientIp"
          >
            <el-icon><CopyDocument /></el-icon>
          </button>
        </dd>
      </div>
      <div>
        <dt>操作系统</dt>
        <dd>
          <i
            :style="{ color: osMeta.color }"
            :class="`fa-brands ${osMeta.icon}`"
            aria-hidden="true"
          />
          <span>{{ `${data.osName} ${data.osVersion}` }}</span>
        </dd>
      </div>
      <div>
        <dt>处理器</dt>
        <dd :title="data.cpuName">{{ data.cpuName }}</dd>
      </div>
    </dl>

    <div class="hardware-line">
      <span><i class="fa-solid fa-microchip" aria-hidden="true"></i>{{ data.cpuCore }} CPU</span>
      <span
        ><i class="fa-solid fa-memory" aria-hidden="true"></i>{{ memoryTotal.toFixed(1) }} GB</span
      >
    </div>

    <div class="utilization">
      <div class="resource-block">
        <div class="resource-label">
          <span>CPU</span>
          <strong>{{ cpuPercentage.toFixed(1) }}%</strong>
        </div>
        <el-progress
          :status="percentageToStatus(cpuPercentage)"
          :percentage="cpuPercentage"
          :stroke-width="6"
          :show-text="false"
        />
      </div>
      <div class="resource-block">
        <div class="resource-label">
          <span>内存</span>
          <strong>{{ memoryUsage.toFixed(1) }} GB</strong>
        </div>
        <el-progress
          :status="percentageToStatus(memoryPercentage)"
          :percentage="memoryPercentage"
          :stroke-width="6"
          :show-text="false"
        />
      </div>
    </div>

    <div class="network-grid">
      <div>
        <span>网络上传</span>
        <strong
          ><el-icon aria-hidden="true"><ArrowUp /></el-icon
          >{{ fitByUnit(data.networkUpload, 'KB') }}/s</strong
        >
      </div>
      <div>
        <span>网络下载</span>
        <strong
          ><el-icon aria-hidden="true"><ArrowDown /></el-icon
          >{{ fitByUnit(data.networkDownload, 'KB') }}/s</strong
        >
      </div>
    </div>
  </article>
</template>

<style scoped>
.instance-card {
  width: 100%;
  min-width: 0;
  min-height: 302px;
  padding: 18px;
  border: 1px solid var(--app-border);
  border-radius: var(--app-radius-md);
  background: var(--app-surface);
  color: var(--app-text);
  box-shadow: var(--app-shadow-sm);
  cursor: pointer;
  transition:
    transform var(--app-transition),
    border-color var(--app-transition),
    box-shadow var(--app-transition);
}

.instance-card:hover {
  transform: translateY(-2px);
  border-color: color-mix(in srgb, var(--app-primary) 45%, var(--app-border));
  box-shadow: 0 12px 30px rgba(15, 35, 63, 0.1);
}

.instance-card:focus-visible {
  outline: 3px solid color-mix(in srgb, var(--app-primary) 48%, transparent);
  outline-offset: 2px;
}

.card-header {
  min-width: 0;
  min-height: 30px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.host-identity,
.host-state,
.hardware-line,
.network-grid strong {
  display: flex;
  align-items: center;
}

.host-identity {
  min-width: 0;
  gap: 8px;
}

.host-identity .fi {
  flex: 0 0 auto;
  font-size: 20px;
  box-shadow: 0 0 0 1px rgba(15, 35, 63, 0.08);
}

.host-identity strong {
  min-width: 0;
  overflow: hidden;
  color: var(--app-text);
  font-size: 15px;
  font-weight: 750;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.host-state {
  flex: 0 0 auto;
  gap: 7px;
  color: var(--app-text-secondary);
  font-size: 12px;
  font-weight: 650;
}

.status-dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: var(--app-success);
  box-shadow: 0 0 0 4px rgba(22, 163, 74, 0.1);
}

.is-offline .status-dot {
  background: var(--app-muted);
  box-shadow: 0 0 0 4px rgba(148, 163, 184, 0.12);
}

.open-arrow {
  margin-left: 3px;
  color: var(--app-text);
  font-size: 15px;
}

.card-icon-button {
  width: 26px;
  height: 26px;
  display: inline-grid;
  flex: 0 0 auto;
  place-items: center;
  padding: 0;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--app-text-secondary);
  cursor: pointer;
  transition:
    color var(--app-transition),
    background-color var(--app-transition);
}

.card-icon-button:hover {
  background: var(--app-primary-soft);
  color: var(--app-primary);
}

.copy-button {
  color: var(--app-primary);
}

.card-divider {
  height: 1px;
  margin: 13px 0 15px;
  background: var(--app-border);
}

.meta-list {
  display: grid;
  gap: 7px;
  margin: 0;
}

.meta-list > div {
  min-width: 0;
  display: grid;
  grid-template-columns: 76px minmax(0, 1fr);
  align-items: center;
  gap: 8px;
}

.meta-list dt {
  color: var(--app-text-secondary);
  font-size: 12px;
}

.meta-list dd {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0;
  overflow: hidden;
  color: var(--app-text);
  font-size: 12px;
  font-weight: 580;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.hardware-line {
  gap: 16px;
  margin-top: 12px;
  color: var(--app-text-secondary);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.hardware-line span {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.utilization {
  display: grid;
  gap: 11px;
  margin-top: 18px;
}

.resource-label {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 5px;
  color: var(--app-text-secondary);
  font-size: 11px;
}

.resource-label strong {
  color: var(--app-text);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}

.resource-block :deep(.el-progress-bar__outer) {
  background: color-mix(in srgb, var(--app-success) 13%, transparent);
}

.network-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  margin-top: 15px;
  padding-top: 13px;
  border-top: 1px solid var(--app-border);
}

.network-grid > div {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.network-grid > div + div {
  padding-left: 14px;
  border-left: 1px solid var(--app-border);
}

.network-grid span {
  color: var(--app-text-secondary);
  font-size: 10px;
}

.network-grid strong {
  min-width: 0;
  gap: 5px;
  color: var(--app-text);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.network-grid .el-icon {
  color: var(--app-primary);
  font-size: 13px;
}

.is-offline .utilization,
.is-offline .network-grid {
  opacity: 0.62;
}

@media (max-width: 680px) {
  .instance-card {
    min-height: 0;
    padding: 17px 16px;
  }

  .card-header {
    align-items: flex-start;
  }

  .host-state {
    padding-top: 3px;
  }

  .hardware-line {
    gap: 12px;
  }
}
</style>
