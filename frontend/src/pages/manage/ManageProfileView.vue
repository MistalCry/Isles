<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import type { Channel } from '@/types/models'
import ChannelProfileForm from '@/components/channel/ChannelProfileForm.vue'

const { t } = useI18n()

const props = defineProps<{
  channel: Channel
  channelId: string
}>()

const emit = defineEmits<{
  updated: [channel: Channel]
}>()
</script>

<template>
  <section class="manage-section">
    <header class="manage-section__header">
      <div>
        <h2>{{ t('channel.settings.tabProfile') }}</h2>
        <p class="muted">{{ t('manage.profile.subtitle') }}</p>
      </div>
    </header>

    <div class="isles-card manage-profile">
      <ChannelProfileForm :channel="channel" @updated="(updated) => emit('updated', updated)" />
      <p class="muted manage-profile__meta">
        {{
          t('manage.profile.meta', {
            id: channelId,
            date: channel.createdAt
              ? new Date(channel.createdAt).toLocaleDateString()
              : t('common.unknown'),
          })
        }}
      </p>
    </div>
  </section>
</template>

<style scoped lang="scss">
.manage-section__header {
  margin-bottom: var(--isles-space-4);

  h2 {
    font-size: var(--isles-font-lg);
    font-weight: 600;
  }

  p {
    margin-top: 2px;
    font-size: var(--isles-font-xs);
  }
}

.manage-profile {
  padding: var(--isles-space-5);
}

.manage-profile__meta {
  padding-top: var(--isles-space-3);
  margin-top: var(--isles-space-4);
  font-size: var(--isles-font-xs);
  border-top: 1px solid var(--isles-border);
}
</style>
