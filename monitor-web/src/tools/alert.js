/**
 * 告警相关常量与展示映射，避免散落于多个组件。
 */

/** 告警等级元数据。 */
const ALERT_LEVELS = [
  { value: 'info', label: '信息', type: 'info', color: '#909399' },
  { value: 'warning', label: '警告', type: 'warning', color: '#e6a23c' },
  { value: 'critical', label: '严重', type: 'danger', color: '#f56c6c' }
]

/** 告警状态元数据。 */
const ALERT_STATUSES = [
  { value: 'firing', label: '触发中', type: 'danger' },
  { value: 'resolved', label: '已解除', type: 'success' },
  { value: 'acknowledged', label: '已确认', type: 'info' }
]

/** 监控指标元数据，与后端 AlertMetric 枚举一致。
 *
 * 单位说明（与后端 AlertEvaluatorImpl.extractMetricValue 对齐）：
 * - CPU / 内存 / 磁盘：阈值按百分比配置（0~100），后端会把客户端上报的原始值
 *   （cpuUsage 0~1 比例、memoryUsage GB 已用量、diskUsage GB 已用量）归一为百分比。
 * - 网络上下行：阈值按速率 KB/s 配置，与客户端上报值同单位，不做归一。
 */
const ALERT_METRICS = [
  { value: 'cpu', label: 'CPU 使用率', unit: '%', max: 100 },
  { value: 'memory', label: '内存使用率', unit: '%', max: 100 },
  { value: 'disk', label: '磁盘使用率', unit: '%', max: 100 },
  { value: 'network_up', label: '网络上行速率', unit: 'KB/s', max: 1000000 },
  { value: 'network_down', label: '网络下行速率', unit: 'KB/s', max: 1000000 }
]

/** 比较运算符元数据。 */
const ALERT_OPERATORS = [
  { value: 'gt', label: '大于 (>)' },
  { value: 'gte', label: '大于等于 (>=)' },
  { value: 'lt', label: '小于 (<)' },
  { value: 'lte', label: '小于等于 (<=)' }
]

/** 通知通道类型元数据。 */
const CHANNEL_TYPES = [
  { value: 'mail', label: '邮件' },
  { value: 'webhook', label: 'Webhook' },
  { value: 'dingtalk', label: '钉钉' },
  { value: 'feishu', label: '飞书' }
]

/**
 * 通过 value 查找告警等级元数据。
 *
 * @param {string} value 等级 value
 * @returns {object} 元数据对象
 */
function levelMeta(value) {
  return ALERT_LEVELS.find((l) => l.value === value) || { label: value, type: '', color: '' }
}

/**
 * 通过 value 查找告警状态元数据。
 *
 * @param {string} value 状态 value
 * @returns {object} 元数据对象
 */
function statusMeta(value) {
  return ALERT_STATUSES.find((s) => s.value === value) || { label: value, type: '' }
}

/**
 * 通过 value 查找指标元数据。
 *
 * @param {string} value 指标 value
 * @returns {object} 元数据对象
 */
function metricMeta(value) {
  return ALERT_METRICS.find((m) => m.value === value) || { label: value, unit: '', max: 0 }
}

/**
 * 通过 value 查找运算符元数据。
 *
 * @param {string} value 运算符 value
 * @returns {object} 元数据对象
 */
function operatorMeta(value) {
  return ALERT_OPERATORS.find((o) => o.value === value) || { label: value }
}

/**
 * 通过 value 查找通道类型元数据。
 *
 * @param {string} value 通道类型 value
 * @returns {object} 元数据对象
 */
function channelTypeMeta(value) {
  return CHANNEL_TYPES.find((c) => c.value === value) || { label: value }
}

/**
 * 格式化指标当前值用于列表展示。
 *
 * @param {string} metric 指标 key
 * @param {number} value 数值
 * @returns {string} 格式化字符串
 */
function formatMetricValue(metric, value) {
  if (value === null || value === undefined) return '-'
  const meta = metricMeta(metric)
  if (meta.unit === '%') return `${Number(value).toFixed(1)} %`
  if (meta.unit === 'KB/s') return `${Number(value).toFixed(1)} KB/s`
  return String(value)
}

export {
  ALERT_LEVELS,
  ALERT_STATUSES,
  ALERT_METRICS,
  ALERT_OPERATORS,
  CHANNEL_TYPES,
  levelMeta,
  statusMeta,
  metricMeta,
  operatorMeta,
  channelTypeMeta,
  formatMetricValue
}
