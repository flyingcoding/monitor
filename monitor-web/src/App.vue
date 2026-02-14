<script setup>
import { onErrorCaptured, ref } from 'vue'
import { useDark, useToggle } from '@vueuse/core'

/**
 * 初始化主题切换能力，保持现有明暗主题行为。
 */
const isDark = useDark({
  selector: 'html',
  attribute: 'class',
  valueDark: 'dark',
  valueLight: 'light'
})
const toggleDark = useToggle(isDark)
useDark({
  onChanged(dark) {
    toggleDark(dark)
  }
})

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
  <div v-if="hasError" class="error-boundary">
    <el-result icon="error" title="页面发生错误" :sub-title="errorMessage">
      <template #extra>
        <el-button type="primary" @click="resetErrorState">重试</el-button>
      </template>
    </el-result>
  </div>
  <header v-else>
    <div class="wrapper">
      <router-view />
    </div>
  </header>
</template>

<style scoped>
header {
  line-height: 1.5;
}

.error-boundary {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
}
</style>
