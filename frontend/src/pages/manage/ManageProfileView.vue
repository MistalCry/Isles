<script setup lang="ts">
import type { Channel } from '@/types/models'
import ChannelProfileForm from '@/components/channel/ChannelProfileForm.vue'

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
        <h2>频道资料</h2>
        <p class="muted">名称、头像、封面、简介与可见性；修改后立即生效</p>
      </div>
    </header>

    <div class="isles-card manage-profile">
      <ChannelProfileForm :channel="channel" @updated="(updated) => emit('updated', updated)" />
      <p class="muted manage-profile__meta">
        频道 ID：{{ channelId }} · 创建于 {{ channel.createdAt ? new Date(channel.createdAt).toLocaleDateString('zh-CN') : '未知' }}
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
