import { fileURLToPath, URL } from 'node:url'
import { defineConfig, mergeConfig } from 'vitest/config'
import baseConfig from './vite.config.js'

/**
 * Vitest 配置：复用 vite.config.js 的插件链与 alias，
 * 同时强制 inline element-plus 让 Vite 的 CSS 转换链路接管其副作用导入，
 * 否则 Node 直接 import "*.css" 会报 "Unknown file extension"。
 *
 * v2.0-tests PR4：排除 e2e/ 目录避免 Vitest 把 Playwright spec 也跑进去（Playwright API
 * 不兼容 Vitest 运行时）。Playwright 自己用 playwright.config.ts 的 testDir: './e2e' 加载。
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
      // PR4：默认 include 是 src/**/*.{test,spec}.{js,ts}，但 vitest 还会扫 e2e/ 下的 *.spec.ts
      // 显式只走 src/ 防止收编 Playwright 文件
      include: ['src/**/*.{test,spec}.{js,ts}'],
      exclude: ['node_modules', 'dist', 'e2e', 'playwright-report', 'test-results'],
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
