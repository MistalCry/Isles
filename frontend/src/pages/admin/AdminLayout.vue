<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { onMounted, ref } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { DataAnalysis, Document, Picture, Setting, User } from '@element-plus/icons-vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import { getAdminIdentity, type AdminIdentity } from './adminApi'
import { resolveErrorMessage } from '@/api/client'

const { t } = useI18n()

interface AdminNavItem {
  name: string
  path: string
  label: string
  icon: typeof DataAnalysis
}

const navItems: AdminNavItem[] = [
  { name: 'admin-overview', path: '/admin/overview', label: t('admin.layout.overview'), icon: DataAnalysis },
  { name: 'admin-ai-models', path: '/admin/ai-models', label: t('admin.layout.ai'), icon: Setting },
  { name: 'admin-reports', path: '/admin/reports', label: t('admin.layout.reports'), icon: Document },
  { name: 'admin-users', path: '/admin/users', label: t('admin.layout.users'), icon: User },
  { name: 'admin-channels', path: '/admin/channels', label: t('admin.channels.title'), icon: Picture },
]

const route = useRoute()
const router = useRouter()
const identity = ref<AdminIdentity | null>(null)
const loading = ref(true)
const error = ref<string | null>(null)

async function loadIdentity(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    identity.value = await getAdminIdentity()
  } catch (cause) {
    error.value = resolveErrorMessage(cause, t('admin.layout.authFailed'))
  } finally {
    loading.value = false
  }
}

function isActive(name: string): boolean {
  return route.name === name
}

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
        <el-tag size="small" type="warning" effect="plain">{{ identity.platformRole }}</el-tag>
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
