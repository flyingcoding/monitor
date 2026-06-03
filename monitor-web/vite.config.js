import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

/**
 * 将稳定的大型第三方依赖拆成可缓存 vendor chunk，避免业务路由 chunk 被终端、图表等依赖放大。
 *
 * @param {string} id Rollup 模块 ID
 * @returns {string|undefined} chunk 名称；返回 undefined 时交给 Rollup 默认策略
 */
function resolveManualChunk(id) {
  if (!id.includes('node_modules')) {
    return undefined
  }
  if (id.includes('/node_modules/echarts/') || id.includes('/node_modules/zrender/')) {
    return 'vendor-echarts'
  }
  if (id.includes('/node_modules/@xterm/')) {
    return 'vendor-xterm'
  }
  if (
    id.includes('/node_modules/element-plus/') ||
    id.includes('/node_modules/@element-plus/')
  ) {
    return 'vendor-element-plus'
  }
  if (id.includes('/node_modules/flag-icons/')) {
    return 'vendor-flags'
  }
  if (
    id.includes('/node_modules/vue') ||
    id.includes('/node_modules/@vue/') ||
    id.includes('/node_modules/vue-router/') ||
    id.includes('/node_modules/pinia') ||
    id.includes('/node_modules/@vueuse/')
  ) {
    return 'vendor-vue'
  }
  return 'vendor'
}

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [
    vue(),
    AutoImport({
      resolvers: [ElementPlusResolver()]
    }),
    Components({
      resolvers: [ElementPlusResolver()]
    })
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  build: {
    rollupOptions: {
      output: {
        manualChunks: resolveManualChunk
      }
    }
  }
})
