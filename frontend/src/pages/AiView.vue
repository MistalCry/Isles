<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import AiChatPanel from '@/components/ai/AiChatPanel.vue'

const { t } = useI18n()
</script>

<template>
  <div class="page ai-page">
    <header class="page-header">
      <div>
        <h1 class="page-title">{{ t('nav.ai') }}</h1>
        <p class="page-subtitle">{{ t('ai.page.subtitle') }}</p>
      </div>
    </header>
    <AiChatPanel class="ai-page__panel" scope="GLOBAL" :show-session-sidebar="true" />
  </div>
</template>

<style scoped lang="scss">
.ai-page {
  display: flex;
  flex-direction: column;
  max-width: 1280px;
  height: calc(100vh - var(--isles-space-12));
  min-height: 560px;
}

.ai-page__panel {
  flex: 1;
  min-height: 0;
}

@media (max-width: 900px) {
  .ai-page {
    height: auto;
    min-height: 0;
  }
}
</style>
