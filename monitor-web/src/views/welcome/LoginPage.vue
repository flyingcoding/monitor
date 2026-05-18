<template>
  <div style="text-align: center; margin: 0 20px">
    <div style="margin-top: 150px">
      <div style="font-size: 25px; font-weight: bold">登录</div>
      <div style="font-size: 14px; color: grey">在进入系统之前请先输入用户名和密码进行登录</div>
    </div>
    <div style="margin-top: 50px">
      <el-form :model="form" :rules="rules" ref="formRef">
        <el-form-item prop="username">
          <el-input v-model="form.username" maxlength="20" type="text" placeholder="用户名/邮箱">
            <template #prefix>
              <el-icon>
                <User />
              </el-icon>
            </template>
          </el-input>
        </el-form-item>
        <el-form-item prop="password">
          <el-input
            v-model="form.password"
            type="password"
            maxlength="20"
            style="margin-top: 10px"
            placeholder="密码"
          >
            <template #prefix>
              <el-icon>
                <Lock />
              </el-icon>
            </template>
          </el-input>
        </el-form-item>
        <el-row style="margin-top: 5px">
          <el-col :span="12" style="text-align: left">
            <el-form-item prop="remember">
              <el-checkbox v-model="form.remember" label="记住我" />
            </el-form-item>
          </el-col>
          <el-col :span="12" style="text-align: right">
            <el-link @click="router.push('/forget')">忘记密码？</el-link>
          </el-col>
        </el-row>
      </el-form>
    </div>
    <div style="margin-top: 40px">
      <el-button @click="userLogin()" style="width: 270px" type="success" plain>立即登录</el-button>
    </div>
    <div v-if="oidcProviders.length" class="oidc-section">
      <el-divider><span style="color: grey; font-size: 12px">或使用以下方式登录</span></el-divider>
      <div class="oidc-buttons">
        <el-button
          v-for="p in oidcProviders"
          :key="p.name"
          @click="loginWithOidc(p.name)"
          plain
          class="oidc-button"
        >
          <img v-if="p.iconUrl" :src="p.iconUrl" alt="" class="oidc-icon" />
          <span>{{ p.displayName || p.name }}</span>
        </el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { User, Lock } from '@element-plus/icons-vue'
import router from '@/router'
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { login, fetchSelf } from '@/net'
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
 *   - oidc_token + expire 命中 → 持久化 token 并跳 /index
 *   - oidc_error + message 命中 → ElMessage 报错
 *   - oidc_bound 命中 → 展示绑定结果并跳回安全设置页
 */
function handleOidcCallback() {
  const params = new URLSearchParams(window.location.search)
  const token = params.get('oidc_token')
  const expire = params.get('expire')
  const error = params.get('oidc_error')
  const message = params.get('message')

  if (token && expire) {
    const expireDate = new Date(parseInt(expire, 10))
    const authObj = { token, expire: expireDate.toISOString() }
    // OIDC 登录默认走 localStorage（与"记住我"等效），便于跨标签页保留状态
    localStorage.setItem('authorize', JSON.stringify(authObj))
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
.oidc-section {
  margin-top: 30px;
}

.oidc-buttons {
  display: flex;
  flex-direction: column;
  gap: 10px;
  align-items: center;
}

.oidc-button {
  width: 270px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
}

.oidc-icon {
  width: 18px;
  height: 18px;
  object-fit: contain;
}
</style>
