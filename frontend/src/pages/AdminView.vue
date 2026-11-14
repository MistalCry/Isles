<script setup lang="ts">
import ComingSoon from '@/components/ComingSoon.vue'

const features = ['AI 模型配置（LMStudio / OpenAI 兼容）', '内容治理与举报处置', '平台数据概览']
</script>

<template>
  <div class="page">
    <header class="page-header">
      <div>
        <h1 class="page-title">平台管理后台</h1>
        <p class="page-subtitle">平台运营与内容治理</p>
      </div>
    </header>
    <ComingSoon
      title="平台管理后台即将上线"
      description="平台管理员将在这里配置 AI 能力、处理举报与查看全站数据。"
      eta="规划中 · M3"
      :features="features"
    />
  </div>
</template>
