<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import EmptyState from '@/components/EmptyState.vue'

const { t } = useI18n()

const router = useRouter()

function goHome(): void {
  void router.push('/feed')
}
</script>

<template>
  <div class="page not-found">
    <EmptyState
      :title="t('notFound.title')"
      :description="t('notFound.hint')"
    >
      <template #action>
        <el-button type="primary" @click="goHome">{{ t('channel.page.backToFeed') }}</el-button>
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
