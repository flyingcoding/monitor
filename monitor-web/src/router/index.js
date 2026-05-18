import { createRouter, createWebHistory } from 'vue-router'
import { unauthorized } from '@/net'
import { useStore } from '@/store'

const OIDC_CALLBACK_KEYS = ['oidc_token', 'oidc_error', 'oidc_bound']

/**
 * 判断 hash fragment 是否携带 OIDC 回调信号。登录 token 使用 fragment 传递，避免进入服务端访问日志。
 *
 * @param {string} hash route hash，例如 "#oidc_token=..."
 * @returns {boolean} 是否包含 OIDC 回调 key
 */
function hasOidcCallbackHash(hash) {
  if (!hash) return false
  const payload = hash.startsWith('#') ? hash.slice(1) : hash
  const normalized = payload.startsWith('?') ? payload.slice(1) : payload
  if (!normalized) return false
  const params = new URLSearchParams(normalized)
  return OIDC_CALLBACK_KEYS.some((key) => params.has(key))
}

/**
 * 判断 welcome 路由是否携带 OIDC 回调信号，避免已登录用户的绑定回调被直接重定向吞掉。
 *
 * @param {import('vue-router').RouteLocationNormalized} to 目标路由
 * @returns {boolean} 是否为 OIDC 回调路由
 */
function isWelcomeOidcCallback(to) {
  if (!to.name || !to.name.toString().startsWith('welcome')) {
    return false
  }
  const query = to.query || {}
  return (
    OIDC_CALLBACK_KEYS.some((key) => Object.prototype.hasOwnProperty.call(query, key)) ||
    hasOidcCallbackHash(to.hash)
  )
}

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'welcome',
      component: () => import('@/views/WelcomeView.vue'),
      children: [
        {
          path: '',
          name: 'welcome-login',
          component: () => import('@/views/welcome/LoginPage.vue')
        },
        {
          path: 'forget',
          name: 'welcome-forget',
          component: () => import('@/views/welcome/ForgetPage.vue')
        }
      ]
    },
    {
      path: '/index',
      name: 'index',
      component: () => import('@/views/IndexView.vue'),
      children: [
        {
          path: '',
          name: 'manage',
          component: () => import('@/views/tabs/Manage.vue')
        },
        {
          path: 'security',
          name: 'security',
          component: () => import('@/views/tabs/Security.vue')
        },
        {
          path: 'status-page-config',
          name: 'status-page-config',
          component: () => import('@/views/tabs/StatusPageConfig.vue'),
          meta: { adminOnly: true }
        },
        {
          path: 'alert',
          component: () => import('@/views/tabs/AlertView.vue'),
          redirect: { name: 'alert-history' },
          children: [
            {
              path: 'history',
              name: 'alert-history',
              component: () => import('@/views/alert/HistoryView.vue')
            },
            {
              path: 'rule',
              name: 'alert-rule',
              component: () => import('@/views/alert/RuleView.vue'),
              meta: { adminOnly: true }
            },
            {
              path: 'channel',
              name: 'alert-channel',
              component: () => import('@/views/alert/ChannelView.vue'),
              meta: { adminOnly: true }
            }
          ]
        }
      ]
    },
    // 公开状态页：v1.2 PRD R24/R25。独立顶级路由；未登录可访问；
    // beforeEach 守卫看到 meta.public 时跳过登录检查。
    {
      path: '/status',
      name: 'status-page',
      component: () => import('@/views/StatusPage.vue'),
      meta: { public: true }
    }
  ]
})

router.beforeEach((to, from, next) => {
  // 公开路由（如 /status）直接放行，不参与登录态判断
  if (to.meta && to.meta.public) {
    next()
    return
  }
  const isUnauthorized = unauthorized()
  if (isWelcomeOidcCallback(to)) {
    next()
  } else if (to.name && to.name.toString().startsWith('welcome') && !isUnauthorized) {
    next('/index')
  } else if (to.fullPath.startsWith('/index') && isUnauthorized) {
    next('/')
  } else if (to.meta && to.meta.adminOnly) {
    const store = useStore()
    if (!store.isAdmin) {
      next({ name: 'alert-history' })
    } else {
      next()
    }
  } else {
    next()
  }
})

export default router
