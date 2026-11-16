import { t } from '@/i18n'
import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { pinia } from '@/stores'
import { useSiteStore } from '@/stores/site'
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
    meta: { title: t('nav.feed') },
  },
  {
    path: '/hot',
    name: 'hot',
    component: () => import('@/pages/HotView.vue'),
    meta: { title: t('nav.hot') },
  },
  {
    path: '/ai',
    name: 'ai',
    component: () => import('@/pages/AiView.vue'),
    meta: { title: t('nav.ai') },
  },
  {
    path: '/channels/:id',
    name: 'channel',
    component: () => import('@/pages/ChannelView.vue'),
    meta: { title: t('common.channel') },
  },
  {
    path: '/channels/:id/voice-room',
    name: 'voice-room',
    component: () => import('@/pages/VoiceRoomView.vue'),
    meta: { title: t('voice.title') },
  },
  {
    path: '/channels/:id/apps/:appId',
    name: 'channel-app',
    component: () => import('@/pages/ChannelAppView.vue'),
    meta: { title: t('channel.apps.title') },
  },
  {
    path: '/posts/:id',
    name: 'post',
    component: () => import('@/pages/PostDetailView.vue'),
    meta: { title: t('router.postDetail') },
  },
  {
    path: '/search',
    name: 'search',
    component: () => import('@/pages/SearchView.vue'),
    meta: { title: t('common.search') },
  },
  {
    path: '/my-channels',
    name: 'my-channels',
    component: () => import('@/pages/MyChannelsView.vue'),
    meta: { title: t('nav.myChannels'), requiresAuth: true },
  },
  {
    path: '/my-posts',
    name: 'my-posts',
    component: () => import('@/pages/MyPostsView.vue'),
    meta: { title: t('nav.myPosts'), requiresAuth: true },
  },
  {
    path: '/messages',
    name: 'messages',
    component: () => import('@/pages/MessagesView.vue'),
    meta: { title: t('nav.messages'), requiresAuth: true },
  },
  {
    path: '/messages/:id',
    name: 'message-detail',
    component: () => import('@/pages/MessagesView.vue'),
    meta: { title: t('friends.dm'), requiresAuth: true },
  },
  {
    path: '/notifications',
    name: 'notifications',
    component: () => import('@/pages/NotificationsView.vue'),
    meta: { title: t('nav.notifications'), requiresAuth: true },
  },
  {
    path: '/friends',
    name: 'friends',
    component: () => import('@/pages/FriendsView.vue'),
    meta: { title: t('dm.share.friends'), requiresAuth: true },
  },
  {
    path: '/invites/:token',
    name: 'invite-accept',
    component: () => import('@/pages/InviteAcceptView.vue'),
    meta: { title: t('router.inviteAccept'), requiresAuth: true },
  },
  {
    path: '/manage/:channelId/:pathMatch(.*)*',
    name: 'manage',
    component: () => import('@/pages/ManageView.vue'),
    meta: { title: t('manage.title'), requiresAuth: true },
  },
  {
    path: '/login',
    name: 'login',
    component: () => import('@/pages/LoginView.vue'),
    meta: { title: t('common.login'), guestOnly: true, authLayout: true },
  },
  {
    path: '/register',
    name: 'register',
    component: () => import('@/pages/RegisterView.vue'),
    meta: { title: t('common.register'), guestOnly: true, authLayout: true },
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
    meta: { title: t('router.notFound') },
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
  const siteName = useSiteStore(pinia).config.name || t('router.appTitle')
  document.title = to.meta.title ? `${to.meta.title} · ${siteName}` : siteName
})

export default router
