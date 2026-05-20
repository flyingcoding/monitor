import { fileURLToPath, URL } from 'node:url'
import { defineConfig, mergeConfig } from 'vitest/config'
import baseConfig from './vite.config.js'

/**
 * Vitest 配置：复用 vite.config.js 的插件链与 alias，
 * 同时强制 inline element-plus 让 Vite 的 CSS 转换链路接管其副作用导入，
 * 否则 Node 直接 import "*.css" 会报 "Unknown file extension"。
 */
export default mergeConfig(
  baseConfig,
  defineConfig({
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url))
      }
    },
    test: {
      environment: 'node',
      globals: false,
      server: {
        deps: {
          // 让 element-plus 走 Vite transform，CSS 副作用导入被丢弃
          inline: [/element-plus/]
        }
      },
      // 默认不处理任何 CSS，组件测试用 stubs 屏蔽样式逻辑
      css: false
    }
  })
)
