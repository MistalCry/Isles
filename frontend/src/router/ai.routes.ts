import type { RouteRecordRaw } from 'vue-router'

/**
 * AI 相关路由（WP4/WP5/WP6）。
 *
 * <p>由 Lead 集成进 `router/index.ts`：
 * 1. 删除现有占位的 `/admin/:pathMatch(.*)*` 路由（name: 'admin'）；
 * 2. `import { aiRoutes } from './ai.routes'` 后把 `...aiRoutes` 加入 routes 数组。
 *
 * <p>频道助手设置页 `pages/admin/ChannelAiSettingsPage.vue` 是组件（需要 channelId prop），
 * 由频道管理中心菜单（WP1，ManageView）集成，不在本文件的全局路由中出现。
 */
export const aiRoutes: RouteRecordRaw[] = [
  {
    path: '/admin',
    component: () => import('@/pages/admin/AdminLayout.vue'),
    meta: { title: '平台管理后台', requiresAuth: true },
    children: [
      { path: '', redirect: '/admin/overview' },
      {
        path: 'overview',
        name: 'admin-overview',
        component: () => import('@/pages/admin/AdminOverviewView.vue'),
        meta: { title: '平台数据', requiresAuth: true },
      },
      {
        path: 'ai-models',
        name: 'admin-ai-models',
        component: () => import('@/pages/admin/AdminAiModelsView.vue'),
        meta: { title: 'AI 配置', requiresAuth: true },
      },
      {
        path: 'reports',
        name: 'admin-reports',
        component: () => import('@/pages/admin/AdminReportsView.vue'),
        meta: { title: '举报处置', requiresAuth: true },
      },
      {
        path: 'users',
        name: 'admin-users',
        component: () => import('@/pages/admin/AdminUsersView.vue'),
        meta: { title: '用户管理', requiresAuth: true },
      },
      {
        path: 'channels',
        name: 'admin-channels',
        component: () => import('@/pages/admin/AdminChannelsView.vue'),
        meta: { title: '频道管理', requiresAuth: true },
      },
    ],
  },
]

export default aiRoutes
