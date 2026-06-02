/**
 * 按单位自适应格式化容量或速率值。
 *
 * @param {number} value 数值
 * @param {string} unit 当前单位
 * @returns {string} 格式化后的字符串
 */
function fitByUnit(value, unit) {
  const units = ['B', 'KB', 'MB', 'GB', 'TB', 'PB']
  let index = units.indexOf(unit)
  while ((value < 1 && value !== 0 && index > 0) || (value >= 1024 && index < units.length - 1)) {
    if (value >= 1024) {
      value /= 1024
      index++
    } else {
      value *= 1024
      index--
    }
  }
  return `${value.toFixed(1)} ${units[index]}`
}

/**
 * 将百分比映射为 Element Plus 进度条状态。
 *
 * @param {number} percentage 百分比
 * @returns {'success'|'warning'|'exception'} 状态
 */
function percentageToStatus(percentage) {
  if (percentage < 50) return 'success'
  if (percentage < 80) return 'warning'
  return 'exception'
}

/**
 * 把字节数格式化为 MB / GB 字符串。
 *
 * @param {number} bytes 字节数
 * @returns {string} 格式化后的内存大小
 */
function formatMemoryBytes(bytes) {
  if (!bytes || bytes <= 0) return '0 MB'
  const mb = bytes / 1024 / 1024
  if (mb >= 1024) return `${(mb / 1024).toFixed(2)} GB`
  return `${mb.toFixed(1)} MB`
}

/**
 * 把 0~1 的比例格式化为百分比字符串。
 *
 * @param {number} value 比例值
 * @returns {string} 百分比字符串
 */
function formatRatioPercent(value) {
  if (value === null || value === undefined || Number.isNaN(value)) return '0.0%'
  return `${(value * 100).toFixed(1)}%`
}

/**
 * 格式化普通数字；空值显示占位符。
 *
 * @param {number} value 数值
 * @param {number} digits 小数位数
 * @param {string} fallback 空值占位符
 * @returns {string} 格式化结果
 */
function formatNumber(value, digits = 1, fallback = '-') {
  if (value == null) return fallback
  return Number(value).toFixed(digits)
}

/**
 * 格式化更新时间；空值显示“尚未上报”。
 *
 * @param {string|number|Date} value 时间值
 * @returns {string} 本地时间字符串
 */
function formatUpdatedAt(value) {
  if (!value) return '尚未上报'
  try {
    return new Date(value).toLocaleString()
  } catch (_e) {
    return String(value)
  }
}

/**
 * 按 danger/warning 阈值返回 Element Plus tag type。
 *
 * @param {number} value 数值
 * @param {number} dangerAt danger 阈值
 * @param {number} warningAt warning 阈值
 * @returns {'info'|'danger'|'warning'|'success'} tag type
 */
function thresholdTagType(value, dangerAt, warningAt) {
  if (value == null) return 'info'
  if (value >= dangerAt) return 'danger'
  if (value >= warningAt) return 'warning'
  return 'success'
}

/**
 * 按 exception/warning 阈值返回 Element Plus progress status。
 *
 * @param {number} value 百分比
 * @param {number} exceptionAt exception 阈值
 * @param {number} warningAt warning 阈值
 * @returns {''|'exception'|'warning'|'success'} progress status
 */
function thresholdProgressStatus(value, exceptionAt, warningAt) {
  if (value == null) return ''
  if (value >= exceptionAt) return 'exception'
  if (value >= warningAt) return 'warning'
  return 'success'
}

export {
  fitByUnit,
  percentageToStatus,
  formatMemoryBytes,
  formatRatioPercent,
  formatNumber,
  formatUpdatedAt,
  thresholdTagType,
  thresholdProgressStatus
}
