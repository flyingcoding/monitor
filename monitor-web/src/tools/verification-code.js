import { ElMessage } from 'element-plus'
import { get } from '@/net'
import { withQuery } from '@/net/query'

const DEFAULT_COOLDOWN_SECONDS = 60
const DEFAULT_COOLDOWN_INTERVAL_MS = 1000

/**
 * 创建邮箱验证码请求器；请求成功后自动启动冷却倒计时，失败时回滚冷却状态。
 *
 * @param {object} options 配置项
 * @param {number} [options.cooldownSeconds] 冷却秒数
 * @param {number} [options.intervalMs] 冷却刷新间隔
 * @param {{value: number}} options.cooldownRef 冷却倒计时响应式引用
 * @returns {{
 *   request: (email: string, type: string, onSuccess?: Function, onFailure?: Function) => void,
 *   stop: () => void,
 *   dispose: () => void
 * }} 验证码请求控制器
 */
function createEmailCodeRequester({
  cooldownSeconds = DEFAULT_COOLDOWN_SECONDS,
  intervalMs = DEFAULT_COOLDOWN_INTERVAL_MS,
  cooldownRef
}) {
  let timer = null

  /**
   * 清理冷却定时器。
   */
  function clearTimer() {
    if (timer) {
      clearInterval(timer)
      timer = null
    }
  }

  /**
   * 启动倒计时并同步写回响应式状态。
   */
  function startCooldown() {
    clearTimer()
    cooldownRef.value = cooldownSeconds
    timer = setInterval(() => {
      cooldownRef.value = Math.max(0, cooldownRef.value - 1)
      if (cooldownRef.value <= 0) {
        clearTimer()
      }
    }, intervalMs)
  }

  /**
   * 停止倒计时并回滚到可重试状态。
   */
  function stop() {
    clearTimer()
    cooldownRef.value = 0
  }

  /**
   * 请求邮件验证码；成功后自动进入冷却，失败则显示后端消息并回滚。
   *
   * @param {string} email 目标邮箱
   * @param {string} type 验证码用途
   * @param {Function} [onSuccess] 成功后回调
   * @param {Function} [onFailure] 失败后回调
   */
  function request(email, type, onSuccess, onFailure) {
    if (!email) {
      ElMessage.warning('请输入邮件地址')
      stop()
      if (typeof onFailure === 'function') onFailure('请输入邮件地址')
      return
    }
    get(
      withQuery('/api/auth/ask-code', { email, type }),
      () => {
        ElMessage.success(`验证码已发送到邮箱: ${email}，请注意查收`)
        startCooldown()
        if (typeof onSuccess === 'function') onSuccess()
      },
      (message) => {
        ElMessage.warning(message)
        stop()
        if (typeof onFailure === 'function') onFailure(message)
      }
    )
  }

  /**
   * 组件卸载时释放定时器资源。
   */
  function dispose() {
    stop()
  }

  return { request, stop, dispose }
}

export { createEmailCodeRequester }
