<script setup>
import { onBeforeUnmount, onMounted, watch } from 'vue'
import { echarts, defaultOption, doubleSeries, singleSeries } from '@/echarts'
import {
  DEFAULT_RUNTIME_HISTORY_MAX_POINTS,
  buildRuntimeHistoryPayload
} from '@/echarts/runtime-history-data'

const charts = []
const props = defineProps({
  data: Object
})

let runtimeWorker = null
let stopDataWatch = null
let requestSeq = 0
let latestPreparedList = []

/**
 * Create the Vite module worker that prepares runtime chart payloads off the UI thread.
 *
 * @returns {Worker} Runtime history worker
 */
function createRuntimeHistoryWorker() {
  return new Worker(new URL('../echarts/runtime-history.worker.js', import.meta.url), {
    type: 'module'
  })
}

/**
 * Clone only chart-required fields before posting data to a Worker.
 *
 * @param {Array<object>} list Runtime history rows from Vue props
 * @returns {Array<object>} Plain structured-clone-safe rows
 */
function cloneRuntimeHistoryList(list) {
  if (!Array.isArray(list)) return []
  return list.map((item) => ({
    timestamp: item && item.timestamp,
    cpuUsage: item && item.cpuUsage,
    memoryUsage: item && item.memoryUsage,
    networkUpload: item && item.networkUpload,
    networkDownload: item && item.networkDownload,
    diskRead: item && item.diskRead,
    diskWrite: item && item.diskWrite
  }))
}

/**
 * Apply a prepared CPU chart payload to ECharts without running ECharts-side sampling again.
 *
 * @param {object} chartPayload Worker-prepared chart payload
 */
function updateCpuUsage(chartPayload) {
  const chart = charts[0]
  if (!chart || !chartPayload) return
  const option = defaultOption('CPU(%)', chartPayload.labels)
  singleSeries(
    option,
    'CPU使用率(%)',
    chartPayload.series[0],
    ['#72c4fe', '#72d5fe', '#2b6fd733'],
    null
  )
  chart.setOption(option)
}

/**
 * Apply a prepared memory chart payload to ECharts without running ECharts-side sampling again.
 *
 * @param {object} chartPayload Worker-prepared chart payload
 */
function updateMemoryUsage(chartPayload) {
  const chart = charts[1]
  if (!chart || !chartPayload) return
  const option = defaultOption('内存(MB)', chartPayload.labels)
  singleSeries(
    option,
    '内存使用(MB)',
    chartPayload.series[0],
    ['#6be3a3', '#bbfad4', '#A5FFD033'],
    null
  )
  chart.setOption(option)
}

/**
 * Apply a prepared network chart payload to ECharts without running ECharts-side sampling again.
 *
 * @param {object} chartPayload Worker-prepared chart payload
 */
function updateNetworkUsage(chartPayload) {
  const chart = charts[2]
  if (!chart || !chartPayload) return
  const option = defaultOption('网络(KB/s)', chartPayload.labels)
  doubleSeries(
    option,
    ['上传(KB/s)', '下载(KB/s)'],
    chartPayload.series,
    [
      ['#f6b66e', '#ffd29c', '#fddfc033'],
      ['#79c7ff', '#3cabf3', 'rgba(192,242,253,0.2)']
    ],
    null
  )
  chart.setOption(option)
}

/**
 * Apply a prepared disk chart payload to ECharts without running ECharts-side sampling again.
 *
 * @param {object} chartPayload Worker-prepared chart payload
 */
function updateDiskUsage(chartPayload) {
  const chart = charts[3]
  if (!chart || !chartPayload) return
  const option = defaultOption('磁盘(MB/s)', chartPayload.labels)
  doubleSeries(
    option,
    ['读取(MB/s)', '写入(MB/s)'],
    chartPayload.series,
    [
      ['#d2d2d2', '#d5d5d5', 'rgba(199,199,199,0.2)'],
      ['#757575', '#7c7c7c', 'rgba(94,94,94,0.2)']
    ],
    null
  )
  chart.setOption(option)
}

/**
 * Apply all worker-prepared chart payloads to the four runtime charts.
 *
 * @param {object} payload Runtime history chart payload
 */
