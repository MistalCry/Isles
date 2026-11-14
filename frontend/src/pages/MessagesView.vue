<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import ComingSoon from '@/components/ComingSoon.vue'

const route = useRoute()
const conversationId = computed(() => (route.params.id ? String(route.params.id) : ''))
const features = ['会话列表与未读红点', '文本、图片与帖子分享', '实时接收与已读状态', '拉黑与免打扰']
</script>

<template>
  <div class="page">
    <header class="page-header">
      <div>
        <h1 class="page-title">我的私信</h1>
        <p class="page-subtitle">和岛民一对一聊天</p>
      </div>
    </header>
    <ComingSoon
      :title="conversationId ? '私信会话即将上线' : '私信功能即将上线'"
      :description="
        conversationId
          ? `会话 ${conversationId} 的聊天窗口将在 M2 随私信功能一起开放。`
          : 'M2 将开放私信与好友能力：可以和任意岛民发起会话、分享帖子，并通过实时通道即时收到消息。'
      "
      eta="规划中 · M2"
      :features="features"
    >
      <p class="muted">侧边栏「我的私信」后续会直接展开会话列表。</p>
    </ComingSoon>
  </div>
</template>

<style scoped lang="scss">
.muted {
  font-size: var(--isles-font-xs);
}
</style>
