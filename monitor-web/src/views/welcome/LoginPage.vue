<template>
  <section class="login-page">
    <header class="login-heading">
      <h1>登录</h1>
      <p>输入账户信息以进入监控控制台</p>
    </header>
    <el-form ref="formRef" class="login-form" :model="form" :rules="rules" @keyup.enter="userLogin">
      <el-form-item prop="username">
        <el-input
          v-model="form.username"
          maxlength="20"
          type="text"
          size="large"
          autocomplete="username"
          placeholder="用户名/邮箱"
        >
          <template #prefix>
            <el-icon><User /></el-icon>
          </template>
        </el-input>
      </el-form-item>
      <el-form-item prop="password">
        <el-input
          v-model="form.password"
          type="password"
          maxlength="20"
          size="large"
          autocomplete="current-password"
          placeholder="密码"
        >
          <template #prefix>
            <el-icon><Lock /></el-icon>
          </template>
        </el-input>
      </el-form-item>
      <div class="login-options">
        <el-form-item prop="remember" class="remember-item">
          <el-checkbox v-model="form.remember" label="记住我" />
        </el-form-item>
        <el-link type="primary" underline="never" @click="router.push('/forget')">
          忘记密码？
        </el-link>
      </div>
    </el-form>
    <el-button class="login-button" size="large" type="primary" @click="userLogin">
      立即登录
    </el-button>
    <div v-if="oidcProviders.length" class="oidc-section">
      <el-divider><span class="oidc-divider-text">或使用以下方式登录</span></el-divider>
      <div class="oidc-buttons">
        <el-button
          v-for="p in oidcProviders"
          :key="p.name"
          @click="loginWithOidc(p.name)"
          class="oidc-button"
        >
          <img v-if="p.iconUrl" :src="p.iconUrl" alt="" class="oidc-icon" />
          <span>{{ p.displayName || p.name }}</span>
        </el-button>
      </div>
    </div>
  </section>
</template>

<script setup>
import { User, Lock } from '@element-plus/icons-vue'
import router from '@/router'
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { login, fetchSelf, storeAccessToken } from '@/net'
import { listPublicProviders } from '@/net/oidc'

const formRef = ref()
const form = reactive({
  username: '',
  password: '',
  remember: false
})

const rules = {
  username: [{ required: true, message: '请输入用户名' }],
  password: [{ required: true, message: '请输入密码' }]
}

const oidcProviders = ref([])

function userLogin() {
  formRef.value.validate((isValid) => {
    if (isValid) {
      login(form.username, form.password, form.remember, () => router.push('/index'))
    }
  })
}

/**
 * 跳转到 OAuth2 授权端点；当前 origin 等同 Spring Boot 服务地址。
 *
 * @param {string} providerName Provider name（registrationId）
 */
function loginWithOidc(providerName) {
  // 由 SecurityFilterChain 接管 /oauth2/authorization/{name}，重定向到 IdP
  window.location.href = `/oauth2/authorization/${encodeURIComponent(providerName)}`
}

/**
 * 解析 query 参数，处理 OIDC 回调：
 *   - fragment/query 中 oidc_token + expire 命中 → 持久化 token 并跳 /index
 *   - oidc_error + message 命中 → ElMessage 报错
 *   - oidc_bound 命中 → 展示绑定结果并跳回安全设置页
 */
function readOidcCallbackParams() {
  const params = new URLSearchParams(window.location.search)
  const hash = window.location.hash || ''
  const payload = hash.startsWith('#') ? hash.slice(1) : hash
  const normalized = payload.startsWith('?') ? payload.slice(1) : payload
  if (normalized) {
    const hashParams = new URLSearchParams(normalized)
    hashParams.forEach((value, key) => {
      if (!params.has(key)) params.set(key, value)
    })
  }
  return params
}

/**
 * 解析 OIDC 回调参数并执行对应 UI 跳转。
 */
function handleOidcCallback() {
  const params = readOidcCallbackParams()
  const token = params.get('oidc_token')
  const expire = params.get('expire')
  const error = params.get('oidc_error')
  const message = params.get('message')

  if (token && expire) {
    const expireDate = new Date(parseInt(expire, 10))
    // OIDC 登录默认走 localStorage（与"记住我"等效），便于跨标签页保留状态
    storeAccessToken(true, token, expireDate.toISOString())
    // 清掉 query 参数避免回退时再次触发
    window.history.replaceState({}, '', window.location.pathname)
    // P2-1：fetchSelf 立即用新 JWT 回填 store.user（role/username/email），
    // 否则 isAdmin 会一直为 false，admin 标签直到刷新才可见。
    fetchSelf(
      () => {
        ElMessage.success('登录成功')
        router.push('/index')
      },
      () => {
        // /me 失败时降级为空 user 状态：跳到 /index，让用户至少能看到普通视图
        ElMessage.success('登录成功')
        router.push('/index')
      }
    )
    return
  }
  const boundFlag = params.get('oidc_bound')
  if (boundFlag === '1') {
    ElMessage.success(`已成功绑定 ${params.get('provider') || ''}`)
    window.history.replaceState({}, '', window.location.pathname)
    router.push('/index/security')
    return
  }
  if (boundFlag === '0') {
    ElMessage.error(message || 'OIDC 绑定失败')
    window.history.replaceState({}, '', window.location.pathname)
    router.push('/index/security')
    return
  }
  if (error) {
    ElMessage.error(message || 'OIDC 登录失败')
    window.history.replaceState({}, '', window.location.pathname)
  }
}

onMounted(() => {
  handleOidcCallback()
  listPublicProviders(
    (list) => {
      oidcProviders.value = Array.isArray(list) ? list : []
    },
    () => {
      // 静默：未启用 OIDC 时返回空，无需提示
    }
  )
})
</script>

<style scoped>
.login-page {
  width: 100%;
}

.login-heading h1 {
  margin: 0;
  color: var(--app-text);
  font-size: 30px;
  font-weight: 760;
  letter-spacing: -0.03em;
  line-height: 1.25;
}

.login-heading p {
  margin: 10px 0 0;
  color: var(--app-text-secondary);
  font-size: 14px;
  line-height: 1.6;
}

.login-form {
  margin-top: 36px;
}

.login-form :deep(.el-form-item) {
  margin-bottom: 18px;
}

.login-form :deep(.el-input__wrapper) {
  min-height: 50px;
  padding-inline: 15px;
  border: 1px solid var(--app-border);
  box-shadow: none;
}

.login-form :deep(.el-input__wrapper:hover),
.login-form :deep(.el-input__wrapper.is-focus) {
  border-color: var(--app-primary);
  box-shadow: 0 0 0 3px var(--app-primary-soft);
}

.login-options {
  min-height: 34px;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.remember-item {
  margin-bottom: 0 !important;
}

.login-button {
  width: 100%;
  min-height: 50px;
  margin-top: 28px;
  font-size: 15px;
  box-shadow: 0 10px 22px rgba(11, 107, 238, 0.2);
}

.oidc-section {
  margin-top: 32px;
}

.oidc-buttons {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.oidc-button {
  width: 100%;
  min-height: 44px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border-color: var(--app-border);
}

.oidc-icon {
  width: 18px;
  height: 18px;
  object-fit: contain;
}

.oidc-divider-text {
  color: var(--app-text-secondary);
  font-size: 12px;
}

@media (max-width: 480px) {
  .login-heading h1 {
    font-size: 27px;
  }

  .login-form {
    margin-top: 30px;
  }

  .login-button {
    margin-top: 22px;
  }
}
</style>
