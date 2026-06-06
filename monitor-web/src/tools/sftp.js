const MAX_SFTP_TRANSFER_BYTES = 10 * 1024 * 1024

/**
 * 拼接远端路径，避免重复斜杠。
 *
 * @param {string} dir 当前目录
 * @param {string} name 文件名或目录名
 * @returns {string} 拼接后的路径
 */
function joinRemotePath(dir, name) {
  if (!dir || dir === '.') return name
  return `${dir.replace(/\/+$/, '')}/${name.replace(/^\/+/, '')}`
}

/**
 * 获取远端路径的父目录。
 *
 * @param {string} path 当前路径
 * @returns {string} 父目录
 */
function parentRemotePath(path) {
  if (!path || path === '.') return '.'
  const normalized = path.replace(/\/+$/, '') || '/'
  if (normalized === '/') return '/'
  const index = normalized.lastIndexOf('/')
  if (index === 0) return '/'
  if (index < 0) return '.'
  return normalized.slice(0, index)
}

/**
 * 从远端路径提取文件名。
 *
 * @param {string} path 远端路径
 * @returns {string} 文件名
 */
function remoteFileName(path) {
  if (!path) return 'download'
  const normalized = path.replace(/\/+$/, '')
  const index = normalized.lastIndexOf('/')
  return index >= 0 ? normalized.slice(index + 1) : normalized
}

/**
 * 将 base64 文件内容触发为浏览器下载。
 *
 * @param {string} contentBase64 base64 内容
 * @param {string} filename 下载文件名
 */
function downloadBase64File(contentBase64, filename) {
  const binary = atob(contentBase64 || '')
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i)
  }
  const blob = new Blob([bytes], { type: 'application/octet-stream' })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename || 'download'
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  URL.revokeObjectURL(url)
}

export {
  MAX_SFTP_TRANSFER_BYTES,
  joinRemotePath,
  parentRemotePath,
  remoteFileName,
  downloadBase64File
}
