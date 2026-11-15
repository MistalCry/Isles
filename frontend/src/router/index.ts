import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { pinia } from '@/stores'
import { aiRoutes } from './ai.routes'

declare module 'vue-router' {
  interface RouteMeta {
    /** 需要登录才能访问 */
    requiresAuth?: boolean
    /** 仅游客可访问（登录页/注册页） */
    guestOnly?: boolean
    /** 使用独立认证布局（不套全局侧边栏） */
    authLayout?: boolean
    title?: string
  }
}

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    redirect: '/feed',
  },
  {
    path: '/feed',
    name: 'feed',
    component: () => import('@/pages/FeedView.vue'),
    meta: { title: '频道动态' },
  },
  {
    path: '/hot',
    name: 'hot',
    component: () => import('@/pages/HotView.vue'),
    meta: { title: '热门讨论' },
  },
  {
    path: '/ai',
    name: 'ai',
    component: () => import('@/pages/AiView.vue'),
    meta: { title: '频道问问' },
  },
  {
    path: '/channels/:id',
    name: 'channel',
    component: () => import('@/pages/ChannelView.vue'),
    meta: { title: '频道' },
  },
  {
    path: '/posts/:id',
    name: 'post',
    component: () => import('@/pages/PostDetailView.vue'),
    meta: { title: '帖子详情' },
  },
  {
    path: '/search',
    name: 'search',
    component: () => import('@/pages/SearchView.vue'),
    meta: { title: '搜索' },
  },
  {
    path: '/my-channels',
    name: 'my-channels',
    component: () => import('@/pages/MyChannelsView.vue'),
    meta: { title: '我的频道', requiresAuth: true },
  },
  {
    path: '/messages',
    name: 'messages',
    component: () => import('@/pages/MessagesView.vue'),
    meta: { title: '我的私信', requiresAuth: true },
  },
  {
    path: '/messages/:id',
    name: 'message-detail',
    component: () => import('@/pages/MessagesView.vue'),
    meta: { title: '私信', requiresAuth: true },
  },
  {
    path: '/notifications',
    name: 'notifications',
    component: () => import('@/pages/NotificationsView.vue'),
    meta: { title: '通知', requiresAuth: true },
  },
  {
    path: '/friends',
    name: 'friends',
    component: () => import('@/pages/FriendsView.vue'),
    meta: { title: '好友', requiresAuth: true },
  },
  {
    path: '/invites/:token',
    name: 'invite-accept',
    component: () => import('@/pages/InviteAcceptView.vue'),
    meta: { title: '邀请加入频道', requiresAuth: true },
  },
  {
    path: '/manage/:channelId/:pathMatch(.*)*',
    name: 'manage',
    component: () => import('@/pages/ManageView.vue'),
    meta: { title: '频道管理中心', requiresAuth: true },
  },
  {
    path: '/login',
    name: 'login',
    component: () => import('@/pages/LoginView.vue'),
    meta: { title: '登录', guestOnly: true, authLayout: true },
  },
  {
    path: '/register',
    name: 'register',
    component: () => import('@/pages/RegisterView.vue'),
    meta: { title: '注册', guestOnly: true, authLayout: true },
  },
  // 平台管理后台（WP6）：AdminLayout + 子路由，未命中的 /admin/* 回落到概览
  ...aiRoutes,
  {
    path: '/admin/:pathMatch(.*)*',
    redirect: '/admin/overview',
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/pages/NotFoundView.vue'),
    meta: { title: '页面不存在' },
  },
]

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
  scrollBehavior(_to, _from, savedPosition) {
    if (savedPosition) return savedPosition
    return { top: 0 }
  },
})

router.beforeEach(async (to) => {
  const auth = useAuthStore(pinia)
  await auth.bootstrap()

  if (to.meta.requiresAuth && !auth.isAuthenticated) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.meta.guestOnly && auth.isAuthenticated) {
    return { path: '/feed' }
  }
  return true
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · 群岛 Isles` : '群岛 Isles'
})

export default router
