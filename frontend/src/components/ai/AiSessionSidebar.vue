<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { ElMessageBox } from 'element-plus'
import { Delete, EditPen, Plus } from '@element-plus/icons-vue'
import type { AiConversation } from '@/api/ai'
import { formatRelativeTime } from '@/utils/format'
import EmptyState from '@/components/EmptyState.vue'

const { t } = useI18n()

defineProps<{
  conversations: AiConversation[]
  activeId: string | null
  loading: boolean
  error: string | null
}>()

const emit = defineEmits<{
  select: [id: string]
  create: []
  rename: [id: string, title: string]
  remove: [id: string]
  retry: []
}>()

async function promptRename(conversation: AiConversation): Promise<void> {
  const result = await ElMessageBox.prompt(t('ai.session.renamePrompt'), t('ai.session.rename'), {
    inputValue: conversation.title,
    confirmButtonText: t('common.save'),
    cancelButtonText: t('common.cancel'),
    inputValidator: (value) => {
      const text = (value ?? '').trim()
      if (!text) return t('ai.session.nameRequired')
      if (text.length > 120) return t('ai.session.nameTooLong')
      return true
    },
  }).catch(() => null)
  if (!result) return
  emit('rename', conversation.id, String(result.value).trim())
}

async function confirmRemove(conversation: AiConversation): Promise<void> {
  const confirmed = await ElMessageBox.confirm(
    t('ai.session.deleteConfirm', { title: conversation.title }),
    t('ai.session.delete'),
    { type: 'warning', confirmButtonText: t('common.delete'), cancelButtonText: t('common.cancel') },
  ).catch(() => false)
  if (confirmed) emit('remove', conversation.id)
}
</script>

<template>
  <aside class="ai-session-sidebar">
    <header class="ai-session-sidebar__head">
      <span class="ai-session-sidebar__title">{{ t('ai.session.title') }}</span>
      <el-button type="primary" size="small" :icon="Plus" @click="emit('create')">{{ t('ai.session.new') }}</el-button>
    </header>

    <div class="ai-session-sidebar__body">
      <el-skeleton v-if="loading && conversations.length === 0" :rows="4" animated />
      <div v-else-if="error" class="ai-session-sidebar__error">
        <p>{{ error }}</p>
        <el-button size="small" @click="emit('retry')">{{ t('common.retry') }}</el-button>
      </div>
      <EmptyState
        v-else-if="conversations.length === 0"
        compact
        :title="t('ai.noConversations')"
        :description="t('ai.noConversationsHint')"
      />
      <ul v-else class="ai-session-list">
        <li
          v-for="conversation in conversations"
          :key="conversation.id"
          class="ai-session-item"
          :class="{ 'is-active': conversation.id === activeId }"
          @click="emit('select', conversation.id)"
        >
          <div class="ai-session-item__main">
            <p class="ai-session-item__title line-clamp-1">{{ conversation.title || t('ai.session.new') }}</p>
            <span class="ai-session-item__meta">
              {{ formatRelativeTime(conversation.lastMessageAt || conversation.updatedAt) }}
              · {{ t('ai.session.messageCount', { n: conversation.messageCount }) }}
            </span>
          </div>
          <div class="ai-session-item__actions">
            <button type="button" class="icon-btn" :aria-label="t('ai.session.rename')" @click.stop="promptRename(conversation)">
              <el-icon :size="13"><EditPen /></el-icon>
            </button>
            <button type="button" class="icon-btn" :aria-label="t('ai.session.delete')" @click.stop="confirmRemove(conversation)">
              <el-icon :size="13"><Delete /></el-icon>
            </button>
          </div>
        </li>
      </ul>
    </div>
  </aside>
</template>

<style scoped lang="scss">
.ai-session-sidebar {
  display: flex;
  flex-direction: column;
  width: 248px;
  min-width: 200px;
  height: 100%;
  overflow: hidden;
  background: var(--isles-surface);
  border: 1px solid var(--isles-border);
  border-radius: var(--isles-radius-lg);
}

.ai-session-sidebar__head {
  display: flex;
  gap: var(--isles-space-2);
  align-items: center;
  justify-content: space-between;
  padding: var(--isles-space-3);
  border-bottom: 1px solid var(--isles-border);
}

.ai-session-sidebar__title {
  color: var(--isles-text-secondary);
  font-size: var(--isles-font-sm);
  font-weight: 600;
}

.ai-session-sidebar__body {
  flex: 1;
  min-height: 0;
  padding: var(--isles-space-2);
  overflow-y: auto;
}

.ai-session-sidebar__error {
  display: flex;
  flex-direction: column;
  gap: var(--isles-space-2);
  align-items: center;
  padding: var(--isles-space-4) var(--isles-space-2);
  color: var(--isles-text-muted);
  font-size: var(--isles-font-xs);
  text-align: center;
}

.ai-session-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.ai-session-item {
  display: flex;
  gap: var(--isles-space-1);
  align-items: center;
  padding: var(--isles-space-2);
  cursor: pointer;
  border-radius: var(--isles-radius-md);

  &:hover {
    background: var(--isles-hover);

    .ai-session-item__actions {
      opacity: 1;
    }
  }

  &.is-active {
    background: var(--isles-primary-soft);
  }
}

.ai-session-item__main {
  flex: 1;
  min-width: 0;
}

.ai-session-item__title {
  color: var(--isles-text);
  font-size: var(--isles-font-sm);
}

.ai-session-item.is-active .ai-session-item__title {
  color: var(--isles-primary);
  font-weight: 600;
}

.ai-session-item__meta {
  color: var(--isles-text-muted);
  font-size: 11px;
}

.ai-session-item__actions {
  display: flex;
  gap: 2px;
  opacity: 0;
  transition: opacity 0.15s ease;
}

.icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  color: var(--isles-text-muted);
  background: transparent;
  border: none;
  border-radius: var(--isles-radius-sm);
  cursor: pointer;

  &:hover {
    color: var(--isles-primary);
    background: var(--isles-surface);
  }
}

@media (max-width: 900px) {
  .ai-session-sidebar {
    width: 100%;
    height: 180px;
  }
}
</style>
