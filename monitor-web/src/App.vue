<script setup>
import { onErrorCaptured, ref } from 'vue'
import { useDark } from '@vueuse/core'

// 在应用根同步持久化主题，确保公开状态页等无主题控件路由也能恢复明暗模式。
useDark()

const hasError = ref(false)
const errorMessage = ref('')

/**
 * 捕获全局渲染错误并显示兜底页面。
 */
onErrorCaptured((err) => {
  hasError.value = true
  errorMessage.value = err instanceof Error ? err.message : String(err)
  return false
})

/**
 * 清除错误状态并尝试恢复页面渲染。
 */
function resetErrorState() {
  hasError.value = false
  errorMessage.value = ''
}
</script>

<template>
  <div class="app-root">
    <div v-if="hasError" class="error-boundary">
      <el-result icon="error" title="页面发生错误" :sub-title="errorMessage">
        <template #extra>
          <el-button type="primary" @click="resetErrorState">重试</el-button>
        </template>
      </el-result>
    </div>
    <router-view v-else />
  </div>
</template>

<style scoped>
.app-root {
  min-height: 100vh;
}

.error-boundary {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--app-canvas);
}
</style>
