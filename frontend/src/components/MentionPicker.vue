<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { channelsApi } from '@/api'
import { resolveErrorMessage } from '@/api/client'
import UserAvatar from './UserAvatar.vue'

const { t } = useI18n()

interface Candidate {
  id: string
  username?: string | null
  nickname?: string | null
  avatarUrl?: string | null
}

const props = withDefaults(
  defineProps<{
    channelId?: string
    /** 触发按钮文案（评论框用 @，长文工具栏可用「提及成员」） */
    label?: string
    disabled?: boolean
  }>(),
  {
    channelId: '',
    label: '@',
    disabled: false,
  },
)

const emit = defineEmits<{
  select: [payload: { id: string; name: string }]
}>()

const visible = ref(false)
const keyword = ref('')
const loading = ref(false)
const candidates = ref<Candidate[]>([])

const displayName = (item: Candidate): string => item.nickname || item.username || t('common.islander')

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return candidates.value
  return candidates.value.filter((item) => displayName(item).toLowerCase().includes(kw))
})

async function load(): Promise<void> {
  if (!props.channelId) return
  loading.value = true
  try {
    candidates.value = await channelsApi.getMentionCandidates(props.channelId)
  } catch (err) {
    ElMessage.error(resolveErrorMessage(err, t('mention.loadFailed')))
    candidates.value = []
  } finally {
    loading.value = false
  }
}

watch(visible, (open) => {
  if (open) {
    keyword.value = ''
    void load()
  }
})

function pick(item: Candidate): void {
  emit('select', { id: item.id, name: displayName(item) })
  visible.value = false
}
</script>

<template>
  <el-popover v-model:visible="visible" :width="260" trigger="click" placement="top-start" :disabled="disabled">
    <template #reference>
      <button type="button" class="mention-trigger" :disabled="disabled" :aria-label="t('ai.mentionLabel', { label })">
        {{ label }}
      </button>
    </template>

    <div class="mention-picker">
      <el-input v-model="keyword" size="small" :placeholder="t('ai.searchNickname')" clearable />
      <p class="mention-picker__tip">{{ t('mention.hint') }}</p>
      <div v-loading="loading" class="mention-picker__list">
        <button
          v-for="item in filtered"
          :key="item.id"
          type="button"
          class="mention-picker__item"
          @click="pick(item)"
        >
          <UserAvatar :src="item.avatarUrl" :name="displayName(item)" :size="24" />
          <span class="mention-picker__name">{{ displayName(item) }}</span>
          <small v-if="item.username" class="muted">@{{ item.username }}</small>
        </button>
        <p v-if="!loading && filtered.length === 0" class="mention-picker__empty">{{ t('mention.empty') }}</p>
      </div>
    </div>
  </el-popover>
</template>

<style scoped lang="scss">
.mention-trigger {
  padding: 0 6px;
  font-size: var(--isles-font-sm);
  line-height: 24px;
  color: var(--isles-text-secondary);
  cursor: pointer;
  background: transparent;
  border: none;
  border-radius: var(--isles-radius-sm);

  &:hover:not(:disabled) {
    color: var(--isles-primary);
    background: var(--isles-surface-muted);
  }

  &:disabled {
    cursor: not-allowed;
    opacity: 0.5;
  }
}

.mention-picker__tip {
  margin: 6px 0;
  font-size: var(--isles-font-xs);
  color: var(--isles-text-muted);
}

.mention-picker__list {
  max-height: 220px;
  overflow-y: auto;
}

.mention-picker__item {
  display: flex;
  gap: 8px;
  align-items: center;
  width: 100%;
  padding: 6px 8px;
  text-align: left;
  cursor: pointer;
  background: transparent;
  border: none;
  border-radius: var(--isles-radius-sm);

  &:hover {
    background: var(--isles-surface-muted);
  }
}

.mention-picker__name {
  overflow: hidden;
  font-size: var(--isles-font-sm);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mention-picker__empty {
  padding: 12px 0;
  font-size: var(--isles-font-xs);
  color: var(--isles-text-muted);
  text-align: center;
}
</style>
