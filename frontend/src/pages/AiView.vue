<script setup lang="ts">
import ComingSoon from '@/components/ComingSoon.vue'

const features = ['基于全站公开帖子的问答', '会话历史与模型选择', '深度思考与图片多模态', '流式输出与来源引用']
</script>

<template>
  <div class="page">
    <header class="page-header">
      <div>
        <h1 class="page-title">频道问问</h1>
        <p class="page-subtitle">让 AI 帮你梳理群岛上的内容</p>
      </div>
    </header>
    <ComingSoon
      title="频道问问即将上线"
      description="AI 问答基于全站公开帖子检索上下文，回答问题时会附上内容来源，帮助你在群岛中快速找到答案。"
      eta="规划中 · M3"
      :features="features"
    />
  </div>
</template>
