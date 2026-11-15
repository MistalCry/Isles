<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { Bell, Search, Setting } from '@element-plus/icons-vue'
import { formatCount } from '@/utils/format'
import type { Channel } from '@/types/models'
import UserAvatar from './UserAvatar.vue'

const { t } = useI18n()

const props = withDefaults(
  defineProps<{
    channel: Channel
    joined?: boolean
    joining?: boolean
    showSettings?: boolean
  }>(),
  {
    joined: false,
    joining: false,
    showSettings: true,
  },
)

defineEmits<{
  join: []
  search: []
  messages: []
  settings: []
}>()

const scrolled = ref(false)

function handleScroll(): void {
  scrolled.value = window.scrollY > 120
}

onMounted(() => {
  handleScroll()
  window.addEventListener('scroll', handleScroll, { passive: true })
})

onBeforeUnmount(() => {
  window.removeEventListener('scroll', handleScroll)
})

const coverStyle = computed(() =>
  props.channel.coverUrl ? { backgroundImage: `url(${props.channel.coverUrl})` } : undefined,
)
const channelNo = computed(() =>
  props.channel.channelNo ? `#${props.channel.channelNo}` : t('channel.noChannelNo'),
)
const isPrivate = computed(() => props.channel.type === 'PRIVATE')
</script>

<template>
  <header class="channel-header" :class="{ 'is-scrolled': scrolled }">
    <div class="channel-header__bg" :class="{ 'has-image': Boolean(channel.coverUrl) }" :style="coverStyle" />
    <div class="channel-header__veil" />

    <div class="channel-header__inner">
      <UserAvatar
        :src="channel.avatarUrl"
        :name="channel.name"
        :size="76"
        square
        class="channel-header__avatar"
      />

      <div class="channel-header__info">
        <div class="channel-header__title-row">
          <h1 class="channel-header__name">{{ channel.name }}</h1>
          <el-tag v-if="isPrivate" size="small" type="info" effect="plain">{{ t('channel.private') }}</el-tag>
        </div>
        <p class="channel-header__meta">
          <span>{{ channelNo }}</span>
          <span>{{ t('common.memberSuffix', { n: formatCount(channel.memberCount) }) }}</span>
          <span>{{ t('common.postSuffix', { n: formatCount(channel.postCount) }) }}</span>
        </p>
        <p v-if="channel.description" class="channel-header__description line-clamp-2">
          {{ channel.description }}
        </p>
      </div>

      <div class="channel-header__actions">
        <el-button circle :aria-label="t('channel.searchContent')" @click="$emit('search')">
          <el-icon><Search /></el-icon>
        </el-button>
        <el-button circle :aria-label="t('channel.messages')" @click="$emit('messages')">
          <el-icon><Bell /></el-icon>
        </el-button>
        <el-button v-if="showSettings" circle :aria-label="t('channel.settingsLabel')" @click="$emit('settings')">
          <el-icon><Setting /></el-icon>
        </el-button>
        <el-button
          v-if="!joined"
          type="primary"
          :loading="joining"
          @click="$emit('join')"
        >{{ t('userCard.joinChannel') }}</el-button>
        <el-tag v-else type="success" effect="plain" round>{{ t('channel.joined') }}</el-tag>
      </div>
    </div>
  </header>
</template>

<style scoped lang="scss">
.channel-header {
  position: relative;
  margin-bottom: var(--isles-space-4);
  overflow: hidden;
  color: #fff;
  background: linear-gradient(135deg, #1a7187, #0e4a5b);
  border-radius: var(--isles-radius-lg);
}

.channel-header__bg {
  position: absolute;
  inset: 0;
  background-position: center;
  background-size: cover;
  opacity: 0.9;
  transform: scale(1.02);
  transition:
    opacity 0.4s ease,
    transform 0.6s ease;

  &:not(.has-image) {
    background: linear-gradient(135deg, #1a7187, #0e4a5b);
  }

  .channel-header.is-scrolled & {
    opacity: 0.18;
    transform: scale(1);
  }
}

.channel-header__veil {
  position: absolute;
  inset: 0;
  background: linear-gradient(90deg, rgba(8, 30, 39, 0.72) 0%, rgba(8, 30, 39, 0.32) 60%, rgba(8, 30, 39, 0.5) 100%);
}

.channel-header__inner {
  position: relative;
  z-index: 1;
  display: flex;
  gap: var(--isles-space-4);
  align-items: flex-start;
  padding: var(--isles-space-6);
}

.channel-header__avatar {
  border: 2px solid rgba(255, 255, 255, 0.5);
}

.channel-header__info {
  flex: 1;
  min-width: 0;
}

.channel-header__title-row {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-2);
  align-items: center;
}

.channel-header__name {
  font-size: var(--isles-font-xl);
  font-weight: 600;
  line-height: var(--isles-line-tight);
}

.channel-header__meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-1) var(--isles-space-3);
  margin-top: var(--isles-space-2);
  color: rgba(255, 255, 255, 0.82);
  font-size: var(--isles-font-xs);
}

.channel-header__description {
  max-width: 640px;
  margin-top: var(--isles-space-2);
  color: rgba(255, 255, 255, 0.86);
  font-size: var(--isles-font-sm);
}

.channel-header__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-2);
  align-items: center;
  justify-content: flex-end;

  :deep(.el-button) {
    background: rgba(255, 255, 255, 0.16);
    border-color: rgba(255, 255, 255, 0.28);
    color: #fff;

    &:hover {
      background: rgba(255, 255, 255, 0.28);
      border-color: rgba(255, 255, 255, 0.4);
    }
  }

  :deep(.el-button--primary) {
    background: var(--isles-accent);
    border-color: var(--isles-accent);

    &:hover {
      background: #c85c26;
      border-color: #c85c26;
    }
  }

  :deep(.el-tag) {
    color: #fff;
    background: rgba(255, 255, 255, 0.2);
    border-color: rgba(255, 255, 255, 0.35);
  }
}

@media (max-width: 767px) {
  .channel-header__inner {
    flex-wrap: wrap;
    padding: var(--isles-space-4);
  }

  .channel-header__actions {
    width: 100%;
    justify-content: flex-start;
  }

  .channel-header__name {
    font-size: var(--isles-font-lg);
  }
}
</style>
