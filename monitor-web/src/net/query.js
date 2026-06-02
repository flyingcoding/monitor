/**
 * 将普通对象转为 query string，空字符串、null、undefined 不参与拼接。
 *
 * @param {object} params 查询参数对象
 * @returns {string} 不含问号的 query string
 */
function buildQueryString(params) {
  return Object.entries(params || {})
    .filter(([, value]) => value !== '' && value !== null && value !== undefined)
    .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(value)}`)
    .join('&')
}

/**
 * 给 URL 追加查询参数；无有效参数时返回原 URL。
 *
 * @param {string} url 原始 URL
 * @param {object} params 查询参数对象
 * @returns {string} 追加 query string 后的 URL
 */
function withQuery(url, params) {
  const query = buildQueryString(params)
  if (!query) return url
  return `${url}${url.includes('?') ? '&' : '?'}${query}`
}

export { buildQueryString, withQuery }
