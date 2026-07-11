<script setup>
import { Moon, Sunny } from '@element-plus/icons-vue'
import { useDark, useToggle } from '@vueuse/core'

const isDark = useDark()
const toggleDark = useToggle(isDark)

/**
 * 切换欢迎页明暗主题。
 */
function toggleTheme() {
  toggleDark()
}
</script>

<template>
  <main class="welcome-shell">
    <section class="brand-panel" aria-label="flying monitor 产品介绍">
      <div class="brand-content">
        <div class="brand-lockup">
          <img src="/icon.svg" alt="" />
          <span>flying monitor</span>
        </div>
        <div class="brand-copy">
          <h1>欢迎来到 flying monitor</h1>
          <p>清晰掌握每台服务器的运行状态</p>
        </div>
      </div>

      <svg class="signal-art" viewBox="0 0 900 420" preserveAspectRatio="none" aria-hidden="true">
        <defs>
          <linearGradient id="signal-fade" x1="0" y1="0" x2="1" y2="0">
            <stop offset="0" stop-color="#438BEB" stop-opacity="0" />
            <stop offset="0.72" stop-color="#438BEB" stop-opacity="0.48" />
            <stop offset="1" stop-color="#438BEB" stop-opacity="0.05" />
          </linearGradient>
          <filter id="signal-glow" x="-20%" y="-50%" width="140%" height="200%">
            <feGaussianBlur stdDeviation="4" result="blur" />
            <feMerge>
              <feMergeNode in="blur" />
              <feMergeNode in="SourceGraphic" />
            </feMerge>
          </filter>
        </defs>
        <g fill="none" stroke="url(#signal-fade)" stroke-width="1">
          <path d="M0 330 C260 330 365 336 470 310 S630 244 900 118" />
          <path d="M0 350 C260 350 370 354 486 326 S650 254 900 138" />
          <path d="M0 372 C250 372 382 374 510 344 S670 270 900 158" />
          <path d="M0 394 C260 394 402 392 534 362 S690 290 900 182" />
        </g>
        <path
          d="M0 324 H542 L558 316 L570 330 L582 280 L595 365 L610 302 L624 324 H842"
          fill="none"
          stroke="#0B6BEE"
          stroke-width="3"
          stroke-linecap="round"
          stroke-linejoin="round"
          filter="url(#signal-glow)"
        />
        <circle cx="842" cy="324" r="5" fill="#FFFFFF" />
        <circle cx="842" cy="324" r="13" fill="#0B6BEE" opacity="0.25" />
      </svg>
    </section>

    <section class="auth-panel">
      <el-tooltip :content="isDark ? '切换到浅色模式' : '切换到深色模式'" placement="left">
        <el-button
          class="welcome-theme-button"
          text
          circle
          :aria-label="isDark ? '切换到浅色模式' : '切换到深色模式'"
          @click="toggleTheme"
        >
          <el-icon><component :is="isDark ? Sunny : Moon" /></el-icon>
        </el-button>
      </el-tooltip>

      <div class="auth-stage">
        <router-view v-slot="{ Component }">
          <transition name="auth-fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </div>
    </section>
  </main>
</template>

<style scoped>
.welcome-shell {
  width: 100%;
  min-height: 100vh;
  display: grid;
  grid-template-columns: minmax(0, 54fr) minmax(440px, 46fr);
  overflow: hidden;
  background: var(--app-canvas);
}

.brand-panel {
  position: relative;
  min-width: 0;
  overflow: hidden;
  background:
    radial-gradient(circle at 78% 22%, rgba(11, 107, 238, 0.11), transparent 33%), var(--app-shell);
  color: #ffffff;
}

.brand-content {
  position: relative;
  z-index: 2;
  width: min(620px, calc(100% - 96px));
  height: 100%;
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  justify-content: center;
  margin: 0 auto;
  padding-bottom: 8vh;
}

.brand-lockup {
  display: flex;
  align-items: center;
  gap: 13px;
  margin-bottom: 44px;
  font-size: 21px;
  font-weight: 750;
  letter-spacing: -0.02em;
}

.brand-lockup img {
  width: 48px;
  height: 48px;
}

.brand-copy h1 {
  max-width: 580px;
  margin: 0;
  font-size: clamp(34px, 3.2vw, 48px);
  font-weight: 760;
  letter-spacing: -0.04em;
  line-height: 1.2;
}

.brand-copy p {
  margin: 18px 0 0;
  color: #c7d6e9;
  font-size: clamp(16px, 1.55vw, 21px);
  line-height: 1.6;
}

.signal-art {
  position: absolute;
  right: -5%;
  bottom: -2%;
  width: 110%;
  height: 48%;
  opacity: 0.96;
}

.auth-panel {
  position: relative;
  min-width: 0;
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 72px clamp(40px, 6vw, 96px);
  background: var(--app-surface);
}

.welcome-theme-button {
  position: absolute;
  top: 28px;
  right: 30px;
  width: 42px;
  height: 42px;
  border: 1px solid var(--app-border);
  color: var(--app-text-secondary);
  font-size: 18px;
}

.welcome-theme-button:hover {
  border-color: var(--app-primary);
  background: var(--app-primary-soft);
  color: var(--app-primary);
}

.auth-stage {
  width: min(100%, 420px);
}

.auth-fade-enter-active,
.auth-fade-leave-active {
  transition:
    opacity var(--app-transition),
    transform var(--app-transition);
}

.auth-fade-enter-from {
  opacity: 0;
  transform: translateY(5px);
}

.auth-fade-leave-to {
  opacity: 0;
}

@media (max-width: 860px) {
  .welcome-shell {
    display: flex;
    flex-direction: column;
    overflow: auto;
  }

  .brand-panel {
    flex: 0 0 190px;
    min-height: 190px;
  }

  .brand-content {
    width: calc(100% - 40px);
    min-height: 190px;
    justify-content: center;
    padding: 0 0 4px;
  }

  .brand-lockup {
    margin-bottom: 16px;
    font-size: 18px;
  }

  .brand-lockup img {
    width: 40px;
    height: 40px;
  }

  .brand-copy h1 {
    font-size: 26px;
  }

  .brand-copy p {
    margin-top: 8px;
    font-size: 14px;
  }

  .signal-art {
    right: -18%;
    bottom: -28%;
    width: 110%;
    height: 116%;
    opacity: 0.56;
  }

  .auth-panel {
    min-height: calc(100vh - 190px);
    align-items: flex-start;
    padding: 68px 20px 44px;
  }

  .welcome-theme-button {
    top: 14px;
    right: 16px;
  }
}

@media (max-width: 480px) {
  .brand-panel,
  .brand-content {
    min-height: 164px;
  }

  .brand-panel {
    flex-basis: 164px;
  }

  .brand-copy h1 {
    font-size: 22px;
  }

  .brand-copy p {
    font-size: 13px;
  }

  .auth-panel {
    min-height: calc(100vh - 164px);
    padding-top: 62px;
  }
}
</style>
