/**
 * CSV 导出工具：把任意对象数组按列定义序列化为 CSV 文本并触发浏览器下载。
 *
 * 设计要点：
 * - 单元格内含逗号 / 换行 / 双引号时自动加双引号包裹并将内部双引号转义为两个双引号
 *   （RFC 4180 §2 规则），保证 Excel / pandas / R 等通用工具能正确解析。
 * - 输出前缀 UTF-8 BOM（U+FEFF），让 Excel 在 Windows 中文环境下默认按 UTF-8 解码，
 *   避免中文表头乱码；不影响 pandas / awk 等正确处理 UTF-8 的工具。
 * - 使用 {@code Blob} + {@code URL.createObjectURL} + 隐式 {@code <a download>} 触发下载，
 *   不污染 window 全局，并在下载后释放 ObjectURL 资源。
 */

/** UTF-8 BOM 标记，前置后让 Excel 按 UTF-8 解码。 */
const UTF8_BOM = '﻿'

/**
 * 将单个值序列化为 CSV 单元格字符串，按需加双引号转义。
 *
 * @param {*} value 任意原始值；{@code null} / {@code undefined} 输出空字符串
 * @returns {string} CSV 单元格字符串
 */
function escapeCell(value) {
  if (value === null || value === undefined) return ''
  const str = String(value)
  // 仅在包含特殊字符时加双引号，减小文件体积
  if (str.includes(',') || str.includes('"') || str.includes('\n') || str.includes('\r')) {
    return `"${str.replace(/"/g, '""')}"`
  }
  return str
}

/**
 * 把数据数组按列定义转换为 CSV 字符串（不含 BOM）。
 *
 * @param {Array<object>} rows 数据数组
 * @param {Array<{key: string, label: string, format?: (v: any, row: object) => *}>} columns 列定义；
 *   {@code key} 取值字段名，{@code label} 表头中文标签，{@code format} 可选格式化回调
 * @returns {string} CSV 字符串，行间以 \r\n 分隔
 */
export function buildCsv(rows, columns) {
  const header = columns.map((c) => escapeCell(c.label)).join(',')
  const body = (rows || [])
    .map((row) =>
      columns
        .map((c) => {
          const raw = row ? row[c.key] : undefined
          const value = typeof c.format === 'function' ? c.format(raw, row) : raw
          return escapeCell(value)
        })
        .join(',')
    )
    .join('\r\n')
  return body ? `${header}\r\n${body}` : header
}

/**
 * 把数据数组转换为 CSV 字符串并触发浏览器下载。
 *
 * @param {Array<object>} rows 数据数组
 * @param {Array<{key: string, label: string, format?: (v: any, row: object) => *}>} columns 列定义
 * @param {string} filename 下载文件名（不含 .csv 扩展，将自动补全）
 */
export function downloadCsv(rows, columns, filename) {
  const csv = buildCsv(rows, columns)
  // BOM 前缀让 Excel 默认按 UTF-8 解码中文表头/数据
  const blob = new Blob([UTF8_BOM, csv], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename.endsWith('.csv') ? filename : `${filename}.csv`
  // Safari 需要 a 挂载到 DOM 才能触发 click
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}
