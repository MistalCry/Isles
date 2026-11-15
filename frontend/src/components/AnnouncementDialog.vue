<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, ref, watch } from 'vue'
import { useAnnouncementStore } from '@/stores/announcements'
import type { AdminAnnouncement } from '@/pages/admin/adminApi'
import { formatDateTime } from '@/utils/format'

const { t } = useI18n()
const store = useAnnouncementStore()

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    /** 打开时高亮的公告 id（从横幅「详情」进入时用） */
    focusId?: string | null
  }>(),
  { focusId: null },
)

const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const highlighted = ref<string | null>(null)

watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    highlighted.value = props.focusId
    void store.load(true)
  },
)

function levelLabel(level?: string | null): string {
  switch (level) {
    case 'WARNING':
      return t('announcement.level.warning')
    case 'CRITICAL':
      return t('announcement.level.critical')
    default:
      return t('announcement.level.info')
  }
}

function levelTagType(level?: string | null): 'success' | 'warning' | 'danger' {
  if (level === 'CRITICAL') return 'danger'
  if (level === 'WARNING') return 'warning'
  return 'success'
}

function range(item: AdminAnnouncement): string {
  if (!item.startAt && !item.endAt) return t('announcement.dialog.forever')
  const start = item.startAt ? formatDateTime(item.startAt) : t('announcement.dialog.immediate')
  const end = item.endAt ? formatDateTime(item.endAt) : t('announcement.dialog.forever')
  return t('announcement.dialog.range', { start, end })
}
</script>

<template>
  <el-dialog v-model="visible" :title="t('announcement.dialog.title')" width="min(620px, 94vw)" append-to-body>
    <p class="announcement-dialog__hint">{{ t('announcement.dialog.hint') }}</p>

    <el-empty v-if="store.activeItems.length === 0" :description="t('announcement.dialog.empty')" />

    <ul v-else class="announcement-dialog__list">
      <li
        v-for="item in store.activeItems"
        :key="item.id"
        class="announcement-card"
        :class="{ 'is-highlighted': highlighted === item.id }"
      >
        <header class="announcement-card__head">
          <el-tag size="small" effect="plain" :type="levelTagType(item.level)">{{ levelLabel(item.level) }}</el-tag>
          <strong class="announcement-card__title">{{ item.title }}</strong>
        </header>
        <p class="announcement-card__content">{{ item.content }}</p>
        <footer class="announcement-card__meta muted">
          <span>{{ range(item) }}</span>
          <span v-if="store.dismissedIds.includes(item.id)">{{ t('announcement.dialog.dismissed') }}</span>
        </footer>
      </li>
    </ul>

    <template #footer>
      <el-button v-if="store.dismissedIds.length > 0" @click="store.restoreAll()">
        {{ t('announcement.dialog.restore') }}
      </el-button>
      <el-button type="primary" @click="visible = false">{{ t('common.confirm') }}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped lang="scss">
.announcement-dialog__hint {
  margin-bottom: var(--isles-space-3);
  color: var(--isles-text-muted);
  font-size: var(--isles-font-sm);
}

.announcement-dialog__list {
  display: flex;
  flex-direction: column;
  gap: var(--isles-space-3);
  max-height: 56vh;
  overflow-y: auto;
}

.announcement-card {
  padding: var(--isles-space-3);
  background: var(--isles-surface);
  border: 1px solid var(--isles-border);
  border-radius: var(--isles-radius-md);

  &.is-highlighted {
    border-color: var(--isles-primary);
    box-shadow: 0 0 0 2px var(--isles-primary-soft);
  }
}

.announcement-card__head {
  display: flex;
  gap: var(--isles-space-2);
  align-items: center;
}

.announcement-card__title {
  font-size: var(--isles-font-md);
}

.announcement-card__content {
  margin-top: var(--isles-space-2);
  color: var(--isles-text-secondary);
  font-size: var(--isles-font-sm);
  white-space: pre-wrap;
}

.announcement-card__meta {
  display: flex;
  gap: var(--isles-space-3);
  margin-top: var(--isles-space-2);
  font-size: var(--isles-font-xs);
}
</style>
