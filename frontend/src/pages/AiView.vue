<script setup lang="ts">
import AiChatPanel from '@/components/ai/AiChatPanel.vue'
</script>

<template>
  <div class="page ai-page">
    <header class="page-header">
      <div>
        <h1 class="page-title">频道问问</h1>
        <p class="page-subtitle">让 AI 帮你梳理群岛上的公开内容，回答附来源引用</p>
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
