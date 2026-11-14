<script setup lang="ts">
import ComingSoon from '@/components/ComingSoon.vue'

const features = ['全站热度排序（浏览 / 点赞 / 评论 + 时间衰减）', '按板块与时间窗聚合', '频道推荐与加入']
</script>

<template>
  <div class="page">
    <header class="page-header">
      <div>
        <h1 class="page-title">热门讨论</h1>
        <p class="page-subtitle">全平台正在被讨论的内容</p>
      </div>
    </header>
    <ComingSoon
      title="热门讨论正在筹备中"
      description="未来在这里可以看到全站热门帖子与推荐频道，发现更多同频的岛民。"
      eta="规划中 · M2 / M3"
      :features="features"
    />
  </div>
</template>