function applyRuntimeHistoryPayload(payload) {
  if (!payload) return
  updateCpuUsage(payload.cpu)
  updateMemoryUsage(payload.memory)
  updateNetworkUsage(payload.network)
  updateDiskUsage(payload.disk)
}

/**
 * Build and apply chart payloads on the main thread when Worker is unavailable or failed.
 *
 * @param {Array<object>} list Plain runtime history rows
 */
function applyRuntimeHistoryFallback(list) {
  applyRuntimeHistoryPayload(
    buildRuntimeHistoryPayload(list, DEFAULT_RUNTIME_HISTORY_MAX_POINTS)
  )
}

/**
 * Handle Worker response and ignore stale responses from older runtime data snapshots.
 *
 * @param {MessageEvent} event Worker message event
 */
function handleWorkerMessage(event) {
  const { seq, payload, error } = event.data || {}
  if (seq !== requestSeq) return
  if (error) {
    applyRuntimeHistoryFallback(latestPreparedList)
    return
  }
  applyRuntimeHistoryPayload(payload)
}

/**
 * Disable the Worker after a hard worker error and fall back to synchronous preparation.
 */
function handleWorkerError() {
  if (runtimeWorker) {
    runtimeWorker.terminate()
    runtimeWorker = null
  }
  applyRuntimeHistoryFallback(latestPreparedList)
}

/**
 * Initialize the Worker when the browser supports it; otherwise keep sync fallback.
 */
function initRuntimeWorker() {
  if (typeof Worker === 'undefined') return
  try {
    runtimeWorker = createRuntimeHistoryWorker()
    runtimeWorker.onmessage = handleWorkerMessage
    runtimeWorker.onerror = handleWorkerError
  } catch (_e) {
    runtimeWorker = null
  }
}

/**
 * Request chart payload preparation for the latest runtime history list.
 *
 * @param {Array<object>} list Runtime history rows from props
 */
function requestRuntimeHistoryPayload(list) {
  if (!list || !list.length) return
  latestPreparedList = cloneRuntimeHistoryList(list)
  requestSeq++

  if (!runtimeWorker) {
    applyRuntimeHistoryFallback(latestPreparedList)
    return
  }

  try {
    runtimeWorker.postMessage({
      seq: requestSeq,
      list: latestPreparedList,
      maxPoints: DEFAULT_RUNTIME_HISTORY_MAX_POINTS
    })
  } catch (_e) {
    handleWorkerError()
  }
}

/**
 * Initialize all ECharts instances after their DOM containers are mounted.
 */
function initCharts() {
  const chartList = [
    document.getElementById('cpuUsage'),
    document.getElementById('memoryUsage'),
    document.getElementById('networkUsage'),
    document.getElementById('diskUsage')
  ]
  for (let i = 0; i < chartList.length; i++) {
    const chart = chartList[i]
    charts[i] = echarts.init(chart)
  }
}

/**
 * Resize every runtime chart when the viewport changes.
 */
function handleResize() {
  charts.forEach((chart) => chart && chart.resize())
}

onMounted(() => {
  initCharts()
  initRuntimeWorker()
  window.addEventListener('resize', handleResize)
  stopDataWatch = watch(
    () => props.data,
    (list) => requestRuntimeHistoryPayload(list),
    { immediate: true, deep: true }
  )
})

onBeforeUnmount(() => {
  if (stopDataWatch) {
    stopDataWatch()
    stopDataWatch = null
  }
  if (runtimeWorker) {
    runtimeWorker.terminate()
    runtimeWorker = null
  }
  window.removeEventListener('resize', handleResize)
  charts.forEach((chart) => {
    if (chart) chart.dispose()
  })
})
</script>

<template>
  <div class="charts">
    <div id="cpuUsage" style="width: 100%; height: 170px"></div>
    <div id="memoryUsage" style="width: 100%; height: 170px"></div>
    <div id="networkUsage" style="width: 100%; height: 170px"></div>
    <div id="diskUsage" style="width: 100%; height: 170px"></div>
  </div>
</template>

<style scoped>
.charts {
  display: grid;
  grid-template-columns: 1fr 1fr;
  grid-gap: 20px;
}
</style>
