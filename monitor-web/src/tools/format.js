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

export { fitByUnit, percentageToStatus }
