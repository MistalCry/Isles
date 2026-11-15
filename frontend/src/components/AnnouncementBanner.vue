<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ArrowLeft, ArrowRight, Close } from '@element-plus/icons-vue'
import { useAnnouncementStore } from '@/stores/announcements'
import type { AdminAnnouncement } from '@/pages/admin/adminApi'

const { t } = useI18n()
const store = useAnnouncementStore()

const emit = defineEmits<{ open: [announcement: AdminAnnouncement] }>()

const index = ref(0)
const paused = ref(false)
let timer: ReturnType<typeof setInterval> | null = null

const items = computed(() => store.bannerItems)
const current = computed<AdminAnnouncement | null>(() => items.value[index.value] ?? null)

/** 公告被关闭后索引可能越界，收回到最后一条 */
watch(items, (list) => {
  if (index.value >= list.length) index.value = Math.max(0, list.length - 1)
})

function prev(): void {
  if (items.value.length === 0) return
  index.value = (index.value - 1 + items.value.length) % items.value.length
}

function next(): void {
  if (items.value.length === 0) return
  index.value = (index.value + 1) % items.value.length
}

function close(): void {
  if (current.value) store.dismiss(current.value.id)
}

function startTimer(): void {
  stopTimer()
  // 多条时自动轮播；单条不轮播，避免无意义的重绘
  if (items.value.length <= 1) return
  timer = setInterval(() => {
    if (!paused.value) next()
  }, 8000)
}

function stopTimer(): void {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

watch(items, startTimer, { immediate: true })

onMounted(() => {
  void store.load()
})

onBeforeUnmount(stopTimer)
</script>

<template>
  <div
    v-if="current"
    class="announcement-banner"
    :class="`announcement-banner--${(current.level || 'INFO').toLowerCase()}`"
    role="status"
    @mouseenter="paused = true"
    @mouseleave="paused = false"
  >
    <div class="announcement-banner__main">
      <strong class="announcement-banner__title">{{ current.title }}</strong>
      <span class="announcement-banner__content">{{ current.content }}</span>
      <button type="button" class="announcement-banner__detail" @click="emit('open', current)">
        {{ t('announcement.banner.detail') }}
      </button>
    </div>

    <div class="announcement-banner__actions">
      <template v-if="items.length > 1">
        <button type="button" class="announcement-banner__icon" :aria-label="t('announcement.banner.prev')" @click="prev">
          <el-icon :size="14"><ArrowLeft /></el-icon>
        </button>
        <span class="announcement-banner__counter">{{ index + 1 }} / {{ items.length }}</span>
        <button type="button" class="announcement-banner__icon" :aria-label="t('announcement.banner.next')" @click="next">
          <el-icon :size="14"><ArrowRight /></el-icon>
        </button>
      </template>
      <button type="button" class="announcement-banner__icon" :aria-label="t('announcement.banner.close')" @click="close">
        <el-icon :size="14"><Close /></el-icon>
      </button>
    </div>
  </div>
</template>

<style scoped lang="scss">
.announcement-banner {
  display: flex;
  gap: var(--isles-space-3);
  align-items: center;
  justify-content: space-between;
  padding: var(--isles-space-2) var(--isles-space-4);
  font-size: var(--isles-font-sm);
  border-bottom: 1px solid var(--isles-border);
  background: var(--isles-primary-soft);
}

.announcement-banner--warning {
  background: #fdf6ec;
  border-bottom-color: #f5dab1;
}

.announcement-banner--critical {
  color: #9a2b2b;
  background: #fef0f0;
  border-bottom-color: #f3c0c0;
}

.announcement-banner__main {
  display: flex;
  flex: 1;
  gap: var(--isles-space-2);
  align-items: baseline;
  min-width: 0;
}

.announcement-banner__title {
  flex: none;
  font-weight: 600;
}

.announcement-banner__content {
  overflow: hidden;
  color: var(--isles-text-secondary);
  white-space: nowrap;
  text-overflow: ellipsis;
}

.announcement-banner__detail {
  flex: none;
  padding: 0;
  color: var(--isles-primary);
  cursor: pointer;
  background: transparent;
  border: none;
}

.announcement-banner__actions {
  display: flex;
  flex: none;
  gap: var(--isles-space-1);
  align-items: center;
}

.announcement-banner__counter {
  color: var(--isles-text-muted);
  font-size: var(--isles-font-xs);
}

.announcement-banner__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  color: var(--isles-text-secondary);
  cursor: pointer;
  background: transparent;
  border: none;
  border-radius: var(--isles-radius-sm);

  &:hover {
    background: rgb(0 0 0 / 6%);
  }
}

@media (max-width: 767px) {
  .announcement-banner__content {
    display: none;
  }
}
</style>
