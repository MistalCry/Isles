<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft } from '@element-plus/icons-vue'
import { listChannelApps, type ChannelApp } from '@/api/apps'
import { resolveErrorMessage } from '@/api/client'
import LoadingState from '@/components/LoadingState.vue'
import ErrorState from '@/components/ErrorState.vue'
import EmptyState from '@/components/EmptyState.vue'

const { t } = useI18n()

const route = useRoute()
const router = useRouter()

const app = ref<ChannelApp | null>(null)
const loading = ref(true)
const error = ref<string | null>(null)

const channelId = computed(() => String(route.params.id ?? ''))
const appId = computed(() => String(route.params.appId ?? ''))

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const apps = await listChannelApps(channelId.value)
    app.value = apps.find((item) => item.id === appId.value) ?? null
  } catch (cause) {
    error.value = resolveErrorMessage(cause, t('manage.apps.loadFailed'))
  } finally {
    loading.value = false
  }
}

function backToChannel(): void {
  void router.push({ name: 'channel', params: { id: channelId.value } })
}

onMounted(load)
</script>

<template>
  <div class="page channel-app-page">
    <el-button text :icon="ArrowLeft" @click="backToChannel">{{ t('ai.backToChannel') }}</el-button>

    <LoadingState v-if="loading" :rows="3" :tip="t('common.loading')" />
    <ErrorState v-else-if="error" :message="error" @retry="load" />
    <EmptyState
      v-else-if="!app"
      :title="t('channel.apps.empty')"
      :description="t('manage.apps.emptyHint')"
    />
    <section v-else class="isles-card channel-app-page__card">
      <span class="channel-app-page__icon">
        <img v-if="app.iconUrl" :src="app.iconUrl" :alt="app.name" />
      </span>
      <h1>{{ app.name }}</h1>
      <p class="muted">
        {{ app.type === 'LIVE_ROOM' ? t('channel.apps.live') : t('channel.apps.custom') }} ·
        {{ t('channel.apps.soon') }}
      </p>
    </section>
  </div>
</template>

<style scoped lang="scss">
.channel-app-page {
  display: flex;
  flex-direction: column;
  gap: var(--isles-space-3);
  align-items: flex-start;
}

.channel-app-page__card {
  display: flex;
  flex-direction: column;
  gap: var(--isles-space-2);
  align-items: center;
  width: 100%;
  padding: var(--isles-space-6);
  text-align: center;
}

.channel-app-page__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 56px;
  height: 56px;
  overflow: hidden;
  background: var(--isles-surface-muted);
  border-radius: var(--isles-radius-lg);

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
}
</style>
