<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

/**
 * 「AI」合规标识（docs/12 §6）：不可关闭、不可隐藏。
 *
 * <p>两种语义：
 * - `content`：这条帖子/评论由 AI 生成（isAiGenerated）
 * - `agent`：这个账号是 AI 代理（isAgent）
 */
const { t } = useI18n()

const props = withDefaults(
  defineProps<{
    kind?: 'content' | 'agent'
    /** 与正文同排时用 sm，独立区块用 md */
    size?: 'sm' | 'md'
  }>(),
  { kind: 'content', size: 'sm' },
)

const tip = computed(() => (props.kind === 'agent' ? t('ai.badge.agentTip') : t('ai.badge.contentTip')))
</script>

<template>
  <el-tooltip :content="tip" placement="top" effect="dark" :show-after="200">
    <span class="ai-badge" :class="`ai-badge--${size}`" role="img" :aria-label="tip">AI</span>
  </el-tooltip>
</template>

<style scoped lang="scss">
.ai-badge {
  display: inline-flex;
  flex: none;
  align-items: center;
  justify-content: center;
  color: var(--isles-primary);
  font-weight: 700;
  line-height: 1;
  letter-spacing: 0.02em;
  white-space: nowrap;
  background: transparent;
  border: 1px solid currentcolor;
  border-radius: var(--isles-radius-pill);
  cursor: default;
  user-select: none;
}

.ai-badge--sm {
  height: 16px;
  padding: 0 4px;
  font-size: 10px;
}

.ai-badge--md {
  height: 20px;
  padding: 0 6px;
  font-size: 11px;
}
</style>
