<script setup>
import { computed } from 'vue'
import { useNotificationStore } from '@/store/notification'

const notificationStore = useNotificationStore()

/**
 * 当前权限状态的中文描述，用于只读展示。
 *
 * @returns {string} 用户友好的权限文本
 */
const permissionText = computed(() => {
  const map = {
    granted: '已授权',
    denied: '已拒绝（请到浏览器站点设置中重新允许）',
    default: '默认未请求',
    unsupported: '当前浏览器不支持桌面通知'
  }
  return map[notificationStore.permission] || notificationStore.permission
})

/**
 * 权限状态对应的 el-tag 类型，用于颜色区分。
 *
 * @returns {string} el-tag type
 */
const permissionTagType = computed(() => {
  const map = {
    granted: 'success',
    denied: 'danger',
    default: 'info',
    unsupported: 'info'
  }
  return map[notificationStore.permission] || 'info'
})

/** 是否展示"申请权限"按钮：只在 default 状态显示（denied/granted/unsupported 都隐藏）。 */
const showRequestButton = computed(() => notificationStore.permission === 'default')

/**
 * 用户主动申请浏览器通知权限。
 */
async function requestPermission() {
  await notificationStore.requestPermission()
}
</script>

<template>
  <div class="info-card">
    <div class="title"><i class="fa-regular fa-bell"></i> 通知偏好</div>
    <el-divider style="margin: 10px 0" />
    <el-alert
      type="info"
      show-icon
      :closable="false"
      style="margin-bottom: 12px"
      title="此处偏好仅控制浏览器桌面通知，不影响系统内告警接收与历史记录。"
    />
    <el-form label-width="120" label-position="left">
      <el-form-item label="启用通知">
        <el-switch v-model="notificationStore.settings.enabled" />
        <span style="margin-left: 12px; font-size: 12px; color: grey">
          关闭后将不再弹出任何浏览器桌面通知
        </span>
      </el-form-item>
      <el-form-item label="最低弹窗等级">
        <el-radio-group v-model="notificationStore.settings.minLevel">
          <el-radio value="info">信息及以上</el-radio>
          <el-radio value="warning">警告及以上</el-radio>
          <el-radio value="critical">仅严重</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="权限状态">
        <el-tag :type="permissionTagType" size="small">{{ permissionText }}</el-tag>
        <el-button
          v-if="showRequestButton"
          size="small"
          type="primary"
          link
          style="margin-left: 12px"
          @click="requestPermission"
        >
          申请权限
        </el-button>
      </el-form-item>
    </el-form>
  </div>
</template>

<style scoped>
.info-card {
  border-radius: 7px;
  padding: 15px 20px;
  background-color: var(--el-bg-color);

  .title {
    font-size: 18px;
    font-weight: bold;
    color: dodgerblue;
  }
}
</style>
