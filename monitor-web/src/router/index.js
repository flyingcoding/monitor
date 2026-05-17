import { createRouter, createWebHistory } from 'vue-router'
import { unauthorized } from '@/net'
import { useStore } from '@/store'

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
  if (to.name && to.name.toString().startsWith('welcome') && !isUnauthorized) {
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
