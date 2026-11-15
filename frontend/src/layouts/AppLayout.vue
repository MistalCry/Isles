<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { onBeforeUnmount, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useRoute, useRouter } from 'vue-router'
import { Bell, Menu, Search } from '@element-plus/icons-vue'
import Sidebar from '@/components/Sidebar.vue'
import { useChannelStore } from '@/stores/channel'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notifications'
import { useDmStore } from '@/stores/dm'
import { useRealtimeStore } from '@/stores/realtime'
import { APP_NAME } from '@/constants/app'

const { t } = useI18n()


const route = useRoute()
const router = useRouter()
const channelStore = useChannelStore()
const auth = useAuthStore()
const notificationStore = useNotificationStore()
const dmStore = useDmStore()
const realtimeStore = useRealtimeStore()
const { isAuthenticated } = storeToRefs(auth)
const { unreadCount } = storeToRefs(notificationStore)
const mobileNavOpen = ref(false)

function closeMobileNav(): void {
  mobileNavOpen.value = false
}

function goSearch(): void {
  void router.push({ name: 'search' })
}

function goNotifications(): void {
  void router.push({ name: 'notifications' })
}

// 登录后连接 STOMP 实时通道并补拉未读；退出登录清理会话级状态
watch(
  [() => auth.accessToken, isAuthenticated],
  () => {
    if (auth.accessToken && isAuthenticated.value) {
      realtimeStore.connect()
      void notificationStore.refreshUnread()
      void dmStore.fetchConversations(true)
    } else {
      realtimeStore.disconnect()
      notificationStore.clear()
      dmStore.clear()
    }
  },
  { immediate: true },
)

onBeforeUnmount(() => {
  realtimeStore.disconnect()
})

watch(
  () => route.fullPath,
  () => {
    mobileNavOpen.value = false
  },
)

// 离开频道页后清理侧边栏「当前频道」（仅未加入频道时展示）
watch(
  () => route.name,
  (name) => {
    if (name !== 'channel') {
      channelStore.clearCurrentChannel()
    }
  },
)
</script>

<template>
  <div class="app-layout">
    <aside class="app-layout__sidebar" :aria-label="t('layout.sidebarLabel')">
      <Sidebar />
    </aside>

    <el-drawer v-model="mobileNavOpen" direction="ltr" size="264px" :with-header="false">
      <Sidebar @navigate="closeMobileNav" />
    </el-drawer>

    <div class="app-layout__main">
      <header class="mobile-topbar">
        <button type="button" class="mobile-topbar__icon" :aria-label="t('layout.openMenu')" @click="mobileNavOpen = true">
          <el-icon :size="20"><Menu /></el-icon>
        </button>
        <RouterLink to="/feed" class="mobile-topbar__brand">{{ APP_NAME }}</RouterLink>
        <div class="mobile-topbar__actions">
          <button type="button" class="mobile-topbar__icon" :aria-label="t('nav.notifications')" @click="goNotifications">
            <el-badge :value="unreadCount" :max="99" :hidden="unreadCount === 0">
              <el-icon :size="20"><Bell /></el-icon>
            </el-badge>
          </button>
          <button type="button" class="mobile-topbar__icon" :aria-label="t('common.search')" @click="goSearch">
            <el-icon :size="20"><Search /></el-icon>
          </button>
        </div>
      </header>

      <main class="app-layout__content">
        <RouterView />
      </main>
    </div>
  </div>
</template>

<style scoped lang="scss">
.app-layout {
  min-height: 100vh;
}

.app-layout__sidebar {
  position: fixed;
  top: 0;
  left: 0;
  bottom: 0;
  z-index: var(--isles-z-header);
  width: var(--isles-sidebar-width);
  height: 100vh;
  border-right: 1px solid var(--isles-border);
  background: var(--isles-sidebar-bg);
}

.app-layout__main {
  min-height: 100vh;
  margin-left: var(--isles-sidebar-width);
}

.app-layout__content {
  min-height: 100vh;
}

.mobile-topbar {
  display: none;
}

@media (max-width: 767px) {
  .app-layout__sidebar {
    display: none;
  }

  .app-layout__main {
    margin-left: 0;
  }

  .mobile-topbar {
    position: sticky;
    top: 0;
    z-index: var(--isles-z-header);
    display: flex;
    align-items: center;
    justify-content: space-between;
    height: var(--isles-mobile-topbar-height);
    padding: 0 var(--isles-space-3);
    background: rgba(255, 255, 255, 0.94);
    border-bottom: 1px solid var(--isles-border);
    backdrop-filter: blur(8px);
  }

  .mobile-topbar__brand {
    font-size: var(--isles-font-md);
    font-weight: 600;
    letter-spacing: 0.06em;
  }

  .mobile-topbar__actions {
    display: flex;
    gap: var(--isles-space-1);
    align-items: center;
  }

  .mobile-topbar__icon {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 36px;
    height: 36px;
    color: var(--isles-text-secondary);
    background: transparent;
    border: none;
    border-radius: var(--isles-radius-sm);
    cursor: pointer;
  }
}
</style>

<style lang="scss">
.el-drawer__body {
  padding: 0;
}
</style>
