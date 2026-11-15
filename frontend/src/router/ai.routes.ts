import { t } from '@/i18n'
import type { RouteRecordRaw } from 'vue-router'

/**
 * 平台管理后台路由（docs/11）。
 *
 * <p>`meta.permission` 与后端权限点一一对应：AdminLayout 用它做菜单显隐与直接访问兜底，
 * 真正的鉴权仍在后端 `@RequirePlatformPermission`。
 *
 * <p>频道助手设置页 `pages/admin/ChannelAiSettingsPage.vue` 是组件（需要 channelId prop），
 * 由频道管理中心菜单集成，不在本文件的全局路由中出现。
 */
export const aiRoutes: RouteRecordRaw[] = [
  {
    path: '/admin',
    component: () => import('@/pages/admin/AdminLayout.vue'),
    meta: { title: t('admin.layout.title'), requiresAuth: true },
    children: [
      { path: '', redirect: '/admin/overview' },
      {
        path: 'overview',
        name: 'admin-overview',
        component: () => import('@/pages/admin/AdminOverviewView.vue'),
        meta: { title: t('admin.layout.overview'), requiresAuth: true, permission: 'STATS_READ' },
      },
      {
        path: 'settings',
        name: 'admin-settings',
        component: () => import('@/pages/admin/AdminSettingsView.vue'),
        meta: { title: t('admin.layout.settings'), requiresAuth: true, permission: 'CONFIG_READ' },
      },
      {
        path: 'announcements',
        name: 'admin-announcements',
        component: () => import('@/pages/admin/AdminAnnouncementsView.vue'),
        meta: { title: t('admin.layout.announcements'), requiresAuth: true, permission: 'ANNOUNCEMENT_MANAGE' },
      },
      {
        path: 'sensitive-words',
        name: 'admin-words',
        component: () => import('@/pages/admin/AdminSensitiveWordsView.vue'),
        meta: { title: t('admin.layout.words'), requiresAuth: true, permission: 'WORD_MANAGE' },
      },
      {
        path: 'reports',
        name: 'admin-reports',
        component: () => import('@/pages/admin/AdminReportsView.vue'),
        meta: { title: t('admin.layout.reports'), requiresAuth: true, permission: 'REPORT_READ' },
      },
      {
        path: 'content',
        name: 'admin-content',
        component: () => import('@/pages/admin/AdminContentView.vue'),
        meta: { title: t('admin.layout.content'), requiresAuth: true, permission: 'CONTENT_READ' },
      },
      {
        path: 'users',
        name: 'admin-users',
        component: () => import('@/pages/admin/AdminUsersView.vue'),
        meta: { title: t('admin.layout.users'), requiresAuth: true, permission: 'USER_READ' },
      },
      {
        path: 'channels',
        name: 'admin-channels',
        component: () => import('@/pages/admin/AdminChannelsView.vue'),
        meta: { title: t('admin.channels.title'), requiresAuth: true, permission: 'CONTENT_READ' },
      },
      {
        path: 'admins',
        name: 'admin-admins',
        component: () => import('@/pages/admin/AdminAdminsView.vue'),
        meta: { title: t('admin.layout.admins'), requiresAuth: true, permission: 'ADMIN_MANAGE' },
      },
      {
        path: 'audit-logs',
        name: 'admin-audit',
        component: () => import('@/pages/admin/AdminAuditLogsView.vue'),
        meta: { title: t('admin.layout.audit'), requiresAuth: true, permission: 'AUDIT_READ' },
      },
      {
        path: 'ai-models',
        name: 'admin-ai-models',
        component: () => import('@/pages/admin/AdminAiModelsView.vue'),
        meta: { title: t('admin.layout.ai'), requiresAuth: true, permission: 'AI_MANAGE' },
      },
    ],
  },
]

export default aiRoutes
