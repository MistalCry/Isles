<script setup lang="ts">
import { useRouter } from 'vue-router'
import EmptyState from '@/components/EmptyState.vue'

const router = useRouter()

function goHome(): void {
  void router.push('/feed')
}
</script>

<template>
  <div class="page not-found">
    <EmptyState
      title="404 · 这座岛不存在"
      description="你访问的页面可能已经漂走，或者链接有误。"
    >
      <template #action>
        <el-button type="primary" @click="goHome">回到频道动态</el-button>
      </template>
    </EmptyState>
  </div>
</template>

<style scoped lang="scss">
.not-found {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 70vh;
}
</style>
