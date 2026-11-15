<script setup lang="ts">
import { useRouter } from 'vue-router'
import { ArrowRight } from '@element-plus/icons-vue'
import type { AiSource } from '@/api/ai'

withDefaults(defineProps<{ sources: AiSource[]; compact?: boolean }>(), { compact: false })

const router = useRouter()

function openSource(source: AiSource): void {
  if (!source.url) return
  void router.push(source.url)
}
</script>

<template>
  <section v-if="sources.length > 0" class="ai-sources" :class="{ 'ai-sources--compact': compact }">
    <p class="ai-sources__title">来源引用（{{ sources.length }}）</p>
    <ol class="ai-sources__list">
      <li v-for="(source, index) in sources" :key="source.id" class="ai-source" @click="openSource(source)">
        <span class="ai-source__index">{{ index + 1 }}</span>
        <div class="ai-source__body">
          <p class="ai-source__name line-clamp-1">{{ source.title }}</p>
          <p v-if="!compact" class="ai-source__excerpt line-clamp-2">{{ source.excerpt }}</p>
          <span v-if="source.channelName" class="ai-source__channel">{{ source.channelName }}</span>
        </div>
        <el-icon class="ai-source__arrow" :size="12"><ArrowRight /></el-icon>
      </li>
    </ol>
  </section>
</template>

<style scoped lang="scss">
.ai-sources {
  padding: var(--isles-space-3);
  margin-top: var(--isles-space-3);
  background: var(--isles-surface-muted);
  border: 1px solid var(--isles-border);
  border-radius: var(--isles-radius-md);
}

.ai-sources--compact {
  padding: var(--isles-space-2);
}

.ai-sources__title {
  margin-bottom: var(--isles-space-2);
  color: var(--isles-text-muted);
  font-size: var(--isles-font-xs);
    font-weight: 600;
}

.ai-sources__list {
  display: flex;
  flex-direction: column;
  gap: var(--isles-space-2);
}

.ai-source {
  display: flex;
  gap: var(--isles-space-2);
  align-items: flex-start;
  padding: var(--isles-space-2);
  cursor: pointer;
  background: var(--isles-surface);
  border: 1px solid var(--isles-border);
  border-radius: var(--isles-radius-sm);
  transition: border-color 0.15s ease;

  &:hover {
    border-color: var(--isles-primary);
  }
}

.ai-source__index {
  display: inline-flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  color: var(--isles-primary);
  font-size: 11px;
  font-weight: 600;
  background: var(--isles-primary-soft);
  border-radius: 50%;
}

.ai-source__body {
  flex: 1;
  min-width: 0;
}

.ai-source__name {
  color: var(--isles-text);
  font-size: var(--isles-font-sm);
  font-weight: 600;
}

.ai-source__excerpt {
  margin-top: 2px;
  color: var(--isles-text-muted);
  font-size: var(--isles-font-xs);
  line-height: 1.5;
}

.ai-source__channel {
  display: inline-block;
  margin-top: 4px;
  color: var(--isles-primary);
  font-size: var(--isles-font-xs);
}

.ai-source__arrow {
  flex: none;
  margin-top: 4px;
  color: var(--isles-text-muted);
}
</style>
