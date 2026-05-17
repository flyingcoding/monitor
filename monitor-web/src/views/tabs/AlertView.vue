<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useStore } from '@/store'

defineOptions({ name: 'AlertView' })

const route = useRoute()
const router = useRouter()
const store = useStore()

const activeTab = computed({
  get() {
    if (route.name === 'alert-rule') return 'rule'
    if (route.name === 'alert-channel') return 'channel'
    return 'history'
  },
  set(value) {
    const map = { history: 'alert-history', rule: 'alert-rule', channel: 'alert-channel' }
    router.push({ name: map[value] || 'alert-history' })
  }
})
</script>

<template>
  <div class="alert-main">
    <div style="display: flex; justify-content: space-between; align-items: end">
      <div>
        <div class="title"><i class="fa-solid fa-bell"></i> 告警中心</div>
        <div class="desc">查看告警历史、配置告警规则与通知通道，及时掌握系统异常</div>
      </div>
    </div>
    <el-divider style="margin: 10px 0" />
    <el-tabs v-model="activeTab" class="alert-tabs">
      <el-tab-pane label="告警历史" name="history" />
      <el-tab-pane label="告警规则" name="rule" v-if="store.isAdmin" />
      <el-tab-pane label="通知通道" name="channel" v-if="store.isAdmin" />
    </el-tabs>
    <router-view v-slot="{ Component }">
      <transition name="el-fade-in-linear" mode="out-in">
        <component :is="Component" />
      </transition>
    </router-view>
  </div>
</template>

<style scoped>
.alert-main {
  margin: 0 50px;
  .title {
    font-size: 22px;
    font-weight: bold;
  }
  .desc {
    font-size: 15px;
    color: grey;
  }
}
.alert-tabs :deep(.el-tabs__nav-wrap)::after {
  display: none;
}
</style>
