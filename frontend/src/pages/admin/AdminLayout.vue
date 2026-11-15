<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import { ADMIN_ROLE_LABEL_KEYS, hasPermission, visibleNav } from './adminPermissions'
import type { AdminPermission } from './adminApi'
import { useAdminAccess } from '@/composables/useAdminAccess'
import { resolveErrorMessage } from '@/api/client'

const { t } = useI18n()

const route = useRoute()
const router = useRouter()
// 与侧边栏共用同一份身份缓存：后台内所有页面都能读到 permissions
const adminAccess = useAdminAccess()
const { identity } = adminAccess
const loading = ref(true)
const error = ref<string | null>(null)

/** 菜单按权限点过滤：角色变化无需改前端 */
const navItems = computed(() =>
  visibleNav(identity.value).map((item) => ({ ...item, label: t(item.labelKey) })),
)

const roleLabel = computed(() => {
  const role = identity.value?.platformRole as keyof typeof ADMIN_ROLE_LABEL_KEYS | undefined
  return role && ADMIN_ROLE_LABEL_KEYS[role] ? t(ADMIN_ROLE_LABEL_KEYS[role]) : (identity.value?.platformRole ?? '')
})

async function loadIdentity(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    await adminAccess.ensure(true)
    ensureRouteAllowed()
  } catch (cause) {
    error.value = resolveErrorMessage(cause, t('admin.layout.authFailed'))
  } finally {
    loading.value = false
  }
}

/**
 * 直接输入 URL 进入无权限页面时，跳到自己有权限的第一个页面，
 * 避免出现「菜单里没有、页面却能打开」的越权错觉。
 */
function ensureRouteAllowed(): void {
  const required = route.meta.permission as AdminPermission | undefined
  if (!required || hasPermission(identity.value, required)) return
  // 身份还没取到（加载中/接口失败）时不跳转：交给页面的骨架与错误态处理，
  // 否则一次网络抖动就会把管理员弹回前台。
  if (!identity.value?.admin) return
  const fallback = navItems.value[0]
  if (fallback) void router.replace(fallback.path)
}

function isActive(name: string): boolean {
  return route.name === name
}

watch(() => route.fullPath, ensureRouteAllowed)
onMounted(loadIdentity)
</script>

<template>
  <div class="page admin-layout">
    <header class="page-header">
      <div>
        <h1 class="page-title">{{ t('admin.layout.title') }}</h1>
        <p class="page-subtitle">{{ t('admin.layout.subtitle') }}</p>
      </div>
      <div v-if="identity" class="admin-layout__identity">
        <el-tag size="small" type="warning" effect="plain">{{ roleLabel }}</el-tag>
        <span>{{ identity.username }}</span>
      </div>
    </header>

    <el-skeleton v-if="loading" :rows="6" animated />
    <ErrorState v-else-if="error" :message="error" @retry="loadIdentity" />
    <EmptyState
      v-else-if="identity && !identity.admin"
      :title="t('admin.noPermission')"
      :description="t('admin.noPermissionHint')"
    >
      <template #action>
        <el-button @click="router.push('/feed')">{{ t('admin.layout.backHome') }}</el-button>
      </template>
    </EmptyState>

    <div v-else class="admin-layout__body">
      <nav class="admin-nav" :aria-label="t('admin.navLabel')">
        <RouterLink
          v-for="item in navItems"
          :key="item.name"
          :to="item.path"
          class="admin-nav__item"
          :class="{ 'is-active': isActive(item.name) }"
        >
          <el-icon :size="16"><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
        </RouterLink>
      </nav>
      <section class="admin-content">
        <RouterView />
      </section>
    </div>
  </div>
</template>

<style scoped lang="scss">
.admin-layout {
  max-width: 1280px;
}

.admin-layout__identity {
  display: flex;
  gap: var(--isles-space-2);
  align-items: center;
  color: var(--isles-text-secondary);
  font-size: var(--isles-font-sm);
}

.admin-layout__body {
  display: flex;
  gap: var(--isles-space-5);
  align-items: flex-start;
}

.admin-nav {
  display: flex;
  flex: none;
  flex-direction: column;
  gap: 2px;
  width: 168px;
  padding: var(--isles-space-2);
  background: var(--isles-surface);
  border: 1px solid var(--isles-border);
  border-radius: var(--isles-radius-lg);
}

.admin-nav__item {
  display: flex;
  gap: var(--isles-space-2);
  align-items: center;
  padding: var(--isles-space-2) var(--isles-space-3);
  color: var(--isles-text-secondary);
  font-size: var(--isles-font-sm);
  border-radius: var(--isles-radius-md);

  &:hover {
    color: var(--isles-text);
    background: var(--isles-hover);
  }

  &.is-active {
    color: var(--isles-primary);
    font-weight: 600;
    background: var(--isles-primary-soft);
  }
}

.admin-content {
  flex: 1;
  min-width: 0;
}

@media (max-width: 900px) {
  .admin-layout__body {
    flex-direction: column;
  }

  .admin-nav {
    flex-direction: row;
    width: 100%;
    overflow-x: auto;
  }
}
</style>
