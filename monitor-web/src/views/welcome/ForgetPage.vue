<template>
  <section class="reset-page">
    <div class="reset-progress">
      <el-steps :active="active" finish-status="success" align-center>
        <el-step title="验证电子邮件" />
        <el-step title="重新设定密码" />
      </el-steps>
    </div>
    <transition name="el-fade-in-linear" mode="out-in">
      <div v-if="active === 0" key="verify" class="reset-step">
        <header class="reset-heading">
          <h1>重置密码</h1>
          <p>验证账户邮箱后即可设置新密码</p>
        </header>
        <el-form
          ref="formRef"
          class="reset-form"
          :model="form"
          :rules="rules"
          @validate="onValidate"
          @keyup.enter="confirmReset"
        >
          <el-form-item prop="email">
            <el-input v-model="form.email" size="large" type="email" placeholder="电子邮件地址">
              <template #prefix>
                <el-icon><Message /></el-icon>
              </template>
            </el-input>
          </el-form-item>
          <el-form-item prop="code">
            <el-row :gutter="10" style="width: 100%">
              <el-col :span="15">
                <el-input
                  v-model="form.code"
                  :maxlength="6"
                  size="large"
                  type="text"
                  placeholder="请输入验证码"
                >
                  <template #prefix>
                    <el-icon><EditPen /></el-icon>
                  </template>
                </el-input>
              </el-col>
              <el-col :span="9">
                <el-button
                  class="code-button"
                  type="primary"
                  plain
                  size="large"
                  @click="validateEmail"
                  :disabled="!isEmailValid || coldTime > 0"
                >
                  {{ coldTime > 0 ? '请稍后 ' + coldTime + ' 秒' : '获取验证码' }}
                </el-button>
              </el-col>
            </el-row>
          </el-form-item>
        </el-form>
        <el-button class="reset-action" size="large" type="primary" @click="confirmReset">
          开始重置密码
        </el-button>
        <el-button class="back-login" link type="primary" @click="router.push('/')">
          返回登录
        </el-button>
      </div>
      <div v-else key="password" class="reset-step">
        <header class="reset-heading">
          <h1>设置新密码</h1>
          <p>请填写并牢记新的账户密码</p>
        </header>
        <el-form
          ref="formRef"
          class="reset-form"
          :model="form"
          :rules="rules"
          @validate="onValidate"
          @keyup.enter="doReset"
        >
          <el-form-item prop="password">
            <el-input
              v-model="form.password"
              :maxlength="16"
              size="large"
              type="password"
              placeholder="新密码"
            >
              <template #prefix>
                <el-icon><Lock /></el-icon>
              </template>
            </el-input>
          </el-form-item>
          <el-form-item prop="password_repeat">
            <el-input
              v-model="form.password_repeat"
              :maxlength="16"
              size="large"
              type="password"
              placeholder="重复新密码"
            >
              <template #prefix>
                <el-icon><Lock /></el-icon>
              </template>
            </el-input>
          </el-form-item>
        </el-form>
        <el-button class="reset-action" size="large" type="primary" @click="doReset">
          立即重置密码
        </el-button>
      </div>
    </transition>
  </section>
</template>

<script setup>
import { onBeforeUnmount, reactive, ref } from 'vue'
import { EditPen, Lock, Message } from '@element-plus/icons-vue'
import { post } from '@/net'
import { ElMessage } from 'element-plus'
import router from '@/router'
import { createEmailCodeRequester } from '@/tools/verification-code'

const active = ref(0)

const form = reactive({
  email: '',
  code: '',
  password: '',
  password_repeat: ''
})

const validatePassword = (rule, value, callback) => {
  if (value === '') {
    callback(new Error('请再次输入密码'))
  } else if (value !== form.password) {
    callback(new Error('两次输入的密码不一致'))
  } else {
    callback()
  }
}

const rules = {
  email: [
    { required: true, message: '请输入邮件地址', trigger: 'blur' },
    { type: 'email', message: '请输入合法的电子邮件地址', trigger: ['blur', 'change'] }
  ],
  code: [{ required: true, message: '请输入获取的验证码', trigger: 'blur' }],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 16, message: '密码的长度必须在6-16个字符之间', trigger: ['blur'] }
  ],
  password_repeat: [{ validator: validatePassword, trigger: ['blur', 'change'] }]
}

const formRef = ref()
const isEmailValid = ref(false)
const coldTime = ref(0)
const emailCodeRequester = createEmailCodeRequester({ cooldownRef: coldTime })

const onValidate = (prop, isValid) => {
  if (prop === 'email') isEmailValid.value = isValid
}

/**
 * 发送重置密码验证码，并在成功后启动倒计时。
 */
function validateEmail() {
  emailCodeRequester.request(form.email, 'reset')
}

const confirmReset = () => {
  formRef.value.validate((isValid) => {
    if (isValid) {
      post(
        '/api/auth/reset-confirm',
        {
          email: form.email,
          code: form.code
        },
        () => active.value++
      )
    }
  })
}

const doReset = () => {
  formRef.value.validate((isValid) => {
    if (isValid) {
      post(
        '/api/auth/reset-password',
        {
          email: form.email,
          code: form.code,
          password: form.password
        },
        () => {
          ElMessage.success('密码重置成功，请重新登录')
          router.push('/')
        }
      )
    }
  })
}

onBeforeUnmount(() => {
  emailCodeRequester.dispose()
})
</script>

<style scoped>
.reset-page {
  width: 100%;
}

.reset-progress {
  margin: 0 0 40px;
}

.reset-progress :deep(.el-step__title) {
  font-size: 12px;
}

.reset-heading h1 {
  margin: 0;
  color: var(--app-text);
  font-size: 28px;
  font-weight: 760;
  letter-spacing: -0.03em;
}

.reset-heading p {
  margin: 10px 0 0;
  color: var(--app-text-secondary);
  font-size: 14px;
  line-height: 1.6;
}

.reset-form {
  margin-top: 32px;
}

.reset-form :deep(.el-form-item) {
  margin-bottom: 18px;
}

.reset-form :deep(.el-input__wrapper) {
  min-height: 50px;
  border: 1px solid var(--app-border);
  box-shadow: none;
}

.reset-form :deep(.el-input__wrapper:hover),
.reset-form :deep(.el-input__wrapper.is-focus) {
  border-color: var(--app-primary);
  box-shadow: 0 0 0 3px var(--app-primary-soft);
}

.code-button,
.reset-action {
  width: 100%;
  min-height: 50px;
}

.reset-action {
  margin-top: 24px;
  box-shadow: 0 10px 22px rgba(11, 107, 238, 0.18);
}

.back-login {
  width: 100%;
  margin: 16px 0 0;
}

@media (max-width: 480px) {
  .reset-progress {
    margin-bottom: 32px;
  }

  .reset-heading h1 {
    font-size: 26px;
  }
}
</style>
