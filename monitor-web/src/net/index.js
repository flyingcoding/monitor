import axios from 'axios'
import { ElMessage } from 'element-plus'
import { useStore } from '@/store'

const authItemName = 'authorize'

const accessHeader = () => {
  return {
    Authorization: `Bearer ${takeAccessToken()}`
  }
}

const defaultError = (error) => {
  console.error(error)
  ElMessage.error('发生了一些错误，请联系管理员')
}

const defaultFailure = (message, status, url) => {
  console.warn(`请求地址: ${url}, 状态码: ${status}, 错误信息: ${message}`)
  ElMessage.warning(message)
}

const silentFailure = () => {}

/**
 * 获取并校验当前登录访问令牌。
 */
function takeAccessToken() {
  const str = localStorage.getItem(authItemName) || sessionStorage.getItem(authItemName)
  if (!str) return null
  const authObj = JSON.parse(str)
  if (new Date(authObj.expire) <= new Date()) {
    deleteAccessToken()
    ElMessage.warning('登录状态已过期，请重新登录！')
    return null
  }
  return authObj.token
}

function storeAccessToken(remember, token, expire) {
  const authObj = {
    token: token,
    expire: expire
  }
  const str = JSON.stringify(authObj)
  if (remember) localStorage.setItem(authItemName, str)
  else sessionStorage.setItem(authItemName, str)
}

function deleteAccessToken() {
  localStorage.removeItem(authItemName)
  sessionStorage.removeItem(authItemName)
}

// GET 请求自动重试拦截器
axios.interceptors.response.use(
  (response) => response,
  (error) => {
    const config = error.config
    if (!config || config.method !== 'get') return Promise.reject(error)

    const status = error.response ? error.response.status : 0
    // 401 不重试，直接跳转登录页
    if (status === 401) return Promise.reject(error)
    // 仅对 5xx 和网络错误重试
    if (status < 500 && status !== 0) return Promise.reject(error)

    config.__retryCount = config.__retryCount || 0
    if (config.__retryCount >= 2) return Promise.reject(error)

    config.__retryCount++
    const delay = 1000 * Math.pow(2, config.__retryCount - 1)
    return new Promise((resolve) => setTimeout(resolve, delay)).then(() => axios(config))
  }
)

/**
 * 解析后端统一 RestBean 响应并分发成功/失败回调。
 *
 * @param {object} body RestBean 响应体
 * @param {string} url 请求地址
 * @param {Function} success 成功回调
 * @param {Function} failure 失败回调
 */
function handleRestBean(body, url, success, failure) {
  if (body && body.code === 200) {
    success(body.data)
    return
  }
  const message = body && body.message ? body.message : '请求失败'
  const status = body && body.code ? body.code : 0
  failure(message, status, url)
}

/**
 * 构建统一 axios catch 处理器，优先透传后端 RestBean message。
 *
 * @param {string} url 请求地址
 * @param {Function} failure 失败回调
 * @param {Function} error 通用错误回调
 * @returns {Function} axios catch handler
 */
function buildErrorHandler(url, failure, error = defaultError) {
  return (err) => {
    const data = err.response && err.response.data
    if (data && data.message) {
      failure(data.message, data.code || err.response.status || 0, url)
      return
    }
    error(err)
    if (failure !== defaultFailure && failure !== silentFailure) {
      failure('请求失败', err.response ? err.response.status : 0, url)
    }
  }
}

function internalPost(url, data, headers, success, failure, error = defaultError) {
  axios
    .post(url, data, { headers: headers })
    .then(({ data }) => handleRestBean(data, url, success, failure))
    .catch(buildErrorHandler(url, failure, error))
}

function internalGet(url, headers, success, failure, error = defaultError) {
  axios
    .get(url, { headers: headers })
    .then(({ data }) => handleRestBean(data, url, success, failure))
    .catch(buildErrorHandler(url, failure, error))
}

function internalPut(url, data, headers, success, failure, error = defaultError) {
  axios
    .put(url, data, { headers: headers })
    .then(({ data }) => handleRestBean(data, url, success, failure))
    .catch(buildErrorHandler(url, failure, error))
}

function internalDelete(url, headers, success, failure, error = defaultError) {
  axios
    .delete(url, { headers: headers })
    .then(({ data }) => handleRestBean(data, url, success, failure))
    .catch(buildErrorHandler(url, failure, error))
}

function login(username, password, remember, success, failure = defaultFailure) {
  internalPost(
    '/api/auth/login',
    {
      username: username,
      password: password
    },
    {
      'Content-Type': 'application/x-www-form-urlencoded'
    },
    (data) => {
      storeAccessToken(remember, data.token, data.expire)
      const store = useStore()
      store.user.role = data.role
      store.user.username = data.username
      store.user.email = data.email
      ElMessage.success(`登录成功，欢迎 ${data.username} `)
      success(data)
    },
    failure
  )
}

function post(url, data, success, failure = defaultFailure) {
  internalPost(url, data, accessHeader(), success, failure)
}

function put(url, data, success, failure = defaultFailure) {
  internalPut(url, data, accessHeader(), success, failure)
}

function del(url, success, failure = defaultFailure) {
  internalDelete(url, accessHeader(), success, failure)
}

function publicGet(url, success, failure = silentFailure) {
  internalGet(url, {}, success, failure, silentFailure)
}

function logout(success, failure = defaultFailure) {
  get(
    '/api/auth/logout',
    () => {
      deleteAccessToken()
      ElMessage.success(`退出登录成功，欢迎您再次使用`)
      success()
    },
    failure
  )
}

function get(url, success, failure = defaultFailure) {
  internalGet(url, accessHeader(), success, failure)
}

/**
 * P2-1：OIDC 回调拿到 JWT 后，前端需要回填 store.user。
 * 调用 GET /api/auth/me 用现有 JWT 取 role/username/email。
 * fetchSelf 失败时静默调用 fallback，调用方可选择跳转兜底页。
 */
function fetchSelf(success, failure = defaultFailure) {
  internalGet(
    '/api/auth/me',
    accessHeader(),
    (data) => {
      const store = useStore()
      if (data) {
        store.user.role = data.role || ''
        store.user.username = data.username || ''
        store.user.email = data.email || ''
      }
      success(data)
    },
    failure
  )
}

function unauthorized() {
  return !takeAccessToken()
}

export { post, put, del, get, publicGet, login, logout, unauthorized, takeAccessToken, fetchSelf }
