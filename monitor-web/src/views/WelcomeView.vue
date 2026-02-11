<template>
  <div style="width: 100vw; height: 100vh; overflow: hidden; display: flex">
    <div style="flex: 1">
      <el-image
        style="width: 100%; height: 100%"
        fit="cover"
        src="https://img1.baidu.com/it/u=4097856652,4033702227&fm=253&fmt=auto&app=120&f=JPEG?w=1422&h=800"
      />
    </div>
    <div class="welcome-title">
      <div style="font-size: 30px; font-weight: bold">欢迎来到flying监控</div>
      <div style="margin-top: 10px">你可以在这里看到你的各个服务器运行状况</div>
    </div>
    <div class="right-card">
      <router-view v-slot="{ Component }">
        <transition name="el-fade-in-linear" mode="out-in">
          <component :is="Component" style="height: 100%" />
        </transition>
      </router-view>
    </div>
  </div>
</template>

<script setup></script>

<style scoped>
.right-card {
  width: 400px;
  z-index: 1;
  background-color: var(--el-bg-color);
}

.welcome-title {
  position: absolute;
  bottom: 30px;
  left: 30px;
  color: white;
  text-shadow: 0 0 10px black;
}
</style>
