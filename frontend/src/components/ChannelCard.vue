<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { formatCount } from '@/utils/format'
import { canManageChannelSummary } from '@/utils/channelPermissions'
import type { ChannelSummary } from '@/types/models'
import UserAvatar from './UserAvatar.vue'

const { t } = useI18n()

const props = withDefaults(
  defineProps<{
    channel: ChannelSummary
    layout?: 'grid' | 'row'
  }>(),
  {
    layout: 'grid',
  },
)

const router = useRouter()
const channelNo = computed(() => (props.channel.channelNo ? `#${props.channel.channelNo}` : ''))
const isPrivate = computed(() => props.channel.type === 'PRIVATE')
const isManager = computed(() => canManageChannelSummary(props.channel))

function openManage(event: Event): void {
  event.preventDefault()
  event.stopPropagation()
  void router.push({ name: 'manage', params: { channelId: props.channel.id } })
}
</script>

<template>
  <RouterLink
    :to="{ name: 'channel', params: { id: channel.id } }"
    class="channel-card"
    :class="`channel-card--${layout}`"
  >
    <UserAvatar :src="channel.avatarUrl" :name="channel.name" :size="layout === 'grid' ? 56 : 44" square />
    <div class="channel-card__body">
      <div class="channel-card__title-row">
        <h3 class="channel-card__name line-clamp-1">{{ channel.name }}</h3>
        <el-tag v-if="isPrivate" size="small" type="info" effect="plain">{{ t('channel.private') }}</el-tag>
        <button v-if="isManager" type="button" class="channel-card__manage" :title="t('channel.settings.manageCenter')" @click="openManage">{{ t('common.manage') }}</button>
      </div>
      <p class="channel-card__meta">
        <span v-if="channelNo">{{ channelNo }}</span>
        <span>{{ t('common.memberSuffix', { n: formatCount(channel.memberCount) }) }}</span>
        <span>{{ t('common.postSuffix', { n: formatCount(channel.postCount) }) }}</span>
      </p>
      <p v-if="channel.description" class="channel-card__description line-clamp-2">
        {{ channel.description }}
      </p>
    </div>
  </RouterLink>
</template>

<style scoped lang="scss">
.channel-card {
  display: flex;
  gap: var(--isles-space-3);
  align-items: flex-start;
  padding: var(--isles-space-4);
  background: var(--isles-surface);
  border: 1px solid var(--isles-border);
  border-radius: var(--isles-radius-lg);
  transition:
    border-color 0.15s ease,
    box-shadow 0.15s ease,
    transform 0.15s ease;

  &:hover {
    border-color: var(--isles-border-strong);
    box-shadow: var(--isles-shadow-md);
    transform: translateY(-1px);
  }
}

.channel-card--row {
  align-items: center;
  border-radius: var(--isles-radius-md);
}

.channel-card__body {
  min-width: 0;
  flex: 1;
}

.channel-card__title-row {
  display: flex;
  gap: var(--isles-space-2);
  align-items: center;
}

.channel-card__manage {
  flex-shrink: 0;
  padding: 0 6px;
  font-size: 10px;
  line-height: 16px;
  color: var(--isles-primary);
  cursor: pointer;
  background: var(--isles-primary-soft, rgb(64 128 255 / 12%));
  border: none;
  border-radius: var(--isles-radius-sm);
}

.channel-card__name {
  font-size: var(--isles-font-base);
  font-weight: 600;
  line-height: var(--isles-line-tight);
}

.channel-card__meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-1) var(--isles-space-3);
  margin-top: 2px;
  color: var(--isles-text-muted);
  font-size: var(--isles-font-xs);
}

.channel-card__description {
  margin-top: var(--isles-space-1);
  color: var(--isles-text-secondary);
  font-size: var(--isles-font-xs);
  line-height: var(--isles-line-base);
}
</style>
