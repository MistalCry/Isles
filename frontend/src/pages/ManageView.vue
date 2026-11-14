<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import ComingSoon from '@/components/ComingSoon.vue'

const route = useRoute()
const channelId = computed(() => String(route.params.channelId ?? ''))
const section = computed(() => {
  const path = route.params.pathMatch
  if (Array.isArray(path) && path.length > 0) return String(path[0])
  return 'home'
})
const features = [
  '首页概览与数据卡片',
  '频道资料与板块管理',
  '内容管理（置顶 / 精华 / 删除）',
  '成员与身份组管理',
  '管理日志与数据中心',
]
</script>

<template>
  <div class="page">
    <header class="page-header">
      <div>
        <h1 class="page-title">频道管理中心</h1>
        <p class="page-subtitle">频道：{{ channelId }} · 当前模块：{{ section }}</p>
      </div>
    </header>
    <ComingSoon
      title="频道管理中心正在建设"
      description="M2 将提供完整的频道运营能力。当前仅保留路由与管理布局占位，便于后续平滑接入。"
      eta="规划中 · M2"
      :features="features"
    >
      <el-button type="primary" plain @click="$router.push({ name: 'channel', params: { id: channelId } })">
        返回频道页
      </el-button>
    </ComingSoon>
  </div>
</template>
